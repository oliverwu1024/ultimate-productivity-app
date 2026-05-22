package com.ultiq.app.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.ultiq.app.data.local.entity.AlarmEntity
import com.ultiq.app.data.local.entity.AlarmEventEntity
import com.ultiq.app.data.local.entity.AlarmTombstoneEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AlarmDao {

    // ── alarms ──────────────────────────────────────────────────────────────

    // §two-alarms-same-time: the `id` tiebreaker is load-bearing. Without
    // it, two alarms with the same trigger time tie in the ORDER BY, the
    // tie breaks non-deterministically per Flow re-emission, and the rows
    // visually swap in the LazyColumn whenever any one of them updates.
    // The user's toggle gets routed correctly by `key = alarm.id`, but the
    // row appears to "jump" to a different position, looking like the
    // wrong toggle responded.
    @Query("SELECT * FROM alarms ORDER BY triggerHour, triggerMinute, id")
    fun getAllAlarms(): Flow<List<AlarmEntity>>

    @Query("SELECT * FROM alarms WHERE enabled = 1")
    suspend fun getEnabledAlarmsSync(): List<AlarmEntity>

    @Query("SELECT * FROM alarms WHERE id = :id")
    suspend fun getAlarmById(id: String): AlarmEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAlarm(alarm: AlarmEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAlarms(alarms: List<AlarmEntity>)

    @Update
    suspend fun updateAlarm(alarm: AlarmEntity)

    @Delete
    suspend fun deleteAlarm(alarm: AlarmEntity)

    @Query("DELETE FROM alarms WHERE id = :id")
    suspend fun deleteAlarmById(id: String)

    @Query("SELECT * FROM alarms WHERE isSynced = 0")
    suspend fun getUnsyncedAlarms(): List<AlarmEntity>

    @Query("UPDATE alarms SET isSynced = 1 WHERE id = :id")
    suspend fun markAlarmSynced(id: String)

    @Query("SELECT id FROM alarms WHERE isSynced = 1")
    suspend fun getSyncedAlarmIds(): List<String>

    // ── alarm_events ────────────────────────────────────────────────────────

    @Query("SELECT * FROM alarm_events WHERE alarmId = :alarmId ORDER BY firedAt DESC")
    fun getEventsForAlarm(alarmId: String): Flow<List<AlarmEventEntity>>

    @Query("SELECT * FROM alarm_events WHERE firedAt BETWEEN :start AND :end ORDER BY firedAt DESC")
    fun getEventsBetween(start: Long, end: Long): Flow<List<AlarmEventEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEvent(event: AlarmEventEntity)

    @Update
    suspend fun updateEvent(event: AlarmEventEntity)

    @Query("SELECT * FROM alarm_events WHERE isSynced = 0")
    suspend fun getUnsyncedEvents(): List<AlarmEventEntity>

    @Query("UPDATE alarm_events SET isSynced = 1 WHERE id = :id")
    suspend fun markEventSynced(id: String)

    // ── alarm_tombstones ────────────────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTombstone(tombstone: AlarmTombstoneEntity)

    @Query("DELETE FROM alarm_tombstones WHERE id = :id")
    suspend fun deleteTombstone(id: String)

    @Query("SELECT * FROM alarm_tombstones")
    suspend fun getAllTombstones(): List<AlarmTombstoneEntity>

    @Query("SELECT id FROM alarm_tombstones")
    suspend fun getTombstonedIds(): List<String>
}
