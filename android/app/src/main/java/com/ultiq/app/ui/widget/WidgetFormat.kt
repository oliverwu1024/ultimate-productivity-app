package com.ultiq.app.ui.widget

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val CLOCK = DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault())

/** Epoch millis → device-local "2:00 PM". */
fun formatClock(epochMillis: Long): String =
    Instant.ofEpochMilli(epochMillis)
        .atZone(ZoneId.systemDefault())
        .toLocalTime()
        .format(CLOCK)

/** Minutes → "7h 20m" / "45m". */
fun formatDuration(totalMinutes: Int): String {
    val h = totalMinutes / 60
    val m = totalMinutes % 60
    return when {
        h > 0 && m > 0 -> "${h}h ${m}m"
        h > 0 -> "${h}h"
        else -> "${m}m"
    }
}

/** Sleep quality rating (1..5) → short label, or null when unrated. */
fun qualityLabel(rating: Int): String? = when (rating) {
    1 -> "Poor"
    2 -> "Fair"
    3 -> "OK"
    4 -> "Good"
    5 -> "Great"
    else -> null
}
