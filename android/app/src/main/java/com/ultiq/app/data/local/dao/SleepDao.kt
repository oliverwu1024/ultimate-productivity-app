package com.ultiq.app.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.ultiq.app.data.local.entity.SleepRecordEntity
import com.ultiq.app.data.local.entity.SleepTombstoneEntity
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

    // §v2.16.1 — Used by the audio-upload worker to self-heal when a
    // batch-create returns 403. A 403 from owns_sleep_record means the
    // local row is marked synced but the backend row is gone, so flip
    // it back to unsynced and let syncUnsyncedSleepRecords re-create it
    // on the next pass (which cascade-relinks the audio events).
    @Query("UPDATE sleep_records SET isSynced = 0 WHERE id = :id")
    suspend fun markUnsynced(id: String)

    @Query("SELECT id FROM sleep_records WHERE isSynced = 1 AND actualBedtime BETWEEN :start AND :end")
    suspend fun getSyncedIdsInRange(start: Long, end: Long): List<String>

    // ── sleep_tombstones ────────────────────────────────────────────────────
    // §v2.15.9 — same pattern as alarm_tombstones. Mark for delete BEFORE
    // wiping local row, so a sync race can't resurrect the record from
    // the server copy. Clear once the server confirms the delete (or 404s).

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTombstone(tombstone: SleepTombstoneEntity)

    @Query("DELETE FROM sleep_tombstones WHERE id = :id")
    suspend fun deleteTombstone(id: String)

    @Query("SELECT * FROM sleep_tombstones")
    suspend fun getAllTombstones(): List<SleepTombstoneEntity>

    @Query("SELECT id FROM sleep_tombstones")
    suspend fun getTombstonedIds(): List<String>
}
