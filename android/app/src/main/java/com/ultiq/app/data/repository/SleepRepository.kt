package com.ultiq.app.data.repository

import com.ultiq.app.audio.SleepAudioClipCapture
import com.ultiq.app.audio.SleepAudioClipUploader
import com.ultiq.app.data.achievements.AchievementChecker
import com.ultiq.app.data.local.dao.SleepAudioEventDao
import com.ultiq.app.data.local.dao.SleepDao
import com.ultiq.app.data.local.entity.SleepAudioEventEntity
import com.ultiq.app.data.local.entity.SleepRecordEntity
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
import java.io.File
import java.util.UUID

class SleepRepository(
    private val sleepDao: SleepDao,
    private val apiService: ApiService,
    private val achievementChecker: AchievementChecker? = null,
    private val sleepAudioEventDao: SleepAudioEventDao? = null,
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
        sleepDao.deleteById(id)
        return try {
            apiService.deleteSleepRecord(id)
            Result.success(Unit)
        } catch (_: Exception) {
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
        try {
            withRetry {
                apiService.batchCreateSleepAudioEvents(
                    BatchCreateSleepAudioEventsDto(
                        sleep_record_id = sleepRecordId,
                        events = unsynced.map { it.toCreateDto() },
                    ),
                )
            }
        } catch (e: Throwable) {
            // Events upload exhausted retries. Rows stay isSynced=false.
            // Caller schedules WorkManager which will keep trying after
            // the app is closed.
            return Result.failure(e)
        }

        try {
            dao.markSyncedBatch(unsynced.map { it.id })
        } catch (_: Exception) {
            // Non-fatal — the next sync pass will re-mark.
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
     * §10.x-fix (WorkManager) — Scan `cacheDir/audio-clips/` for any
     * leftover clip files (named `clip-{eventId}.m4a`) and try to upload
     * each one. The uploader returns false on 404 (event not yet on
     * backend) or 409 (already attached) and the file stays on disk;
     * [SleepAudioClipCapture.pruneStale] handles eventual eviction at
     * the next session start. Files are removed on successful upload.
     */
    suspend fun uploadOrphanClipFiles(cacheDir: File) {
        if (sleepAudioEventDao == null) return
        val dir = SleepAudioClipCapture.clipDir(cacheDir)
        val files = dir.listFiles() ?: return
        for (file in files) {
            val name = file.name
            if (!name.startsWith("clip-") || !name.endsWith(".m4a")) continue
            val eventId = name.removePrefix("clip-").removeSuffix(".m4a")
            runCatching { clipUploader.upload(eventId, file) }
        }
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
            val resp = apiService.batchCreatePhonePickups(
                BatchCreatePhonePickupsDto(
                    sleep_record_id = sleepRecordId,
                    events = events.map { it.toBatchItemDto() },
                ),
            )
            Result.success(resp)
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
                apiService.batchCreateSleepAudioEvents(
                    BatchCreateSleepAudioEventsDto(
                        sleep_record_id = sleepRecordId,
                        events = batch.map { it.toCreateDto() },
                    ),
                )
                dao.markSyncedBatch(batch.map { it.id })
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
    suspend fun syncUnsyncedSleepRecords() {
        val unsynced = try {
            sleepDao.getUnsyncedRecords()
        } catch (_: Exception) {
            return
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

    suspend fun sync() {
        try {
            syncUnsyncedSleepRecords()

            val serverRecords = apiService.getSleepRecords(null, null)
            val serverIds = serverRecords.map { it.id }.toSet()

            // Reconcile: drop local synced rows in the pulled window that no longer
            // exist on the server (deleted from web or another device).
            val now = System.currentTimeMillis()
            val day = 24L * 60L * 60L * 1000L
            val rangeStart = now - 30L * day
            val rangeEnd = now
            val localSyncedIds = sleepDao.getSyncedIdsInRange(rangeStart, rangeEnd)
            for (id in localSyncedIds) {
                if (id !in serverIds) {
                    sleepDao.deleteById(id)
                }
            }

            sleepDao.insertAll(serverRecords.map { it.toEntity() })

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
                fetchRecentAudioEvents(serverRecords.map { it.id }, sinceMs = now - 7L * day)
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
}
