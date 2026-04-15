package com.app.productivity.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.app.productivity.data.local.entity.SessionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SessionDao {

    @Query("SELECT * FROM productivity_sessions ORDER BY startedAt DESC")
    fun getAllSessions(): Flow<List<SessionEntity>>

    @Query("SELECT * FROM productivity_sessions WHERE startedAt BETWEEN :start AND :end ORDER BY startedAt DESC")
    fun getSessionsBetween(start: Long, end: Long): Flow<List<SessionEntity>>

    @Query("SELECT * FROM productivity_sessions WHERE id = :id")
    suspend fun getById(id: String): SessionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(session: SessionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(sessions: List<SessionEntity>)

    @Update
    suspend fun update(session: SessionEntity)

    @Delete
    suspend fun delete(session: SessionEntity)

    @Query("DELETE FROM productivity_sessions WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("SELECT * FROM productivity_sessions WHERE completed = 0 ORDER BY startedAt DESC")
    fun getActiveSessions(): Flow<List<SessionEntity>>

    @Query("SELECT * FROM productivity_sessions WHERE isSynced = 0")
    suspend fun getUnsyncedSessions(): List<SessionEntity>

    @Query("UPDATE productivity_sessions SET isSynced = 1 WHERE id = :id")
    suspend fun markSynced(id: String)

    @Query("SELECT DISTINCT (startedAt / 86400000) * 86400000 FROM productivity_sessions WHERE completed = 1 AND startedAt BETWEEN :start AND :end ORDER BY 1 DESC")
    suspend fun getCompletedSessionDates(start: Long, end: Long): List<Long>

    @Query("SELECT SUM(durationMinutes) FROM productivity_sessions WHERE completed = 1 AND startedAt BETWEEN :start AND :end")
    suspend fun getTotalFocusMinutes(start: Long, end: Long): Int?
}
