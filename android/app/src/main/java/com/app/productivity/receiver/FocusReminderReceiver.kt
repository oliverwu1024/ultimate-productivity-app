package com.app.productivity.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.app.productivity.util.AlarmScheduler
import com.app.productivity.util.NotificationHelper
import com.app.productivity.util.ReminderPreferences
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
