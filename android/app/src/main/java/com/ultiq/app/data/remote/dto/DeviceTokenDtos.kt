package com.ultiq.app.data.remote.dto

/// §9.8 — Body for POST /devices/register. Mobile sends the FCM token it
/// receives from `FirebaseMessaging.getInstance().token` (and again from
/// `UltiqMessagingService.onNewToken` when the token rotates). Backend
/// upserts on the token column.
data class RegisterDeviceTokenRequest(
    val token: String,
    val platform: String = "android",
)

/// §9.8 — Response shape from POST /devices/register. Mobile doesn't act
/// on these fields today; kept as a typed DTO so a future feature (e.g.
/// "show registered devices" in Settings) can deserialise the same call.
data class DeviceTokenResponse(
    val id: String,
    val user_id: String,
    val token: String,
    val platform: String,
    val last_seen_at: String,
    val created_at: String,
)
