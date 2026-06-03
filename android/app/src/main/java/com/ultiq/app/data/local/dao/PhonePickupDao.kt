package com.ultiq.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.ultiq.app.data.local.entity.PhonePickupEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PhonePickupDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(rows: List<PhonePickupEntity>)

    /// §v2.16.17 — Reactive read used by SleepViewModel.fetchRecordDetails.
    /// Subscribes for the lifetime of the ViewModel; any write to this
    /// record's pickups (initial save, post-online refresh, server pull)
    /// triggers a recomposition with the new list. This is the fix for
    /// the "pickup timeline disappears after offline save" bug.
    @Query("SELECT * FROM phone_pickups WHERE sleepRecordId = :sleepRecordId ORDER BY pickedUpAt ASC")
    fun observeBySleepRecord(sleepRecordId: String): Flow<List<PhonePickupEntity>>

    @Query("SELECT * FROM phone_pickups WHERE sleepRecordId = :sleepRecordId ORDER BY pickedUpAt ASC")
    suspend fun getBySleepRecord(sleepRecordId: String): List<PhonePickupEntity>

    @Query("SELECT * FROM phone_pickups WHERE isSynced = 0")
    suspend fun getUnsynced(): List<PhonePickupEntity>

    @Query("UPDATE phone_pickups SET isSynced = 1 WHERE id IN (:ids)")
    suspend fun markSyncedBatch(ids: List<String>)

    /// §v2.16.17 — Wipe + re-insert pattern used by fetchPickupsForRecord
    /// so the server's canonical view (with server ids + app_category)
    /// replaces local stand-ins. Same pattern as
    /// SleepRepository.fetchAudioEventsForRecord.
    @Query("DELETE FROM phone_pickups WHERE sleepRecordId = :sleepRecordId")
    suspend fun deleteBySleepRecord(sleepRecordId: String)
}
