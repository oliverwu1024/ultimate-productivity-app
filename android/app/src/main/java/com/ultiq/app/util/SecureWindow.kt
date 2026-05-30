package com.ultiq.app.util

import android.app.Activity
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext

/**
 * Adds FLAG_SECURE to the host Activity's window for the lifetime of the
 * composable. Blocks screenshots + screen recording while this screen is
 * on top, and prevents the content from showing on non-secure mirrored
 * displays (Miracast / cast).
 *
 * Apply to any screen that exposes credentials (auth flows), tokens, or
 * other content that shouldn't end up in someone else's screen-recording
 * background tape. Activity-level screens (AlarmActivity, LockoutActivity)
 * set the flag in onCreate directly; this helper is for the Compose
 * screens hosted inside MainActivity that need scoped protection.
 *
 * Idempotent — calling addFlags / clearFlags repeatedly is safe.
 */
@Composable
fun SecureWindow() {
    val context = LocalContext.current
    DisposableEffect(Unit) {
        val activity = context as? Activity
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }
}
