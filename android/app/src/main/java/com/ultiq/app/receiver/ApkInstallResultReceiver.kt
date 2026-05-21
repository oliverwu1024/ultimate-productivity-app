package com.ultiq.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import com.ultiq.app.util.ApkUpdater

/**
 * Handles the [PackageInstaller] commit callback for in-app updates.
 *
 * Two outcomes that matter:
 *  - [PackageInstaller.STATUS_PENDING_USER_ACTION]: Android wants to show the
 *    "Install?" confirm. The system gives us an [Intent] in [Intent.EXTRA_INTENT];
 *    we launch it. (On API 31+ this also surfaces the "allow installs from this
 *    source" toggle if the permission hasn't been granted yet.)
 *  - [PackageInstaller.STATUS_SUCCESS]: install succeeded. The OS kills our
 *    process before this fires for same-package replaces in many cases, so
 *    don't rely on this callback for UI continuity — the new APK launching is
 *    its own signal.
 *
 * Anything else is treated as a failure with the OS-supplied status message.
 */
class ApkInstallResultReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_INSTALL_RESULT) return
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -999)
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                ApkUpdater.onAwaitingConfirm()
                @Suppress("DEPRECATION")
                val promptIntent: Intent? = intent.getParcelableExtra(Intent.EXTRA_INTENT)
                promptIntent?.let {
                    it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(it)
                }
            }
            PackageInstaller.STATUS_SUCCESS -> ApkUpdater.onInstallSuccess()
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                    ?: "Install failed (code $status)"
                ApkUpdater.onInstallFailed(msg)
            }
        }
    }

    companion object {
        const val ACTION_INSTALL_RESULT = "com.ultiq.app.APK_INSTALL_RESULT"
    }
}
