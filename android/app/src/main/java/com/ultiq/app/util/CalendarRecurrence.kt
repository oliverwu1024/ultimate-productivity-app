package com.ultiq.app.util

import com.ultiq.app.data.local.entity.CalendarEventEntity
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId

/// §2026-06-09 — Single source of truth for recurring-event expansion.
/// Originally lived as private helpers inside CalendarRepository, but the
/// reminder scheduler also needs to expand to arm one alarm per occurrence
/// (the master row's startTime is the FIRST occurrence and is usually in
/// the past — scheduling against it alone meant no reminder ever fired
/// for a recurring series after its first day).
object CalendarRecurrence {

    private val LOCAL_ZONE: ZoneId get() = ZoneId.systemDefault()

    /// §tz/calendar Phase B — expand in the EVENT's own zone (eventTz) so a
    /// recurring "9am" stays 9am across DST and matches the backend. Falls back
    /// to the device zone when eventTz is null (pre-migration rows) or invalid.
    private fun zoneOf(event: CalendarEventEntity): ZoneId =
        event.eventTz?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: LOCAL_ZONE

    /** Expand a list of master-row events into concrete instances inside
     *  the given range. Non-recurring events pass through unchanged.
     *  Result is sorted ascending by `startTime`. */
    fun expandWindow(
        events: List<CalendarEventEntity>,
        rangeStart: Long,
        rangeEnd: Long,
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
        rangeEnd: Long,
    ): List<CalendarEventEntity> {
        val ruleRaw = event.recurrenceRule ?: return emptyList()
        val (base, until) = parseRule(ruleRaw)
        val duration = event.endTime - event.startTime
        val zone = zoneOf(event)
        val eventTime = Instant.ofEpochMilli(event.startTime).atZone(zone).toLocalTime()
        val eventStartDate = Instant.ofEpochMilli(event.startTime).atZone(zone).toLocalDate()
        val rangeStartDate = Instant.ofEpochMilli(rangeStart).atZone(zone).toLocalDate()
        val rangeEndDateRaw = Instant.ofEpochMilli(rangeEnd).atZone(zone).toLocalDate()
        // v2.16.0 — UNTIL cap applied as the effective end.
        val rangeEndDate = if (until != null && until.isBefore(rangeEndDateRaw)) until else rangeEndDateRaw
        if (rangeEndDate.isBefore(rangeStartDate)) return emptyList()

        val excluded = parseDateSet(event.excludedDates)
        val done = parseDateSet(event.doneDates)

        val instances = mutableListOf<CalendarEventEntity>()

        when {
            base == "DAILY" -> {
                var cur = maxOf(eventStartDate, rangeStartDate)
                while (!cur.isAfter(rangeEndDate)) {
                    addIfVisible(instances, event, cur, eventTime, duration, excluded, done)
                    cur = cur.plusDays(1)
                }
            }
            base.startsWith("WEEKLY:") -> {
                val targetDays = base.removePrefix("WEEKLY:")
                    .split(",")
                    .mapNotNull { parseWeekday(it.trim()) }
                if (targetDays.isEmpty()) return emptyList()

                var cur = maxOf(eventStartDate, rangeStartDate)
                while (!cur.isAfter(rangeEndDate)) {
                    if (cur.dayOfWeek in targetDays) {
                        addIfVisible(instances, event, cur, eventTime, duration, excluded, done)
                    }
                    cur = cur.plusDays(1)
                }
            }
            base.startsWith("MONTHLY:") -> {
                val targetDay = base.removePrefix("MONTHLY:").trim().toIntOrNull()
                    ?: return emptyList()
                val first = maxOf(eventStartDate, rangeStartDate)
                var ym = YearMonth.of(first.year, first.month)
                val limit = YearMonth.of(rangeEndDate.year, rangeEndDate.month).plusMonths(1)

                while (ym.isBefore(limit)) {
                    if (targetDay <= ym.lengthOfMonth()) {
                        val date = ym.atDay(targetDay)
                        if (!date.isBefore(eventStartDate) && !date.isBefore(rangeStartDate) && !date.isAfter(rangeEndDate)) {
                            addIfVisible(instances, event, date, eventTime, duration, excluded, done)
                        }
                    }
                    ym = ym.plusMonths(1)
                }
            }
        }

        return instances
    }

    private fun addIfVisible(
        instances: MutableList<CalendarEventEntity>,
        master: CalendarEventEntity,
        date: LocalDate,
        time: LocalTime,
        duration: Long,
        excluded: Set<LocalDate>,
        done: Set<LocalDate>,
    ) {
        if (date in excluded) return
        val instance = makeInstance(master, date, time, duration)
        instances += if (date in done) instance.copy(isDone = true) else instance
    }

    private fun makeInstance(
        event: CalendarEventEntity,
        date: LocalDate,
        time: LocalTime,
        duration: Long,
    ): CalendarEventEntity {
        // Rebuild the occurrence instant from its local date+time in the event's
        // zone (DST-correct), matching the extraction in expandRecurrence.
        val start = date.atTime(time).atZone(zoneOf(event)).toInstant().toEpochMilli()
        return event.copy(startTime = start, endTime = start + duration)
    }

    /** v2.16.0 — Split a rule into its base + optional UNTIL cap.
     *  "WEEKLY:MON,WED:UNTIL=2026-06-15" → ("WEEKLY:MON,WED", 2026-06-15). */
    private fun parseRule(rule: String): Pair<String, LocalDate?> {
        val idx = rule.indexOf(":UNTIL=")
        if (idx < 0) return rule to null
        val base = rule.substring(0, idx)
        val suffix = rule.substring(idx + ":UNTIL=".length).trim()
        val until = runCatching { LocalDate.parse(suffix) }.getOrNull()
        return base to until
    }

    private fun parseDateSet(raw: String?): Set<LocalDate> {
        if (raw.isNullOrBlank()) return emptySet()
        return raw.split(",")
            .mapNotNull { runCatching { LocalDate.parse(it.trim()) }.getOrNull() }
            .toSet()
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
}
