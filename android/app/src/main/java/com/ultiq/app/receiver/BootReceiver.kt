package com.ultiq.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.ultiq.app.alarm.WakeAlarmScheduler
import com.ultiq.app.data.local.AppDatabase
import com.ultiq.app.data.remote.RetrofitClient
import com.ultiq.app.data.repository.CalendarRepository
import com.ultiq.app.util.AlarmScheduler
import com.ultiq.app.util.ReminderPreferences
import com.ultiq.app.util.TokenManager
import com.ultiq.app.util.UserPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

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

                // §2026-06-06 — Same recurrence-expansion fix as
                // MorningSummaryReceiver: the raw DAO query returned every
                // recurring event's base row regardless of whether it
                // actually occurs in the window. The scheduler then
                // scheduled a single reminder at the base startTime for
                // each recurring event — recurring reminders effectively
                // misfired after a boot. Going through
                // CalendarRepository.getEventsForRange expands each
                // recurring event into its actual per-day occurrences
                // over the next 30 days.
                val today = LocalDate.now(ZoneId.systemDefault())
                val db = AppDatabase.getInstance(app)
                val calendarRepo = CalendarRepository(
                    db.calendarEventDao(),
                    RetrofitClient.create(TokenManager(app)),
                )
                val events = calendarRepo
                    .getEventsForRange(today, today.plusDays(30))
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
