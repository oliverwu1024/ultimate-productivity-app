package com.ultiq.app.util

import androidx.annotation.StringRes
import com.ultiq.app.R

/**
 * Mirrors the backend's `validate_password_strength`. Server is the source of
 * truth — this exists only so the UI can show inline feedback as the user types.
 * Labels are @StringRes so the checklist renders in the active language.
 */
object PasswordStrength {

    data class Check(
        @StringRes val labelRes: Int,
        val passed: Boolean,
    )

    fun checks(password: String): List<Check> = listOf(
        Check(R.string.pw_check_length, password.length >= 8),
        Check(R.string.pw_check_uppercase, password.any { it.isUpperCase() }),
        Check(R.string.pw_check_lowercase, password.any { it.isLowerCase() }),
        Check(R.string.pw_check_digit, password.any { it.isDigit() }),
        Check(
            R.string.pw_check_special,
            password.any { !it.isLetterOrDigit() && !it.isWhitespace() },
        ),
    )

    fun isValid(password: String): Boolean = checks(password).all { it.passed }
}
