package com.app.productivity.data.remote.dto

import com.app.productivity.data.local.entity.CalendarEventEntity
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
    val color: String?
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
        color = color
    )
}
