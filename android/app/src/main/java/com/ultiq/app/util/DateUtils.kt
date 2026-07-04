package com.ultiq.app.util

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * §13 (i18n) — date/time formatting threaded through the user's current app
 * locale, so month names / AM-PM / numerals follow the chosen language. The
 * locale defaults to [LocaleManager.currentLocale] and is read per call, so a
 * language switch (which recreates the activity) is picked up automatically.
 */
object DateUtils {
    fun formatDate(dateTime: LocalDateTime, locale: Locale = LocaleManager.currentLocale()): String =
        dateTime.format(DateTimeFormatter.ofPattern("MMM dd, yyyy", locale))

    fun formatTime(dateTime: LocalDateTime, locale: Locale = LocaleManager.currentLocale()): String =
        dateTime.format(DateTimeFormatter.ofPattern("hh:mm a", locale))
}
