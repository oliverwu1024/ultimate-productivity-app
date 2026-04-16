package com.app.productivity.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.app.productivity.data.local.entity.CalendarEventEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CalendarEventDao {

    @Query("SELECT * FROM calendar_events ORDER BY startTime ASC")
    fun getAllEvents(): Flow<List<CalendarEventEntity>>

    @Query("SELECT * FROM calendar_events WHERE startTime BETWEEN :start AND :end ORDER BY startTime ASC")
    fun getEventsBetween(start: Long, end: Long): Flow<List<CalendarEventEntity>>

    @Query("SELECT * FROM calendar_events WHERE startTime BETWEEN :start AND :end AND category = :category ORDER BY startTime ASC")
    fun getEventsBetweenByCategory(start: Long, end: Long, category: String): Flow<List<CalendarEventEntity>>

    @Query("SELECT * FROM calendar_events WHERE id = :id")
    suspend fun getById(id: String): CalendarEventEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(event: CalendarEventEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(events: List<CalendarEventEntity>)

    @Update
    suspend fun update(event: CalendarEventEntity)

    @Delete
    suspend fun delete(event: CalendarEventEntity)

    @Query("DELETE FROM calendar_events WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("SELECT * FROM calendar_events WHERE (isRecurring = 0 AND startTime BETWEEN :start AND :end) OR (isRecurring = 1 AND startTime <= :end) ORDER BY startTime ASC")
    fun getEventsForRange(start: Long, end: Long): Flow<List<CalendarEventEntity>>

    @Query("SELECT * FROM calendar_events WHERE isSynced = 0")
    suspend fun getUnsyncedEvents(): List<CalendarEventEntity>

    @Query("UPDATE calendar_events SET isSynced = 1 WHERE id = :id")
    suspend fun markSynced(id: String)
}
