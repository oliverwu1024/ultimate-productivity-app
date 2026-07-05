package com.ultiq.app.data.achievements

import androidx.annotation.StringRes
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
import com.ultiq.app.R

enum class AchievementId(
    @StringRes val displayNameRes: Int,
    @StringRes val descriptionRes: Int,
    val icon: ImageVector,
) {
    EARLY_BIRD(
        R.string.achv_early_bird_name,
        R.string.achv_early_bird_desc,
        Icons.Default.WbTwilight,
    ),
    NIGHT_OWL_NO_MORE(
        R.string.achv_night_owl_name,
        R.string.achv_night_owl_desc,
        Icons.Default.Bedtime,
    ),
    FOCUS_MASTER(
        R.string.achv_focus_master_name,
        R.string.achv_focus_master_desc,
        Icons.Default.SelfImprovement,
    ),
    CENTURY(
        R.string.achv_century_name,
        R.string.achv_century_desc,
        Icons.Default.EmojiEvents,
    ),
    ZEN_MODE(
        R.string.achv_zen_mode_name,
        R.string.achv_zen_mode_desc,
        Icons.Default.DoNotDisturb,
    ),
    IRON_STREAK(
        R.string.achv_iron_streak_name,
        R.string.achv_iron_streak_desc,
        Icons.Default.LocalFireDepartment,
    ),
    SLEEP_CHAMPION(
        R.string.achv_sleep_champion_name,
        R.string.achv_sleep_champion_desc,
        Icons.Default.Stars,
    ),
    MARATHON(
        R.string.achv_marathon_name,
        R.string.achv_marathon_desc,
        Icons.AutoMirrored.Filled.DirectionsRun,
    );
}
