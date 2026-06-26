package com.ultiq.app.data.remote.dto

import com.ultiq.app.data.local.entity.CalendarEventEntity
import java.time.Instant

data class CreateCalendarEventDto(
    val title: String,
    val description: String?,
    val start_time: String,
    val end_time: String,
    val category: String,
    val priority: String,
    val is_recurring: Boolean,
    val recurrence_rule: String?,
    val color: String?,
    val is_done: Boolean? = null,
    /// v2.13.1 — List of minutes-before-start_time offsets. Null = preserve
    /// stored value on update (server COALESCEs) / use client default on
    /// new creates. v2.13.0 had this as Int?; widened for multi-reminder.
    val reminder_minutes: List<Int>? = null,
    /// §tz/calendar — event's IANA zone; sent on create (device zone),
    /// preserved on update. Null on older clients (server defaults to user tz).
    val event_tz: String? = null,
)

data class CalendarEventDto(
    val id: String,
    val user_id: String,
    val title: String,
    val description: String?,
    val start_time: String,
    val end_time: String,
    val category: String,
    val priority: String,
    val is_recurring: Boolean,
    val recurrence_rule: String?,
    val color: String,
    val is_done: Boolean = false,
    /// v2.13.1 — Optional on the wire. JSON shape: `null` (default) or
    /// an array `[]` / `[15]` / `[1440, 60, 5]`. Pre-2.13 rows: null.
    val reminder_minutes: List<Int>? = null,
    /// v2.16.0 — Comma-separated YYYY-MM-DD list (server-canonical).
    /// Drives the per-occurrence done flag on expanded instances.
    /// Older servers omit the field, so default to null.
    val done_dates: String? = null,
    /// v2.16.0 — Comma-separated YYYY-MM-DD list of dates to skip when
    /// expanding the series ("Just this one" delete).
    val excluded_dates: String? = null,
    val event_tz: String? = null,
    val created_at: String,
    val updated_at: String
)

fun CalendarEventDto.toEntity(): CalendarEventEntity {
    return CalendarEventEntity(
        id = id,
        userId = user_id,
        title = title,
        description = description,
        startTime = Instant.parse(start_time).toEpochMilli(),
        endTime = Instant.parse(end_time).toEpochMilli(),
        category = category,
        priority = priority,
        isRecurring = is_recurring,
        recurrenceRule = recurrence_rule,
        color = color,
        isDone = is_done,
        // v2.13.1 — server may return null (default) or an array; pass
        // through directly. List type preserves opt-out (empty list).
        reminderMinutes = reminder_minutes,
        // v2.16.0 — Per-occurrence override strings; backend writes
        // these via PUT/DELETE with ?occurrence_date=.
        doneDates = done_dates,
        excludedDates = excluded_dates,
        eventTz = event_tz,
        createdAt = Instant.parse(created_at).toEpochMilli(),
        updatedAt = Instant.parse(updated_at).toEpochMilli(),
        isSynced = true
    )
}

fun CalendarEventEntity.toCreateDto(): CreateCalendarEventDto {
    return CreateCalendarEventDto(
        title = title,
        description = description,
        start_time = Instant.ofEpochMilli(startTime).toString(),
        end_time = Instant.ofEpochMilli(endTime).toString(),
        category = category,
        priority = priority,
        is_recurring = isRecurring,
        recurrence_rule = recurrenceRule,
        color = color,
        is_done = isDone,
        reminder_minutes = reminderMinutes,
        event_tz = eventTz,
    )
}
