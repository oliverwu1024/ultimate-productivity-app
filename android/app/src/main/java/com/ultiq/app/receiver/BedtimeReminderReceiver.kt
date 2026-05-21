package com.ultiq.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.ultiq.app.util.AlarmScheduler
import com.ultiq.app.util.NotificationHelper
import com.ultiq.app.util.ReminderPreferences
import com.ultiq.app.util.ReminderSettings
import com.ultiq.app.util.UserPreferences
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
                val app = context.applicationContext
                val prefs = ReminderPreferences(app).snapshot()
                if (prefs.bedtimeEnabled) {
                    // §fix-bedtime-unified — re-arm tomorrow's reminder
                    // against the canonical target bedtime, not the stale
                    // reminder-prefs copy.
                    val targetBedtime = UserPreferences(app).snapshot().targetBedtime
                    AlarmScheduler(app).scheduleBedtime(targetBedtime)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
