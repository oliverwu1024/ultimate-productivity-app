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

        // v2.13.19 — Prefer the user-picked offset (set by AlarmScheduler) so
        // the notification body always matches what they chose in the picker.
        // Recomputing from (startMillis - now) was off-by-one when the alarm
        // fired even a fraction of a second late: a 2880-min lead displayed
        // as "2879 min". Fall back to a recompute only when the extra is
        // missing (pre-2.13.19 PendingIntents still in flight).
        val pickedMinutes = intent.getIntExtra(AlarmScheduler.EXTRA_REMINDER_MINUTES, -1)
        val minutesUntil = when {
            pickedMinutes > 0 -> pickedMinutes
            startMillis > 0 ->
                ((startMillis - System.currentTimeMillis()) / 60_000L).toInt().coerceAtLeast(0)
            else -> AlarmScheduler.EVENT_LEAD_MINUTES
        }

        NotificationHelper.ensureChannels(context)
        NotificationHelper.showEventReminder(context, eventId, title, minutesUntil)
    }
}
