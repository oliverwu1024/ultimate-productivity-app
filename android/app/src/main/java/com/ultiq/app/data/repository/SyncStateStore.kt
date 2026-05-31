package com.ultiq.app.data.repository

import android.content.Context

/**
 * §v2.15.10 — Persists "how many consecutive empty server responses
 * have we seen?" per entity type. Used to gate the destructive
 * reconciliation step in [SleepRepository.sync],
 * [CalendarRepository.sync], and [ChecklistRepository.sync]:
 *
 *   - First time the server returns an empty list while local has rows,
 *     we treat it as suspicious (could be a backend hiccup, rate-limit
 *     fallout, query-window edge case, JWT-resolves-wrong-user, etc.)
 *     and refuse to wipe — just bump the streak.
 *   - Second consecutive empty response confirms the user really did
 *     delete everything from another device. Reset the streak and let
 *     the normal reconciliation wipe local rows.
 *
 * Surfaces across the v2.15.7→9 incident were all variations on
 * "server returned empty once, phone wiped everything, dashboard still
 * has the data" — the user's calendar + checklist disappeared even
 * though backend was intact. This guard makes that single-bad-response
 * wipe impossible.
 *
 * Backed by SharedPreferences so the streak survives app restarts and
 * worker process restarts. Cleared on uninstall (along with everything
 * else local).
 */
class SyncStateStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE,
    )

    /** How many consecutive empty responses we've seen for this entity. */
    fun getEmptyStreak(entityKey: String): Int =
        prefs.getInt(streakKey(entityKey), 0)

    /** Increment the streak, return the new value. */
    fun incrementEmptyStreak(entityKey: String): Int {
        val next = getEmptyStreak(entityKey) + 1
        prefs.edit().putInt(streakKey(entityKey), next).apply()
        return next
    }

    /** Reset to 0. Call when the server returns a non-empty response,
     *  OR after we've decided to honour the wipe (so the next cycle
     *  starts fresh). */
    fun resetEmptyStreak(entityKey: String) {
        prefs.edit().remove(streakKey(entityKey)).apply()
    }

    private fun streakKey(entityKey: String) = "empty_streak_$entityKey"

    companion object {
        private const val PREFS_NAME = "ultiq_sync_state"

        // Entity keys — referenced from each repository to avoid string drift.
        const val ENTITY_SLEEP = "sleep_records"
        const val ENTITY_CALENDAR = "calendar_events"
        const val ENTITY_CHECKLIST = "checklist_items"

        /** Threshold: number of consecutive empty responses we require
         *  before honouring the wipe. 2 is the minimum useful value —
         *  one suspicious response gets a second chance. */
        const val REQUIRED_EMPTY_STREAK = 2
    }
}
