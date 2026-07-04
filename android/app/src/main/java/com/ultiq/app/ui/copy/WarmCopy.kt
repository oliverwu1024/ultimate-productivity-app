package com.ultiq.app.ui.copy

import android.content.Context
import com.ultiq.app.R
import java.time.LocalTime

/**
 * Copy variants that make Ultiq sound like a companion, not a form.
 * Picks deterministic-but-varied lines from time-of-day or simple context.
 *
 * §13.1 (i18n) — the branching stays in Kotlin; only the text moved to
 * `strings.xml`. Every entry point takes a [Context] so it resolves in the
 * user's chosen app language (callers pass `LocalContext.current`).
 */
object WarmCopy {

    fun greeting(context: Context, now: LocalTime = LocalTime.now()): String = context.getString(
        when (now.hour) {
            in 5..7 -> R.string.warm_greeting_up_early
            in 8..11 -> R.string.warm_greeting_morning
            in 12..13 -> R.string.warm_greeting_lunch
            in 14..16 -> R.string.warm_greeting_afternoon
            in 17..19 -> R.string.warm_greeting_evening
            in 20..22 -> R.string.warm_greeting_winding_down
            else -> R.string.warm_greeting_up_late
        }
    )

    fun greetingSubtitle(context: Context, now: LocalTime = LocalTime.now()): String = context.getString(
        when (now.hour) {
            in 5..11 -> R.string.warm_subtitle_fresh_start
            in 12..16 -> R.string.warm_subtitle_half_day
            in 17..20 -> R.string.warm_subtitle_how_land
            in 21..23 -> R.string.warm_subtitle_rest_soon
            else -> R.string.warm_subtitle_new_run
        }
    )

    /**
     * Sleep card heading. Keys off the 1–5 star rating so every tier has a
     * clear line (no "Last night" dead zones for quality 3 or a short 4).
     * For mid-tiers, a night that ran >1h under optimal gets a "short"
     * nuance; top/rough tiers ignore duration (the user's own rating already
     * tells that story, and the exact delta shows in the line below the card).
     */
    fun sleepHeader(context: Context, quality: Int?, vsTargetMinutes: Int?): String {
        if (quality == null || quality < 1) return context.getString(R.string.warm_sleep_how)
        val short = (vsTargetMinutes ?: 0) < -60
        return context.getString(
            when {
                quality >= 5 -> R.string.warm_sleep_top
                quality == 4 -> if (short) R.string.warm_sleep_solid_short else R.string.warm_sleep_solid
                quality == 3 -> if (short) R.string.warm_sleep_decent_short else R.string.warm_sleep_decent
                else -> R.string.warm_sleep_rough // quality 1–2
            }
        )
    }

    fun sleepEmpty(context: Context): Pair<String, String> =
        context.getString(R.string.warm_sleep_empty_title) to context.getString(R.string.warm_sleep_empty_body)

    /** Focus card heading varies with minutes today. */
    fun focusHeader(context: Context, minutesToday: Int?): String {
        val m = minutesToday ?: 0
        return context.getString(
            when {
                m == 0 -> R.string.warm_focus_ready
                m < 25 -> R.string.warm_focus_starting
                m < 90 -> R.string.warm_focus_momentum
                m < 180 -> R.string.warm_focus_deep
                else -> R.string.warm_focus_big
            }
        )
    }

    fun focusEmpty(context: Context): Pair<String, String> =
        context.getString(R.string.warm_focus_empty_title) to context.getString(R.string.warm_focus_empty_body)

    /** Streak phrasing — surfaces record proximity when relevant. */
    fun streakLine(context: Context, current: Int, longest: Int): String? {
        if (current <= 0) return null
        return when {
            current >= longest && current >= 2 ->
                context.resources.getQuantityString(R.plurals.warm_streak_longest, current, current)
            current == longest - 1 && longest >= 3 ->
                context.resources.getQuantityString(R.plurals.warm_streak_one_off, current, current)
            else ->
                context.resources.getQuantityString(R.plurals.warm_streak_days, current, current)
        }
    }

    fun achievementEarned(context: Context, name: String): String =
        context.getString(R.string.warm_achievement_unlocked, name)

    fun calendarEmpty(context: Context): Pair<String, String> =
        context.getString(R.string.warm_calendar_empty_title) to context.getString(R.string.warm_calendar_empty_body)

    fun checklistEmpty(context: Context): Pair<String, String> =
        context.getString(R.string.warm_checklist_empty_title) to context.getString(R.string.warm_checklist_empty_body)

    fun sessionsEmpty(context: Context): Pair<String, String> =
        context.getString(R.string.warm_sessions_empty_title) to context.getString(R.string.warm_sessions_empty_body)

    fun upcomingEventsEmpty(context: Context): String =
        context.getString(R.string.warm_upcoming_empty)
}
