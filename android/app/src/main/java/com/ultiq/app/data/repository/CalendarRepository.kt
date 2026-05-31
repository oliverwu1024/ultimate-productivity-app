package com.ultiq.app.data.repository

import com.ultiq.app.data.local.dao.CalendarEventDao
import com.ultiq.app.data.local.entity.CalendarEventEntity
import com.ultiq.app.data.remote.ApiService
import com.ultiq.app.data.remote.dto.CreateCalendarEventDto
import com.ultiq.app.data.remote.dto.toCreateDto
import com.ultiq.app.data.remote.dto.toEntity
import com.ultiq.app.util.AlarmScheduler
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

private val LOCAL_ZONE: ZoneId get() = ZoneId.systemDefault()

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
            expandAll(events, start, end)
        }
    }

    fun getEventsForRange(startDate: LocalDate, endDate: LocalDate): Flow<List<CalendarEventEntity>> {
        val start = startDate.atStartOfDay(LOCAL_ZONE).toInstant().toEpochMilli()
        val end = endDate.atTime(23, 59, 59).atZone(LOCAL_ZONE).toInstant().toEpochMilli()
        return calendarEventDao.getEventsForRange(start, end).map { events ->
            expandAll(events, start, end)
        }
    }

    fun getEventsForDay(date: LocalDate): Flow<List<CalendarEventEntity>> {
        val start = date.atStartOfDay(LOCAL_ZONE).toInstant().toEpochMilli()
        val end = date.atTime(23, 59, 59).atZone(LOCAL_ZONE).toInstant().toEpochMilli()
        return calendarEventDao.getEventsForRange(start, end).map { events ->
            expandAll(events, start, end)
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

    /** Toggle a calendar event's done flag. Reuses the full PUT update so the
     *  server / local stay consistent with the existing single-update path. */
    suspend fun setEventDone(id: String, userId: String, isDone: Boolean): Result<CalendarEventEntity> {
        val existing = calendarEventDao.getById(id)
            ?: return Result.failure(IllegalStateException("event not found locally"))
        val dto = existing.toCreateDto().copy(is_done = isDone)
        return updateEvent(id, dto, userId)
    }

    suspend fun deleteEvent(id: String): Result<Unit> {
        calendarEventDao.deleteById(id)
        alarmScheduler?.cancelEventReminder(id)
        return try {
            apiService.deleteCalendarEvent(id)
            Result.success(Unit)
        } catch (_: Exception) {
            Result.success(Unit)
        }
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
            val serverEvents = apiService.getCalendarEvents(
                start = syncRangeStart,
                end = syncRangeEnd,
                category = null,
                priority = null,
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

    // ── Client-side recurrence expansion (mirrors backend logic) ───────

    private fun expandAll(
        events: List<CalendarEventEntity>,
        rangeStart: Long,
        rangeEnd: Long
    ): List<CalendarEventEntity> {
        val result = mutableListOf<CalendarEventEntity>()
        for (event in events) {
            if (event.isRecurring) {
                result.addAll(expandRecurrence(event, rangeStart, rangeEnd))
            } else {
                result.add(event)
            }
        }
        return result.sortedBy { it.startTime }
    }

    private fun expandRecurrence(
        event: CalendarEventEntity,
        rangeStart: Long,
        rangeEnd: Long
    ): List<CalendarEventEntity> {
        val rule = event.recurrenceRule ?: return emptyList()
        val duration = event.endTime - event.startTime
        val eventTime = Instant.ofEpochMilli(event.startTime).atZone(LOCAL_ZONE).toLocalTime()
        val eventStartDate = Instant.ofEpochMilli(event.startTime).atZone(LOCAL_ZONE).toLocalDate()
        val rangeStartDate = Instant.ofEpochMilli(rangeStart).atZone(LOCAL_ZONE).toLocalDate()
        val rangeEndDate = Instant.ofEpochMilli(rangeEnd).atZone(LOCAL_ZONE).toLocalDate()

        val instances = mutableListOf<CalendarEventEntity>()

        when {
            rule == "DAILY" -> {
                var cur = maxOf(eventStartDate, rangeStartDate)
                while (!cur.isAfter(rangeEndDate)) {
                    instances.add(makeInstance(event, cur, eventTime, duration))
                    cur = cur.plusDays(1)
                }
            }
            rule.startsWith("WEEKLY:") -> {
                val targetDays = rule.removePrefix("WEEKLY:")
                    .split(",")
                    .mapNotNull { parseWeekday(it.trim()) }
                if (targetDays.isEmpty()) return emptyList()

                var cur = maxOf(eventStartDate, rangeStartDate)
                while (!cur.isAfter(rangeEndDate)) {
                    if (cur.dayOfWeek in targetDays) {
                        instances.add(makeInstance(event, cur, eventTime, duration))
                    }
                    cur = cur.plusDays(1)
                }
            }
            rule.startsWith("MONTHLY:") -> {
                val targetDay = rule.removePrefix("MONTHLY:").trim().toIntOrNull()
                    ?: return emptyList()
                val first = maxOf(eventStartDate, rangeStartDate)
                var ym = YearMonth.of(first.year, first.month)
                val limit = YearMonth.of(rangeEndDate.year, rangeEndDate.month).plusMonths(1)

                while (ym.isBefore(limit)) {
                    if (targetDay <= ym.lengthOfMonth()) {
                        val date = ym.atDay(targetDay)
                        if (!date.isBefore(eventStartDate) && !date.isBefore(rangeStartDate) && !date.isAfter(rangeEndDate)) {
                            instances.add(makeInstance(event, date, eventTime, duration))
                        }
                    }
                    ym = ym.plusMonths(1)
                }
            }
        }

        return instances
    }

    private fun makeInstance(
        event: CalendarEventEntity,
        date: LocalDate,
        time: LocalTime,
        duration: Long
    ): CalendarEventEntity {
        val start = date.atTime(time).atZone(LOCAL_ZONE).toInstant().toEpochMilli()
        return event.copy(startTime = start, endTime = start + duration)
    }

    private fun parseWeekday(s: String): DayOfWeek? = when (s.uppercase()) {
        "MON" -> DayOfWeek.MONDAY
        "TUE" -> DayOfWeek.TUESDAY
        "WED" -> DayOfWeek.WEDNESDAY
        "THU" -> DayOfWeek.THURSDAY
        "FRI" -> DayOfWeek.FRIDAY
        "SAT" -> DayOfWeek.SATURDAY
        "SUN" -> DayOfWeek.SUNDAY
        else -> null
    }

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
