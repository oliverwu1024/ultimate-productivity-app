package com.ultiq.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.ultiq.app.util.AlarmScheduler
import com.ultiq.app.util.NotificationHelper
import com.ultiq.app.util.ReminderPreferences
import com.ultiq.app.util.ReminderSettings
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
