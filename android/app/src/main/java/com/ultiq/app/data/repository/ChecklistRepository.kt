package com.ultiq.app.data.repository

import android.util.Log
import androidx.room.withTransaction
import com.ultiq.app.data.local.AppDatabase
import com.ultiq.app.data.local.dao.ChecklistCompletionDao
import com.ultiq.app.data.local.dao.ChecklistDao
import com.ultiq.app.data.local.entity.ChecklistCompletionEntity
import com.ultiq.app.data.local.entity.ChecklistEntity
import com.ultiq.app.data.remote.ApiService
import com.ultiq.app.data.remote.dto.ChecklistItemDto
import com.ultiq.app.data.remote.dto.CreateChecklistItemDto
import com.ultiq.app.data.remote.dto.completionEntities
import com.ultiq.app.data.remote.dto.toCreateDto
import com.ultiq.app.data.remote.dto.toEntity
import com.ultiq.app.data.remote.dto.toUpdateDto
import kotlinx.coroutines.flow.Flow
import retrofit2.HttpException
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.UUID

// §v2.16.3 — Mirrors SleepRepository.BATCH_CHUNK_SIZE. Keeps each
// bulk-create request under the AWS WAF 8KB body inspection limit.
private const val BATCH_CHUNK_SIZE = 25

