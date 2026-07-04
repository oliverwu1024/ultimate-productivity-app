package com.ultiq.app.ui.calendar

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.ultiq.app.R
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Locale

/**
 * §13.1 (i18n) — display labels for the stored "enum-ish" event fields
 * (category / priority / frequency / weekday), resolved in the app locale.
 * The STORED values ("study", "high", "WEEKLY", "MON", …) never change —
 * only their on-screen text does.
 */

@Composable
internal fun categoryLabel(category: String): String = stringResource(
    when (category) {
        "study" -> R.string.category_study
        "project" -> R.string.category_project
        "exercise" -> R.string.category_exercise
        "personal" -> R.string.category_personal
        else -> R.string.category_other
    }
)

/** Full priority word for the picker chips ("High" / "Medium" / "Low"). */
@Composable
internal fun priorityChipLabel(priority: String): String = stringResource(
    when (priority) {
        "high" -> R.string.priority_high
        "low" -> R.string.priority_low
        else -> R.string.priority_medium
    }
)

@Composable
internal fun frequencyLabel(frequency: String): String = stringResource(
    when (frequency) {
        "DAILY" -> R.string.freq_daily
        "WEEKLY" -> R.string.freq_weekly
        else -> R.string.freq_monthly
    }
)

/** Reminder offset in minutes → localized compact label (min / hr / day / week). */
@Composable
internal fun reminderOffsetLabel(minutes: Int): String = when {
    minutes < 60 -> pluralStringResource(R.plurals.estimate_minutes, minutes, minutes)
    minutes < 1440 -> pluralStringResource(R.plurals.reminder_hr, minutes / 60, minutes / 60)
    minutes < 10080 -> pluralStringResource(R.plurals.reminder_day, minutes / 1440, minutes / 1440)
    else -> pluralStringResource(R.plurals.reminder_week, minutes / 10080, minutes / 10080)
}

/** Stored recurrence weekday code ("MON".."SUN") → localized short day name. */
internal fun weekdayShortLabel(day: String, locale: Locale): String {
    val dow = when (day) {
        "MON" -> DayOfWeek.MONDAY
        "TUE" -> DayOfWeek.TUESDAY
        "WED" -> DayOfWeek.WEDNESDAY
        "THU" -> DayOfWeek.THURSDAY
        "FRI" -> DayOfWeek.FRIDAY
        "SAT" -> DayOfWeek.SATURDAY
        else -> DayOfWeek.SUNDAY
    }
    return dow.getDisplayName(TextStyle.SHORT, locale)
}
