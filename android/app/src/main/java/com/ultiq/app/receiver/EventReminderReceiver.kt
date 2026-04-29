package com.ultiq.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.ultiq.app.util.AlarmScheduler
import com.ultiq.app.util.NotificationHelper

class EventReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val eventId = intent.getStringExtra(AlarmScheduler.EXTRA_EVENT_ID) ?: return
        val title = intent.getStringExtra(AlarmScheduler.EXTRA_EVENT_TITLE) ?: "Event"
        val startMillis = intent.getLongExtra(AlarmScheduler.EXTRA_EVENT_START, 0L)

        val minutesUntil = if (startMillis > 0) {
            ((startMillis - System.currentTimeMillis()) / 60_000L).toInt().coerceAtLeast(0)
        } else {
            AlarmScheduler.EVENT_LEAD_MINUTES
        }

        NotificationHelper.ensureChannels(context)
        NotificationHelper.showEventReminder(context, eventId, title, minutesUntil)
    }
}