class ChecklistRepository(
    private val dao: ChecklistDao,
    private val completionDao: ChecklistCompletionDao,
    private val apiService: ApiService,
    private val syncStateStore: SyncStateStore? = null,
    /// Optional so existing test fakes and any non-sync callers keep
    /// working without a real Room db. When null we fall back to running
    /// the writes outside of a transaction — same as before this change.
    private val database: AppDatabase? = null,
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

    /// Stream all (item, day) completion stamps. ViewModels join this
    /// against [getByDate] to decide which rows are "done today" for
    /// recurring items.
    fun observeCompletions(): Flow<List<ChecklistCompletionEntity>> = completionDao.observeAll()

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
            persistCompletionsFromServer(server)
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
            persistCompletionsFromServer(server)
            Result.success(entity)
        } catch (_: Exception) {
            Result.success(updated)
        }
    }

    /// §024 — Tick a recurring row for a specific day. Local row is
    /// inserted first so the UI flips immediately even when offline;
    /// the server call is best-effort. If the network fails the next
    /// sync pull will reconcile via `completed_epoch_days`.
    ///
    /// §v2.16.8 — No post-API server snapshot apply. The local optimistic
    /// write above already has the correct semantic state (completion
    /// stamp in the per-day log). The server response only refines
    /// timestamps + flips `row.completed` 0->1 on rows that nothing
    /// renders for recurring items, but those writes triggered a second
    /// Room invalidation that the partition-pair `distinctUntilChanged`
    /// in ChecklistViewModel could not always filter (any visible-field
    /// difference between the local and server snapshots — e.g. server-
    /// side description trim, or future field additions — would slip a
    /// redundant emit through and re-layout the LazyColumn ~150 ms after
    /// the legit first move). Next `sync()` reconciles any drift.
    suspend fun markRecurringCompletedOn(id: String, epochDay: Long): Result<Unit> {
        val now = System.currentTimeMillis()
        completionDao.insert(ChecklistCompletionEntity(id, epochDay, now))
        return try {
            apiService.completeChecklistItemOn(id, epochDay)
            Result.success(Unit)
        } catch (_: Exception) {
            Result.success(Unit)
        }
    }

    /// §024 — Untick a recurring row for a specific day. Symmetric with
    /// [markRecurringCompletedOn] — local row is deleted immediately,
    /// server call is best-effort.
    ///
    /// §v2.16.8 — Same no-snapshot-apply rationale as
    /// [markRecurringCompletedOn].
    suspend fun markRecurringIncompleteOn(id: String, epochDay: Long): Result<Unit> {
        completionDao.delete(id, epochDay)
        return try {
            apiService.uncompleteChecklistItemOn(id, epochDay)
            Result.success(Unit)
        } catch (_: Exception) {
            Result.success(Unit)
        }
    }

    suspend fun markCompleted(id: String): Result<Unit> {
        val now = System.currentTimeMillis()
        dao.markCompletedLocally(id, completedAt = now, updatedAt = now)
        return try {
            apiService.completeChecklistItem(id)
            // §v2.16.8 — Flip isSynced only; don't re-insert the row from
            // the server response. See [markRecurringCompletedOn] for the
            // full flicker rationale. The non-recurring local write
            // already set `completed = 1` + timestamps; the server's
            // response only refines those timestamps which nothing
            // renders, so the redundant Room invalidation from a full
            // REPLACE caused a second LazyColumn re-layout.
            dao.markSynced(id)
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
            apiService.uncompleteChecklistItem(id)
            // §v2.16.8 — Same isSynced-only update as [markCompleted].
            dao.markSynced(id)
            Result.success(Unit)
        } catch (_: Exception) {
            Result.success(Unit)
        }
    }

    suspend fun delete(id: String): Result<Unit> {
        val existing = dao.getById(id)
        Log.d(tag, "delete id=$id existsLocally=${existing != null} isSynced=${existing?.isSynced}")

        if (existing != null && !existing.isSynced) {
            // FK cascade handles the completions table; explicit call kept
            // for clarity and to cover any edge case where the local row was
            // created before the FK was wired.
            completionDao.deleteAllForItem(id)
            dao.deleteById(id)
            Log.d(tag, "delete id=$id — dropped local-only row, no API call")
            return Result.success(Unit)
        }

        return try {
            apiService.deleteChecklistItem(id)
            completionDao.deleteAllForItem(id)
            dao.deleteById(id)
            Log.d(tag, "delete id=$id — API + local both succeeded")
            Result.success(Unit)
        } catch (e: HttpException) {
            Log.w(tag, "delete id=$id — API HTTP ${e.code()}", e)
            if (e.code() == 404) {
                completionDao.deleteAllForItem(id)
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
            // §v2.16.3 — Chunk to stay under the WAF 8KB body limit, same
            // pattern as the sleep-audio and phone-pickups batches.
            // Checklist items can be ~200-300B each with notes; bulk import
            // of 30+ items could otherwise exceed the limit.
            val allServer = mutableListOf<ChecklistItemDto>()
            for (chunk in items.chunked(BATCH_CHUNK_SIZE)) {
                val server = apiService.bulkCreateChecklistItems(chunk)
                allServer.addAll(server)
            }
            val entities = allServer.map { it.toEntity() }
            dao.insertAll(entities)
            for (dto in allServer) persistCompletionsFromServer(dto)
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
                    // §flicker-fix — keep delete + insert + completions
                    // persistence inside a single Room transaction so the
                    // InvalidationTracker fires once at commit instead of
                    // three times mid-flight. Without this the row briefly
                    // disappears between deleteById and insert, which
                    // shows up in the UI as the Completed bucket flashing
                    // for every unsynced item the user has.
                    txWriteEach {
                        dao.deleteById(item.id)
                        dao.insert(server.toEntity())
                        persistCompletionsFromServer(server)
                    }
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

            // §v2.15.10 — Two-empty-response guard. Don't mirror a
            // server "you have nothing" response if local still has
            // rows, until we've seen it confirmed by a second sync.
            // See SleepRepository.shouldReconcile for the rationale.
            // §flicker-fix — every DB statement below runs inside one
            // Room transaction so the reconcile-deletes + bulk insert +
            // per-item completions persistence collapse into a single
            // InvalidationTracker fire. Pre-fix this loop emitted ~2N
            // times for N items with completions, which the partition
            // combine flow turned into N visible "Completed" flickers
            // on every checklist screen open.
            val reconcileDeletes = txWriteBulk {
                val localSyncedIds = dao.getSyncedIds()
                Log.d(tag, "sync — localSyncedIds=$localSyncedIds")

                val shouldReconcile = shouldReconcile(
                    serverEmpty = serverItems.isEmpty(),
                    localHasRows = localSyncedIds.isNotEmpty(),
                    entityKey = SyncStateStore.ENTITY_CHECKLIST,
                )
                var deletes = 0
                if (shouldReconcile) {
                    for (id in localSyncedIds) {
                        if (id !in serverIds) {
                            completionDao.deleteAllForItem(id)
                            dao.deleteById(id)
                            deletes++
                            Log.d(tag, "sync — orphan local row deleted: id=$id")
                        }
                    }
                } else {
                    Log.w(tag, "sync — defer reconcile (server empty, local has ${localSyncedIds.size} rows)")
                }

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
                for (dto in serverItems) persistCompletionsFromServer(dto)
                deletes
            }
            Log.d(tag, "sync — reconcile deleted $reconcileDeletes orphan local row(s)")
            Log.d(tag, "sync — inserted ${serverItems.size} item(s) from server")
        } catch (e: Exception) {
            Log.w(tag, "sync — failed (offline or API error)", e)
        }
    }

    /// Run a per-item write batch inside a single Room transaction if a
    /// database handle is available, otherwise run the writes directly.
    /// Direct-run fallback keeps test fakes and any caller that didn't
    /// inject [database] working without behavioural change.
    private suspend inline fun txWriteEach(crossinline block: suspend () -> Unit) {
        val db = database
        if (db != null) db.withTransaction { block() } else block()
    }

    /// Same as [txWriteEach] but returns the block's result. Used by the
    /// pull-side bulk write so the orphan-delete count can be logged
    /// without leaking transaction scope.
    private suspend inline fun <T> txWriteBulk(crossinline block: suspend () -> T): T {
        val db = database
        return if (db != null) db.withTransaction { block() } else block()
    }

    /// §024 — Reconcile the local `checklist_completions` mirror with
    /// the server's `completed_epoch_days` array.
    ///
    /// §flicker-fix — Diff-based: delete only the days the server no
    /// longer reports, then upsert the rest. The previous "wipe + re-
    /// insert" pattern briefly emptied the table between the two
    /// statements, which made `observeCompletions()` emit a midpoint
    /// state. The ViewModel partition treated the missing stamps as
    /// "not done today" and the row jumped from Completed → Open →
    /// Completed on every sync of a recurring done-today row. The diff
    /// approach leaves matching rows untouched (0 deletes, no INSERT
    /// trigger fires when REPLACE has nothing to change for the kept
    /// PKs in practice — Room still invalidates, but the partition
    /// output is identical, so MutableStateFlow dedupes downstream).
    ///
    /// Pre-024 backend payloads omit `completed_epoch_days` entirely;
    /// in that case we leave the local mirror alone so an older server
    /// can't accidentally erase the user's local ticks.
    private suspend fun persistCompletionsFromServer(dto: ChecklistItemDto) {
        val days = dto.completed_epoch_days ?: return
        if (days.isEmpty()) {
            // Server says no completions for this item — drop everything.
            completionDao.deleteAllForItem(dto.id)
            return
        }
        val updatedAt = runCatching {
            java.time.Instant.parse(dto.updated_at).toEpochMilli()
        }.getOrDefault(System.currentTimeMillis())
        completionDao.deleteForItemExcept(dto.id, days)
        completionDao.insertAll(dto.completionEntities(updatedAt))
    }

    /**
     * §v2.15.10 — Two-empty-response guard. Same logic as
     * SleepRepository.shouldReconcile / CalendarRepository.
     */
    private fun shouldReconcile(
        serverEmpty: Boolean,
        localHasRows: Boolean,
        entityKey: String,
    ): Boolean {
        val store = syncStateStore ?: return true
        if (!serverEmpty) {
            store.resetEmptyStreak(entityKey)
            return true
        }
        if (!localHasRows) {
            store.resetEmptyStreak(entityKey)
            return true
        }
        val streak = store.incrementEmptyStreak(entityKey)
        return if (streak >= SyncStateStore.REQUIRED_EMPTY_STREAK) {
            store.resetEmptyStreak(entityKey)
            true
        } else {
            false
        }
    }
}
