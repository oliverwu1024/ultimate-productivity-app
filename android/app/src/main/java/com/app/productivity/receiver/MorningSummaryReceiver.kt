package com.app.productivity.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.app.productivity.data.local.AppDatabase
import com.app.productivity.util.AlarmScheduler
import com.app.productivity.util.NotificationHelper
import com.app.productivity.util.ReminderPreferences
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
                val todayStart = today.atStartOfDay(zone).toInstant().toEpochMilli()
                val todayEnd = todayStart + 86_400_000 - 1
                val lookbackStart = todayStart - 36 * 3_600_000L

                val sleepRecords = db.sleepDao()
                    .getRecordsBetween(lookbackStart, todayStart)
                    .firstOrNull()
                    .orEmpty()
                val lastNight = sleepRecords.firstOrNull()
                val sleepStr = lastNight?.let {
                    val mins = ((it.actualWakeTime - it.actualBedtime) / 60_000L).toInt()
                    val h = mins / 60
                    val m = mins % 60
                    if (h > 0) "${h}h ${m}m" else "${m}m"
                }

                val events = db.calendarEventDao()
                    .getEventsForRange(todayStart, todayEnd)
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
