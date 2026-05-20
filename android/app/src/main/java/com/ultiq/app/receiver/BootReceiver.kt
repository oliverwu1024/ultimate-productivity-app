package com.ultiq.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.ultiq.app.alarm.WakeAlarmScheduler
import com.ultiq.app.data.local.AppDatabase
import com.ultiq.app.util.AlarmScheduler
import com.ultiq.app.util.ReminderPreferences
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
                scheduler.applyDailyReminders(prefs)

                val now = System.currentTimeMillis()
                val cutoff = now + 30L * 86_400_000L
                val events = AppDatabase.getInstance(app)
                    .calendarEventDao()
                    .getEventsForRange(now, cutoff)
                    .firstOrNull()
                    .orEmpty()
                scheduler.rescheduleAllEventReminders(events)

                // Phase 8: re-arm wake-up alarms after reboot / app update.
                WakeAlarmScheduler(app).rescheduleAll()
            } finally {
                pendingResult.finish()
            }
        }
    }
}
