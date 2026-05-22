package com.ultiq.app.alarm

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.ultiq.app.R
import com.ultiq.app.data.local.AppDatabase
import com.ultiq.app.data.local.entity.AlarmEntity
import com.ultiq.app.data.local.entity.AlarmEventEntity
import com.ultiq.app.util.NotificationHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Holds the alarm in the user's face: plays the sound, vibrates, owns a wake
 * lock, and posts a full-screen-intent notification that bounces the user
 * into [AlarmActivity].
 *
 * Lifecycle:
 *   AlarmReceiver → startForegroundService(ACTION_START)
 *     → service plays sound + posts FSI notification + launches AlarmActivity
 *   AlarmActivity (after user dismisses)
 *     → startService(ACTION_DISMISS) → service logs event + stops itself
 */
class AlarmRingService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var firedAt: Long = 0L
    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var rampJob: Job? = null
    private var startJob: Job? = null
    private var currentAlarm: AlarmEntity? = null

    override fun onCreate() {
        super.onCreate()
        NotificationHelper.ensureChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> handleStart(intent.getStringExtra(EXTRA_ALARM_ID))
            ACTION_DISMISS -> handleDismiss(intent.getStringExtra(EXTRA_ALARM_ID), method = "mission")
            ACTION_FORCE_DISMISS -> handleDismiss(intent.getStringExtra(EXTRA_ALARM_ID), method = "force")
        }
        // Don't auto-restart if killed — boot path / explicit fire is the
        // only way an alarm should ring.
        return START_NOT_STICKY
    }

    private fun handleStart(alarmId: String?) {
        if (alarmId == null) {
            Log.w(TAG, "ACTION_START with no alarm id; stopping")
            stopSelf()
            return
        }
        firedAt = System.currentTimeMillis()
        _currentAlarmId.value = alarmId

        // Move to foreground BEFORE doing any DB I/O so the FGS deadline is
        // satisfied immediately.
        startInForeground(alarmId)

        // Force the AlarmActivity to the top even when the phone is in
        // active use. The notification's `setFullScreenIntent` only auto-
        // launches when the device is in a "compelling" state (locked,
        // idle screen, DND-ringing) per Android 14+ docs — when the user
        // is actively using their phone, the OS demotes the FSI to a
        // heads-up notification and the alarm screen never appears. A
        // foreground service started from an alarm BroadcastReceiver is
        // exempt from the background-activity-launch restrictions, so
        // calling `startActivity` here is allowed and takes the screen.
        launchAlarmActivity(alarmId)

        // §L4: acquire the wake lock FIRST so we keep the CPU alive even if
        // sound/vibration setup throws on this device.
        acquireWakeLock(alarmId)

        startJob = scope.launch {
            val dao = AppDatabase.getInstance(this@AlarmRingService).alarmDao()
            val alarm = dao.getAlarmById(alarmId)
            if (alarm == null) {
                Log.w(TAG, "Alarm $alarmId vanished from DB; stopping")
                stopRinging()
                stopSelf()
                return@launch
            }
            currentAlarm = alarm
            startSound(alarm)
            startVibration(alarm)
        }
    }

    private fun handleDismiss(alarmId: String?, method: String) {
        val id = alarmId ?: _currentAlarmId.value ?: run {
            Log.w(TAG, "ACTION_DISMISS without alarm id; stopping")
            stopSelf()
            return
        }
        Log.d(TAG, "Dismiss $id ($method)")
        val firedAtCapture = firedAt
        val previousStartJob = startJob

        scope.launch {
            // §M1: wait for any in-flight handleStart coroutine to finish or
            // be cancelled BEFORE tearing down. Without this, the start
            // coroutine could call startSound() right after stopRinging()
            // and leak the MediaPlayer.
            previousStartJob?.cancelAndJoin()
            val alarmCapture = currentAlarm
            stopRinging()

            val dao = AppDatabase.getInstance(this@AlarmRingService).alarmDao()
            val alarm = alarmCapture ?: dao.getAlarmById(id)

            // §M2: the parent alarm row may have been deleted between fire
            // and dismiss. The FK ON DELETE SET NULL means we should record
            // the event with alarmId = NULL rather than crashing on a FK
            // constraint violation.
            val now = System.currentTimeMillis()
            dao.insertEvent(
                AlarmEventEntity(
                    id = UUID.randomUUID().toString(),
                    userId = alarm?.userId ?: "",
                    alarmId = if (alarm != null) id else null,
                    firedAt = if (firedAtCapture > 0) firedAtCapture else now,
                    dismissedAt = now,
                    dismissMethod = method,
                    snoozeCount = 0,
                    missionKind = alarm?.missionKind,
                    missionAttempts = 1,
                    missionDurationMs = if (firedAtCapture > 0) (now - firedAtCapture).toInt() else null,
                    createdAt = now,
                ),
            )

            // Recurring → re-arm for next occurrence.
            // One-shot → mark disabled so it doesn't fire blindly tomorrow.
            if (alarm != null) {
                if (alarm.daysOfWeekMask == 0) {
                    dao.updateAlarm(alarm.copy(enabled = false, updatedAt = now, isSynced = false))
                } else {
                    // Re-load in case the user edited the row while the alarm
                    // was ringing (§8.10 edit-while-ringing path: AlarmRepository
                    // skips the live reschedule for the ringing alarm so the
                    // new settings only take effect on the next firing).
                    val fresh = dao.getAlarmById(id) ?: alarm
                    WakeAlarmScheduler(this@AlarmRingService).schedule(fresh)
                }
            }

            // Sleep session stays running after the alarm dismisses — user
            // ends it manually from the Sleep tab. Auto-ending here lost the
            // session record entirely if the user dismissed without going
            // into the end-sleep flow.
            stopSelf()
        }
    }

    /// Build the Intent that launches AlarmActivity for [alarmId]. Used both
    /// as the notification's content/full-screen intent and for the direct
    /// `startActivity` push from `handleStart`.
    private fun alarmActivityIntent(alarmId: String): Intent = Intent(this, AlarmActivity::class.java).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        putExtra(EXTRA_ALARM_ID, alarmId)
    }

    /// Explicit `startActivity` to bring AlarmActivity over the user's
    /// current app. Required when the phone is actively in use — the
    /// notification's fullScreenIntent alone won't trigger a takeover in
    /// that state. The activity itself sets `showWhenLocked` +
    /// `turnScreenOn` so the locked-phone path still works the same.
    private fun launchAlarmActivity(alarmId: String) {
        try {
            startActivity(alarmActivityIntent(alarmId))
        } catch (t: Throwable) {
            // Background-activity-launch restrictions can fire here on
            // some OEM ROMs even though FGS-from-alarm-receiver is meant
            // to be exempt. Log and let the notification's fullScreenIntent
            // remain the fallback — the user still gets the heads-up and
            // can tap it.
            Log.w(TAG, "launchAlarmActivity failed for $alarmId: $t")
        }
    }

    private fun startInForeground(alarmId: String) {
        val tapIntent = alarmActivityIntent(alarmId)
        val tapPi = PendingIntent.getActivity(
            this,
            FSI_REQUEST_CODE,
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification: Notification = NotificationCompat.Builder(this, NotificationHelper.CHANNEL_ALARM)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Alarm")
            .setContentText("Tap to dismiss")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setOngoing(true)
            .setAutoCancel(false)
            .setContentIntent(tapPi)
            .setFullScreenIntent(tapPi, true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun startSound(alarm: AlarmEntity) {
        val uri: Uri = alarm.soundUri?.let(Uri::parse)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: return

        try {
            player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                setDataSource(this@AlarmRingService, uri)
                isLooping = true
                prepare()
                start()
            }
            applyVolume(alarm)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to start alarm sound", e)
        }
    }

    private fun applyVolume(alarm: AlarmEntity) {
        val target = (alarm.volumePct / 100f).coerceIn(0f, 1f)
        if (!alarm.volumeEscalates) {
            player?.setVolume(target, target)
            return
        }
        val start = (target * 0.3f).coerceAtLeast(0.05f)
        player?.setVolume(start, start)
        rampJob = scope.launch {
            val steps = 20
            val stepMs = 1_500L
            for (i in 1..steps) {
                delay(stepMs)
                val v = start + (target - start) * (i / steps.toFloat())
                player?.setVolume(v, v)
            }
        }
    }

    private fun startVibration(alarm: AlarmEntity) {
        if (!alarm.vibration) return
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(VIBRATOR_SERVICE) as Vibrator
        }
        val pattern = longArrayOf(0, 800, 600)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator?.vibrate(VibrationEffect.createWaveform(pattern, 0))
        } else {
            @Suppress("DEPRECATION")
            vibrator?.vibrate(pattern, 0)
        }
    }

    private fun acquireWakeLock(alarmId: String) {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "ultiq:alarm-ring-$alarmId",
        ).apply { acquire(MAX_RING_MS) }
    }

    private fun stopRinging() {
        _currentAlarmId.value = null
        rampJob?.cancel()
        rampJob = null
        startJob = null
        try { player?.stop() } catch (_: Exception) {}
        player?.release()
        player = null
        vibrator?.cancel()
        vibrator = null
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    override fun onDestroy() {
        stopRinging()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "AlarmRingService"
        private const val NOTIFICATION_ID = 3001
        private const val FSI_REQUEST_CODE = 30_001
        private const val MAX_RING_MS = 10L * 60 * 1000 // 10 min hard cap on wake lock

        const val ACTION_START = "com.ultiq.app.alarm.RING_START"
        const val ACTION_DISMISS = "com.ultiq.app.alarm.RING_DISMISS"
        const val ACTION_FORCE_DISMISS = "com.ultiq.app.alarm.RING_FORCE_DISMISS"
        const val EXTRA_ALARM_ID = "alarm_id"

        /** §L3: write-protected. Only AlarmRingService mutates the backing flow. */
        private val _currentAlarmId = MutableStateFlow<String?>(null)

        /** Null when no alarm is currently ringing. */
        val currentAlarmId: StateFlow<String?> = _currentAlarmId.asStateFlow()
    }
}
