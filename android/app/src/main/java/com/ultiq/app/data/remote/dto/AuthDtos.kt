package com.ultiq.app.data.remote.dto

import com.google.gson.JsonObject

data class RegisterRequest(
    val email: String,
    val password: String
)

data class LoginRequest(
    val email: String,
    val password: String
)

data class UserResponse(
    val id: String,
    val email: String,
    val created_at: String,
    val sleep_target_minutes: Int = 480,
    /** §sync-prefs: server's view of the user's per-device preferences. The
     *  Android client mirrors this into UserPreferences on login + every
     *  sync, so a reinstall on a new device doesn't lose your config. */
    val preferences: JsonObject? = null,
    /** §i18n (v2.13.9): IANA timezone string stored against the account.
     *  Backend uses it for "today" bucketing + the per-user anomaly
     *  scheduler. Surfaced here so Settings → Region can show what the
     *  server thinks the user is in. Default 'UTC' from the v2.13.9
     *  migration before the client first pushes its detected zone. */
    val timezone: String = "UTC",
)

data class UpdateProfileRequest(
    val sleep_target_minutes: Int? = null,
    /** §sync-prefs: partial blob — only the keys you want changed. Server
     *  JSONB-merges this into the existing preferences. */
    val preferences: JsonObject? = null,
    /** §i18n (v2.13.9): IANA timezone string (e.g. "Australia/Sydney").
     *  Android sends this right after every login / signup with
     *  `ZoneId.systemDefault().id`. Server validates the string and
     *  rejects unknown zones with 400. Null = leave the stored value
     *  alone. */
    val timezone: String? = null,
)

data class AuthResponse(
    val token: String,
    val user: UserResponse
)

data class ChangePasswordRequest(
    val current_password: String,
    val new_password: String,
)

data class ForgotPasswordRequest(
    val email: String,
)

data class ResetPasswordRequest(
    val token: String,
    val new_password: String,
)
