package com.ultiq.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.ultiq.app.alarm.WakeAlarmScheduler
import com.ultiq.app.data.local.AppDatabase
import com.ultiq.app.util.AlarmScheduler
import com.ultiq.app.util.ReminderPreferences
import com.ultiq.app.util.UserPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_LOCKED_BOOT_COMPLETED &&
            action != "android.intent.action.MY_PACKAGE_REPLACED" &&
            action != "android.intent.action.QUICKBOOT_POWERON"
        ) return

        val pendingResult = goAsync()
        val app = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val scheduler = AlarmScheduler(app)
                val prefs = ReminderPreferences(app).snapshot()
                val targetBedtime = UserPreferences(app).snapshot().targetBedtime
                scheduler.applyDailyReminders(prefs, targetBedtime)

                // §2026-06-09 — AlarmScheduler.rescheduleAllEventReminders
                // now takes master rows and expands recurring series
                // internally (single source of truth for the schedule
                // window + per-occurrence request codes). The pre-v2.17.x
                // path here passed the unexpanded master directly, which
                // armed only a single alarm at the master's first-ever
                // startTime; the v2.17.1 fix passed an expanded list,
                // which then collapsed every occurrence back onto one
                // PendingIntent slot inside the scheduler. Passing
                // masters straight from the DAO restores the right shape
                // for both the new fix and any future tweaks.
                val db = AppDatabase.getInstance(app)
                val now = System.currentTimeMillis()
                val end = now + 30L * 86_400_000L
                val masters = db.calendarEventDao()
                    .getEventsForRange(now, end)
                    .firstOrNull()
                    .orEmpty()
                scheduler.rescheduleAllEventReminders(masters)

                // Phase 8: re-arm wake-up alarms after reboot / app update.
                WakeAlarmScheduler(app).rescheduleAll()
            } finally {
                pendingResult.finish()
            }
        }
    }
}
