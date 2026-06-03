package com.ultiq.app.data.repository

import com.ultiq.app.audio.ClipUploadOutcome
import com.ultiq.app.audio.SleepAudioClipCapture
import com.ultiq.app.audio.SleepAudioClipUploader
import com.ultiq.app.data.achievements.AchievementChecker
import com.ultiq.app.data.local.dao.SleepAudioEventDao
import com.ultiq.app.data.local.dao.SleepDao
import com.ultiq.app.data.local.entity.SleepAudioEventEntity
import com.ultiq.app.data.local.entity.SleepRecordEntity
import com.ultiq.app.data.local.entity.SleepTombstoneEntity
import com.ultiq.app.data.remote.ApiService
import com.ultiq.app.data.remote.dto.BatchCreatePhonePickupsDto
import com.ultiq.app.data.remote.dto.BatchCreateSleepAudioEventsDto
import com.ultiq.app.data.remote.dto.CreateSleepRecordDto
import com.ultiq.app.data.remote.dto.PhonePickupDto
import com.ultiq.app.data.remote.dto.toBatchItemDto
import com.ultiq.app.data.remote.dto.toCreateDto
import com.ultiq.app.data.remote.dto.toEntity
import com.ultiq.app.service.PickupEvent
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import retrofit2.HttpException
import java.io.File
import java.io.IOException
import java.util.UUID

// §v2.16.2 — Mirrors SleepAudioUploadWorker.BATCH_CHUNK_SIZE. Each event
// serialises to ~200B of JSON; 25 events ≈ 5KB, well under the AWS WAF
// ManagedRulesCommonRuleSet SizeRestrictions_BODY 8KB limit. Without
// chunking, heavy-snoring nights with 50+ events were blocked by WAF
// at 403 *before* reaching the backend, which v2.16.1's self-heal
// misread as a missing sleep_record.
private const val BATCH_CHUNK_SIZE = 25

