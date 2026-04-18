package com.app.productivity.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.app.productivity.util.AlarmScheduler
import com.app.productivity.util.NotificationHelper
import com.app.productivity.util.ReminderPreferences
import com.app.productivity.util.ReminderSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class BedtimeReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        NotificationHelper.ensureChannels(context)
        NotificationHelper.showBedtimeReminder(context, ReminderSettings.BEDTIME_LEAD_MINUTES)

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val prefs = ReminderPreferences(context.applicationContext).snapshot()
                if (prefs.bedtimeEnabled) {
                    AlarmScheduler(context.applicationContext).scheduleBedtime(prefs.bedtimeTime)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
