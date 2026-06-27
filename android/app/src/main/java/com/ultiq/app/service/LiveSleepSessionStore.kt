package com.ultiq.app.service

import android.content.Context

/**
 * Durable snapshot of the in-progress sleep session, written at session
 * start and read back if the foreground service is killed + restarted by
 * the system (START_STICKY hands `onStartCommand` a null intent on restart).
 *
 * Why this exists: before it, the live session lived only in volatile state —
 * `SleepTrackingService.sessionStartTime` (an in-memory static) and the
 * per-session target window (only in `SleepViewModel`'s UI state). An
 * overnight process kill therefore corrupted a saved record two ways:
 *   - `onStartCommand` re-stamped `sessionStartTime` to the restart moment,
 *     so the *actual bedtime* reset to "now" (0h0m records), and
 *   - the recreated ViewModel's target window fell back to its
 *     `LocalTime.now()` / `+8h` defaults at End-Sleep time.
 *
 * SharedPreferences (not the app's usual DataStore) on purpose: the restore
 * has to be a *synchronous* read inside `onStartCommand`, before any
 * coroutine can run. `commit()` (not `apply()`) on writes so the row is on
 * disk before a kill can happen — durability is the whole point here.
 *
 * Target times are stored as minute-of-day (0..1439); -1 means "unset".
 */
class LiveSleepSessionStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    data class Snapshot(
        val startMs: Long,
        val targetBedtimeMin: Int,
        val targetWakeMin: Int,
        val pendingSessionId: String,
    )

    /** Persist a freshly-started session. Synchronous (`commit`) so it
     *  survives an immediate kill. */
    fun save(snapshot: Snapshot) {
        prefs.edit()
            .putLong(KEY_START, snapshot.startMs)
            .putInt(KEY_BED, snapshot.targetBedtimeMin)
            .putInt(KEY_WAKE, snapshot.targetWakeMin)
            .putString(KEY_PENDING, snapshot.pendingSessionId)
            .commit()
    }

    /** The saved session, or null when none is active (nothing to resume). */
    fun load(): Snapshot? {
        val start = prefs.getLong(KEY_START, 0L)
        if (start <= 0L) return null
        return Snapshot(
            startMs = start,
            targetBedtimeMin = prefs.getInt(KEY_BED, -1),
            targetWakeMin = prefs.getInt(KEY_WAKE, -1),
            pendingSessionId = prefs.getString(KEY_PENDING, "").orEmpty(),
        )
    }

    /** Drop the snapshot once the user explicitly ends the session. NOT
     *  called from the service's onDestroy — a system kill must leave the
     *  snapshot intact so the restart can resume it. */
    fun clear() {
        prefs.edit().clear().commit()
    }

    companion object {
        private const val PREFS = "live_sleep_session"
        private const val KEY_START = "start_ms"
        private const val KEY_BED = "target_bed_min"
        private const val KEY_WAKE = "target_wake_min"
        private const val KEY_PENDING = "pending_id"
    }
}
