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

    fun getByDate(epochDay: Long, dayOfWeekBit: Int): Flow<List<ChecklistEntity>> =
        dao.getByDate(epochDay, dayOfWeekBit)

    fun getCarryoverCandidates(
        epochDay: Long,
        yesterdayBit: Int,
        todayBit: Int,
    ): Flow<List<ChecklistEntity>> = dao.getCarryoverCandidates(epochDay, yesterdayBit, todayBit)

    fun getInRange(startEpochDay: Long, endEpochDay: Long): Flow<List<ChecklistEntity>> =
        dao.getInRange(startEpochDay, endEpochDay)

    suspend fun create(
        userId: String,
        title: String,
        description: String?,
        dueDate: LocalDate,
        estimatedMinutes: Int?,
        priority: Int,
        recurrenceDaysMask: Int = 0,
        showUntilDue: Boolean = false,
    ): Result<ChecklistEntity> {
        val createDto = CreateChecklistItemDto(
            title = title,
            description = description,
            due_date = dueDate.format(isoDate),
            estimated_minutes = estimatedMinutes,
            priority = priority,
            recurrence_days_mask = recurrenceDaysMask,
            show_until_due = showUntilDue,
        )
        return try {
            val server = apiService.createChecklistItem(createDto)
            // Preserve the schedule fields the server may not echo back yet —
            // backend deploy can lag the client.
            val entity = server.toEntity().copy(
                recurrenceDaysMask = recurrenceDaysMask,
                showUntilDue = showUntilDue,
            )
            dao.insert(entity)
            Log.d(tag, "create — server returned id=${server.id}")
            Result.success(entity)
        } catch (e: Exception) {
            Log.w(tag, "create — API failed, falling back to local-only", e)
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
                recurrenceDaysMask = recurrenceDaysMask,
                showUntilDue = showUntilDue,
                lastCompletedEpochDay = null,
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
            // Server may strip schedule fields on older deploys — re-apply
            // them from the local row so the local source-of-truth survives.
            val entity = server.toEntity().copy(
                recurrenceDaysMask = item.recurrenceDaysMask,
                showUntilDue = item.showUntilDue,
                lastCompletedEpochDay = item.lastCompletedEpochDay,
            )
            dao.insert(entity)
            Result.success(entity)
        } catch (_: Exception) {
            Result.success(updated)
        }
    }

    /** Recurring-item toggle: stamps lastCompletedEpochDay so the row re-opens
     *  the next time its day-of-week comes round. completed flag stays false. */
    suspend fun markRecurringCompletedOn(id: String, epochDay: Long): Result<Unit> {
        val now = System.currentTimeMillis()
        dao.setLastCompletedEpochDay(id, epochDay, now)
        // Push as a regular update so the schedule fields ride along.
        return try {
            val existing = dao.getById(id) ?: return Result.success(Unit)
            val server = apiService.updateChecklistItem(id, existing.toUpdateDto())
            dao.insert(
                server.toEntity().copy(
                    recurrenceDaysMask = existing.recurrenceDaysMask,
                    showUntilDue = existing.showUntilDue,
                    lastCompletedEpochDay = existing.lastCompletedEpochDay,
                ),
            )
            Result.success(Unit)
        } catch (_: Exception) {
            Result.success(Unit)
        }
    }

    suspend fun markRecurringIncompleteOn(id: String): Result<Unit> {
        val now = System.currentTimeMillis()
        dao.setLastCompletedEpochDay(id, null, now)
        return try {
            // §recurring-uncomplete-fix — go through the dedicated
            // /uncomplete endpoint so the server actually clears
            // `last_completed_epoch_day`. The old path (PUT with
            // last_completed_epoch_day = null) couldn't be distinguished
            // from "field omitted" on the wire, so the server kept the
            // old stamp and the row flipped back to completed on next
            // sync.
            val server = apiService.uncompleteChecklistItem(id)
            dao.insert(server.toEntity())
            Result.success(Unit)
        } catch (_: Exception) {
            Result.success(Unit)
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
            // §recurring-uncomplete-fix — same dedicated endpoint as the
            // recurring path so completed/completed_at are explicitly
            // cleared without ambiguity from the generic update body.
            val server = apiService.uncompleteChecklistItem(id)
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

            // §UX: backend may not have the schedule columns yet — fall back
            // to the local row's recurrence fields when the server returns
            // null / 0 to avoid clobbering a user-set repeat on every sync.
            val merged = serverItems.map { dto ->
                val incoming = dto.toEntity()
                val local = dao.getById(dto.id)
                if (local == null) {
                    incoming
                } else {
                    incoming.copy(
                        recurrenceDaysMask = dto.recurrence_days_mask
                            ?: local.recurrenceDaysMask,
                        showUntilDue = dto.show_until_due ?: local.showUntilDue,
                        lastCompletedEpochDay = dto.last_completed_epoch_day
                            ?: local.lastCompletedEpochDay,
                    )
                }
            }
            dao.insertAll(merged)
            Log.d(tag, "sync — inserted ${serverItems.size} item(s) from server")
        } catch (e: Exception) {
            Log.w(tag, "sync — failed (offline or API error)", e)
        }
    }
}
