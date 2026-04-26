package com.app.productivity.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.app.productivity.MainActivity
import com.app.productivity.ui.lockout.LockoutActivity
import com.app.productivity.ui.lockout.LockoutMode
import com.app.productivity.util.UserPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class FocusTrackingService : Service() {

    companion object {
        const val CHANNEL_ID = "focus_tracking"
        const val NOTIFICATION_ID = 1002

        val isRunning = MutableStateFlow(false)
        val sessionStartTime = MutableStateFlow(0L)
        val unlockCount = MutableStateFlow(0)
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var userPreferences: UserPreferences

    private val userPresentReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != Intent.ACTION_USER_PRESENT) return
            serviceScope.launch {
                val settings = userPreferences.settings.first()
                if (!settings.lockoutForFocus) return@launch

                unlockCount.value = unlockCount.value + 1

                val launchIntent = LockoutActivity.launchIntent(
                    context = context.applicationContext,
                    mode = LockoutMode.FOCUS,
                    sessionStartedAt = sessionStartTime.value,
                )
                context.applicationContext.startActivity(launchIntent)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        userPreferences = UserPreferences(applicationContext)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        unlockCount.value = 0
        sessionStartTime.value = System.currentTimeMillis()
        isRunning.value = true

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                createNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, createNotification())
        }

        val filter = IntentFilter(Intent.ACTION_USER_PRESENT)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(userPresentReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(userPresentReceiver, filter)
        }

        return START_STICKY
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(userPresentReceiver)
        } catch (_: Exception) { }

        serviceScope.cancel()
        isRunning.value = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Focus Tracking",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Tracks your focus session"
            setShowBadge(false)
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun createNotification(): Notification {
        val tapIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, tapIntent, PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Focus session active")
            .setContentText("Tap to open app")
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()
    }
}
