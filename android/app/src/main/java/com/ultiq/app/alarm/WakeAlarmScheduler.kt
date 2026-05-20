package com.ultiq.app.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.ultiq.app.data.local.AppDatabase
import com.ultiq.app.data.local.entity.AlarmEntity
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * Schedules Ultiq wake-up alarms (Phase 8) via [AlarmManager.setAlarmClock].
 *
 * Distinct from [com.ultiq.app.util.AlarmScheduler], which handles
 * non-user-facing reminder notifications (bedtime, focus, morning summary,
 * event reminders). That class uses `setExactAndAllowWhileIdle`; the doze-
 * bypass guarantee for actual ring-the-phone alarms is only available via
 * `setAlarmClock`, which is what this class uses.
 */
class WakeAlarmScheduler(private val context: Context) {

    private val alarmManager: AlarmManager =
        context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    /**
     * Schedule (or re-schedule) the next occurrence of [alarm].
     *
     * No-op if the alarm is disabled, has no future occurrence in the next
     * week, or `SCHEDULE_EXACT_ALARM` has been revoked.
     */
    fun schedule(alarm: AlarmEntity): Long? {
        if (!alarm.enabled) {
            cancel(alarm.id)
            return null
        }
        if (!canScheduleExact()) {
            Log.w(TAG, "SCHEDULE_EXACT_ALARM not granted — cannot schedule ${alarm.id}")
            return null
        }
        val triggerAt = computeNextTrigger(alarm, System.currentTimeMillis())
        if (triggerAt == null) {
            Log.w(TAG, "No future trigger for alarm ${alarm.id} (mask=${alarm.daysOfWeekMask})")
            return null
        }
        val info = AlarmManager.AlarmClockInfo(triggerAt, buildShowIntent())
        alarmManager.setAlarmClock(info, buildFirePendingIntent(alarm.id))
        Log.d(TAG, "Scheduled alarm ${alarm.id} at $triggerAt")
        return triggerAt
    }

    /** Cancel any pending trigger for [alarmId]. Safe to call on unknown ids. */
    fun cancel(alarmId: String) {
        // PendingIntent equality is action + data + component — extras are
        // not part of the identity, so we omit them here.
        val pi = PendingIntent.getBroadcast(
            context,
            requestCode(alarmId),
            Intent(context, AlarmReceiver::class.java).apply { action = ACTION_FIRE },
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        )
        if (pi != null) {
            alarmManager.cancel(pi)
            pi.cancel()
        }
    }

    /**
     * Re-schedule every enabled alarm from local Room. Called on boot, app
     * start, and after sync replaces the local alarm set.
     */
    suspend fun rescheduleAll() {
        val dao = AppDatabase.getInstance(context).alarmDao()
        val enabled = dao.getEnabledAlarmsSync()
        Log.d(TAG, "Rescheduling ${enabled.size} enabled alarm(s)")
        enabled.forEach { schedule(it) }
    }

    private fun canScheduleExact(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()

    private fun buildFirePendingIntent(alarmId: String): PendingIntent {
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            action = ACTION_FIRE
            putExtra(EXTRA_ALARM_ID, alarmId)
        }
        return PendingIntent.getBroadcast(
            context,
            requestCode(alarmId),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /**
     * Lockscreen "alarm icon" target — tapping the alarm-clock icon on the
     * status bar should bring the user into the app's alarm list. For v0 we
     * just route to MainActivity; the alarms screen comes in §8.6.
     */
    private fun buildShowIntent(): PendingIntent {
        val intent = Intent(context, com.ultiq.app.MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            SHOW_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        private const val TAG = "WakeAlarmScheduler"

        const val ACTION_FIRE = "com.ultiq.app.alarm.action.FIRE"
        const val EXTRA_ALARM_ID = "alarm_id"

        private const val REQ_BASE = 20_000
        private const val SHOW_REQUEST_CODE = 19_999

        /** Stable, positive, conflict-free per-alarm request code. */
        internal fun requestCode(alarmId: String): Int =
            REQ_BASE + (alarmId.hashCode() and 0x0FFFFFFF)

        /**
         * Compute the next millis-epoch trigger for [alarm] strictly after [nowMillis].
         *
         * - `daysOfWeekMask == 0` is one-shot: today if still ahead, else tomorrow.
         * - Otherwise: scan the next 7 days, pick the first day whose bit is set
         *   where the alarm time is in the future.
         */
        internal fun computeNextTrigger(alarm: AlarmEntity, nowMillis: Long): Long? {
            val zone = ZoneId.systemDefault()
            val now = LocalDateTime.ofInstant(
                java.time.Instant.ofEpochMilli(nowMillis), zone,
            )
            val time = LocalTime.of(alarm.triggerHour, alarm.triggerMinute)

            if (alarm.daysOfWeekMask == 0) {
                val today = now.toLocalDate().atTime(time)
                val candidate = if (today.isAfter(now)) today else today.plusDays(1)
                return candidate.atZone(zone).toInstant().toEpochMilli()
            }

            // mask != 0 means at least one bit is set, so a match is guaranteed
            // within the next 7 days. The 0..6 range is exhaustive.
            for (i in 0..6) {
                val day = now.toLocalDate().plusDays(i.toLong())
                val candidate = day.atTime(time)
                if (!candidate.isAfter(now)) continue
                if ((alarm.daysOfWeekMask shr bitIndexFor(day.dayOfWeek)) and 1 == 1) {
                    return candidate.atZone(zone).toInstant().toEpochMilli()
                }
            }
            return null
        }

        /** bit 0 = Sunday … bit 6 = Saturday. */
        private fun bitIndexFor(day: DayOfWeek): Int = day.value % 7
    }
}
