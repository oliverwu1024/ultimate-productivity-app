package com.ultiq.app.data.repository

import com.ultiq.app.data.achievements.AchievementChecker
import com.ultiq.app.data.local.dao.SleepDao
import com.ultiq.app.data.local.entity.SleepRecordEntity
import com.ultiq.app.data.remote.ApiService
import com.ultiq.app.data.remote.dto.CreateSleepRecordDto
import com.ultiq.app.data.remote.dto.toCreateDto
import com.ultiq.app.data.remote.dto.toEntity
import kotlinx.coroutines.flow.Flow
import java.util.UUID

class SleepRepository(
    private val sleepDao: SleepDao,
    private val apiService: ApiService,
    private val achievementChecker: AchievementChecker? = null,
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
            sleepDao.insertAll(serverRecords.map { it.toEntity() })
        } catch (_: Exception) {
            // offline, skip sync
        }
    }
}
