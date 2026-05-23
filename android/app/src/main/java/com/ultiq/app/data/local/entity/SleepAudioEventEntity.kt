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
)
