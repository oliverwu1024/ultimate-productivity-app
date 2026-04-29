package com.ultiq.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.ultiq.app.util.AlarmScheduler
import com.ultiq.app.util.NotificationHelper
import com.ultiq.app.util.ReminderPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class FocusReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        NotificationHelper.ensureChannels(context)
        NotificationHelper.showFocusReminder(context)

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val prefs = ReminderPreferences(context.applicationContext).snapshot()
                if (prefs.focusEnabled) {
                    AlarmScheduler(context.applicationContext).scheduleFocus(prefs.focusTime)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
