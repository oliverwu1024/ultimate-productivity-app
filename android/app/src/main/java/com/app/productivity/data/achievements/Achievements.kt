package com.app.productivity.data.achievements

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.DoNotDisturb
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.SelfImprovement
import androidx.compose.material.icons.filled.Stars
import androidx.compose.material.icons.filled.WbTwilight
import androidx.compose.ui.graphics.vector.ImageVector

enum class AchievementId(
    val displayName: String,
    val description: String,
    val icon: ImageVector,
) {
    EARLY_BIRD(
        "Early Bird",
        "7 consecutive days hitting your target wake time",
        Icons.Default.WbTwilight,
    ),
    NIGHT_OWL_NO_MORE(
        "Night Owl No More",
        "7 consecutive days hitting your target bedtime",
        Icons.Default.Bedtime,
    ),
    FOCUS_MASTER(
        "Focus Master",
        "50 total focus hours",
        Icons.Default.SelfImprovement,
    ),
    CENTURY(
        "Century",
        "100 completed sessions",
        Icons.Default.EmojiEvents,
    ),
    ZEN_MODE(
        "Zen Mode",
        "5 sessions with zero phone pickups",
        Icons.Default.DoNotDisturb,
    ),
    IRON_STREAK(
        "Iron Streak",
        "30-day focus streak",
        Icons.Default.LocalFireDepartment,
    ),
    SLEEP_CHAMPION(
        "Sleep Champion",
        "14-day sleep target streak",
        Icons.Default.Stars,
    ),
    MARATHON(
        "Marathon",
        "4+ hours of focus in a single day",
        Icons.AutoMirrored.Filled.DirectionsRun,
    );
}
