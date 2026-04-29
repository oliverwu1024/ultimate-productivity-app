package com.ultiq.app.data.repository

import android.util.Log
import com.ultiq.app.data.local.dao.ChecklistDao
import com.ultiq.app.data.local.entity.ChecklistEntity
import com.ultiq.app.data.remote.ApiService
import com.ultiq.app.data.remote.dto.CreateChecklistItemDto
import com.ultiq.app.data.remote.dto.toCreateDto
import com.ultiq.app.data.remote.dto.toEntity
import com.ultiq.app.data.remote.dto.toUpdateDto
import kotlinx.coroutines.flow.Flow
import retrofit2.HttpException
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.UUID

class ChecklistRepository(
    private val dao: ChecklistDao,
    private val apiService: ApiService,
) {
    private val tag = "ChecklistRepo"
    private val isoDate: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE

    fun getByDate(epochDay: Long): Flow<List<ChecklistEntity>> = dao.getByDate(epochDay)

    fun getOpenForDate(epochDay: Long): Flow<List<ChecklistEntity>> = dao.getOpenForDate(epochDay)

    fun getInRange(startEpochDay: Long, endEpochDay: Long): Flow<List<ChecklistEntity>> =
        dao.getInRange(startEpochDay, endEpochDay)

    suspend fun create(
        userId: String,
        title: String,
        description: String?,
        dueDate: LocalDate,
        estimatedMinutes: Int?,
        priority: Int,
    ): Result<ChecklistEntity> {
        val createDto = CreateChecklistItemDto(
            title = title,
            description = description,
            due_date = dueDate.format(isoDate),
            estimated_minutes = estimatedMinutes,
            priority = priority,
        )
        return try {
            val server = apiService.createChecklistItem(createDto)
            val entity = server.toEntity()
            dao.insert(entity)
            Log.d(tag, "create — server returned id=${server.id} title='$title'")
            Result.success(entity)
        } catch (e: Exception) {
            Log.w(tag, "create — API failed for title='$title', falling back to local-only", e)
            // Offline fallback
            val now = System.currentTimeMillis()
            val entity = ChecklistEntity(
                id = UUID.randomUUID().toString(),
                userId = userId,
                title = title,
                description = description,
                dueDateEpochDay = dueDate.toEpochDay(),
                estimatedMinutes = estimatedMinutes,
                priority = priority,
                completed = false,
                completedAt = null,
                createdAt = now,
                updatedAt = now,
                isSynced = false,
            )
            try {
                dao.insert(entity)
                Result.success(entity)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    suspend fun update(item: ChecklistEntity): Result<ChecklistEntity> {
        val now = System.currentTimeMillis()
        val updated = item.copy(updatedAt = now, isSynced = false)
        return try {
            dao.insert(updated)
            val server = apiService.updateChecklistItem(item.id, item.toUpdateDto())
            val entity = server.toEntity()
            dao.insert(entity)
            Result.success(entity)
        } catch (_: Exception) {
            Result.success(updated)
        }
    }

    suspend fun markCompleted(id: String): Result<Unit> {
        val now = System.currentTimeMillis()
        dao.markCompletedLocally(id, completedAt = now, updatedAt = now)
        return try {
            val server = apiService.completeChecklistItem(id)
            dao.insert(server.toEntity())
            Result.success(Unit)
        } catch (_: Exception) {
            Result.success(Unit)
        }
    }

    suspend fun markIncomplete(id: String): Result<Unit> {
        val now = System.currentTimeMillis()
        dao.markIncompleteLocally(id, updatedAt = now)
        return try {
            val existing = dao.getById(id) ?: return Result.success(Unit)
            val server = apiService.updateChecklistItem(id, existing.toUpdateDto())
            dao.insert(server.toEntity())
            Result.success(Unit)
        } catch (_: Exception) {
            Result.success(Unit)
        }
    }

    suspend fun delete(id: String): Result<Unit> {
        val existing = dao.getById(id)
        Log.d(tag, "delete id=$id existsLocally=${existing != null} isSynced=${existing?.isSynced}")

        if (existing != null && !existing.isSynced) {
            dao.deleteById(id)
            Log.d(tag, "delete id=$id — dropped local-only row, no API call")
            return Result.success(Unit)
        }

        return try {
            apiService.deleteChecklistItem(id)
            dao.deleteById(id)
            Log.d(tag, "delete id=$id — API + local both succeeded")
            Result.success(Unit)
        } catch (e: HttpException) {
            Log.w(tag, "delete id=$id — API HTTP ${e.code()}", e)
            if (e.code() == 404) {
                dao.deleteById(id)
                Result.success(Unit)
            } else {
                Result.failure(e)
            }
        } catch (e: Exception) {
            Log.w(tag, "delete id=$id — API failed", e)
            Result.failure(e)
        }
    }

    suspend fun bulkCreate(items: List<CreateChecklistItemDto>): Result<List<ChecklistEntity>> {
        if (items.isEmpty()) return Result.success(emptyList())
        return try {
            val server = apiService.bulkCreateChecklistItems(items)
            val entities = server.map { it.toEntity() }
            dao.insertAll(entities)
            Result.success(entities)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun sync() {
        try {
            val unsynced = dao.getUnsynced()
            Log.d(tag, "sync start — ${unsynced.size} unsynced")
            for (item in unsynced) {
                try {
                    val server = try {
                        apiService.updateChecklistItem(item.id, item.toUpdateDto())
                    } catch (_: Exception) {
                        apiService.createChecklistItem(item.toCreateDto())
                    }
                    dao.deleteById(item.id)
                    dao.insert(server.toEntity())
                } catch (_: Exception) {
                    // skip, retry on next sync
                }
            }

            // Pull a 60-day window centred on today
            val today = LocalDate.now()
            val start = today.minusDays(30).format(isoDate)
            val end = today.plusDays(30).format(isoDate)
            val serverItems = apiService.getChecklistItems(start, end, null)
            Log.d(tag, "sync — server returned ${serverItems.size} item(s); full ids=${serverItems.map { it.id }}")
            val serverIds = serverItems.map { it.id }.toSet()

            val localSyncedIds = dao.getSyncedIds()
            Log.d(tag, "sync — localSyncedIds=$localSyncedIds")
            var reconcileDeletes = 0
            for (id in localSyncedIds) {
                if (id !in serverIds) {
                    dao.deleteById(id)
                    reconcileDeletes++
                    Log.d(tag, "sync — orphan local row deleted: id=$id")
                }
            }
            Log.d(tag, "sync — reconcile deleted $reconcileDeletes orphan local row(s)")

            dao.insertAll(serverItems.map { it.toEntity() })
            Log.d(tag, "sync — inserted ${serverItems.size} item(s) from server")
        } catch (e: Exception) {
            Log.w(tag, "sync — failed (offline or API error)", e)
        }
    }
}
