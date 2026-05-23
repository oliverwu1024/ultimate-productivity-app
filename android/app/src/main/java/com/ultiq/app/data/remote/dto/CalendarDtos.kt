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
    /// v2.13.0 — Minutes before start_time to fire the reminder. Null
    /// means "preserve server-side stored value" on update (server
    /// COALESCEs); for new creates, null = use server/client default.
    val reminder_minutes: Int? = null,
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
    /// v2.13.0 — Optional on the wire: pre-2.13 server rows return null,
    /// post-2.13 rows return the user's picked offset or null if not set.
    val reminder_minutes: Int? = null,
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
        reminderMinutes = reminder_minutes,
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
    )
}
