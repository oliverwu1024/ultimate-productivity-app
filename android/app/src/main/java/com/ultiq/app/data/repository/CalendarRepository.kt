package com.ultiq.app.data.repository

import com.ultiq.app.data.local.dao.CalendarEventDao
import com.ultiq.app.data.local.entity.CalendarEventEntity
import com.ultiq.app.data.remote.ApiService
import com.ultiq.app.data.remote.dto.CreateCalendarEventDto
import com.ultiq.app.data.remote.dto.toCreateDto
import com.ultiq.app.data.remote.dto.toEntity
import com.ultiq.app.util.AlarmScheduler
import com.ultiq.app.util.CalendarRecurrence
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

private val LOCAL_ZONE: ZoneId get() = ZoneId.systemDefault()
private val ISO_DATE: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE

/** v2.16.0 — Three-way scope used by edit + delete on a recurring event.
 *  Mirrors Google Calendar's "Edit recurring event" dialog. Mark-done is
 *  always JUST_THIS (no prompt, by design). */
enum class RecurringScope { JUST_THIS, THIS_AND_FOLLOWING, ALL }

class CalendarRepository(
    private val calendarEventDao: CalendarEventDao,
    private val apiService: ApiService,
    private val alarmScheduler: AlarmScheduler? = null,
    private val syncStateStore: SyncStateStore? = null,
) {

    fun getEventsForMonth(year: Int, month: Int): Flow<List<CalendarEventEntity>> {
        val ym = YearMonth.of(year, month)
        val start = ym.atDay(1).atStartOfDay(LOCAL_ZONE).toInstant().toEpochMilli()
        val end = ym.atEndOfMonth().atTime(23, 59, 59).atZone(LOCAL_ZONE).toInstant().toEpochMilli()
        return calendarEventDao.getEventsForRange(start, end).map { events ->
            CalendarRecurrence.expandWindow(events, start, end)
        }
    }

    fun getEventsForRange(startDate: LocalDate, endDate: LocalDate): Flow<List<CalendarEventEntity>> {
        val start = startDate.atStartOfDay(LOCAL_ZONE).toInstant().toEpochMilli()
        val end = endDate.atTime(23, 59, 59).atZone(LOCAL_ZONE).toInstant().toEpochMilli()
        return calendarEventDao.getEventsForRange(start, end).map { events ->
            CalendarRecurrence.expandWindow(events, start, end)
        }
    }

    fun getEventsForDay(date: LocalDate): Flow<List<CalendarEventEntity>> {
        val start = date.atStartOfDay(LOCAL_ZONE).toInstant().toEpochMilli()
        val end = date.atTime(23, 59, 59).atZone(LOCAL_ZONE).toInstant().toEpochMilli()
        return calendarEventDao.getEventsForRange(start, end).map { events ->
            CalendarRecurrence.expandWindow(events, start, end)
        }
    }

    suspend fun createEvent(dto: CreateCalendarEventDto, userId: String): Result<CalendarEventEntity> {
        val result = try {
            val serverEvent = apiService.createCalendarEvent(dto)
            val entity = serverEvent.toEntity()
            calendarEventDao.insert(entity)
            Result.success(entity)
        } catch (e: Exception) {
            try {
                val now = System.currentTimeMillis()
                val entity = CalendarEventEntity(
                    id = UUID.randomUUID().toString(),
                    userId = userId,
                    title = dto.title,
                    description = dto.description,
                    startTime = Instant.parse(dto.start_time).toEpochMilli(),
                    endTime = Instant.parse(dto.end_time).toEpochMilli(),
                    category = dto.category,
                    priority = dto.priority,
                    isRecurring = dto.is_recurring,
                    recurrenceRule = dto.recurrence_rule,
                    color = dto.color ?: defaultColor(dto.category),
                    isDone = dto.is_done ?: false,
                    reminderMinutes = dto.reminder_minutes,
                    createdAt = now,
                    updatedAt = now,
                    isSynced = false
                )
                calendarEventDao.insert(entity)
                Result.success(entity)
            } catch (localErr: Exception) {
                Result.failure(localErr)
            }
        }
        result.getOrNull()?.let { alarmScheduler?.scheduleEventReminder(it) }
        return result
    }

    suspend fun updateEvent(id: String, dto: CreateCalendarEventDto, userId: String): Result<CalendarEventEntity> {
        val result = try {
            val serverEvent = apiService.updateCalendarEvent(id, dto)
            val entity = serverEvent.toEntity()
            calendarEventDao.insert(entity)
            Result.success(entity)
        } catch (e: Exception) {
            try {
                val now = System.currentTimeMillis()
                val existing = calendarEventDao.getById(id)
                val entity = CalendarEventEntity(
                    id = id,
                    userId = userId,
                    title = dto.title,
                    description = dto.description,
                    startTime = Instant.parse(dto.start_time).toEpochMilli(),
                    endTime = Instant.parse(dto.end_time).toEpochMilli(),
                    category = dto.category,
                    priority = dto.priority,
                    isRecurring = dto.is_recurring,
                    recurrenceRule = dto.recurrence_rule,
                    color = dto.color ?: defaultColor(dto.category),
                    isDone = dto.is_done ?: existing?.isDone ?: false,
                    reminderMinutes = dto.reminder_minutes ?: existing?.reminderMinutes,
                    createdAt = existing?.createdAt ?: now,
                    updatedAt = now,
                    isSynced = false
                )
                calendarEventDao.insert(entity)
                Result.success(entity)
            } catch (localErr: Exception) {
                Result.failure(localErr)
            }
        }
        result.getOrNull()?.let {
            alarmScheduler?.cancelEventReminder(it.id)
            alarmScheduler?.scheduleEventReminder(it)
        }
        return result
    }

    /** Toggle a calendar event's done flag.
     *
     *  When `occurrenceDate` is null, flips the master row's `isDone` — this
     *  is the only meaningful path for non-recurring events.
     *
     *  When `occurrenceDate` is non-null, treats it as a per-occurrence
     *  toggle on a recurring series: the occurrence's local date is added
     *  to (or removed from) the master row's `doneDates` set without
     *  touching the row's own `isDone` column. This is the v2.16.0 fix
     *  for "marking one occurrence ticked every occurrence." */
    suspend fun setEventDone(
        id: String,
        userId: String,
        isDone: Boolean,
        occurrenceDate: LocalDate? = null,
    ): Result<CalendarEventEntity> {
        val existing = calendarEventDao.getById(id)
            ?: return Result.failure(IllegalStateException("event not found locally"))

        if (occurrenceDate == null || !existing.isRecurring) {
            // One-shot path: same as pre-2.16 — flip master via full PUT.
            val dto = existing.toCreateDto().copy(is_done = isDone)
            return updateEvent(id, dto, userId)
        }

        // Per-occurrence path. Optimistic local update first, then a
        // narrowly-scoped server PUT that targets only `done_dates`.
        val dateStr = occurrenceDate.format(ISO_DATE)
        val updatedDates = toggleDate(existing.doneDates, dateStr, present = isDone)
        val localUpdated = existing.copy(
            doneDates = updatedDates,
            updatedAt = System.currentTimeMillis(),
            isSynced = false,
        )
        calendarEventDao.insert(localUpdated)

        return try {
            val body = existing.toCreateDto().copy(is_done = isDone)
            val serverEvent = apiService.updateCalendarOccurrence(id, dateStr, body)
            val entity = serverEvent.toEntity()
            calendarEventDao.insert(entity)
            Result.success(entity)
        } catch (_: Exception) {
            // Server unreachable: keep local change unsynced so sync()
            // retries on the next opportunity. Mark-done is idempotent on
            // the backend (set semantics) so a retried PUT is safe.
            Result.success(localUpdated)
        }
    }

    /**
     * Optimistic LOCAL-ONLY mark-done (no network). Flips the same state
     * [setEventDone] would — per-occurrence `doneDates` for recurring events, the
     * `isDone` flag for one-offs — so a caller (e.g. the home-screen widget) can
     * update its UI instantly and then call [setEventDone] to sync. Idempotent.
     */
    suspend fun markDoneLocally(id: String, isDone: Boolean, occurrenceDate: LocalDate? = null) {
        val existing = calendarEventDao.getById(id) ?: return
        val now = System.currentTimeMillis()
        val updated = if (occurrenceDate != null && existing.isRecurring) {
            existing.copy(
                doneDates = toggleDate(existing.doneDates, occurrenceDate.format(ISO_DATE), present = isDone),
                updatedAt = now,
                isSynced = false,
            )
        } else {
            existing.copy(isDone = isDone, updatedAt = now, isSynced = false)
        }
        calendarEventDao.insert(updated)
    }

    /** Delete a calendar event. Scope controls the radius of the action on
     *  recurring series; for non-recurring rows the scope is ignored. */
    suspend fun deleteEvent(
        id: String,
        occurrenceDate: LocalDate? = null,
        scope: RecurringScope = RecurringScope.ALL,
    ): Result<Unit> {
        val existing = calendarEventDao.getById(id)
        // Defensive: missing row → fall through to whole-row delete to
        // keep the legacy behaviour. The server call still runs in case
        // there's a row server-side we haven't pulled yet.
        if (existing == null || !existing.isRecurring || scope == RecurringScope.ALL) {
            calendarEventDao.deleteById(id)
            alarmScheduler?.cancelEventReminder(id)
            return try {
                apiService.deleteCalendarEvent(id)
                Result.success(Unit)
            } catch (_: Exception) {
                Result.success(Unit)
            }
        }

        val date = occurrenceDate ?: run {
            // Recurring scope was set but caller didn't supply an
            // occurrence date — fall back to whole-series delete rather
            // than silently doing nothing.
            calendarEventDao.deleteById(id)
            alarmScheduler?.cancelEventReminder(id)
            return try {
                apiService.deleteCalendarEvent(id)
                Result.success(Unit)
            } catch (_: Exception) {
                Result.success(Unit)
            }
        }

        return when (scope) {
            RecurringScope.JUST_THIS -> deleteJustThisOccurrence(existing, date)
            RecurringScope.THIS_AND_FOLLOWING -> deleteThisAndFollowing(existing, date)
            RecurringScope.ALL -> error("handled above")
        }
    }

    private suspend fun deleteJustThisOccurrence(
        existing: CalendarEventEntity,
        date: LocalDate,
    ): Result<Unit> {
        val dateStr = date.format(ISO_DATE)
        val updated = existing.copy(
            excludedDates = toggleDate(existing.excludedDates, dateStr, present = true),
            updatedAt = System.currentTimeMillis(),
            isSynced = false,
        )
        calendarEventDao.insert(updated)

        return try {
            apiService.deleteCalendarOccurrence(existing.id, dateStr)
            // Mark synced by re-pulling the canonical server row, which
            // also picks up any backend-side normalisation of the
            // excluded_dates string.
            val server = apiService.getCalendarEvent(existing.id)
            calendarEventDao.insert(server.toEntity())
            Result.success(Unit)
        } catch (_: Exception) {
            // Offline: keep local change for sync() to retry. Excluded-
            // date append is set-semantics on the server so a re-send is
            // a no-op.
            Result.success(Unit)
        }
    }

    private suspend fun deleteThisAndFollowing(
        existing: CalendarEventEntity,
        date: LocalDate,
    ): Result<Unit> {
        // "This and following" = cap the series the day before. The
        // picked occurrence and every future occurrence stop appearing.
        // If `date` is on or before the series start, this is identical
        // to a whole-series delete — short-circuit.
        val seriesStart = Instant.ofEpochMilli(existing.startTime)
            .atZone(LOCAL_ZONE).toLocalDate()
        if (!date.isAfter(seriesStart)) {
            calendarEventDao.deleteById(existing.id)
            alarmScheduler?.cancelEventReminder(existing.id)
            return try {
                apiService.deleteCalendarEvent(existing.id)
                Result.success(Unit)
            } catch (_: Exception) {
                Result.success(Unit)
            }
        }
        val newRule = setUntil(existing.recurrenceRule, date.minusDays(1))
        val updatedDto = existing.toCreateDto().copy(recurrence_rule = newRule)
        return updateEvent(existing.id, updatedDto, existing.userId).map { }
    }

    /** Edit a recurring event with an explicit scope. Used by the
     *  three-button dialog on Save. For one-shot events / ALL on a
     *  recurring series, this is equivalent to `updateEvent`. */
    suspend fun updateEventWithScope(
        id: String,
        dto: CreateCalendarEventDto,
        userId: String,
        occurrenceDate: LocalDate?,
        scope: RecurringScope,
    ): Result<CalendarEventEntity> {
        val existing = calendarEventDao.getById(id)
        if (existing == null || !existing.isRecurring || scope == RecurringScope.ALL) {
            return updateEvent(id, dto, userId)
        }
        val date = occurrenceDate ?: return updateEvent(id, dto, userId)

        return when (scope) {
            RecurringScope.JUST_THIS -> editJustThisOccurrence(existing, dto, userId, date)
            RecurringScope.THIS_AND_FOLLOWING -> editThisAndFollowing(existing, dto, userId, date)
            RecurringScope.ALL -> updateEvent(id, dto, userId)
        }
    }

    private suspend fun editJustThisOccurrence(
        existing: CalendarEventEntity,
        editedDto: CreateCalendarEventDto,
        userId: String,
        date: LocalDate,
    ): Result<CalendarEventEntity> {
        // Detach pattern: (1) add the date to the master's excluded set
        // so the recurring expansion skips that day, then (2) create a
        // brand-new non-recurring event for that date with the edited
        // fields. Step 2 happens whether or not step 1 reached the
        // server — the local Room write for step 1 is enough to keep the
        // visible UI consistent, and sync() will reconcile.
        deleteJustThisOccurrence(existing, date)
        val detachedDto = editedDto.copy(
            is_recurring = false,
            recurrence_rule = null,
        )
        return createEvent(detachedDto, userId)
    }

    private suspend fun editThisAndFollowing(
        existing: CalendarEventEntity,
        editedDto: CreateCalendarEventDto,
        userId: String,
        date: LocalDate,
    ): Result<CalendarEventEntity> {
        // Cap the original series the day before, then create a new
        // master starting at `date` with the edited fields. The new
        // master keeps its own recurrence rule (the user's edits flow
        // through normally). If the picked date equals the series
        // start, the original master has nothing left to express — drop
        // it and just create the replacement.
        val seriesStart = Instant.ofEpochMilli(existing.startTime)
            .atZone(LOCAL_ZONE).toLocalDate()
        if (date.isAfter(seriesStart)) {
            val cappedRule = setUntil(existing.recurrenceRule, date.minusDays(1))
            val cappedDto = existing.toCreateDto().copy(recurrence_rule = cappedRule)
            updateEvent(existing.id, cappedDto, userId)
        } else {
            // Replace the whole original — same effect as picking the
            // first occurrence to start the rewrite from.
            deleteEvent(existing.id)
        }
        return createEvent(editedDto, userId)
    }

    /** v2.16.0 — Insert / remove a YYYY-MM-DD into a comma-separated set.
     *  Order is preserved (sorted) so the wire format is stable across
     *  clients; empty list → null so the column reads NULL on the server. */
    private fun toggleDate(raw: String?, date: String, present: Boolean): String? {
        val set = raw?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }?.toMutableSet()
            ?: mutableSetOf()
        if (present) set.add(date) else set.remove(date)
        if (set.isEmpty()) return null
        return set.sorted().joinToString(",")
    }

    /** v2.16.0 — Append (or replace) the `:UNTIL=YYYY-MM-DD` suffix on a
     *  recurrence rule. Used by "This and following" to cap the original
     *  series. Returns null if the input rule was null (a non-recurring
     *  row should never reach this path). */
    private fun setUntil(rule: String?, until: LocalDate): String? {
        if (rule == null) return null
        val base = rule.substringBefore(":UNTIL=")
        return "$base:UNTIL=${until.format(ISO_DATE)}"
    }

    suspend fun sync() {
        try {
            val unsynced = calendarEventDao.getUnsyncedEvents()
            for (event in unsynced) {
                try {
                    try {
                        val serverEvent = apiService.updateCalendarEvent(event.id, event.toCreateDto())
                        calendarEventDao.deleteById(event.id)
                        calendarEventDao.insert(serverEvent.toEntity())
                    } catch (_: Exception) {
                        val serverEvent = apiService.createCalendarEvent(event.toCreateDto())
                        calendarEventDao.deleteById(event.id)
                        calendarEventDao.insert(serverEvent.toEntity())
                    }
                } catch (_: Exception) {
                    // skip, will retry next sync
                }
            }

            // v2.11.8 — pull an explicit past+future window. The backend's
            // GET /calendar default is `start = now - 30d, end = now` (past
            // only — see backend/src/routes/calendar.rs:122), so calling
            // with all-null params silently excluded every event the user
            // had added for a future date: visible on web (via SSE push),
            // invisible on Android (never landed in Room because it wasn't
            // in the pulled set). The widened window covers the typical
            // "view the next year of recurring stuff" case without paging.
            val today = LocalDate.now(LOCAL_ZONE)
            val syncRangeStart = today.minusDays(30).toString()  // YYYY-MM-DD, matches backend NaiveDate
            val syncRangeEnd = today.plusDays(365).toString()
            // §v2.17.2-sync-collision — `expand=false` tells the backend to
            // skip per-occurrence expansion of recurring events. Before this,
            // a DAILY series over the ±year window came back as ~395 rows
            // all sharing the master `id`; `insertAll` (REPLACE on conflict)
            // collapsed them into the furthest-future instance only, and
            // the `startTime <= rangeEnd` predicate in the local DAO then
            // returned nothing for any current view. Client-side expansion
            // (CalendarRecurrence.expandWindow) is the single source of
            // truth for every read path on Android, so receiving the
            // master alone is what we want.
            val serverEvents = apiService.getCalendarEvents(
                start = syncRangeStart,
                end = syncRangeEnd,
                category = null,
                priority = null,
                expand = "false",
            )
            val serverIds = serverEvents.map { it.id }.toSet()

            // Reconcile deletes: any local synced row in the pulled window
            // that's no longer on the server was deleted elsewhere. Range
            // MUST match the pull window above (was hard-coded ±30 days,
            // which previously also lined up with the broken pull default;
            // now follows the corrected -30d / +365d window so we don't
            // accidentally wipe a local row the server still owns).
            val rangeStart = today.minusDays(30).atStartOfDay(LOCAL_ZONE).toInstant().toEpochMilli()
            val rangeEnd = today.plusDays(365).atTime(23, 59, 59).atZone(LOCAL_ZONE).toInstant().toEpochMilli()
            val localSyncedIds = calendarEventDao.getSyncedIdsInRange(rangeStart, rangeEnd)

            // §v2.15.10 — Two-empty-response guard. See SleepRepository
            // for the full rationale. Without it, a single empty server
            // response (the v2.15.7 storm caused several) wipes every
            // local row in the window even though backend still has
            // the data.
            val shouldReconcile = shouldReconcile(
                serverEmpty = serverEvents.isEmpty(),
                localHasRows = localSyncedIds.isNotEmpty(),
                entityKey = SyncStateStore.ENTITY_CALENDAR,
            )
            if (shouldReconcile) {
                for (id in localSyncedIds) {
                    if (id !in serverIds) {
                        calendarEventDao.deleteById(id)
                        alarmScheduler?.cancelEventReminder(id)
                    }
                }
            }

            val entities = serverEvents.map { it.toEntity() }
            calendarEventDao.insertAll(entities)
            alarmScheduler?.rescheduleAllEventReminders(entities)
        } catch (_: Exception) {
            // offline, skip sync
        }
    }

    // Client-side recurrence expansion lives in util/CalendarRecurrence.kt
    // — see getEventsForMonth/Range/Day above and AlarmScheduler for the
    // two read sites (UI flows + per-occurrence reminder scheduling).

    private fun defaultColor(category: String): String =
        com.ultiq.app.ui.theme.CategoryColors.hexForCategory(category)

    /**
     * §v2.15.10 — Two-empty-response guard. Same logic as
     * SleepRepository.shouldReconcile / ChecklistRepository.
     * Returns true when the destructive reconciliation should proceed,
     * false when we want to wait for a second corroborating response.
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
