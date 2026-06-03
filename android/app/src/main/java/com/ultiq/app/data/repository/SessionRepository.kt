package com.ultiq.app.data.repository

import com.ultiq.app.data.achievements.AchievementChecker
import com.ultiq.app.data.local.dao.PhonePickupDao
import com.ultiq.app.data.local.dao.SessionDao
import com.ultiq.app.data.local.entity.PhonePickupEntity
import com.ultiq.app.data.local.entity.SessionEntity
import com.ultiq.app.data.remote.ApiService
import com.ultiq.app.data.remote.dto.BatchCreatePhonePickupsDto
import com.ultiq.app.data.remote.dto.CreatePhonePickupBatchItemDto
import com.ultiq.app.data.remote.dto.CreateSessionDto
import com.ultiq.app.data.remote.dto.PhonePickupDto
import com.ultiq.app.data.remote.dto.UpdateSessionDto
import com.ultiq.app.data.remote.dto.toCreateDto
import com.ultiq.app.data.remote.dto.toDto
import com.ultiq.app.data.remote.dto.toEntity
import com.ultiq.app.data.remote.dto.toLocalEntity
import com.ultiq.app.data.remote.dto.toUpdateDto
import com.ultiq.app.service.PickupEvent
import kotlinx.coroutines.flow.Flow
import java.time.Instant
import java.util.UUID

// §v2.16.18 — Mirrors SleepRepository.BATCH_CHUNK_SIZE. WAF body limit
// is 8KB; ~200B per pickup → 25 events ≈ 5KB stays safely under it.
private const val SESSION_PICKUP_BATCH_CHUNK_SIZE = 25

