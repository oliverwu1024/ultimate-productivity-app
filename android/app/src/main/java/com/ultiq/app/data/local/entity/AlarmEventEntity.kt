package com.ultiq.app.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "alarm_events",
    foreignKeys = [
        ForeignKey(
            entity = AlarmEntity::class,
            parentColumns = ["id"],
            childColumns = ["alarmId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [
        Index(value = ["userId", "firedAt"], name = "idx_alarm_events_user_fired"),
        Index(value = ["alarmId", "firedAt"], name = "idx_alarm_events_alarm_fired"),
    ],
)
data class AlarmEventEntity(
    @PrimaryKey val id: String,
    val userId: String,
    /** Nullable so the row survives the parent alarm being deleted (ON DELETE SET NULL). */
    val alarmId: String?,
    val firedAt: Long,
    val dismissedAt: Long?,
    /** One of: "mission", "snooze", "force", "abandoned". Null while still ringing. */
    val dismissMethod: String?,
    val snoozeCount: Int,
    val missionKind: String?,
    val missionAttempts: Int,
    val missionDurationMs: Int?,
    val createdAt: Long,
    val isSynced: Boolean = false,
)
