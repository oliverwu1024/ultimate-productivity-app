package com.app.productivity.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "productivity_sessions")
data class SessionEntity(
    @PrimaryKey val id: String,
    val userId: String,
    val tag: String,
    val durationMinutes: Int,
    val workDuration: Int,
    val breakDuration: Int,
    val phonePickups: Int,
    val startedAt: Long,
    val endedAt: Long?,
    val completed: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
    val checklistItemId: String? = null,
    val isSynced: Boolean = false,
)
