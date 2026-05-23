package com.ultiq.app.data.remote.dto

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

/// §10 — One element inside a batch upload. The `sleep_record_id` is carried
/// at the batch level (all events share the same record).
data class CreatePhonePickupBatchItemDto(
    val picked_up_at: String,
    val duration_seconds: Int,
    val app_category: String? = null,
)

/// §10 — Body for POST /phone-pickups/batch. Android buffers per-pickup
/// events in `SleepTrackingService.pickupEvents` during a session and
/// uploads the lot when the user saves the sleep record.
data class BatchCreatePhonePickupsDto(
    val sleep_record_id: String,
    val events: List<CreatePhonePickupBatchItemDto>,
)

fun PickupEvent.toBatchItemDto(): CreatePhonePickupBatchItemDto {
    return CreatePhonePickupBatchItemDto(
        picked_up_at = Instant.ofEpochMilli(pickedUpAt).toString(),
        duration_seconds = durationSeconds,
        app_category = null,
    )
}
