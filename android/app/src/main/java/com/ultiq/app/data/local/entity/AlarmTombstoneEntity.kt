package com.ultiq.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * §M5: local-delete marker. When the user deletes an alarm we write a
 * tombstone *before* removing the row, so a sync pull that returns the
 * still-server-side alarm doesn't resurrect it on the device.
 *
 * Tombstones are cleared once the server confirms the delete (or returns
 * 404, meaning it never existed there in the first place).
 */
@Entity(tableName = "alarm_tombstones")
data class AlarmTombstoneEntity(
    @PrimaryKey val id: String,
    val deletedAt: Long,
)
