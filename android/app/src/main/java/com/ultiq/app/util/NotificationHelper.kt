package com.ultiq.app.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.ultiq.app.MainActivity
import com.ultiq.app.R

object NotificationHelper {

    const val CHANNEL_REMINDERS = "reminders"
    const val CHANNEL_SUMMARY = "summary"
    const val CHANNEL_ALARM = "alarm"

    const val NOTIFICATION_ID_BEDTIME = 2001
    const val NOTIFICATION_ID_FOCUS = 2002
    const val NOTIFICATION_ID_MORNING_SUMMARY = 2003
    /// §9.8 — Anomaly insight (FCM-delivered). One notification id keeps
    /// repeat alerts collapsed into a single row in the tray.
    const val NOTIFICATION_ID_ANOMALY = 2004
    /// §mic-permission (v2.13.5) — Surfaced when a sleep session starts with
    /// audio_tracking_enabled = true but RECORD_AUDIO not granted. Single id
    /// so back-to-back sessions just refresh the same row.
    const val NOTIFICATION_ID_MIC_PERMISSION = 2005
    private const val EVENT_NOTIFICATION_ID_BASE = 30_000

    const val EXTRA_DEEP_LINK = "deep_link_target"
    const val DEEP_LINK_DASHBOARD = "dashboard"
    const val DEEP_LINK_SLEEP = "sleep"
    const val DEEP_LINK_SESSIONS = "sessions"
    const val DEEP_LINK_CALENDAR = "calendar"
    const val DEEP_LINK_CHECKLIST = "checklist"

    fun ensureChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return

        val reminders = NotificationChannel(
            CHANNEL_REMINDERS,
            context.getString(R.string.notif_channel_reminders),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = context.getString(R.string.notif_channel_reminders_desc)
            enableVibration(true)
        }

        val summary = NotificationChannel(
            CHANNEL_SUMMARY,
            context.getString(R.string.notif_channel_summary),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = context.getString(R.string.notif_channel_summary_desc)
        }

        val alarm = NotificationChannel(
            CHANNEL_ALARM,
            context.getString(R.string.notif_channel_alarm),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(R.string.notif_channel_alarm_desc)
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
            context.getString(R.string.notif_bedtime_now)
        } else {
            context.resources.getQuantityString(
                R.plurals.notif_bedtime_soon, minutesUntilBedtime, minutesUntilBedtime,
            )
        }
        notify(
            context = context,
            id = NOTIFICATION_ID_BEDTIME,
            channelId = CHANNEL_REMINDERS,
            title = context.getString(R.string.notif_bedtime_title),
            body = text,
            deepLink = DEEP_LINK_SLEEP
        )
    }

    fun showFocusReminder(context: Context) {
        notify(
            context = context,
            id = NOTIFICATION_ID_FOCUS,
            channelId = CHANNEL_REMINDERS,
            title = context.getString(R.string.notif_focus_title),
            body = context.getString(R.string.notif_focus_body),
            deepLink = DEEP_LINK_SESSIONS
        )
    }

    /// v2.13.19 — Humanises the reminder lead time so the body reads
    /// "In 2 days" / "In 1 week" rather than "In 2880 min" / "In 10080 min".
    /// Aligned with the picker labels in AddEventDialog so users see the same
    /// wording end-to-end. Falls back to a "Xh Ym" mix for offsets that don't
    /// land on a clean unit (e.g. a 90-min reminder reads "1 hr 30 min").
    private fun formatLeadTime(minutes: Int): String {
        return when {
            minutes <= 0 -> "0 min"
            minutes % 10080 == 0 -> {
                val weeks = minutes / 10080
                if (weeks == 1) "1 week" else "$weeks weeks"
            }
            minutes % 1440 == 0 -> {
                val days = minutes / 1440
                if (days == 1) "1 day" else "$days days"
            }
            minutes >= 60 -> {
                val hours = minutes / 60
                val rem = minutes % 60
                val hPart = if (hours == 1) "1 hr" else "$hours hr"
                if (rem == 0) hPart else "$hPart $rem min"
            }
            else -> "$minutes min"
        }
    }

    fun showEventReminder(context: Context, eventId: String, eventTitle: String, minutesUntil: Int) {
        val body = if (minutesUntil <= 0) {
            context.getString(R.string.notif_event_now, eventTitle)
        } else {
            context.getString(R.string.notif_event_soon, formatLeadTime(minutesUntil), eventTitle)
        }
        notify(
            context = context,
            id = eventNotificationId(eventId),
            channelId = CHANNEL_REMINDERS,
            title = context.getString(R.string.notif_event_title),
            body = body,
            deepLink = DEEP_LINK_CALENDAR
        )
    }

    fun showMorningSummary(context: Context, sleepDuration: String?, eventCount: Int) {
        val parts = mutableListOf<String>()
        if (sleepDuration != null) parts += context.getString(R.string.notif_summary_slept, sleepDuration)
        parts += if (eventCount <= 0) {
            context.getString(R.string.notif_summary_no_events)
        } else {
            context.resources.getQuantityString(R.plurals.notif_summary_events, eventCount, eventCount)
        }
        notify(
            context = context,
            id = NOTIFICATION_ID_MORNING_SUMMARY,
            channelId = CHANNEL_SUMMARY,
            title = context.getString(R.string.notif_summary_title),
            body = parts.joinToString(" "),
            deepLink = DEEP_LINK_DASHBOARD
        )
    }

    fun cancelEventReminder(context: Context, eventId: String) {
        NotificationManagerCompat.from(context).cancel(eventNotificationId(eventId))
    }

    /// §mic-permission (v2.13.5) — Failsafe for the synced-on-without-grant
    /// case: server says audio_tracking_enabled = true but RECORD_AUDIO was
    /// never granted on this install, so the YAMNet pipeline silently no-ops
    /// inside SleepTrackingService.maybeStartAudioTracking. Fires once per
    /// session start; tapping opens the app's system permission page (we
    /// can't request runtime permissions from a Service).
    fun showMicPermissionNeeded(context: Context) {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return

        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", context.packageName, null),
        ).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID_MIC_PERMISSION,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val body = context.getString(R.string.notif_snore_off_body)
        val notification = NotificationCompat.Builder(context, CHANNEL_REMINDERS)
            // §branding — monochrome silhouette; Android renders smallIcon
            // via alpha only so passing the launcher mipmap used to surface
            // as a solid white circle in the status bar.
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ContextCompat.getColor(context, R.color.ultiq_indigo))
            .setContentTitle(context.getString(R.string.notif_snore_off_title))
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()
        try {
            manager.notify(NOTIFICATION_ID_MIC_PERMISSION, notification)
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS not granted on Android 13+
        }
    }

    /// §9.8 — Surface an FCM-delivered anomaly alert. Used by
    /// UltiqMessagingService.onMessageReceived for the foreground case (FCM
    /// auto-renders the tray notification only when the app is backgrounded,
    /// so foregrounded alerts otherwise silently disappear).
    fun showAnomalyAlert(context: Context, title: String, body: String) {
        notify(
            context = context,
            id = NOTIFICATION_ID_ANOMALY,
            channelId = CHANNEL_REMINDERS,
            title = title,
            body = body,
            deepLink = DEEP_LINK_DASHBOARD,
        )
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
            // §branding — see comment above for why monochrome.
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ContextCompat.getColor(context, R.color.ultiq_indigo))
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
