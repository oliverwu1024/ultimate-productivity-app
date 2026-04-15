package com.app.productivity.data.repository

import com.app.productivity.data.local.dao.SessionDao
import com.app.productivity.data.local.entity.SessionEntity
import com.app.productivity.data.remote.ApiService
import com.app.productivity.data.remote.dto.CreateSessionDto
import com.app.productivity.data.remote.dto.UpdateSessionDto
import com.app.productivity.data.remote.dto.toCreateDto
import com.app.productivity.data.remote.dto.toEntity
import com.app.productivity.data.remote.dto.toUpdateDto
import kotlinx.coroutines.flow.Flow
import java.util.UUID

class SessionRepository(
    private val sessionDao: SessionDao,
    private val apiService: ApiService
) {
    fun getAllSessions(): Flow<List<SessionEntity>> = sessionDao.getAllSessions()

    fun getSessionsBetween(start: Long, end: Long): Flow<List<SessionEntity>> =
        sessionDao.getSessionsBetween(start, end)

    fun getActiveSessions(): Flow<List<SessionEntity>> = sessionDao.getActiveSessions()

    suspend fun createSession(tag: String, workDuration: Int, breakDuration: Int, userId: String): Result<SessionEntity> {
        val createDto = CreateSessionDto(
            tag = tag,
            work_duration = workDuration,
            break_duration = breakDuration
        )
        return try {
            val serverSession = apiService.createSession(createDto)
            val entity = serverSession.toEntity()
            sessionDao.insert(entity)
            Result.success(entity)
        } catch (e: Exception) {
            try {
                val now = System.currentTimeMillis()
                val entity = SessionEntity(
                    id = UUID.randomUUID().toString(),
                    userId = userId,
                    tag = tag,
                    durationMinutes = 0,
                    workDuration = workDuration,
                    breakDuration = breakDuration,
                    phonePickups = 0,
                    startedAt = now,
                    endedAt = null,
                    completed = false,
                    createdAt = now,
                    updatedAt = now,
                    isSynced = false
                )
                sessionDao.insert(entity)
                Result.success(entity)
            } catch (localErr: Exception) {
                Result.failure(localErr)
            }
        }
    }

    suspend fun completeSession(id: String, durationMinutes: Int, phonePickups: Int): Result<SessionEntity> {
        val existing = sessionDao.getById(id)
            ?: return Result.failure(IllegalArgumentException("Session not found"))
        val now = System.currentTimeMillis()

        return try {
            val updateDto = UpdateSessionDto(
                ended_at = null,
                completed = true,
                phone_pickups = phonePickups,
                duration_minutes = durationMinutes
            )
            val serverSession = apiService.updateSession(id, updateDto)
            val entity = serverSession.toEntity()
            sessionDao.insert(entity)
            Result.success(entity)
        } catch (e: Exception) {
            try {
                val updated = existing.copy(
                    completed = true,
                    durationMinutes = durationMinutes,
                    phonePickups = phonePickups,
                    endedAt = now,
                    updatedAt = now,
                    isSynced = false
                )
                sessionDao.insert(updated)
                Result.success(updated)
            } catch (localErr: Exception) {
                Result.failure(localErr)
            }
        }
    }

    suspend fun deleteSession(id: String): Result<Unit> {
        sessionDao.deleteById(id)
        return try {
            apiService.deleteSession(id)
            Result.success(Unit)
        } catch (_: Exception) {
            Result.success(Unit)
        }
    }

    suspend fun sync() {
        try {
            val unsynced = sessionDao.getUnsyncedSessions()
            for (session in unsynced) {
                try {
                    if (session.completed) {
                        // Completed offline — try update first (session may exist on server)
                        try {
                            val serverSession = apiService.updateSession(session.id, session.toUpdateDto())
                            sessionDao.deleteById(session.id)
                            sessionDao.insert(serverSession.toEntity())
                        } catch (_: Exception) {
                            // Not on server — create then complete
                            val created = apiService.createSession(session.toCreateDto())
                            val updated = apiService.updateSession(created.id, session.toUpdateDto())
                            sessionDao.deleteById(session.id)
                            sessionDao.insert(updated.toEntity())
                        }
                    } else {
                        // Incomplete — just create on server
                        val serverSession = apiService.createSession(session.toCreateDto())
                        sessionDao.deleteById(session.id)
                        sessionDao.insert(serverSession.toEntity())
                    }
                } catch (_: Exception) {
                    // skip, will retry next sync
                }
            }

            val serverSessions = apiService.getSessions(null, null, null)
            sessionDao.insertAll(serverSessions.map { it.toEntity() })
        } catch (_: Exception) {
            // offline, skip sync
        }
    }
}
