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

    /**
     * Sleep card heading. Keys off the 1–5 star rating so every tier has a
     * clear line (no "Last night" dead zones for quality 3 or a short 4).
     * For mid-tiers, a night that ran >1h under optimal gets a "short"
     * nuance; top/rough tiers ignore duration (the user's own rating already
     * tells that story, and the exact delta shows in the line below the card).
     */
    fun sleepHeader(quality: Int?, vsTargetMinutes: Int?): String {
        if (quality == null || quality < 1) return "How'd you sleep?"
        val short = (vsTargetMinutes ?: 0) < -60
        return when {
            quality >= 5 -> "Top night"
            quality == 4 -> if (short) "Solid, but a bit short" else "Solid night"
            quality == 3 -> if (short) "Decent, but cut short" else "Decent night"
            else -> "Rough one — go easy today" // quality 1–2
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

    fun upcomingEventsEmpty(): String = "Nothing on your calendar today"
}
