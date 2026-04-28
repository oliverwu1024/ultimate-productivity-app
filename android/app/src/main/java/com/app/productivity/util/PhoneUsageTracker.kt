package com.app.productivity.util

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Process
import android.provider.Settings

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
