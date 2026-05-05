package com.ultiq.app.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.ultiq.app.data.local.entity.SleepRecordEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SleepDao {

    @Query("SELECT * FROM sleep_records ORDER BY actualBedtime DESC")
    fun getAllRecords(): Flow<List<SleepRecordEntity>>

    @Query("SELECT * FROM sleep_records WHERE actualBedtime BETWEEN :start AND :end ORDER BY actualBedtime DESC")
    fun getRecordsBetween(start: Long, end: Long): Flow<List<SleepRecordEntity>>

    @Query("SELECT * FROM sleep_records WHERE id = :id")
    suspend fun getById(id: String): SleepRecordEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(record: SleepRecordEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(records: List<SleepRecordEntity>)

    @Delete
    suspend fun delete(record: SleepRecordEntity)

    @Query("DELETE FROM sleep_records WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("SELECT * FROM sleep_records WHERE isSynced = 0")
    suspend fun getUnsyncedRecords(): List<SleepRecordEntity>

    @Query("UPDATE sleep_records SET isSynced = 1 WHERE id = :id")
    suspend fun markSynced(id: String)

    @Query("SELECT id FROM sleep_records WHERE isSynced = 1 AND actualBedtime BETWEEN :start AND :end")
    suspend fun getSyncedIdsInRange(start: Long, end: Long): List<String>
}
