package com.ultiq.app.service

import android.content.Context

/**
 * Durable "a focus session is running" flag, backed by SharedPreferences so it is
 * reliable across process boundaries and cold widget-update processes. The Focus
 * home-screen widget reads this instead of [FocusTrackingService]'s in-memory
 * `isRunning`/`sessionStartTime` statics, which proved unreliable when read from a
 * widget refresh (the widget could render before the static was visible, so the
 * timer never appeared).
 *
 * Mirrors [LiveSleepSessionStore]. Written by [FocusTrackingService] on a fresh
 * start and cleared on service destroy (so any normal stop — widget, lockscreen,
 * or in-app — clears it); the widget/lockscreen callbacks also write/clear it
 * optimistically for instant feedback. `commit()` (not `apply()`) so the flag is
 * on disk before a widget refresh can read it.
 */
class LiveFocusSessionStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    data class Snapshot(
        /** Effective (pause-adjusted) wall-clock start: running elapsed = now - startMs. */
        val startMs: Long,
        val plannedMinutes: Int,
        /** -1 = running; >= 0 = paused, holding the frozen elapsed ms at the pause. */
        val pausedElapsedMs: Long = -1L,
    )

    fun save(snapshot: Snapshot) {
        prefs.edit()
            .putLong(KEY_START, snapshot.startMs)
            .putInt(KEY_PLANNED, snapshot.plannedMinutes)
            .putLong(KEY_PAUSED, snapshot.pausedElapsedMs)
            .commit()
    }

    /** The running session, or null when none is active. */
    fun load(): Snapshot? {
        val start = prefs.getLong(KEY_START, 0L)
        if (start <= 0L) return null
        return Snapshot(start, prefs.getInt(KEY_PLANNED, 0), prefs.getLong(KEY_PAUSED, -1L))
    }

    fun clear() {
        prefs.edit().clear().commit()
    }

    companion object {
        private const val PREFS = "live_focus_session"
        private const val KEY_START = "start_ms"
        private const val KEY_PLANNED = "planned_min"
        private const val KEY_PAUSED = "paused_elapsed_ms"
    }
}
