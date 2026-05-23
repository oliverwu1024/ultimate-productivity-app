package com.ultiq.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.ultiq.app.data.local.entity.SleepAudioEventEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SleepAudioEventDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(event: SleepAudioEventEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(events: List<SleepAudioEventEntity>)

    @Query("SELECT * FROM sleep_audio_events WHERE sleepRecordId = :sleepRecordId ORDER BY startedAt ASC")
    fun observeBySleepRecord(sleepRecordId: String): Flow<List<SleepAudioEventEntity>>

    @Query("SELECT * FROM sleep_audio_events WHERE sleepRecordId = :sleepRecordId ORDER BY startedAt ASC")
    suspend fun getBySleepRecord(sleepRecordId: String): List<SleepAudioEventEntity>

    @Query("SELECT COUNT(*) FROM sleep_audio_events WHERE sleepRecordId = :sleepRecordId AND eventType = :type")
    suspend fun countByType(sleepRecordId: String, type: String): Int

    @Query("SELECT * FROM sleep_audio_events WHERE isSynced = 0")
    suspend fun getUnsynced(): List<SleepAudioEventEntity>

    @Query("UPDATE sleep_audio_events SET isSynced = 1 WHERE id IN (:ids)")
    suspend fun markSyncedBatch(ids: List<String>)

    @Query("DELETE FROM sleep_audio_events WHERE sleepRecordId = :sleepRecordId")
    suspend fun deleteBySleepRecord(sleepRecordId: String)
}
