package com.app.productivity.util

/**
 * Mirrors the backend's `validate_password_strength`. Server is the source of
 * truth — this exists only so the UI can show inline feedback as the user types.
 */
object PasswordStrength {

    data class Check(
        val label: String,
        val passed: Boolean,
    )

    fun checks(password: String): List<Check> = listOf(
        Check("At least 8 characters", password.length >= 8),
        Check("An uppercase letter", password.any { it.isUpperCase() }),
        Check("A lowercase letter", password.any { it.isLowerCase() }),
        Check("A digit", password.any { it.isDigit() }),
        Check(
            "A special character",
            password.any { !it.isLetterOrDigit() && !it.isWhitespace() },
        ),
    )

    fun isValid(password: String): Boolean = checks(password).all { it.passed }
}
