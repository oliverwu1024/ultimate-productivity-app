package com.ultiq.app.data.repository

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
import java.util.UUID

class SleepRepository(
    private val sleepDao: SleepDao,
    private val apiService: ApiService,
    private val achievementChecker: AchievementChecker? = null,
    private val sleepAudioEventDao: SleepAudioEventDao? = null,
) {
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
            apiService.batchCreateSleepAudioEvents(
                BatchCreateSleepAudioEventsDto(
                    sleep_record_id = sleepRecordId,
                    events = stamped.map { it.toCreateDto() },
                ),
            )
            dao.markSyncedBatch(stamped.map { it.id })
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
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
        } catch (_: Exception) {
            // offline, skip sync
        }
    }
}
