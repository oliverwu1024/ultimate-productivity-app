package com.ultiq.app.data.remote.dto

import com.ultiq.app.data.local.entity.ChecklistEntity
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter

data class CreateChecklistItemDto(
    val title: String,
    val description: String? = null,
    val due_date: String,                 // ISO date "YYYY-MM-DD"
    val estimated_minutes: Int? = null,
    val priority: Int = 1,                // 0=low, 1=med, 2=high
    // Optional schedule fields; older backends ignore them safely.
    val recurrence_days_mask: Int = 0,
    val show_until_due: Boolean = false,
)

data class UpdateChecklistItemDto(
    val title: String? = null,
    val description: String? = null,
    val due_date: String? = null,
    val estimated_minutes: Int? = null,
    val priority: Int? = null,
    val completed: Boolean? = null,
    val recurrence_days_mask: Int? = null,
    val show_until_due: Boolean? = null,
    val last_completed_epoch_day: Long? = null,
)

data class ChecklistItemDto(
    val id: String,
    val user_id: String,
    val title: String,
    val description: String?,
    val due_date: String,
    val estimated_minutes: Int?,
    val priority: Int,
    val completed: Boolean,
    val completed_at: String?,
    val created_at: String,
    val updated_at: String,
    // Nullable because older backend builds omit them entirely.
    val recurrence_days_mask: Int? = null,
    val show_until_due: Boolean? = null,
    val last_completed_epoch_day: Long? = null,
)

private val ISO_DATE: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE

fun ChecklistItemDto.toEntity(): ChecklistEntity {
    return ChecklistEntity(
        id = id,
        userId = user_id,
        title = title,
        description = description,
        dueDateEpochDay = LocalDate.parse(due_date, ISO_DATE).toEpochDay(),
        estimatedMinutes = estimated_minutes,
        priority = priority,
        completed = completed,
        completedAt = completed_at?.let { Instant.parse(it).toEpochMilli() },
        createdAt = Instant.parse(created_at).toEpochMilli(),
        updatedAt = Instant.parse(updated_at).toEpochMilli(),
        isSynced = true,
        recurrenceDaysMask = recurrence_days_mask ?: 0,
        showUntilDue = show_until_due ?: false,
        lastCompletedEpochDay = last_completed_epoch_day,
    )
}

fun ChecklistEntity.toCreateDto(): CreateChecklistItemDto {
    return CreateChecklistItemDto(
        title = title,
        description = description,
        due_date = LocalDate.ofEpochDay(dueDateEpochDay).format(ISO_DATE),
        estimated_minutes = estimatedMinutes,
        priority = priority,
        recurrence_days_mask = recurrenceDaysMask,
        show_until_due = showUntilDue,
    )
}

fun ChecklistEntity.toUpdateDto(): UpdateChecklistItemDto {
    return UpdateChecklistItemDto(
        title = title,
        description = description,
        due_date = LocalDate.ofEpochDay(dueDateEpochDay).format(ISO_DATE),
        estimated_minutes = estimatedMinutes,
        priority = priority,
        completed = completed,
        recurrence_days_mask = recurrenceDaysMask,
        show_until_due = showUntilDue,
        last_completed_epoch_day = lastCompletedEpochDay,
    )
}
