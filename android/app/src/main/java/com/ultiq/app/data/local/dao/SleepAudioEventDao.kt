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

    // §10.x-fix — Reactive count for the "last night's sounds haven't synced"
    // banner. Flow re-emits on every row insert/update, so the banner shows
    // up the moment a session-end upload fails and disappears the moment
    // WorkManager catches up.
    @Query("SELECT COUNT(*) FROM sleep_audio_events WHERE isSynced = 0 AND sleepRecordId NOT LIKE 'pending-%'")
    fun observeUnsyncedCount(): Flow<Int>

    // §10.x-fix — Snapshot count, used by the WorkManager worker to decide
    // whether there's anything to retry without subscribing to a Flow.
    @Query("SELECT COUNT(*) FROM sleep_audio_events WHERE isSynced = 0 AND sleepRecordId NOT LIKE 'pending-%'")
    suspend fun countUnsynced(): Int

    @Query("UPDATE sleep_audio_events SET isSynced = 1 WHERE id IN (:ids)")
    suspend fun markSyncedBatch(ids: List<String>)

    @Query("DELETE FROM sleep_audio_events WHERE sleepRecordId = :sleepRecordId")
    suspend fun deleteBySleepRecord(sleepRecordId: String)

    // §10.x-fix (4th piece) — During the live session, the aggregator writes
    // each event to Room immediately with sleepRecordId="pending-{startMs}"
    // (the real sleep_records row doesn't exist yet). At session-end, after
    // the real record is created on the backend, this re-links every
    // placeholder row in one UPDATE so the session-end batch upload picks
    // them up by `WHERE sleepRecordId = realId`.
    @Query("UPDATE sleep_audio_events SET sleepRecordId = :realId, userId = :userId WHERE sleepRecordId = :pendingId")
    suspend fun relinkPendingSession(pendingId: String, realId: String, userId: String)

    // §10.x-fix (4th piece) — Cleanup pass at session-start. If a previous
    // session never reached "End Sleep" (force-stop, uninstall+reinstall,
    // forgotten session), its placeholder rows would accumulate forever.
    // Delete any pending-* rows whose first event is older than 24h.
    @Query("DELETE FROM sleep_audio_events WHERE sleepRecordId LIKE 'pending-%' AND startedAt < :cutoffMs")
    suspend fun deleteOrphanPendingEvents(cutoffMs: Long)

    // §10.x-fix (4th piece) — Read events for a still-live session so the
    // ViewModel can snapshot them into the End Sleep dialog even if the
    // in-memory `pendingAudioEvents` was lost to a service restart.
    @Query("SELECT * FROM sleep_audio_events WHERE sleepRecordId = :pendingId ORDER BY startedAt ASC")
    suspend fun getByPendingSession(pendingId: String): List<SleepAudioEventEntity>

    // §10.x-fix (v2.15.3) — Cascade rewrite for the local-fallback path.
    // When createSleepRecord fails over the network at session-end, the
    // ViewModel inserts a local-only sleep_record with a random UUID and
    // saveAudioEvents stamps the audio events with that UUID. Later
    // SleepRepository.sync() re-creates the sleep_record on the backend
    // and the backend issues a new id; the old local row is deleted. Without
    // this UPDATE, the audio events are orphaned forever — they reference
    // a sleepRecordId the backend never knew. Caller must invoke this
    // *before* deleting the local sleep_record row.
    @Query("UPDATE sleep_audio_events SET sleepRecordId = :newId WHERE sleepRecordId = :oldId")
    suspend fun relinkSleepRecord(oldId: String, newId: String)
}
