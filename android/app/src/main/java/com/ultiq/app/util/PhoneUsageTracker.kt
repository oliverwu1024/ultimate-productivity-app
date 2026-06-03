package com.ultiq.app.util

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Process
import android.provider.Settings
import com.ultiq.app.service.PickupEvent

class PhoneUsageTracker(private val context: Context) {

    private val usageStatsManager =
        context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager

    fun hasPermission(): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun openPermissionSettings() {
        val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    /**
     * Returns the number of non-system app foreground events since [sinceTime].
     * Each app coming to the foreground counts as one phone distraction.
     */
    fun getPickupsSince(sinceTime: Long): Int {
        if (!hasPermission()) return 0

        val now = System.currentTimeMillis()
        val events = usageStatsManager.queryEvents(sinceTime, now)
        val event = UsageEvents.Event()
        val ownPackage = context.packageName

        var count = 0
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            @Suppress("DEPRECATION")
            if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                val pkg = event.packageName
                if (pkg != ownPackage && !isSystemComponent(pkg)) {
                    count++
                }
            }
        }

        return count
    }

    /**
     * §v2.16.18 — Per-event pickup detail for the focus pickup timeline
     * (mirror of what `SleepTrackingService.pickupEvents` provides for
     * sleep sessions). Each non-own, non-system MOVE_TO_FOREGROUND event
     * is a pickup; its duration is the gap until the next foreground
     * event of any kind (or `now` for the most-recent pickup if the
     * session is still in progress at query time).
     *
     * Called from `SessionsViewModel.completeSession` with the session
     * start as `sinceTime`, so we get every pickup that happened during
     * the focus window in one shot — no need to long-poll inside a
     * service.
     */
    fun getPickupEventsSince(sinceTime: Long): List<PickupEvent> {
        if (!hasPermission()) return emptyList()

        val now = System.currentTimeMillis()
        val events = usageStatsManager.queryEvents(sinceTime, now)
        val event = UsageEvents.Event()
        val ownPackage = context.packageName

        // Collect every foreground transition (including own + system)
        // so we can compute "time until next foreground event" — the
        // user leaving the distraction app ends that pickup, even if
        // they return to Ultiq.
        data class Fg(val time: Long, val isPickup: Boolean)
        val all = mutableListOf<Fg>()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            @Suppress("DEPRECATION")
            if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                val pkg = event.packageName ?: continue
                val isPickup = pkg != ownPackage && !isSystemComponent(pkg)
                all.add(Fg(time = event.timeStamp, isPickup = isPickup))
            }
        }

        val result = mutableListOf<PickupEvent>()
        for ((i, e) in all.withIndex()) {
            if (!e.isPickup) continue
            val nextTime = if (i + 1 < all.size) all[i + 1].time else now
            val durationSec = ((nextTime - e.time) / 1000).toInt().coerceAtLeast(0)
            result.add(PickupEvent(pickedUpAt = e.time, durationSeconds = durationSec))
        }
        return result
    }

    /**
     * Returns the package name of whatever app is currently in the foreground, by
     * scanning the most recent MOVE_TO_FOREGROUND event in the given window. Returns
     * null if usage access isn't granted or no event is found.
     */
    fun getForegroundPackage(lookbackMs: Long = 10_000L): String? {
        if (!hasPermission()) return null
        val now = System.currentTimeMillis()
        val events = usageStatsManager.queryEvents(now - lookbackMs, now)
        val event = UsageEvents.Event()
        var latestPackage: String? = null
        var latestTime = 0L
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            @Suppress("DEPRECATION")
            if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND &&
                event.timeStamp >= latestTime
            ) {
                latestTime = event.timeStamp
                latestPackage = event.packageName
            }
        }
        return latestPackage
    }

    fun isSystemComponent(packageName: String): Boolean {
        return packageName == "android" ||
                packageName.contains("launcher") ||
                packageName.contains("systemui") ||
                packageName.contains("inputmethod")
    }
}
