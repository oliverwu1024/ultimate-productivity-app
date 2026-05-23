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
import com.ultiq.app.MainActivity
import com.ultiq.app.alarm.AlarmRingService
import com.ultiq.app.audio.SleepAudioClassifier
import com.ultiq.app.audio.SleepAudioEventAggregator
import com.ultiq.app.data.local.entity.SleepAudioEventEntity
import com.ultiq.app.ui.lockout.LockoutMode
import com.ultiq.app.ui.lockout.LockoutOverlayController
import com.ultiq.app.util.LockoutNotifier
import com.ultiq.app.util.PhoneUsageTracker
import com.ultiq.app.util.UserPreferences
import kotlinx.coroutines.CoroutineExceptionHandler
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

data class PickupEvent(
    val pickedUpAt: Long,
    val durationSeconds: Int
)

class SleepTrackingService : Service() {

    companion object {
        const val TAG = "SleepTrackingService"
        const val CHANNEL_ID = "sleep_tracking"
        const val NOTIFICATION_ID = 1001

        val isRunning = MutableStateFlow(false)
        val sessionStartTime = MutableStateFlow(0L)
        val pickupEvents = MutableStateFlow<List<PickupEvent>>(emptyList())
        val sessionUnlockCount = MutableStateFlow(0)

        // §10 — Audio events accumulated during the current sleep session.
        // Carries placeholder userId / sleepRecordId; SleepRepository rewrites
        // these with the real sleep_record id when persisting to Room at
        // session-end. Buffer is cleared at each session start.
        val pendingAudioEvents = MutableStateFlow<List<SleepAudioEventEntity>>(emptyList())
        val audioTrackingActive = MutableStateFlow(false)

        private var lastScreenOnTime: Long? = null
    }

