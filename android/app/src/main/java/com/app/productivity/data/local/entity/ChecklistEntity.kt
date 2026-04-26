package com.app.productivity.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "checklist_items",
    indices = [
        Index(value = ["userId", "dueDateEpochDay"], name = "idx_checklist_user_due"),
    ],
)
data class ChecklistEntity(
    @PrimaryKey val id: String,
    val userId: String,
    val title: String,
    val description: String?,
    val dueDateEpochDay: Long,        // LocalDate.toEpochDay()
    val estimatedMinutes: Int?,
    val priority: Int,                // 0 = low, 1 = med, 2 = high
    val completed: Boolean,
    val completedAt: Long?,           // epoch millis
    val createdAt: Long,
    val updatedAt: Long,
    val isSynced: Boolean = false,
)
