package com.ultiq.app.alarm

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * Three OS-level permissions gate reliable Phase 8 alarms:
 *
 * - `POST_NOTIFICATIONS` (Android 13+) — needed for the ring service's
 *   foreground notification, which carries the full-screen intent.
 * - `SCHEDULE_EXACT_ALARM` (Android 12+) — needed to call `setAlarmClock`.
 * - `USE_FULL_SCREEN_INTENT` (Android 14+) — needed for the alarm activity
 *   to actually launch over the lock screen rather than just sitting as a
 *   notification.
 *
 * The "alarm only shows as a notification, not over the lock screen" failure
 * mode is the third one going missing.
 */
data class AlarmPermissionState(
    val hasNotifications: Boolean,
    val hasExactAlarm: Boolean,
    val hasFullScreenIntent: Boolean,
) {
    val allGranted: Boolean
        get() = hasNotifications && hasExactAlarm && hasFullScreenIntent
}

@Composable
fun rememberAlarmPermissionState(): AlarmPermissionState {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var state by remember { mutableStateOf(checkAlarmPermissions(context)) }
    DisposableEffect(lifecycleOwner) {
        // Re-check whenever the user returns to this screen — they might've
        // just toggled the permission in system settings.
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                state = checkAlarmPermissions(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    return state
}

fun checkAlarmPermissions(context: Context): AlarmPermissionState {
    val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    val notifManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    val hasNotifications = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    } else true

    val hasExactAlarm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        alarmManager.canScheduleExactAlarms()
    } else true

    val hasFullScreenIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        notifManager.canUseFullScreenIntent()
    } else true

    return AlarmPermissionState(hasNotifications, hasExactAlarm, hasFullScreenIntent)
}

/** Open the system settings page for the missing permission. */
fun openExactAlarmSettings(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
    val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
        data = Uri.fromParts("package", context.packageName, null)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(intent)
}

fun openFullScreenIntentSettings(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
    val intent = Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT).apply {
        data = Uri.fromParts("package", context.packageName, null)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(intent)
}

fun openAppNotificationSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
        putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(intent)
}
