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
import java.time.ZoneOffset
import java.util.UUID

class CalendarRepository(
    private val calendarEventDao: CalendarEventDao,
    private val apiService: ApiService,
    private val alarmScheduler: AlarmScheduler? = null,
) {

    fun getEventsForMonth(year: Int, month: Int): Flow<List<CalendarEventEntity>> {
        val ym = YearMonth.of(year, month)
        val start = ym.atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
        val end = ym.atEndOfMonth().atTime(23, 59, 59).toInstant(ZoneOffset.UTC).toEpochMilli()
        return calendarEventDao.getEventsForRange(start, end).map { events ->
            expandAll(events, start, end)
        }
    }

    fun getEventsForRange(startDate: LocalDate, endDate: LocalDate): Flow<List<CalendarEventEntity>> {
        val start = startDate.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
        val end = endDate.atTime(23, 59, 59).toInstant(ZoneOffset.UTC).toEpochMilli()
        return calendarEventDao.getEventsForRange(start, end).map { events ->
            expandAll(events, start, end)
        }
    }

    fun getEventsForDay(date: LocalDate): Flow<List<CalendarEventEntity>> {
        val start = date.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
        val end = date.atTime(23, 59, 59).toInstant(ZoneOffset.UTC).toEpochMilli()
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

            val serverEvents = apiService.getCalendarEvents(null, null, null, null)
            val serverIds = serverEvents.map { it.id }.toSet()

            // Reconcile deletes: any local synced row in the pulled window that's no longer
            // on the server was deleted elsewhere (web dashboard, another device). Match the
            // backend's default ±30-day window.
            val now = System.currentTimeMillis()
            val day = 24L * 60L * 60L * 1000L
            val rangeStart = now - 30L * day
            val rangeEnd = now + 30L * day
            val localSyncedIds = calendarEventDao.getSyncedIdsInRange(rangeStart, rangeEnd)
            for (id in localSyncedIds) {
                if (id !in serverIds) {
                    calendarEventDao.deleteById(id)
                    alarmScheduler?.cancelEventReminder(id)
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
        val eventTime = Instant.ofEpochMilli(event.startTime).atOffset(ZoneOffset.UTC).toLocalTime()
        val eventStartDate = Instant.ofEpochMilli(event.startTime).atOffset(ZoneOffset.UTC).toLocalDate()
        val rangeStartDate = Instant.ofEpochMilli(rangeStart).atOffset(ZoneOffset.UTC).toLocalDate()
        val rangeEndDate = Instant.ofEpochMilli(rangeEnd).atOffset(ZoneOffset.UTC).toLocalDate()

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
        val start = date.atTime(time).toInstant(ZoneOffset.UTC).toEpochMilli()
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
}