class SleepRepository(
    private val sleepDao: SleepDao,
    private val apiService: ApiService,
    private val achievementChecker: AchievementChecker? = null,
    private val sleepAudioEventDao: SleepAudioEventDao? = null,
    private val syncStateStore: SyncStateStore? = null,
) {
    // §10.x — Lazy uploader so existing call sites that construct
    // SleepRepository without an explicit uploader still work (sync worker
    // doesn't need it; only saveAudioEvents does). The uploader is a thin
    // facade over ApiService + OkHttp, so creating it on first use is cheap.
    private val clipUploader: SleepAudioClipUploader by lazy {
        SleepAudioClipUploader(apiService)
    }

    fun getSleepRecords(): Flow<List<SleepRecordEntity>> = sleepDao.getAllRecords()

    fun getSleepRecordsBetween(start: Long, end: Long): Flow<List<SleepRecordEntity>> =
        sleepDao.getRecordsBetween(start, end)

    suspend fun createSleepRecord(record: CreateSleepRecordDto, userId: String): Result<SleepRecordEntity> {
        val result = try {
            val serverRecord = apiService.createSleepRecord(record)
            val entity = serverRecord.toEntity()
            sleepDao.insert(entity)
            Result.success(entity)
        } catch (e: Exception) {
            try {
                val id = UUID.randomUUID().toString()
                val now = System.currentTimeMillis()
                val entity = SleepRecordEntity(
                    id = id,
                    userId = userId,
                    targetBedtime = record.target_bedtime.take(5),
                    targetWakeTime = record.target_wake_time.take(5),
                    actualBedtime = java.time.Instant.parse(record.actual_bedtime).toEpochMilli(),
                    actualWakeTime = java.time.Instant.parse(record.actual_wake_time).toEpochMilli(),
                    qualityRating = record.quality_rating,
                    phonePickups = record.phone_pickups,
                    totalPhoneMinutes = record.total_phone_minutes,
                    notes = record.notes,
                    createdAt = now,
                    updatedAt = now,
                    isSynced = false
                )
                sleepDao.insert(entity)
                Result.success(entity)
            } catch (localErr: Exception) {
                Result.failure(localErr)
            }
        }
        if (result.isSuccess) {
            runCatching { achievementChecker?.checkAndStore() }
        }
        return result
    }

    suspend fun updateSleepRecord(id: String, record: CreateSleepRecordDto, userId: String): Result<SleepRecordEntity> {
        return try {
            val serverRecord = apiService.updateSleepRecord(id, record)
            val entity = serverRecord.toEntity()
            sleepDao.insert(entity)
            Result.success(entity)
        } catch (e: Exception) {
            try {
                val now = System.currentTimeMillis()
                val existing = sleepDao.getById(id)
                val entity = SleepRecordEntity(
                    id = id,
                    userId = userId,
                    targetBedtime = record.target_bedtime.take(5),
                    targetWakeTime = record.target_wake_time.take(5),
                    actualBedtime = java.time.Instant.parse(record.actual_bedtime).toEpochMilli(),
                    actualWakeTime = java.time.Instant.parse(record.actual_wake_time).toEpochMilli(),
                    qualityRating = record.quality_rating,
                    phonePickups = record.phone_pickups,
                    totalPhoneMinutes = record.total_phone_minutes,
                    notes = record.notes,
                    createdAt = existing?.createdAt ?: now,
                    updatedAt = now,
                    isSynced = false
                )
                sleepDao.insert(entity)
                Result.success(entity)
            } catch (localErr: Exception) {
                Result.failure(localErr)
            }
        }
    }

    suspend fun deleteSleepRecord(id: String): Result<Unit> {
        // §10.x-fix (v2.15.4) — Cascade-delete audio events so they don't
        // outlive their parent and end up driving the unsynced-events
        // banner with a sleepRecordId that no longer maps to anything.
        // Backend has its own ON DELETE CASCADE for the server copy.
        try {
            sleepAudioEventDao?.deleteBySleepRecord(id)
        } catch (_: Exception) { /* non-fatal */ }
        // §v2.15.9 — Write a tombstone BEFORE removing the local row so a
        // sync pull that races with the delete sees the tombstone and
        // skips re-inserting from the server. Without this, deletes that
        // failed at the server step (network blip, 429, force-stop)
        // silently disappeared locally but resurrected on the next pull.
        // Observed during v2.15.7 testing under the shared-bucket rate
        // limit: users saw their cleanup work undo itself on reinstall.
        sleepDao.insertTombstone(SleepTombstoneEntity(id, System.currentTimeMillis()))
        sleepDao.deleteById(id)
        return try {
            apiService.deleteSleepRecord(id)
            sleepDao.deleteTombstone(id) // server confirmed; tombstone done.
            Result.success(Unit)
        } catch (e: HttpException) {
            // 404 → record never existed on server (local-only fallback
            // row that hadn't synced yet). Tombstone done either way.
            if (e.code() == 404) sleepDao.deleteTombstone(id)
            // Anything else: keep the tombstone, sync will retry.
            Result.success(Unit)
        } catch (_: IOException) {
            // Offline; tombstone remains, sync will retry.
            Result.success(Unit)
        }
    }

    /**
     * §10 — Persist sleep audio events (snore/cough/sleep_talk) captured
     * during the session that was just saved. Stamps events with the real
     * sleep_record id, writes to Room, batch-uploads to backend, then
     * uploads any pro-tier clips independently.
     *
     * §10.x-fix — Decoupled retry pipeline.
     *  Phase 1: in-session retry of the events batch (3 attempts, 0/2/8s).
     *           Failure leaves events in Room with isSynced=false; the
     *           caller (ViewModel) schedules a WorkManager job to keep
     *           retrying after the user closes the app.
     *  Phase 2: per-clip retry, fully decoupled. A clip failure no longer
     *           rolls back the events status, and an events failure no
     *           longer skips the clip upload step.
     *  Phase 3: refresh canonical state so local Room reflects the server
     *           (has_clip / clip_duration_ms flipping to true).
     *
     * §10.x-fix (4th piece) — `pendingSessionId` re-links rows the
     * SleepTrackingService aggregator wrote to Room during the session
     * with sleepRecordId="pending-{startMs}". Same primary keys, so the
     * subsequent insertAll is INSERT OR REPLACE — no duplicates. If the
     * service died mid-session and `events` is empty, the relink still
     * recovers everything that made it to disk.
     */
    suspend fun saveAudioEvents(
        sleepRecordId: String,
        userId: String,
        events: List<SleepAudioEventEntity>,
        pendingSessionId: String? = null,
    ): Result<Unit> {
        val dao = sleepAudioEventDao ?: return Result.success(Unit)

        // 4th piece: re-link rows the aggregator wrote during the live
        // session. Runs before the insertAll so the snapshot's row wins
        // on the PK conflict (REPLACE strategy).
        if (pendingSessionId != null) {
            try {
                dao.relinkPendingSession(pendingSessionId, sleepRecordId, userId)
            } catch (_: Exception) {
                // Non-fatal — worst case the rows are still keyed to the
                // placeholder and orphan cleanup gets them in 24h. The
                // insertAll below also fixes anything the snapshot covers.
            }
        }

        val stamped = events.map { e ->
            e.copy(sleepRecordId = sleepRecordId, userId = userId)
        }
        if (stamped.isNotEmpty()) {
            try {
                dao.insertAll(stamped)
            } catch (e: Exception) {
                return Result.failure(e)
            }
        }

        // Read the full set of events for this record from Room. Covers
        // (a) the in-memory snapshot, (b) anything written during the
        // live session that the snapshot didn't see (service restart),
        // (c) anything still unsynced from a prior failed attempt.
        val allLocal = try {
            dao.getBySleepRecord(sleepRecordId)
        } catch (_: Exception) {
            stamped
        }
        val unsynced = allLocal.filter { !it.isSynced }

        if (unsynced.isEmpty()) {
            // Everything already on backend. Still attempt any pending
            // clip uploads + refresh canonical state.
            runCatching { uploadCapturedClipsWithRetry(allLocal) }
            SleepAudioClipCapture.clearAll()
            runCatching { fetchAudioEventsForRecord(sleepRecordId) }
            return Result.success(Unit)
        }

        // Phase 1: events batch upload with retry. ServerEvents response
        // is intentionally discarded — since v2.14.1 client-supplied ids
        // equal server ids, so the local `unsynced` list is already the
        // authoritative pairing key for clip uploads.
        //
        // §v2.16.2 — Chunk to stay under the AWS WAF SizeRestrictions_BODY
        // 8KB limit. Mark each chunk synced as it lands so a mid-sequence
        // failure doesn't roll back earlier progress (the worker can
        // retry only what's still isSynced=false).
        try {
            for (chunk in unsynced.chunked(BATCH_CHUNK_SIZE)) {
                withRetry {
                    apiService.batchCreateSleepAudioEvents(
                        BatchCreateSleepAudioEventsDto(
                            sleep_record_id = sleepRecordId,
                            events = chunk.map { it.toCreateDto() },
                        ),
                    )
                }
                try {
                    dao.markSyncedBatch(chunk.map { it.id })
                } catch (_: Exception) { /* non-fatal — worker re-marks next pass */ }
            }
        } catch (e: Throwable) {
            // §v2.16.14 — Removed v2.16.1 403-self-heal markUnsynced.
            // It was the direct duplicate-spawner: any 403 (WAF chunk
            // overshoot, rate limit, transient backend) flipped the
            // already-synced parent to isSynced=false, then a racing
            // syncUnsyncedSleepRecords POSTed it again. Backend has no
            // idempotency, so a fresh row was created each time. v2.16.2
            // removed the original WAF trigger by chunking at 25; the
            // self-heal has been doing more harm than good ever since.
            // Worker retries the events upload regardless; parent stays
            // synced.
            if (e is HttpException && e.code() == 403) {
                android.util.Log.w(
                    "SleepRepository",
                    "saveAudioEvents 403 for $sleepRecordId — no markUnsynced (v2.16.14)",
                )
            }
            return Result.failure(e)
        }

        // Phase 2: clip uploads, decoupled from events status. One bad
        // clip doesn't roll anything back. Failures stay on disk for the
        // WorkManager pass.
        runCatching { uploadCapturedClipsWithRetry(unsynced) }
        SleepAudioClipCapture.clearAll()

        // Phase 3: pull post-attach state so has_clip + clip_duration_ms
        // are correct locally.
        runCatching { fetchAudioEventsForRecord(sleepRecordId) }
        return Result.success(Unit)
    }

    /**
     * §10.x-fix — Per-clip retry. A failed clip doesn't abort the rest of
     * the pass; remaining clip files stay on disk for the WorkManager
     * pass to pick up by scanning `cacheDir/audio-clips/`.
     */
    private suspend fun uploadCapturedClipsWithRetry(
        events: List<SleepAudioEventEntity>,
    ) {
        if (SleepAudioClipCapture.pendingCount() == 0) return
        for (event in events) {
            val clipFile = SleepAudioClipCapture.takeClipFor(event.id) ?: continue
            try {
                withRetry {
                    val ok = clipUploader.upload(event.id, clipFile)
                    if (!ok) throw RuntimeException("clip upload returned false")
                }
            } catch (_: Throwable) {
                // Logged inside uploader; swallow here. The file is still
                // on disk (uploader only deletes on success), so the
                // WorkManager scan-disk pass can retry it.
            }
        }
    }

    /**
     * §10.x-fix — Linear backoff retry helper. 3 attempts at 0/2/8s
     * covers the common transient-network case while keeping the user's
     * "I just hit save" interaction under ~10s. The caller surfaces
     * exhaustion explicitly so the ViewModel can show a banner +
     * schedule WorkManager.
     */
    private suspend fun <T> withRetry(
        attempts: Int = 3,
        delays: List<Long> = listOf(0L, 2_000L, 8_000L),
        block: suspend () -> T,
    ): T {
        require(delays.size == attempts) { "delays size must equal attempts" }
        var last: Throwable? = null
        for (i in 0 until attempts) {
            if (delays[i] > 0) delay(delays[i])
            try {
                return block()
            } catch (e: Throwable) {
                last = e
            }
        }
        throw last ?: IllegalStateException("withRetry: no exception captured")
    }

    /**
     * §10.x-fix (banner) — Observable count of events still waiting to
     * sync to the backend. Excludes rows from a live in-progress session
     * (those use the "pending-*" placeholder sleepRecordId).
     */
    fun observeUnsyncedAudioEventCount(): Flow<Int>? =
        sleepAudioEventDao?.observeUnsyncedCount()

    /**
     * §10.x-fix (4th piece) — Drop placeholder rows from sessions that
     * never reached "End Sleep". Called at session-start to keep the
     * table from growing forever after force-stops / uninstalls.
     */
    suspend fun cleanupOrphanPendingEvents() {
        val dao = sleepAudioEventDao ?: return
        val cutoff = System.currentTimeMillis() - 24L * 60L * 60L * 1000L
        try {
            dao.deleteOrphanPendingEvents(cutoff)
        } catch (_: Exception) { /* non-fatal */ }
    }

    /**
     * §10.x-fix (v2.15.4) — Sweep audio events whose parent sleep_record
     * no longer exists locally. Catches three cases:
     *   1. User deleted a local-fallback record before sync() could
     *      upload it.
     *   2. v2.15.2 users with already-stuck banners from the original
     *      Test A bug (where the relink never ran).
     *   3. Any future drift between the two tables.
     * Called from sync() AFTER syncUnsyncedSleepRecords (which gets first
     * shot at recovering events by relinking) and from the WorkManager
     * worker. Returns the number of rows deleted for logging.
     */
    suspend fun cleanupOrphanedAudioEvents(): Int {
        val dao = sleepAudioEventDao ?: return 0
        return try {
            dao.deleteOrphanedAudioEvents()
        } catch (_: Exception) {
            0
        }
    }

    /**
     * §10.x-fix (WorkManager) — Scan `cacheDir/audio-clips/` for any
     * leftover clip files (named `clip-{eventId}.m4a`) and try to upload
     * each one.
     *
     * §v2.15.7 — Uses [SleepAudioClipUploader.uploadDetailed] so we can:
     *  - Delete clips whose target event is 404 on the backend (instead
     *    of retrying them on every worker run forever — observed 12+
     *    permanently-orphan clips piling up in the cache, all hitting
     *    presign 404).
     *  - Bail out of the loop on HTTP 429 (rate-limited) instead of
     *    burning more rate-limit budget — observed the backend
     *    throttling the worker after ~5–10 rapid presign calls, which
     *    cascaded into 429s for the subsequent fetchAudioEventsForRecord
     *    refresh and prevented has_clip from updating locally.
     *  - Sleep 300 ms between uploads so we don't burst-fire 12 presign
     *    requests in <1s.
     *
     * Returns true when the loop completed without rate-limiting (caller
     * can treat as a complete pass); false when we bailed on 429 (caller
     * should let WorkManager retry the whole job).
     */
    suspend fun uploadOrphanClipFiles(cacheDir: File): Boolean {
        val dao = sleepAudioEventDao ?: return true
        val dir = SleepAudioClipCapture.clipDir(cacheDir)
        val files = dir.listFiles() ?: return true

        // Collect candidate event IDs from filenames.
        val candidates = files
            .mapNotNull { f ->
                val n = f.name
                if (n.startsWith("clip-") && n.endsWith(".m4a")) {
                    n.removePrefix("clip-").removeSuffix(".m4a") to f
                } else null
            }
        if (candidates.isEmpty()) return true

        // §v2.15.8 — Only attempt to upload a clip if its parent event is
        // already synced to the backend. If the event is still
        // isSynced=false locally, the presign request would 404 and the
        // v2.15.7 orphan-detection path would DELETE the clip — even
        // though the event is queued for the next batch retry. That's a
        // real data-loss scenario whenever an events batch upload fails
        // for any reason (rate limit, network blip, backend hiccup) and
        // a subsequent uploadOrphanClipFiles runs against the same
        // unsynced row. Filter unsynced events out here and let the next
        // worker run pick them up after the batch upload succeeds.
        val syncedIds = try {
            dao.filterSyncedEventIds(candidates.map { it.first }).toSet()
        } catch (_: Throwable) {
            // If the lookup fails, fall back to the v2.15.7 behaviour —
            // worse to silently skip uploads than to silently delete
            // pending clips.
            candidates.map { it.first }.toSet()
        }
        val uploadable = candidates.filter { it.first in syncedIds }
        val skipped = candidates.size - uploadable.size
        if (skipped > 0) {
            android.util.Log.i(
                "SleepRepository",
                "Skipping $skipped clip file(s) whose parent event isn't synced yet",
            )
        }

        for ((i, pair) in uploadable.withIndex()) {
            val (eventId, file) = pair
            val outcome = runCatching {
                clipUploader.uploadDetailed(eventId, file)
            }.getOrDefault(ClipUploadOutcome.Failed)
            if (outcome == ClipUploadOutcome.RateLimited) {
                return false
            }
            // Brief pause so 10+ files in cache don't burst-fire the
            // presign endpoint. 300 ms per upload caps at ~3/s — well
            // below typical rate limits.
            if (i < uploadable.size - 1) delay(300L)
        }
        return true
    }

    /**
     * §10 — Batch-upload phone-pickup events captured during the just-ended
     * sleep session. Events live in [SleepTrackingService.pickupEvents] until
     * the user saves the sleep record; this method ships the lot to the
     * backend so the past-records expansion can render the per-pickup
     * timeline later.
     *
     * Failure here is non-fatal: the sleep_record itself was already saved
     * with summary count + total_minutes, so the record stays consistent.
     * Pickup detail just stays unavailable for that record. Logged at the
     * caller level via Result.
     */
    suspend fun savePickupEvents(
        sleepRecordId: String,
        events: List<PickupEvent>,
    ): Result<List<PhonePickupDto>> {
        if (events.isEmpty()) return Result.success(emptyList())
        return try {
            // §v2.16.3 — Chunk to stay under the WAF 8KB body limit, same
            // pattern as batchCreateSleepAudioEvents. Heavy phone-use
            // nights (30-50+ pickups) would otherwise pack ~150-200B per
            // pickup into a single request and exceed the limit.
            val collected = mutableListOf<PhonePickupDto>()
            for (chunk in events.chunked(BATCH_CHUNK_SIZE)) {
                val resp = apiService.batchCreatePhonePickups(
                    BatchCreatePhonePickupsDto(
                        sleep_record_id = sleepRecordId,
                        events = chunk.map { it.toBatchItemDto() },
                    ),
                )
                collected.addAll(resp)
            }
            Result.success(collected)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * §10 — Fetch persisted pickup detail for a specific sleep_record so the
     * Sleep tab record-expansion can render the full per-pickup timeline.
     * On network failure returns empty list (the card falls back to count
     * + total minutes, which is already in the local SleepRecordEntity).
     */
    suspend fun getPickupsForSleep(sleepRecordId: String): List<PhonePickupDto> {
        return try {
            apiService.getPhonePickupsForSleep(sleepRecordId)
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * §10 — Retry-upload of any locally-persisted audio events whose
     * `isSynced=false`. Called from the periodic sync pass.
     */
    suspend fun syncAudioEvents() {
        val dao = sleepAudioEventDao ?: return
        val unsynced = try {
            dao.getUnsynced()
        } catch (_: Exception) {
            return
        }
        if (unsynced.isEmpty()) return
        val byRecord = unsynced.groupBy { it.sleepRecordId }
        for ((sleepRecordId, batch) in byRecord) {
            try {
                // §v2.16.2 — Chunk to stay under WAF 8KB body limit;
                // markSyncedBatch per chunk so partial progress sticks.
                for (chunk in batch.chunked(BATCH_CHUNK_SIZE)) {
                    apiService.batchCreateSleepAudioEvents(
                        BatchCreateSleepAudioEventsDto(
                            sleep_record_id = sleepRecordId,
                            events = chunk.map { it.toCreateDto() },
                        ),
                    )
                    dao.markSyncedBatch(chunk.map { it.id })
                }
            } catch (_: Exception) {
                // skip — next sync will retry
            }
        }
    }

    /**
     * §10.x-fix (v2.15.3) — Extracted helper so both the foreground sync()
     * pass and the SleepAudioUploadWorker can drive it. Uploads any locally-
     * created sleep_record rows (isSynced=false from the network-fallback
     * path in createSleepRecord) and, crucially, **relinks every audio event
     * that referenced the old local id** to the new server-issued id BEFORE
     * deleting the local row. Without the relink the audio events would be
     * permanently orphaned — the WorkManager event-upload pass would loop
     * on a 404 forever.
     */
    // §v2.16.14 — Process-wide mutex around syncUnsyncedSleepRecords.
    // Multiple SleepRepository instances exist (one in SyncManager via
    // UltiqApp.wireRealtimeSync, one freshly built inside every
    // SleepAudioUploadWorker.doWork pass, plus per-ViewModel ones),
    // so a per-instance lock wouldn't help. The companion-object
    // Mutex is shared across every caller in the process.
    //
    // The race we're killing: SSE onConnected fires syncAll() (which
    // calls this) at the same moment the worker fires this directly,
    // both see the same isSynced=false row, both POST, backend has no
    // idempotency check on /sleep-records → 2 server rows. With the
    // lock, the second caller waits, then sees the unsynced list is
    // empty (first caller already replaced the row with the synced
    // server response) and no-ops.
    suspend fun syncUnsyncedSleepRecords() = syncUnsyncedMutex.withLock {
        val unsynced = try {
            sleepDao.getUnsyncedRecords()
        } catch (_: Exception) {
            return@withLock
        }
        for (record in unsynced) {
            try {
                val dto = record.toCreateDto()
                val serverRecord = apiService.createSleepRecord(dto)
                val newId = serverRecord.toEntity().id
                if (newId != record.id) {
                    // Cascade-rewrite audio events from the local UUID to
                    // the server-issued one. No-op if newId == oldId
                    // (defensive — shouldn't happen, server always issues
                    // its own id, but cheap to check).
                    try {
                        sleepAudioEventDao?.relinkSleepRecord(record.id, newId)
                    } catch (_: Exception) { /* non-fatal */ }
                }
                sleepDao.deleteById(record.id)
                sleepDao.insert(serverRecord.toEntity())
            } catch (_: Exception) {
                // skip, will retry next sync
            }
        }
    }

    companion object {
        private val syncUnsyncedMutex = Mutex()
    }

    suspend fun sync() {
        try {
            // §v2.15.9 — Retry server-side deletes for any pending
            // tombstones BEFORE pulling fresh server state. Otherwise
            // the subsequent getSleepRecords + insertAll would
            // resurrect rows the user already deleted but whose backend
            // call failed (the common case under the rate-limit issue
            // PR #137 fixed). Same pattern as AlarmRepository.sync().
            val tombstones = try { sleepDao.getAllTombstones() } catch (_: Exception) { emptyList() }
            for (t in tombstones) {
                try {
                    apiService.deleteSleepRecord(t.id)
                    sleepDao.deleteTombstone(t.id)
                } catch (e: HttpException) {
                    if (e.code() == 404) sleepDao.deleteTombstone(t.id)
                    // 5xx / 4xx-other: keep tombstone, try next sync
                } catch (_: IOException) {
                    // Network blip; try next sync
                }
            }

            syncUnsyncedSleepRecords()
            // §10.x-fix (v2.15.4) — Sweep orphans after the relink had
            // first crack at recovering them. Anything left has no
            // parent sleep_record and would only drive a stuck banner.
            cleanupOrphanedAudioEvents()

            val serverRecords = apiService.getSleepRecords(null, null)
            // §v2.15.9 — Exclude any records still tombstoned locally so
            // we don't re-insert what we're still trying to delete. Real
            // tombstones get cleared at the top of next sync once the
            // server delete finally goes through.
            val activeTombstones = try {
                sleepDao.getTombstonedIds().toSet()
            } catch (_: Exception) { emptySet() }
            val effectiveServerRecords = serverRecords.filter { it.id !in activeTombstones }
            val serverIds = effectiveServerRecords.map { it.id }.toSet()

            // Reconcile: drop local synced rows in the pulled window that no longer
            // exist on the server (deleted from web or another device).
            val now = System.currentTimeMillis()
            val day = 24L * 60L * 60L * 1000L
            val rangeStart = now - 30L * day
            val rangeEnd = now
            val localSyncedIds = sleepDao.getSyncedIdsInRange(rangeStart, rangeEnd)

            // §v2.15.10 — Two-empty-response guard. A single empty
            // server response is suspicious (backend hiccup, rate
            // limit, auth quirk, query-window edge case). Require two
            // in a row before believing it and mirroring the deletion
            // to local. Without this, the user's calendar / checklist
            // disappeared during the v2.15.7 rate-limit storm even
            // though the backend was intact.
            val shouldReconcile = shouldReconcile(
                serverEmpty = effectiveServerRecords.isEmpty(),
                localHasRows = localSyncedIds.isNotEmpty(),
                entityKey = SyncStateStore.ENTITY_SLEEP,
            )
            if (shouldReconcile) {
                for (id in localSyncedIds) {
                    if (id !in serverIds) {
                        sleepDao.deleteById(id)
                    }
                }
            }

            sleepDao.insertAll(effectiveServerRecords.map { it.toEntity() })

            // §10 — Also retry any unsynced audio events as part of the
            // standard sync pass. Failures here don't abort the outer sync.
            try { syncAudioEvents() } catch (_: Exception) { }

            // §10-backfill (v2.13.14) — Pull audio events for the past 7
            // days of records into local Room. Backend stores them
            // canonically (uploaded at session-end) but pre-v2.13.14 we
            // never pulled them back, so a fresh install / new device
            // lost the Tonight's Sounds dashboard card + the snore/cough
            // sections inside past-record expansion until the user
            // generated new ones. Idempotent — INSERT OR REPLACE on the
            // primary id de-dupes if the events are already local.
            try {
                fetchRecentAudioEvents(effectiveServerRecords.map { it.id }, sinceMs = now - 7L * day)
            } catch (_: Exception) { /* non-fatal */ }
        } catch (_: Exception) {
            // offline, skip sync
        }
    }

    /// §10-backfill (v2.13.14) — Fetch audio events from the server for
    /// every sleep_record whose actualBedtime is at/after `sinceMs`, and
    /// persist them locally. Used by the standard sync pass to repopulate
    /// the Tonight's Sounds card + past-record expansions after a fresh
    /// install. Also reachable directly from
    /// `SleepViewModel.fetchRecordDetails` for older records the user
    /// expands manually. One round-trip per record — fine at our record
    /// counts (≤ ~10/week); revisit with a since= bulk endpoint once
    /// active users grow.
    suspend fun fetchAudioEventsForRecord(sleepRecordId: String): List<SleepAudioEventEntity> {
        val dao = sleepAudioEventDao ?: return emptyList()
        val events = apiService.getSleepAudioEvents(sleepRecordId)
        val entities = events.map { it.toEntity() }
        // §10.x-fix (v2.14.1) — Replace, not merge. v2.14.0 created local
        // rows with phone-side UUIDs and server rows with backend-side
        // UUIDs (different); INSERT-OR-REPLACE by primary key never deduped
        // them, leaving every event displayed twice. Wiping the per-record
        // slice before re-inserting makes the server the single source of
        // truth and clears those ghost duplicates on the first fetch after
        // upgrading.
        dao.deleteBySleepRecord(sleepRecordId)
        if (entities.isNotEmpty()) dao.insertAll(entities)
        return entities
    }

    private suspend fun fetchRecentAudioEvents(allRecordIds: List<String>, sinceMs: Long) {
        val dao = sleepAudioEventDao ?: return
        // Filter to records whose actualBedtime is recent. We don't have
        // the timestamps here (we only have ids from the sync response),
        // so re-read from Room — already inserted above.
        val recent = sleepDao
            .getRecordsBetween(sinceMs, Long.MAX_VALUE)
            .firstOrNull()
            ?.map { it.id }
            ?.toSet()
            ?: return
        val targets = allRecordIds.filter { it in recent }
        for (id in targets) {
            try {
                val events = apiService.getSleepAudioEvents(id)
                if (events.isNotEmpty()) {
                    dao.insertAll(events.map { it.toEntity() })
                }
            } catch (_: Exception) {
                // Skip this record; next sync will retry. Per-record
                // failure shouldn't abort the rest of the back-fill.
            }
        }
    }

    /**
     * §v2.15.10 — Two-empty-response guard helper. Returns true when
     * the destructive reconciliation should proceed. Same logic in
     * CalendarRepository and ChecklistRepository.
     *
     * Decision matrix:
     *   - serverEmpty=false → reset streak, proceed (normal case)
     *   - serverEmpty=true, local empty → no-op either way, reset
     *     streak so future signal isn't stale, proceed
     *   - serverEmpty=true, local has rows, streak <  2 → suspicious,
     *     bump streak, SKIP reconcile this cycle
     *   - serverEmpty=true, local has rows, streak >= 2 → confirmed,
     *     reset streak, PROCEED (the wipe was real)
     */
    private fun shouldReconcile(
        serverEmpty: Boolean,
        localHasRows: Boolean,
        entityKey: String,
    ): Boolean {
        val store = syncStateStore ?: return true // not wired → behave like before
        if (!serverEmpty) {
            store.resetEmptyStreak(entityKey)
            return true
        }
        if (!localHasRows) {
            store.resetEmptyStreak(entityKey)
            return true
        }
        val streak = store.incrementEmptyStreak(entityKey)
        return if (streak >= SyncStateStore.REQUIRED_EMPTY_STREAK) {
            store.resetEmptyStreak(entityKey)
            true
        } else {
            false
        }
    }
}
