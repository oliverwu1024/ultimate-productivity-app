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

        /**
         * Re-arm signal sent when a paused session resumes: pop the gate immediately
         * (mirroring the gate shown on a fresh start) instead of waiting for the next
         * lock/unlock. Handled without re-initialising the already-live receiver/watcher.
         */
        const val ACTION_SHOW_GATE_NOW = "com.ultiq.app.focus.SHOW_GATE_NOW"

        val isRunning = MutableStateFlow(false)
        val sessionStartTime = MutableStateFlow(0L)
        /** Planned focus minutes for the active session — drives the overtime label on the overlay. */
        val plannedWorkMinutes = MutableStateFlow(0)
        val unlockCount = MutableStateFlow(0)
        /**
         * Live pause state of the running focus session, set by the in-app timer
         * (SessionsViewModel.pauseTimer/resumeTimer). It's purely a change signal: this
         * service collects it and repaints its ongoing notification (frozen "Paused" vs
         * live chronometer). The durable [LiveFocusSessionStore] the timer writes remains
         * the source of truth for the actual pause math; this only survives while the
         * process lives, and the notification is rebuilt from the store regardless.
         */
        val isPaused = MutableStateFlow(false)
    }

    /** Set once the service has entered the foreground, so a notification refresh
     *  (via the [isPaused] collector) can't post before the FGS promotion. */
    @Volatile
    private var foregrounded = false

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
                if (isFocusPaused()) {
                    Log.d(TAG, "USER_PRESENT — focus paused, gate suppressed")
                    return@launch
                }

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
        // Repaint the ongoing notification whenever the in-app timer flips pause state
        // (SessionsViewModel.pauseTimer/resumeTimer set isPaused): running → live
        // chronometer, paused → frozen "Paused". Guarded by `foregrounded` so the
        // initial replay can't post ahead of the FGS promotion.
        serviceScope.launch {
            isPaused.collect { refreshNotification() }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand — action=${intent?.action}")

        // Resume re-arm: a paused session was resumed. The receiver + foreground watcher
        // are already live from the original start, so don't re-init them — just cancel any
        // leftover "I need my phone" grace and pop the gate now (isFocusPaused() is already
        // false because resumeTimer commits pausedElapsedMs = -1 before signalling us).
        if (intent?.action == ACTION_SHOW_GATE_NOW) {
            enterForeground()
            LockoutOverlayController.clearGrace()
            showLockoutNow()
            return START_STICKY
        }

        // Only treat this as a fresh start when the caller supplied a planned
        // duration. If Android system-restarts the service (START_STICKY with a
        // null intent), we must NOT wipe sessionStartTime / plannedWorkMinutes
        // — that would break the overtime label on the overlay.
        val durationFromIntent = intent?.getIntExtra(EXTRA_WORK_DURATION_MIN, 0) ?: 0
        if (durationFromIntent > 0) {
            unlockCount.value = 0
            // Fresh session always begins running — reset the (static) pause flag in
            // case a prior session in this process left it paused.
            isPaused.value = false
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

        enterForeground()

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

    /** Start (or refresh) the foreground notification. Idempotent when already foreground. */
    private fun enterForeground() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                createNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, createNotification())
        }
        foregrounded = true
    }

    /** Repost the ongoing notification in place so it reflects the current pause state
     *  (frozen "Paused" vs live chronometer). Safe from any thread; no-op until the
     *  service has entered the foreground. */
    private fun refreshNotification() {
        if (!foregrounded) return
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, createNotification())
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
                if (isFocusPaused()) continue
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
            if (isFocusPaused()) {
                Log.d(TAG, "Initial gate skipped — focus paused")
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

    /**
     * A paused focus session must not gate the phone — pausing is the user's explicit
     * "I need a break" signal, so the lockout should neither pop nor re-snap while
     * paused. Reads the durable [LiveFocusSessionStore] the in-app timer writes on
     * pause/resume (`pausedElapsedMs >= 0` ⇒ paused), which covers every pause path.
     */
    private fun isFocusPaused(): Boolean =
        (LiveFocusSessionStore(applicationContext).load()?.pausedElapsedMs ?: -1L) >= 0L

    override fun onDestroy() {
        Log.d(TAG, "onDestroy — stopping focus tracking")
        try {
            unregisterReceiver(userPresentReceiver)
        } catch (_: Exception) { }

        LockoutNotifier.cancel(applicationContext)
        LockoutOverlayController.hide()
        serviceScope.cancel()
        isRunning.value = false
        isPaused.value = false
        foregrounded = false
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

        // Derive display + pause state from the durable store so the notification stays
        // correct across a process restart (the in-memory anchors reset to 0/false).
        val live = LiveFocusSessionStore(applicationContext).load()
        val paused = (live?.pausedElapsedMs ?: -1L) >= 0L
        // Effective (pause-adjusted) start so the running chronometer reflects real focus
        // time, excluding paused spans. Fall back to the in-memory anchor.
        val start = live?.startMs?.takeIf { it > 0L } ?: sessionStartTime.value

        // While paused, surface the frozen elapsed next to "Paused" (a running chronometer
        // can't freeze in place). DateUtils.formatElapsedTime matches the chronometer's own
        // M:SS / H:MM:SS rendering, so the number reads as the same timer, just stopped.
        val contentText = if (paused) {
            val frozen = android.text.format.DateUtils.formatElapsedTime(
                ((live?.pausedElapsedMs ?: 0L) / 1000L).coerceAtLeast(0L),
            )
            "$frozen · ${getString(R.string.notif_focus_paused)}"
        } else {
            getString(R.string.notif_tap_open)
        }

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.lockout_focus_active))
            .setContentText(contentText)
            // §branding — see LockoutNotifier / NotificationHelper.
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ContextCompat.getColor(this, R.color.ultiq_indigo))
            .setOngoing(true)
            // §lockscreen — public + STOPWATCH so the running session shows on a
            // secure lockscreen. Display-only: pause is driven from the in-app timer,
            // which this notification then mirrors (frozen "Paused" vs live chronometer).
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_STOPWATCH)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(pendingIntent)
        // Live elapsed, OS-ticked while running; frozen (chronometer off) while paused,
        // so the notification timer stops with the in-app timer. Guard the post-process-
        // kill case where the start anchor reset to 0.
        if (!paused && start > 0L) {
            builder.setUsesChronometer(true).setWhen(start)
        } else {
            builder.setShowWhen(false)
        }
        return builder.build()
    }
}
