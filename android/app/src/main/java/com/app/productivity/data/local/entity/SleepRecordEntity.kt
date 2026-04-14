package com.app.productivity.data.local.entity

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
    val isSynced: Boolean = false
)
