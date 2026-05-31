package com.ultiq.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * §v2.15.9 — local-delete marker for sleep_records. Same pattern as
 * [AlarmTombstoneEntity]. Without this, deletes that fail at the
 * server step (network blip, backend rate limit, app force-stop
 * mid-call) silently lose only the local row; the next pull from the
 * server re-inserts the "deleted" record. Observed during v2.15.7
 * testing when the shared rate-limit bucket was 429-ing every call,
 * including delete requests — users saw their cleanup work undone
 * the moment they reinstalled.
 *
 * Tombstones are cleared once the server confirms the delete (or
 * returns 404, meaning it never existed there in the first place).
 */
@Entity(tableName = "sleep_tombstones")
data class SleepTombstoneEntity(
    @PrimaryKey val id: String,
    val deletedAt: Long,
)
