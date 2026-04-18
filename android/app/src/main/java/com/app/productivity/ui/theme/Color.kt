package com.app.productivity.ui.theme

import androidx.compose.ui.graphics.Color

// ── Brand: calm blue-purple ─────────────────────────────────────────────

val LightPrimary = Color(0xFF4F5BC4)
val LightOnPrimary = Color(0xFFFFFFFF)
val LightPrimaryContainer = Color(0xFFDEE0FF)
val LightOnPrimaryContainer = Color(0xFF000F5C)

val LightSecondary = Color(0xFF5C5D72)
val LightOnSecondary = Color(0xFFFFFFFF)
val LightSecondaryContainer = Color(0xFFE1E0F9)
val LightOnSecondaryContainer = Color(0xFF181A2C)

val LightTertiary = Color(0xFF785378)
val LightOnTertiary = Color(0xFFFFFFFF)
val LightTertiaryContainer = Color(0xFFFFD7F5)
val LightOnTertiaryContainer = Color(0xFF2E1131)

val LightError = Color(0xFFBA1A1A)
val LightOnError = Color(0xFFFFFFFF)
val LightErrorContainer = Color(0xFFFFDAD6)
val LightOnErrorContainer = Color(0xFF410002)

val LightBackground = Color(0xFFFEFBFF)
val LightOnBackground = Color(0xFF1B1B1F)
val LightSurface = Color(0xFFFEFBFF)
val LightOnSurface = Color(0xFF1B1B1F)
val LightSurfaceVariant = Color(0xFFE3E1EC)
val LightOnSurfaceVariant = Color(0xFF46464F)
val LightOutline = Color(0xFF777680)

val DarkPrimary = Color(0xFFBCC2FF)
val DarkOnPrimary = Color(0xFF1A2790)
val DarkPrimaryContainer = Color(0xFF3641AB)
val DarkOnPrimaryContainer = Color(0xFFDEE0FF)

val DarkSecondary = Color(0xFFC5C4DD)
val DarkOnSecondary = Color(0xFF2D2F42)
val DarkSecondaryContainer = Color(0xFF444559)
val DarkOnSecondaryContainer = Color(0xFFE1E0F9)

val DarkTertiary = Color(0xFFE7B8DC)
val DarkOnTertiary = Color(0xFF452643)
val DarkTertiaryContainer = Color(0xFF5E3C5B)
val DarkOnTertiaryContainer = Color(0xFFFFD7F5)

val DarkError = Color(0xFFFFB4AB)
val DarkOnError = Color(0xFF690005)
val DarkErrorContainer = Color(0xFF93000A)
val DarkOnErrorContainer = Color(0xFFFFDAD6)

val DarkBackground = Color(0xFF1B1B1F)
val DarkOnBackground = Color(0xFFE4E1E6)
val DarkSurface = Color(0xFF1B1B1F)
val DarkOnSurface = Color(0xFFE4E1E6)
val DarkSurfaceVariant = Color(0xFF46464F)
val DarkOnSurfaceVariant = Color(0xFFC7C5D0)
val DarkOutline = Color(0xFF918F9A)

// ── Category & priority semantic colors ─────────────────────────────────

object CategoryColors {
    val Study = Color(0xFF4A90D9)
    val Project = Color(0xFF2ECC71)
    val Exercise = Color(0xFFE67E22)
    val Personal = Color(0xFF9B59B6)
    val Other = Color(0xFF95A5A6)

    fun forCategory(category: String): Color = when (category) {
        "study" -> Study
        "project" -> Project
        "exercise" -> Exercise
        "personal" -> Personal
        else -> Other
    }

    fun hexForCategory(category: String): String = when (category) {
        "study" -> "#4A90D9"
        "project" -> "#2ECC71"
        "exercise" -> "#E67E22"
        "personal" -> "#9B59B6"
        else -> "#95A5A6"
    }
}

object PriorityColors {
    val High = Color(0xFFD32F2F)
    val Medium = Color(0xFFFFA000)
    val Low = Color(0xFF388E3C)

    fun forPriority(priority: String): Color = when (priority) {
        "high" -> High
        "low" -> Low
        else -> Medium
    }
}

// ── Quality star color (shared across screens) ──────────────────────────

val QualityStar = Color(0xFFFFB300)