class SessionRepository(
    private val sessionDao: SessionDao,
    private val apiService: ApiService,
    private val achievementChecker: AchievementChecker? = null,
    // §v2.16.18 — Optional so callers that don't need pickup persistence
    // (sync workers, etc.) keep working unchanged. ViewModel passes it in.
    private val phonePickupDao: PhonePickupDao? = null,
) {
    fun getAllSessions(): Flow<List<SessionEntity>> = sessionDao.getAllSessions()

    fun getSessionsBetween(start: Long, end: Long): Flow<List<SessionEntity>> =
        sessionDao.getSessionsBetween(start, end)

    fun getActiveSessions(): Flow<List<SessionEntity>> = sessionDao.getActiveSessions()

    suspend fun createSession(
        tag: String,
        workDuration: Int,
        userId: String,
        checklistItemId: String? = null,
    ): Result<SessionEntity> {
        // Breaks were removed from the UX; persisted as 0 for new sessions so the
        // legacy column on the entity/DTO stays populated.
        val createDto = CreateSessionDto(
            tag = tag,
            work_duration = workDuration,
            break_duration = 0,
            checklist_item_id = checklistItemId,
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
                    breakDuration = 0,
                    phonePickups = 0,
                    startedAt = now,
                    endedAt = null,
                    completed = false,
                    createdAt = now,
                    updatedAt = now,
                    checklistItemId = checklistItemId,
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

        val result = try {
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
        if (result.isSuccess) {
            runCatching { achievementChecker?.checkAndStore() }
        }
        return result
    }

    /**
     * §v2.16.18 — Persist per-pickup detail for a just-completed focus
     * session so the past-sessions card can render a timeline. Mirror
     * of `SleepRepository.savePickupEvents` (which v2.16.17 made
     * local-first to survive offline saves).
     *
     * 1. Write rows to Room with `sessionId` set + `isSynced=false` so
     *    the SessionsViewModel Flow subscription renders them
     *    immediately even when offline.
     * 2. Attempt the backend batch upload (chunked at 25 to stay under
     *    the WAF 8KB body limit).
     * 3. On success, replace local rows with server canonical so
     *    subsequent device-syncs match server ids.
     *
     * Each event carries a client-generated UUID — backend's v2.16.18
     * `ON CONFLICT (id) DO NOTHING` collapses retries onto one row, so
     * a buggy worker can't spawn duplicates.
     *
     * Failure is non-fatal — the session row already carries the
     * aggregate pickup count, so the past-sessions card still shows
     * "N pickups" even when the timeline upload fails.
     */
    suspend fun savePickupEvents(
        sessionId: String,
        userId: String,
        events: List<PickupEvent>,
    ): Result<List<PhonePickupDto>> {
        if (events.isEmpty()) return Result.success(emptyList())

        val now = System.currentTimeMillis()
        val localRows = events.map { e ->
            PhonePickupEntity(
                id = UUID.randomUUID().toString(),
                userId = userId,
                sleepRecordId = null,
                sessionId = sessionId,
                pickedUpAt = e.pickedUpAt,
                durationSeconds = e.durationSeconds,
                appCategory = null,
                createdAt = now,
                isSynced = false,
            )
        }
        runCatching { phonePickupDao?.insertAll(localRows) }

        return try {
            val collected = mutableListOf<PhonePickupDto>()
            for ((index, chunk) in localRows.chunked(SESSION_PICKUP_BATCH_CHUNK_SIZE).withIndex()) {
                val resp = apiService.batchCreatePhonePickups(
                    BatchCreatePhonePickupsDto(
                        sleep_record_id = null,
                        session_id = sessionId,
                        events = chunk.map { row ->
                            CreatePhonePickupBatchItemDto(
                                id = row.id,
                                picked_up_at = Instant.ofEpochMilli(row.pickedUpAt).toString(),
                                duration_seconds = row.durationSeconds,
                                app_category = null,
                            )
                        },
                    ),
                )
                collected.addAll(resp)
                // Index used only to make the for-loop a single
                // statement; mark-synced is per-chunk so partial
                // progress sticks if a later chunk fails.
                runCatching {
                    phonePickupDao?.markSyncedBatch(chunk.map { it.id })
                }
                @Suppress("UNUSED_EXPRESSION") index
            }
            // Replace local stand-ins with server canonical (server ids
            // when the client omitted them; server-canonical app_category
            // when added in the future). Same wipe-and-reinsert pattern
            // as SleepRepository.
            runCatching {
                val dao = phonePickupDao
                if (dao != null) {
                    dao.deleteBySessionId(sessionId)
                    dao.insertAll(collected.map { it.toLocalEntity(synced = true) })
                }
            }
            Result.success(collected)
        } catch (e: Exception) {
            // Upload failed; local rows already on disk with isSynced=false.
            // Future device-sync passes can pick them up.
            Result.failure(e)
        }
    }

    /**
     * §v2.16.18 — Local-first read for the past-session pickup timeline.
     * Mirror of `SleepRepository.getPickupsForSleep` (v2.16.17 form).
     * Tries the backend first to get server canonical state, mirrors to
     * Room on success; on failure (offline / 5xx / 429) falls back to
     * whatever Room already has so the expanded session card stays
     * populated.
     */
    suspend fun getPickupsForSession(sessionId: String): List<PhonePickupDto> {
        val dao = phonePickupDao
        return try {
            val server = apiService.getPhonePickupsForSession(sessionId)
            runCatching {
                if (dao != null) {
                    dao.deleteBySessionId(sessionId)
                    dao.insertAll(server.map { it.toLocalEntity(synced = true) })
                }
            }
            server
        } catch (_: Exception) {
            val local = runCatching { dao?.getBySessionId(sessionId) }
                .getOrNull() ?: emptyList()
            local.map { it.toDto() }
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
            val serverIds = serverSessions.map { it.id }.toSet()

            // Reconcile: drop local synced rows in the pulled window that no longer
            // exist on the server.
            val now = System.currentTimeMillis()
            val day = 24L * 60L * 60L * 1000L
            val rangeStart = now - 30L * day
            val rangeEnd = now
            val localSyncedIds = sessionDao.getSyncedIdsInRange(rangeStart, rangeEnd)
            for (id in localSyncedIds) {
                if (id !in serverIds) {
                    sessionDao.deleteById(id)
                }
            }

            sessionDao.insertAll(serverSessions.map { it.toEntity() })
        } catch (_: Exception) {
            // offline, skip sync
        }
    }
}
