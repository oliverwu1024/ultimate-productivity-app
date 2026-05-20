package com.ultiq.app.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.ultiq.app.MainActivity
import com.ultiq.app.R

object NotificationHelper {

    const val CHANNEL_REMINDERS = "reminders"
    const val CHANNEL_SUMMARY = "summary"
    const val CHANNEL_ALARM = "alarm"

    const val NOTIFICATION_ID_BEDTIME = 2001
    const val NOTIFICATION_ID_FOCUS = 2002
    const val NOTIFICATION_ID_MORNING_SUMMARY = 2003
    private const val EVENT_NOTIFICATION_ID_BASE = 30_000

    const val EXTRA_DEEP_LINK = "deep_link_target"
    const val DEEP_LINK_DASHBOARD = "dashboard"
    const val DEEP_LINK_SLEEP = "sleep"
    const val DEEP_LINK_SESSIONS = "sessions"
    const val DEEP_LINK_CALENDAR = "calendar"

    fun ensureChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return

        val reminders = NotificationChannel(
            CHANNEL_REMINDERS,
            "Reminders",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Bedtime, focus, and calendar event reminders"
            enableVibration(true)
        }

        val summary = NotificationChannel(
            CHANNEL_SUMMARY,
            "Daily Summary",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Morning summary of sleep and the day ahead"
        }

        val alarm = NotificationChannel(
            CHANNEL_ALARM,
            "Alarms",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Wake-up alarms — bypass Do Not Disturb"
            enableVibration(true)
            setBypassDnd(true)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            val alarmAttrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            // Channel sound is a fallback — the active sound comes from
            // AlarmRingService's MediaPlayer so we can stop it on dismiss.
            setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM), alarmAttrs)
        }

        manager.createNotificationChannel(reminders)
        manager.createNotificationChannel(summary)
        manager.createNotificationChannel(alarm)
    }

    fun showBedtimeReminder(context: Context, minutesUntilBedtime: Int) {
        val text = if (minutesUntilBedtime <= 0) {
            "Bedtime is now — start your sleep session"
        } else {
            "Time to wind down — bedtime in $minutesUntilBedtime minutes"
        }
        notify(
            context = context,
            id = NOTIFICATION_ID_BEDTIME,
            channelId = CHANNEL_REMINDERS,
            title = "Wind down",
            body = text,
            deepLink = DEEP_LINK_SLEEP
        )
    }

    fun showFocusReminder(context: Context) {
        notify(
            context = context,
            id = NOTIFICATION_ID_FOCUS,
            channelId = CHANNEL_REMINDERS,
            title = "Time to focus",
            body = "Start a session and protect your next block of deep work",
            deepLink = DEEP_LINK_SESSIONS
        )
    }

    fun showEventReminder(context: Context, eventId: String, eventTitle: String, minutesUntil: Int) {
        val body = if (minutesUntil <= 0) {
            "Starting now: $eventTitle"
        } else {
            "In $minutesUntil min: $eventTitle"
        }
        notify(
            context = context,
            id = eventNotificationId(eventId),
            channelId = CHANNEL_REMINDERS,
            title = "Upcoming event",
            body = body,
            deepLink = DEEP_LINK_CALENDAR
        )
    }

    fun showMorningSummary(context: Context, sleepDuration: String?, eventCount: Int) {
        val parts = mutableListOf<String>()
        if (sleepDuration != null) parts += "You slept $sleepDuration."
        parts += when (eventCount) {
            0 -> "No events scheduled today."
            1 -> "1 event on the calendar."
            else -> "$eventCount events on the calendar today."
        }
        notify(
            context = context,
            id = NOTIFICATION_ID_MORNING_SUMMARY,
            channelId = CHANNEL_SUMMARY,
            title = "Good morning",
            body = parts.joinToString(" "),
            deepLink = DEEP_LINK_DASHBOARD
        )
    }

    fun cancelEventReminder(context: Context, eventId: String) {
        NotificationManagerCompat.from(context).cancel(eventNotificationId(eventId))
    }

    fun eventNotificationId(eventId: String): Int =
        EVENT_NOTIFICATION_ID_BASE + (eventId.hashCode() and 0x0fffffff)

    private fun notify(
        context: Context,
        id: Int,
        channelId: String,
        title: String,
        body: String,
        deepLink: String,
    ) {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_DEEP_LINK, deepLink)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            id,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        try {
            manager.notify(id, notification)
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS not granted on Android 13+
        }
    }
}
