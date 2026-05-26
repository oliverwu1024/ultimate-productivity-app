package com.ultiq.app.util

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * §sleep-day (v2.13.17) — A sleep "belongs to" the calendar day it
 * started on after shifting clocks back by [SLEEP_DAY_SHIFT_HOURS] in
 * the user's local timezone. The shift matches Whoop/Oura semantics:
 *
 *   - Bedtime Tue 02:00 local  → sleep_day = Monday (it's Monday night)
 *   - Bedtime Tue 22:00 local  → sleep_day = Tuesday
 *   - Tue 2 pm nap             → sleep_day = Tuesday
 *
 * Tuned to 6h so all-nighters and late nights still bucket to the
 * previous day's night without breaking daytime naps. The Kotlin
 * helper here mirrors `crate::tz::sleep_day_for` on the backend; any
 * change to one must change the other.
 */
const val SLEEP_DAY_SHIFT_HOURS: Long = 6

/** Sleep-day for a UTC-ms bedtime, in the given (or system-default) zone. */
fun sleepDayFor(actualBedtimeMs: Long, zone: ZoneId = ZoneId.systemDefault()): LocalDate {
    val shifted = Instant.ofEpochMilli(actualBedtimeMs).minusSeconds(SLEEP_DAY_SHIFT_HOURS * 3600)
    return shifted.atZone(zone).toLocalDate()
}

/**
 * The UTC-ms instant at which [sleepDay] begins for bedtime purposes —
 * 6 am local on [sleepDay]. Use this for SQL/Room range filters when
 * you want all bedtimes belonging to a sleep-day range:
 *
 *   start = sleepDayStartMs(rangeStart)
 *   end   = sleepDayStartMs(rangeEndInclusive.plusDays(1))   // half-open
 */
fun sleepDayStartMs(sleepDay: LocalDate, zone: ZoneId = ZoneId.systemDefault()): Long =
    sleepDay
        .atTime(SLEEP_DAY_SHIFT_HOURS.toInt(), 0)
        .atZone(zone)
        .toInstant()
        .toEpochMilli()
