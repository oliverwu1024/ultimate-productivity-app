package com.ultiq.app.ui.copy

import java.time.LocalTime

/**
 * Copy variants that make Ultiq sound like a companion, not a form.
 * Picks deterministic-but-varied lines from time-of-day or simple context.
 */
object WarmCopy {

    fun greeting(now: LocalTime = LocalTime.now()): String = when (now.hour) {
        in 5..7 -> "Up early"
        in 8..11 -> "Good morning"
        in 12..13 -> "Lunch break?"
        in 14..16 -> "Good afternoon"
        in 17..19 -> "Good evening"
        in 20..22 -> "Winding down"
        else -> "Up late"
    }

    fun greetingSubtitle(now: LocalTime = LocalTime.now()): String = when (now.hour) {
        in 5..11 -> "A fresh start."
        in 12..16 -> "Half the day to go."
        in 17..20 -> "How'd today land?"
        in 21..23 -> "Time to rest soon."
        else -> "Tomorrow's a new run."
    }

    /** Sleep card heading varies with quality + how it stacked vs target. */
    fun sleepHeader(quality: Int?, vsTargetMinutes: Int?): String {
        if (quality == null) return "How'd you sleep?"
        return when {
            quality >= 5 -> "Top night"
            quality >= 4 && (vsTargetMinutes ?: 0) >= 0 -> "Solid night"
            quality <= 2 -> "Rough one — go easy today"
            (vsTargetMinutes ?: 0) < -60 -> "Cut a bit short"
            else -> "Last night"
        }
    }

    fun sleepEmpty(): Pair<String, String> =
        "No nights logged yet" to "Tap Start Sleep tonight — we'll handle the rest."

    /** Focus card heading varies with minutes today. */
    fun focusHeader(minutesToday: Int?): String {
        val m = minutesToday ?: 0
        return when {
            m == 0 -> "Ready to focus?"
            m < 25 -> "Just getting started"
            m < 90 -> "Good momentum"
            m < 180 -> "Deep in it"
            else -> "Big focus day"
        }
    }

    fun focusEmpty(): Pair<String, String> =
        "Nothing yet today" to "Tag a task and start your first focus block."

    /** Streak phrasing — surfaces record proximity when relevant. */
    fun streakLine(current: Int, longest: Int): String? {
        if (current <= 0) return null
        return when {
            current >= longest && current >= 2 -> "$current-day streak — longest yet"
            current == longest - 1 && longest >= 3 -> "$current days, one off your record"
            else -> "$current day${if (current != 1) "s" else ""}"
        }
    }

    fun achievementEarned(name: String): String = "Unlocked: $name"

    fun calendarEmpty(): Pair<String, String> =
        "Nothing scheduled" to "Add an event and it'll show up here."

    fun checklistEmpty(): Pair<String, String> =
        "Today's wide open" to "Add a task to get going."

    fun sessionsEmpty(): Pair<String, String> =
        "Ready to focus?" to "Tag a task, pick durations, and start your first focus block."

    fun upcomingEventsEmpty(): String = "No calendar events planned today"
}
