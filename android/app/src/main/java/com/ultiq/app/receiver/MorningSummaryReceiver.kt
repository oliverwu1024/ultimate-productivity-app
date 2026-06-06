package com.ultiq.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.ultiq.app.data.local.AppDatabase
import com.ultiq.app.data.remote.RetrofitClient
import com.ultiq.app.data.repository.CalendarRepository
import com.ultiq.app.util.AlarmScheduler
import com.ultiq.app.util.NotificationHelper
import com.ultiq.app.util.ReminderPreferences
import com.ultiq.app.util.TokenManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

class MorningSummaryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = goAsync()
        val app = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val db = AppDatabase.getInstance(app)
                val zone = ZoneId.systemDefault()
                val today = LocalDate.now(zone)

                // §fix-morning-summary — query window ends at "now" not
                // "todayStart" so a session that ended this morning is
                // included. Then the wake-time staleness check below
                // suppresses the sleep line entirely when the most-recent
                // record is too old to plausibly be "last night" — e.g.
                // the user is still sleeping and the only saved record
                // is from a previous night.
                val now = System.currentTimeMillis()
                val windowStart = now - 24 * 3_600_000L
                val staleCutoffMs = now - 12 * 3_600_000L

                val sleepRecords = db.sleepDao()
                    .getRecordsBetween(windowStart, now)
                    .firstOrNull()
                    .orEmpty()
                val lastNight = sleepRecords
                    .firstOrNull()
                    ?.takeIf { it.actualWakeTime >= staleCutoffMs }
                val sleepStr = lastNight?.let {
                    val mins = ((it.actualWakeTime - it.actualBedtime) / 60_000L).toInt()
                    val h = mins / 60
                    val m = mins % 60
                    if (h > 0) "${h}h ${m}m" else "${m}m"
                }

                // §2026-06-06 — Go through CalendarRepository.getEventsForDay
                // so recurring events get expanded against today's date.
                // The raw DAO query returns every recurring event whose
                // base startTime is on or before today, which would count
                // a weekly Tuesday event on Wed/Thu/Fri mornings too. The
                // repository's expandAll walks the rule and emits only
                // occurrences actually on `today`. apiService is only
                // touched by write paths and never called here.
                val calendarRepo = CalendarRepository(
                    db.calendarEventDao(),
                    RetrofitClient.create(TokenManager(app)),
                )
                val events = calendarRepo
                    .getEventsForDay(today)
                    .firstOrNull()
                    .orEmpty()

                NotificationHelper.ensureChannels(context)
                NotificationHelper.showMorningSummary(context, sleepStr, events.size)

                val prefs = ReminderPreferences(app).snapshot()
                if (prefs.morningSummaryEnabled) {
                    AlarmScheduler(app).scheduleMorningSummary(prefs.morningSummaryTime)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
