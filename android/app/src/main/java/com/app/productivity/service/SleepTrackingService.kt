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

data class PickupEvent(
    val pickedUpAt: Long,
    val durationSeconds: Int
)

class SleepTrackingService : Service() {

    companion object {
        const val CHANNEL_ID = "sleep_tracking"
        const val NOTIFICATION_ID = 1001

        val isRunning = MutableStateFlow(false)
        val sessionStartTime = MutableStateFlow(0L)
        val pickupEvents = MutableStateFlow<List<PickupEvent>>(emptyList())
        val sessionUnlockCount = MutableStateFlow(0)

        private var lastScreenOnTime: Long? = null
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var userPreferences: UserPreferences

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_ON -> {
                    lastScreenOnTime = System.currentTimeMillis()
                }
                Intent.ACTION_SCREEN_OFF -> {
                    lastScreenOnTime?.let { onTime ->
                        val now = System.currentTimeMillis()
                        val duration = ((now - onTime) / 1000).toInt()
                        pickupEvents.value = pickupEvents.value + PickupEvent(
                            pickedUpAt = onTime,
                            durationSeconds = duration
                        )
                        lastScreenOnTime = null
                    }
                }
                Intent.ACTION_USER_PRESENT -> {
                    serviceScope.launch {
                        val settings = userPreferences.settings.first()
                        if (!settings.lockoutForSleep) return@launch

                        sessionUnlockCount.value = sessionUnlockCount.value + 1

                        val launchIntent = LockoutActivity.launchIntent(
                            context = context.applicationContext,
                            mode = LockoutMode.SLEEP,
                            sessionStartedAt = sessionStartTime.value,
                        )
                        context.applicationContext.startActivity(launchIntent)
                    }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        userPreferences = UserPreferences(applicationContext)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        pickupEvents.value = emptyList()
        sessionUnlockCount.value = 0
        sessionStartTime.value = System.currentTimeMillis()
        lastScreenOnTime = null
        isRunning.value = true

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, createNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, createNotification())
        }

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(screenReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(screenReceiver, filter)
        }

        return START_STICKY
    }

    override fun onDestroy() {
        // Capture in-progress pickup if screen is still on
        lastScreenOnTime?.let { onTime ->
            val now = System.currentTimeMillis()
            val duration = ((now - onTime) / 1000).toInt()
            pickupEvents.value = pickupEvents.value + PickupEvent(
                pickedUpAt = onTime,
                durationSeconds = duration
            )
            lastScreenOnTime = null
        }

        try {
            unregisterReceiver(screenReceiver)
        } catch (_: Exception) { }

        serviceScope.cancel()
        isRunning.value = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Sleep Tracking",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Tracks your sleep session"
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
            this, 0, tapIntent, PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Sleep tracking active")
            .setContentText("Tap to open app")
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()
    }
}
