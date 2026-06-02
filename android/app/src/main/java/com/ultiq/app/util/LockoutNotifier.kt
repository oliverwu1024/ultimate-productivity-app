package com.ultiq.app.util

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.ultiq.app.R
import com.ultiq.app.ui.lockout.LockoutActivity
import com.ultiq.app.ui.lockout.LockoutMode

/**
 * Posts a heads-up + full-screen-intent notification that opens LockoutActivity.
 *
 * Why a notification instead of context.startActivity():
 *   On Android 10+ (and far stricter on 14/15/16), Background Activity Launch (BAL)
 *   restrictions silently block startActivity() calls made from a BroadcastReceiver,
 *   even one registered by a foreground service. The system *does* allow activities
 *   launched via setFullScreenIntent() on a high-priority notification — this is the
 *   same path alarm clocks and incoming-call apps use, and it's the only reliable
 *   way to trigger a full-screen UI in response to ACTION_USER_PRESENT.
 */
object LockoutNotifier {
    private const val CHANNEL_ID = "lockout_alerts"
    private const val NOTIFICATION_ID = 2001

    private const val EXTRA_MODE = "mode"
    private const val EXTRA_SESSION_STARTED_AT = "session_started_at"

    fun notify(context: Context, mode: LockoutMode, sessionStartedAt: Long) {
        ensureChannel(context)

        val activityIntent = LockoutActivity.launchIntent(
            context = context,
            mode = mode,
            sessionStartedAt = sessionStartedAt,
        )

        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            mode.ordinal,
            activityIntent,
            flags,
        )

        val title = when (mode) {
            LockoutMode.FOCUS -> "Focus session active"
            LockoutMode.SLEEP -> "Sleep session active"
        }
        val body = when (mode) {
            LockoutMode.FOCUS -> "Tap to confirm you really need your phone"
            LockoutMode.SLEEP -> "Tap to confirm you really need your phone"
        }

        val notification: Notification = NotificationCompat.Builder(context, CHANNEL_ID)
            // §branding — was the generic Android padlock; replaced with the
            // monochrome Ultiq mark for consistency with the rest of the
            // notification surfaces.
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ContextCompat.getColor(context, R.color.ultiq_indigo))
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setOngoing(true)
            .setAutoCancel(false)
            .setContentIntent(pendingIntent)
            .setFullScreenIntent(pendingIntent, true)
            .build()

        val manager = context.getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification)
    }

    fun cancel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.cancel(NOTIFICATION_ID)
    }

    private fun ensureChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Lockout alerts",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Friction prompt when you unlock during a session"
            setBypassDnd(false)
            enableVibration(true)
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }
}
