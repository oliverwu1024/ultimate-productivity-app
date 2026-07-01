package com.ultiq.app.service

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
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.ultiq.app.MainActivity
import com.ultiq.app.R
import com.ultiq.app.ui.lockout.LockoutMode
import com.ultiq.app.ui.lockout.LockoutOverlayController
import com.ultiq.app.util.LockoutNotifier
import com.ultiq.app.util.PhoneUsageTracker
import com.ultiq.app.util.UserPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class FocusTrackingService : Service() {

    companion object {
        const val TAG = "FocusTrackingService"
        // §lockscreen — bumped from "focus_tracking": a channel's lockscreen
        // visibility is frozen after first creation, so a new id is required to
        // give existing installs the public/controllable notification.
        const val CHANNEL_ID = "focus_tracking_v2"
        const val NOTIFICATION_ID = 1002
        const val EXTRA_WORK_DURATION_MIN = "extra_work_duration_min"
        const val EXTRA_TAG = "extra_tag"

        val isRunning = MutableStateFlow(false)
        val sessionStartTime = MutableStateFlow(0L)
        /** Planned focus minutes for the active session — drives the overtime label on the overlay. */
        val plannedWorkMinutes = MutableStateFlow(0)
        val unlockCount = MutableStateFlow(0)
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var userPreferences: UserPreferences
    private var foregroundWatcherJob: Job? = null

    private val userPresentReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            Log.d(TAG, "onReceive action=${intent.action}")
            if (intent.action != Intent.ACTION_USER_PRESENT) return
            val appContext = context.applicationContext
            serviceScope.launch {
                val settings = userPreferences.settings.first()
                Log.d(TAG, "USER_PRESENT — lockoutForFocus=${settings.lockoutForFocus}")
                if (!settings.lockoutForFocus) return@launch

                unlockCount.value = unlockCount.value + 1

                if (LockoutOverlayController.canShow(appContext)) {
                    Log.d(TAG, "Showing lockout overlay (focus)")
                    withContext(Dispatchers.Main) {
                        LockoutOverlayController.show(
                            context = appContext,
                            mode = LockoutMode.FOCUS,
                            sessionStartedAt = sessionStartTime.value,
                            graceMinutes = settings.focusLockoutGraceMinutes,
                        )
                    }
                } else {
                    Log.d(TAG, "Overlay permission missing — falling back to notification")
                    LockoutNotifier.notify(
                        context = appContext,
                        mode = LockoutMode.FOCUS,
                        sessionStartedAt = sessionStartTime.value,
                    )
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
        Log.d(TAG, "onStartCommand — starting focus tracking")
        // Only treat this as a fresh start when the caller supplied a planned
        // duration. If Android system-restarts the service (START_STICKY with a
        // null intent), we must NOT wipe sessionStartTime / plannedWorkMinutes
        // — that would break the overtime label on the overlay.
        val durationFromIntent = intent?.getIntExtra(EXTRA_WORK_DURATION_MIN, 0) ?: 0
        if (durationFromIntent > 0) {
            unlockCount.value = 0
            sessionStartTime.value = System.currentTimeMillis()
            plannedWorkMinutes.value = durationFromIntent
            // §widget — durable live-session flag the Focus widget reads. The
            // in-memory statics proved unreliable when read from a widget refresh.
            val tag = intent?.getStringExtra(EXTRA_TAG)?.takeIf { it.isNotBlank() } ?: "Focus"
            LiveFocusSessionStore(this).save(
                LiveFocusSessionStore.Snapshot(sessionStartTime.value, durationFromIntent, tag = tag),
            )
        }
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

        // §widget-sync — reflect the running session on the Focus home-screen widget
        // immediately, covering app-initiated starts (the widget's own start already
        // repaints). Synchronous RemoteViews push, so it's instant even in background.
        com.ultiq.app.ui.widget.FocusWidgetProvider.updateAll(applicationContext)

        val filter = IntentFilter(Intent.ACTION_USER_PRESENT)
        // System broadcasts: register as EXPORTED on Android 13+ so the system can deliver them.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(userPresentReceiver, filter, RECEIVER_EXPORTED)
        } else {
            registerReceiver(userPresentReceiver, filter)
        }
        Log.d(TAG, "USER_PRESENT receiver registered")

        // Pop the gate immediately so the user feels the session has begun.
        // We use the same overlay-with-notification-fallback path as the unlock receiver.
        showLockoutNow()
        startForegroundWatcher()

        return START_STICKY
    }

    /**
     * Periodically check the foreground app. If the user has navigated away from this
     * app (or any system surface) during a focus session and isn't in the grace
     * window from "I need my phone", snap the lockout overlay back into place.
     */
    private fun startForegroundWatcher() {
        foregroundWatcherJob?.cancel()
        foregroundWatcherJob = serviceScope.launch {
            val tracker = PhoneUsageTracker(applicationContext)
            val ownPackage = applicationContext.packageName
            while (true) {
                delay(3_000)
                val settings = userPreferences.settings.first()
                if (!settings.lockoutForFocus) continue
                if (LockoutOverlayController.isShown()) continue
                if (LockoutOverlayController.isInGracePeriod()) continue
                val foreground = tracker.getForegroundPackage() ?: continue
                if (foreground == ownPackage) continue
                if (tracker.isSystemComponent(foreground)) continue

                if (LockoutOverlayController.canShow(applicationContext)) {
                    Log.d(TAG, "Foreground=$foreground — re-showing lockout overlay")
                    withContext(Dispatchers.Main) {
                        LockoutOverlayController.show(
                            context = applicationContext,
                            mode = LockoutMode.FOCUS,
                            sessionStartedAt = sessionStartTime.value,
                            graceMinutes = settings.focusLockoutGraceMinutes,
                        )
                    }
                }
            }
        }
    }

    private fun showLockoutNow() {
        serviceScope.launch {
            val settings = userPreferences.settings.first()
            if (!settings.lockoutForFocus) {
                Log.d(TAG, "Initial gate skipped — lockoutForFocus disabled")
                return@launch
            }

            if (LockoutOverlayController.canShow(applicationContext)) {
                Log.d(TAG, "Showing initial lockout overlay (focus)")
                withContext(Dispatchers.Main) {
                    LockoutOverlayController.show(
                        context = applicationContext,
                        mode = LockoutMode.FOCUS,
                        sessionStartedAt = sessionStartTime.value,
                        graceMinutes = settings.focusLockoutGraceMinutes,
                    )
                }
            } else {
                Log.d(TAG, "Initial gate — overlay perm missing, posting notification")
                LockoutNotifier.notify(
                    context = applicationContext,
                    mode = LockoutMode.FOCUS,
                    sessionStartedAt = sessionStartTime.value,
                )
            }
        }
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy — stopping focus tracking")
        try {
            unregisterReceiver(userPresentReceiver)
        } catch (_: Exception) { }

        LockoutNotifier.cancel(applicationContext)
        LockoutOverlayController.hide()
        serviceScope.cancel()
        isRunning.value = false
        plannedWorkMinutes.value = 0
        // §widget — any normal stop (widget / lockscreen / in-app) routes through
        // stopService → onDestroy, so clearing here covers every end path.
        LiveFocusSessionStore(applicationContext).clear()
        // §widget-sync — reflect the ended session on the Focus widget immediately.
        com.ultiq.app.ui.widget.FocusWidgetProvider.updateAll(applicationContext)
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
            // §lockscreen — show the session + Stop control on a secure lockscreen.
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        val manager = getSystemService(NotificationManager::class.java)
        // Drop the pre-lockscreen channel so upgraders don't keep its frozen
        // (private) visibility.
        manager.deleteNotificationChannel("focus_tracking")
        manager.createNotificationChannel(channel)
    }

    private fun createNotification(): Notification {
        val tapIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, tapIntent, PendingIntent.FLAG_IMMUTABLE,
        )

        val start = sessionStartTime.value
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Focus session active")
            .setContentText("Tap to open app")
            // §branding — see LockoutNotifier / NotificationHelper.
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ContextCompat.getColor(this, R.color.ultiq_indigo))
            .setOngoing(true)
            // §lockscreen — public + STOPWATCH so the running session shows on a
            // secure lockscreen. Display-only: controlling the session is app-only.
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_STOPWATCH)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(pendingIntent)
        // Live elapsed, OS-ticked. Guard the post-process-kill case where the
        // start anchor reset to 0 (would otherwise show elapsed-since-epoch).
        if (start > 0L) {
            builder.setUsesChronometer(true).setWhen(start)
        }
        return builder.build()
    }
}
