package com.ultiq.app.data.remote.dto

import com.ultiq.app.data.local.entity.PhonePickupEntity
import com.ultiq.app.service.PickupEvent
import java.time.Instant

/// §10 — Single phone-pickup row returned from the backend (mirrors the
/// `phone_pickups` table row).
data class PhonePickupDto(
    val id: String,
    val user_id: String,
    val sleep_record_id: String?,
    val session_id: String?,
    val picked_up_at: String,
    val duration_seconds: Int,
    val app_category: String?,
    val created_at: String,
)

/// §10 — One element inside a batch upload. The parent (sleep_record_id
/// or session_id) is carried at the batch level.
///
/// §v2.16.18 — Optional `id` for client-supplied UUIDs. Backend's new
/// `ON CONFLICT (id) DO NOTHING` collapses retries onto one row, same
/// idempotency pattern as v2.16.15 sleep_records. Older callers that
/// omit `id` keep working — backend mints one server-side.
data class CreatePhonePickupBatchItemDto(
    val id: String? = null,
    val picked_up_at: String,
    val duration_seconds: Int,
    val app_category: String? = null,
)

/// §10 — Body for POST /phone-pickups/batch. Android buffers per-pickup
/// events in `SleepTrackingService.pickupEvents` (sleep) or queries
/// PhoneUsageTracker at session-end (focus) and uploads the lot.
///
/// §v2.16.18 — `sleep_record_id` is now nullable and `session_id` is
/// new. Exactly one of the two must be set; the backend rejects both
/// or neither. Sleep flow keeps using `sleep_record_id`; focus flow
/// uses `session_id`.
data class BatchCreatePhonePickupsDto(
    val sleep_record_id: String?,
    val session_id: String?,
    val events: List<CreatePhonePickupBatchItemDto>,
)

fun PickupEvent.toBatchItemDto(): CreatePhonePickupBatchItemDto {
    return CreatePhonePickupBatchItemDto(
        picked_up_at = Instant.ofEpochMilli(pickedUpAt).toString(),
        duration_seconds = durationSeconds,
        app_category = null,
    )
}

/// §v2.16.17 — Server DTO → local Room row. `synced=true` for rows that
/// came back from the backend (canonical), `false` for the offline
/// stand-ins savePickupEvents writes before the upload attempt.
fun PhonePickupDto.toLocalEntity(synced: Boolean): PhonePickupEntity {
    return PhonePickupEntity(
        id = id,
        userId = user_id,
        sleepRecordId = sleep_record_id,
        sessionId = session_id,
        pickedUpAt = Instant.parse(picked_up_at).toEpochMilli(),
        durationSeconds = duration_seconds,
        appCategory = app_category,
        createdAt = Instant.parse(created_at).toEpochMilli(),
        isSynced = synced,
    )
}

/// §v2.16.17 — Local Room row → server DTO, used by the offline-fallback
/// path in getPickupsForSleep so the ViewModel sees a uniform shape
/// regardless of whether the data came from backend or Room. Local rows
/// won't have a server-issued `createdAt` until they sync, so we round-
/// trip `createdAt` through Instant for consistency.
fun PhonePickupEntity.toDto(): PhonePickupDto {
    return PhonePickupDto(
        id = id,
        user_id = userId,
        sleep_record_id = sleepRecordId,
        session_id = sessionId,
        picked_up_at = Instant.ofEpochMilli(pickedUpAt).toString(),
        duration_seconds = durationSeconds,
        app_category = appCategory,
        created_at = Instant.ofEpochMilli(createdAt).toString(),
    )
}
