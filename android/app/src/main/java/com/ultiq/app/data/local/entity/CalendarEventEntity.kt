package com.ultiq.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "calendar_events")
data class CalendarEventEntity(
    @PrimaryKey val id: String,
    val userId: String,
    val title: String,
    val description: String?,
    val startTime: Long,
    val endTime: Long,
    val category: String,       // "study", "project", "exercise", "personal", "other"
    val priority: String,       // "high", "medium", "low"
    val isRecurring: Boolean,
    val recurrenceRule: String?,
    val color: String,
    val createdAt: Long,
    val updatedAt: Long,
    val isSynced: Boolean = false
)
