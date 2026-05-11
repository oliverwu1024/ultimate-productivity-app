package com.ultiq.app.ui.lockout

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.ultiq.app.MainActivity
import com.ultiq.app.service.FocusTrackingService
import com.ultiq.app.service.SleepTrackingService
import com.ultiq.app.ui.theme.UltiqTheme
import kotlinx.coroutines.delay

/**
 * Renders the lockout UI as a system overlay (TYPE_APPLICATION_OVERLAY) so it actually
 * takes over the screen on Android 14+. The full-screen-intent path used by LockoutNotifier
 * only auto-promotes on a non-interactive device; once the user has unlocked, the system
 * demotes it to a heads-up notification. A WindowManager overlay bypasses that constraint.
 *
 * Requires SYSTEM_ALERT_WINDOW. The user grants it once via Settings → Special access →
 * Display over other apps. If not granted, callers should fall back to the notifier.
 */
object LockoutOverlayController {
    private const val TAG = "LockoutOverlay"

    private var overlayView: ComposeView? = null
    private var owner: ViewTreeOwner? = null
    private var windowManager: WindowManager? = null

    /**
     * Timestamp in epoch millis until which the foreground-app watcher should NOT
     * re-show the overlay. Set when the user taps "Yes, I need my phone" so they
     * get a short window to actually use it before the gate snaps back into place.
     */
    @Volatile
    private var graceUntilMillis: Long = 0L

    fun canShow(context: Context): Boolean = Settings.canDrawOverlays(context)

    fun isShown(): Boolean = overlayView != null

    fun isInGracePeriod(): Boolean = System.currentTimeMillis() < graceUntilMillis

    fun show(context: Context, mode: LockoutMode, sessionStartedAt: Long, graceMinutes: Int = 5) {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "LockoutOverlayController.show must be called on the main thread"
        }
        if (overlayView != null) {
            Log.d(TAG, "show — already visible, skipping")
            return
        }
        if (!canShow(context)) {
            Log.d(TAG, "show — SYSTEM_ALERT_WINDOW not granted, can't draw overlay")
            return
        }

        val ctx = context.applicationContext
        val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        // FLAG_TURN_SCREEN_ON / FLAG_SHOW_WHEN_LOCKED are deprecated for
        // Activities (use setTurnScreenOn/setShowWhenLocked instead) but are
        // still the only way to get equivalent behavior on a non-Activity
        // window like this SYSTEM_ALERT_WINDOW overlay.
        @Suppress("DEPRECATION")
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED,
            PixelFormat.OPAQUE,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }

        val viewOwner = ViewTreeOwner().also { it.attach() }

        val view = ComposeView(ctx).apply {
            setViewTreeLifecycleOwner(viewOwner)
            setViewTreeViewModelStoreOwner(viewOwner)
            setViewTreeSavedStateRegistryOwner(viewOwner)
            setContent {
                var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
                LaunchedEffect(Unit) {
                    while (true) {
                        delay(1_000)
                        now = System.currentTimeMillis()
                    }
                }

                val unlockCount by when (mode) {
                    LockoutMode.FOCUS -> FocusTrackingService.unlockCount
                    LockoutMode.SLEEP -> SleepTrackingService.sessionUnlockCount
                }.collectAsState()
                val plannedWorkMinutes by FocusTrackingService.plannedWorkMinutes.collectAsState()

                UltiqTheme {
                    LockoutScreen(
                        mode = mode,
                        elapsedMillis = (now - sessionStartedAt).coerceAtLeast(0),
                        plannedWorkMinutes = if (mode == LockoutMode.FOCUS) plannedWorkMinutes else 0,
                        unlockCount = unlockCount,
                        showUnlockCount = true,
                        allowEndSession = true,
                        onCancel = {
                            hide()
                            // If the user has granted Device Admin, "Stay locked" actually
                            // locks the screen. The next unlock fires ACTION_USER_PRESENT,
                            // which re-shows this overlay — turning it into a hard loop.
                            if (LockoutAdmin.isAdminActive(ctx)) {
                                LockoutAdmin.lockNow(ctx)
                            }
                        },
                        onConfirm = {
                            // Give the user a short window to actually use the phone before
                            // the foreground-app watcher snaps the overlay back over.
                            graceUntilMillis = System.currentTimeMillis() +
                                graceMinutes.coerceIn(1, 10) * 60_000L
                            hide()
                        },
                        onEndSession = {
                            hide()
                            ctx.startActivity(
                                Intent(ctx, MainActivity::class.java).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                                },
                            )
                        },
                    )
                }
            }
        }

        try {
            wm.addView(view, params)
            overlayView = view
            windowManager = wm
            owner = viewOwner
            Log.d(TAG, "Overlay attached for mode=$mode")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to attach overlay", e)
            viewOwner.detach()
        }
    }

    fun hide() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            // Hop to main if called from a coroutine
            android.os.Handler(Looper.getMainLooper()).post { hide() }
            return
        }
        overlayView?.let { v ->
            try {
                windowManager?.removeView(v)
            } catch (_: Exception) { }
        }
        owner?.detach()
        overlayView = null
        windowManager = null
        owner = null
        Log.d(TAG, "Overlay detached")
    }
}

/**
 * Minimal owner triumvirate so a ComposeView can run outside an Activity.
 * Compose needs LifecycleOwner (drives recomposition lifecycle), ViewModelStoreOwner
 * (so any ViewModel-scoped state has a place to live), and SavedStateRegistryOwner
 * (required by ComposeView's saveable state machinery).
 */
private class ViewTreeOwner :
    LifecycleOwner,
    ViewModelStoreOwner,
    SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val store = ViewModelStore()
    private val ssrController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore: ViewModelStore get() = store
    override val savedStateRegistry: SavedStateRegistry get() = ssrController.savedStateRegistry

    fun attach() {
        ssrController.performAttach()
        ssrController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
    }

    fun detach() {
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        store.clear()
    }
}
