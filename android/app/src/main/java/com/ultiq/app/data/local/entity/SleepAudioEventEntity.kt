package com.ultiq.app.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "sleep_audio_events",
    indices = [
        Index(value = ["sleepRecordId", "startedAt"]),
    ],
)
data class SleepAudioEventEntity(
    @PrimaryKey val id: String,
    val userId: String,
    val sleepRecordId: String,
    val eventType: String,
    val startedAt: Long,
    val endedAt: Long,
    val peakConfidence: Float,
    val createdAt: Long,
    val isSynced: Boolean = false,
    // §10.x — Pro-tier audio clip metadata, mirrored from the server.
    // Null/false on every row created locally before upload; the server
    // response after attachClip is the source of truth and overwrites
    // the local cache via toEntity().
    val hasClip: Boolean = false,
    val clipDurationMs: Int? = null,
)
