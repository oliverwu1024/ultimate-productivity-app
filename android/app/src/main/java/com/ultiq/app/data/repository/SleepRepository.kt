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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
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
     * §10 — Persist sleep audio events (snore/cough) captured during the
     * session that was just saved. The events were emitted by the aggregator
     * with placeholder userId/sleepRecordId because the sleep_record didn't
     * exist yet; this method stamps the real IDs, writes to Room, and
     * batch-uploads to the backend.
     *
     * Failure modes:
     *  - Backend upload fails → events stay in Room with isSynced=false and
     *    will be retried by a later [syncAudioEvents] pass.
     *  - Room insert fails → returned as Result.failure; the in-memory
     *    buffer the caller pulled from is the only loss point.
     */
    suspend fun saveAudioEvents(
        sleepRecordId: String,
        userId: String,
        events: List<SleepAudioEventEntity>,
    ): Result<Unit> {
        val dao = sleepAudioEventDao ?: return Result.success(Unit)
        if (events.isEmpty()) return Result.success(Unit)

        val stamped = events.map { e ->
            e.copy(sleepRecordId = sleepRecordId, userId = userId)
        }

        try {
            dao.insertAll(stamped)
        } catch (e: Exception) {
            return Result.failure(e)
        }

        return try {
            val serverEvents = apiService.batchCreateSleepAudioEvents(
                BatchCreateSleepAudioEventsDto(
                    sleep_record_id = sleepRecordId,
                    events = stamped.map { it.toCreateDto() },
                ),
            )
            dao.markSyncedBatch(stamped.map { it.id })

            // §10.x — Upload any clips captured during the session. The
            // backend returns events in the same order we posted them, so
            // we pair positionally: stamped[i] (local id, may have a clip
            // on disk) → serverEvents[i] (server id, target of POST .../clip).
            // Failures are non-fatal — the event row is already saved; the
            // clip just doesn't appear in playback. Captured-but-unuploaded
            // files are cleared by [SleepAudioClipCapture.clearAll] at the
            // end of this pass so disk doesn't grow unbounded.
            uploadCapturedClips(stamped, serverEvents)
            SleepAudioClipCapture.clearAll()

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun uploadCapturedClips(
        local: List<SleepAudioEventEntity>,
        server: List<com.ultiq.app.data.remote.dto.SleepAudioEventDto>,
    ) {
        if (SleepAudioClipCapture.pendingCount() == 0) return
        val n = minOf(local.size, server.size)
        for (i in 0 until n) {
            val clipFile = SleepAudioClipCapture.takeClipFor(local[i].id) ?: continue
            try {
                clipUploader.upload(server[i].id, clipFile)
            } catch (_: Throwable) {
                // Logged inside uploader; swallow here so one failed clip
                // doesn't abort the rest of the upload pass.
            }
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

    suspend fun sync() {
        try {
            val unsynced = sleepDao.getUnsyncedRecords()
            for (record in unsynced) {
                try {
                    val dto = record.toCreateDto()
                    val serverRecord = apiService.createSleepRecord(dto)
                    sleepDao.deleteById(record.id)
                    sleepDao.insert(serverRecord.toEntity())
                } catch (_: Exception) {
                    // skip, will retry next sync
                }
            }

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
