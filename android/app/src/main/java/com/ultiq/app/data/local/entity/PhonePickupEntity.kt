package com.ultiq.app.data.local.entity

import androidx.room.Entity
import androidx.room.Index

/**
 * §v2.16.17 — Local Room row for a single phone-pickup event captured during
 * a sleep session. Before v2.16.17 pickups lived only on the backend, so an
 * offline session ended → in-memory list uploaded best-effort → backend GET
 * returned empty when the user expanded the record offline → the expanded
 * card showed only the count, never the per-pickup timeline. With this
 * entity Room is the local source of truth, the backend GET is only a
 * refresh, and the UI can render the timeline regardless of network.
 *
 * Same write+upload pattern as `sleep_audio_events`:
 *   1. SleepRepository.savePickupEvents writes to Room with isSynced=0
 *      first, then attempts the backend batch upload, then flips
 *      isSynced=1 on success.
 *   2. SleepRepository.getPickupsForSleep reads from Room as the
 *      authoritative source; a backend refresh runs alongside but on
 *      failure the Room data still renders.
 *   3. SleepRepository.fetchPickupsForRecord (post-online refresh)
 *      deletes the per-record slice and re-inserts from the server so
 *      Room mirrors backend canonical state (gets server-issued ids,
 *      app_category, etc.).
 *
 * `id` is client-generated at session-end (v4 UUID) so the upload is
 * naturally idempotent — backend's eventual `ON CONFLICT (id) DO NOTHING`
 * will collapse retries the same way sleep_records v2.16.15 does.
 */
@Entity(
    tableName = "phone_pickups",
    indices = [Index(value = ["sleepRecordId"]), Index(value = ["isSynced"])],
)
data class PhonePickupEntity(
    @androidx.room.PrimaryKey val id: String,
    val userId: String,
    val sleepRecordId: String?,
    val sessionId: String?,
    val pickedUpAt: Long,
    val durationSeconds: Int,
    val appCategory: String?,
    val createdAt: Long,
    val isSynced: Boolean = false,
)
