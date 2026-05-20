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
)

data class UpdateProfileRequest(
    val sleep_target_minutes: Int? = null,
    /** §sync-prefs: partial blob — only the keys you want changed. Server
     *  JSONB-merges this into the existing preferences. */
    val preferences: JsonObject? = null,
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
