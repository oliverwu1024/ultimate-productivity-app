package com.ultiq.app.data.local.entity

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
    // §9.7 / 2026-06-06 — User-written "what did you work on?" line
    // (≤ 240 chars, validated server-side) + Haiku-assigned tag. Backend
    // has had these columns since migration 017; the Android side just
    // wasn't reading them. NULL until the user submits the post-session
    // dialog (skippable).
    val debrief: String? = null,
    val debriefTag: String? = null,
    // §tz-anchor — IANA zone this session was started in. Past sessions render
    // their clock times in this zone. null → device tz.
    val recordedTz: String? = null,
)
