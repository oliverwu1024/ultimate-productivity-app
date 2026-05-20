package com.ultiq.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "alarms")
data class AlarmEntity(
    @PrimaryKey val id: String,
    val userId: String,
    val label: String?,
    /** Local trigger hour (0..23). Combined with [triggerMinute] this is the
     *  device-local wall-clock time at which the alarm should fire. */
    val triggerHour: Int,
    val triggerMinute: Int,
    /** Bitmask: bit 0 = Sunday … bit 6 = Saturday. 0 means one-shot (fires once
     *  on the next occurrence of triggerHour:triggerMinute, then disables itself). */
    val daysOfWeekMask: Int,
    val enabled: Boolean,
    val soundUri: String?,
    val volumePct: Int,
    val volumeEscalates: Boolean,
    val vibration: Boolean,
    val snoozeMinutes: Int,
    val snoozeMax: Int,
    /** One of: "none", "math", "shake", "photo". */
    val missionKind: String,
    /** JSON blob describing the mission's parameters (difficulty, shake count, photo
     *  reference URI + pHash, etc.). Parsed at the mission boundary. */
    val missionConfigJson: String,
    val createdAt: Long,
    val updatedAt: Long,
    val isSynced: Boolean = false,
)
