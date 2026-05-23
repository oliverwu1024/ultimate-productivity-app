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
    val isDone: Boolean = false,
    /// v2.13.1 — List of minutes-before-startTime offsets, one entry per
    /// reminder ("1 day before AND 1 hour before" = listOf(1440, 60)).
    /// null = "use client default" (single 15-min reminder); empty list =
    /// explicit opt-out. v2.13.0 stored a single Int? here; the Room
    /// migration 11→12 widens to TEXT and IntListConverter does the
    /// Kotlin↔SQLite marshalling.
    val reminderMinutes: List<Int>? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val isSynced: Boolean = false
)
