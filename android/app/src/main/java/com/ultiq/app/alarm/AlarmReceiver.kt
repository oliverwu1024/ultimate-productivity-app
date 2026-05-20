package com.ultiq.app.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Entry point for a fired alarm. Runs in the system's broadcast dispatcher,
 * so it must be brief: grab a short partial wake lock, start the foreground
 * ring service, and get out of the way.
 */
class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != WakeAlarmScheduler.ACTION_FIRE) return
        val alarmId = intent.getStringExtra(WakeAlarmScheduler.EXTRA_ALARM_ID) ?: run {
            Log.w(TAG, "Fire intent missing alarm id")
            return
        }
        Log.d(TAG, "Fire $alarmId")

        // Hold the CPU long enough for the FGS to start and acquire its own
        // wake lock. We do NOT release this in finally — `startForegroundService`
        // is async, and an immediate release races with the service-side
        // acquire on busy devices. The OS auto-releases after the timeout.
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wl = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "ultiq:alarm-fire-$alarmId",
        )
        wl.acquire(WAKE_LOCK_MAX_MS)

        // minSdk=26 (O), so `startForegroundService` is always available; no
        // pre-O branch needed.
        val ringIntent = Intent(context, AlarmRingService::class.java).apply {
            action = AlarmRingService.ACTION_START
            putExtra(AlarmRingService.EXTRA_ALARM_ID, alarmId)
        }
        ContextCompat.startForegroundService(context, ringIntent)
    }

    companion object {
        private const val TAG = "AlarmReceiver"
        private const val WAKE_LOCK_MAX_MS = 10_000L
    }
}
