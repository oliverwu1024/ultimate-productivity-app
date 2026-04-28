package com.app.productivity.data.remote.dto

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
    val created_at: String
)

data class AuthResponse(
    val token: String,
    val user: UserResponse
)

data class ChangePasswordRequest(
    val current_password: String,
    val new_password: String,
)
