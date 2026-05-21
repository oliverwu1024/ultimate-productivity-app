package com.ultiq.app.data.local.entity

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
    // Bit 0 = Sunday … bit 6 = Saturday. 0 = not recurring (one-off / by-due).
    val recurrenceDaysMask: Int = 0,
    // True → item appears every day from today through dueDate until completed
    // (the "due on which day" mode). Ignored when recurrenceDaysMask != 0.
    val showUntilDue: Boolean = false,
    // For recurring items: epoch day the user last ticked it off. Lets each
    // day-of-week occurrence reset overnight without touching the master row.
    val lastCompletedEpochDay: Long? = null,
)