    // §10.x — Backstop for any unhandled throwable inside a launched coroutine.
    // Without this, an Error (e.g. NoClassDefFoundError from R8-stripped
    // MediaPipe internals) propagating up from maybeStartAudioTracking would
    // hit the default uncaught-exception handler and crash the whole app
    // process when the user tapped Start Sleep. The handler logs at ERROR
    // level so the same condition still surfaces in logcat — it just no
    // longer takes the app down with it.
    private val serviceExceptionHandler = CoroutineExceptionHandler { _, throwable ->
        Log.e(TAG, "Uncaught throwable in serviceScope coroutine — swallowed to keep the service alive", throwable)
    }
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + serviceExceptionHandler)
    private lateinit var userPreferences: UserPreferences
    private var foregroundWatcherJob: Job? = null
    private var audioClassifier: SleepAudioClassifier? = null
    private var audioAggregator: SleepAudioEventAggregator? = null

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            Log.d(TAG, "onReceive action=${intent.action}")
            // §8.10: pickup detection is suspended whenever a Phase 8 wake
            // alarm is ringing — the screen-on/off cycle caused by dismissing
            // the alarm activity isn't a real pickup and shouldn't count.
            if (AlarmRingService.currentAlarmId.value != null) {
                Log.d(TAG, "${intent.action} ignored — alarm ringing")
                lastScreenOnTime = null
                return
            }
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
                    val appContext = context.applicationContext
                    serviceScope.launch {
                        val settings = userPreferences.settings.first()
                        Log.d(TAG, "USER_PRESENT — lockoutForSleep=${settings.lockoutForSleep}")
                        if (!settings.lockoutForSleep) return@launch

                        sessionUnlockCount.value = sessionUnlockCount.value + 1

                        if (LockoutOverlayController.canShow(appContext)) {
                            Log.d(TAG, "Showing lockout overlay (sleep)")
                            withContext(Dispatchers.Main) {
                                LockoutOverlayController.show(
                                    context = appContext,
                                    mode = LockoutMode.SLEEP,
                                    sessionStartedAt = sessionStartTime.value,
                                    graceMinutes = settings.sleepLockoutGraceMinutes,
                                )
                            }
                        } else {
                            Log.d(TAG, "Overlay permission missing — falling back to notification")
                            LockoutNotifier.notify(
                                context = appContext,
                                mode = LockoutMode.SLEEP,
                                sessionStartedAt = sessionStartTime.value,
                            )
                        }
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
        pendingAudioEvents.value = emptyList()
        audioTrackingActive.value = false
        lastScreenOnTime = null
        isRunning.value = true

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, createNotification(audioActive = false), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, createNotification(audioActive = false))
        }

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // System broadcasts: register as EXPORTED on Android 13+ so the system can deliver them.
            registerReceiver(screenReceiver, filter, RECEIVER_EXPORTED)
        } else {
            registerReceiver(screenReceiver, filter)
        }
        Log.d(TAG, "Screen + USER_PRESENT receiver registered")

        // Pop the gate immediately so the user feels the session has begun.
        showLockoutNow()
        startForegroundWatcher()
        maybeStartAudioTracking()

        return START_STICKY
    }

    /** §10 — Reads `audioTrackingEnabled` + mic permission asynchronously
     *  (DataStore is suspending). If both are true, upgrades the foreground
     *  service type to include MICROPHONE on Android 14+ and starts the
     *  YAMNet capture loop. No-ops otherwise.
     *
     *  Defense-in-depth: every step is wrapped in a `Throwable` (not just
     *  `Exception`) catch so an Error from a broken MediaPipe class load
     *  in release-build minified APKs degrades to "audio tracking off" rather
     *  than crashing the app. v2.11.0/v2.11.1 had this path throw an
     *  unhandled `Error` (R8 stripped AutoValue subclasses) which propagated
     *  to the default uncaught-exception handler and killed the process. */
    private fun maybeStartAudioTracking() {
        serviceScope.launch {
            val enabled = try {
                userPreferences.snapshot().audioTrackingEnabled
            } catch (e: Throwable) {
                Log.w(TAG, "Failed to read audio tracking pref", e)
                false
            }
            if (!enabled) return@launch
            if (!SleepAudioClassifier.isMicPermitted(applicationContext)) {
                Log.w(TAG, "Audio tracking on but RECORD_AUDIO not granted — skipping")
                return@launch
            }

            // Upgrade FGS type so the mic loop is allowed (Android 14+).
            // Pre-14 doesn't enforce per-type, so the SPECIAL_USE startForeground
            // from onStartCommand is sufficient.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                try {
                    startForeground(
                        NOTIFICATION_ID,
                        createNotification(audioActive = true),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
                    )
                } catch (e: Throwable) {
                    Log.e(TAG, "Failed to add MICROPHONE service type — audio tracking aborted", e)
                    return@launch
                }
            }

            // userId + sleepRecordId are stamped at session-end by the
            // SleepRepository when the actual sleep_record row is created;
            // the aggregator emits with placeholders that get rewritten.
            val agg = try {
                SleepAudioEventAggregator(
                    userId = "",
                    sleepRecordId = "",
                    onEventReady = { event ->
                        pendingAudioEvents.value = pendingAudioEvents.value + event
                        Log.i(
                            TAG,
                            "Audio event: ${event.eventType} @${event.startedAt} → ${event.endedAt} " +
                                "(peak conf=${event.peakConfidence})",
                        )
                    },
                )
            } catch (e: Throwable) {
                Log.e(TAG, "Aggregator init failed", e)
                revertNotificationToNoAudio()
                return@launch
            }
            audioAggregator = agg

            val classifier = try {
                SleepAudioClassifier.create(applicationContext, agg)
            } catch (e: Throwable) {
                Log.e(TAG, "SleepAudioClassifier.create threw", e)
                null
            }
            if (classifier == null) {
                Log.w(TAG, "SleepAudioClassifier.create returned null — RECORD_AUDIO probably not granted at the framework level")
                revertNotificationToNoAudio()
                return@launch
            }
            audioClassifier = classifier
            // §10.x — start() now returns Boolean (v2.11.3+). Check it
            // explicitly: a return-of-false means MediaPipe/AudioRecord init
            // hit a caught Throwable inside start() and silently disabled
            // itself; we must revert the notification + clear our references
            // so the rest of the session reflects the real state.
            val ok = try {
                classifier.start()
            } catch (e: Throwable) {
                Log.e(TAG, "classifier.start() threw — audio tracking disabled this session", e)
                false
            }
            if (!ok) {
                Log.w(TAG, "classifier.start() returned false — audio init failed; reverting notification + clearing references")
                audioClassifier = null
                audioAggregator = null
                revertNotificationToNoAudio()
                return@launch
            }
            audioTrackingActive.value = true
            Log.i(TAG, "Audio tracking started — pipeline confirmed live")
        }
    }

    /** §10.x (v2.11.3) — Undo the "+ sounds" notification text + drop the
     *  MICROPHONE service-type bitmask when audio init fails after the
     *  FGS-type upgrade already happened. Before this, the user saw
     *  "Tracking sleep + sounds" with no mic indicator + no events — the
     *  notification lied about what the service was actually doing. */
    private fun revertNotificationToNoAudio() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            try {
                startForeground(
                    NOTIFICATION_ID,
                    createNotification(audioActive = false),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                )
            } catch (e: Throwable) {
                Log.w(TAG, "Failed to revert FGS type after audio init failure (non-fatal)", e)
            }
        } else {
            try {
                startForeground(NOTIFICATION_ID, createNotification(audioActive = false))
            } catch (e: Throwable) {
                Log.w(TAG, "Failed to refresh notification after audio init failure (non-fatal)", e)
            }
        }
    }

    private fun startForegroundWatcher() {
        foregroundWatcherJob?.cancel()
        foregroundWatcherJob = serviceScope.launch {
            val tracker = PhoneUsageTracker(applicationContext)
            val ownPackage = applicationContext.packageName
            while (true) {
                delay(3_000)
                val settings = userPreferences.settings.first()
                if (!settings.lockoutForSleep) continue
                if (LockoutOverlayController.isShown()) continue
                if (LockoutOverlayController.isInGracePeriod()) continue
                val foreground = tracker.getForegroundPackage() ?: continue
                if (foreground == ownPackage) continue
                if (tracker.isSystemComponent(foreground)) continue

                if (LockoutOverlayController.canShow(applicationContext)) {
                    Log.d(TAG, "Foreground=$foreground — re-showing lockout overlay (sleep)")
                    withContext(Dispatchers.Main) {
                        LockoutOverlayController.show(
                            context = applicationContext,
                            mode = LockoutMode.SLEEP,
                            sessionStartedAt = sessionStartTime.value,
                            graceMinutes = settings.sleepLockoutGraceMinutes,
                        )
                    }
                }
            }
        }
    }

    private fun showLockoutNow() {
        serviceScope.launch {
            val settings = userPreferences.settings.first()
            if (!settings.lockoutForSleep) {
                Log.d(TAG, "Initial gate skipped — lockoutForSleep disabled")
                return@launch
            }

            if (LockoutOverlayController.canShow(applicationContext)) {
                Log.d(TAG, "Showing initial lockout overlay (sleep)")
                withContext(Dispatchers.Main) {
                    LockoutOverlayController.show(
                        context = applicationContext,
                        mode = LockoutMode.SLEEP,
                        sessionStartedAt = sessionStartTime.value,
                        graceMinutes = settings.sleepLockoutGraceMinutes,
                    )
                }
            } else {
                Log.d(TAG, "Initial gate — overlay perm missing, posting notification")
                LockoutNotifier.notify(
                    context = applicationContext,
                    mode = LockoutMode.SLEEP,
                    sessionStartedAt = sessionStartTime.value,
                )
            }
        }
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

        // Tear down the audio loop. shutdown() flushes any in-flight event
        // through the aggregator so it ends up in pendingAudioEvents before
        // SleepRepository reads it at session-end.
        try {
            audioClassifier?.shutdown()
        } catch (e: Exception) {
            Log.w(TAG, "audioClassifier shutdown failed", e)
        }
        audioClassifier = null
        audioAggregator = null
        audioTrackingActive.value = false

        try {
            unregisterReceiver(screenReceiver)
        } catch (_: Exception) { }

        LockoutNotifier.cancel(applicationContext)
        LockoutOverlayController.hide()
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

    private fun createNotification(audioActive: Boolean): Notification {
        val tapIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, tapIntent, PendingIntent.FLAG_IMMUTABLE
        )

        val title = if (audioActive) "Tracking sleep + sounds" else "Sleep tracking active"
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText("Tap to open app")
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()
    }
}
