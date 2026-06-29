package com.ultiq.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "sleep_records")
data class SleepRecordEntity(
    @PrimaryKey val id: String,
    val userId: String,
    val targetBedtime: String,
    val targetWakeTime: String,
    val actualBedtime: Long,
    val actualWakeTime: Long,
    val qualityRating: Int,
    val phonePickups: Int,
    val totalPhoneMinutes: Int?,
    val notes: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val isSynced: Boolean = false,
    // §tz-anchor — IANA zone this sleep was logged in (backend `recorded_tz`).
    // Past records render their clock times in THIS zone, not the device's
    // current one, so an "11pm Sydney" sleep stays 11pm after a move. null → device tz.
    val recordedTz: String? = null,
    // §last-night — true for daytime naps / short test sessions, so the "last
    // night" surfaces skip them when picking the most-recent overnight sleep.
    val isNap: Boolean = false
)
