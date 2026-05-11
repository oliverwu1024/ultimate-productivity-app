package com.ultiq.app.ui.lockout

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.ultiq.app.MainActivity
import com.ultiq.app.service.FocusTrackingService
import com.ultiq.app.service.SleepTrackingService
import com.ultiq.app.ui.theme.UltiqTheme
import com.ultiq.app.ui.theme.ThemeMode
import com.ultiq.app.util.LockoutNotifier
import com.ultiq.app.util.ThemePreference
import com.ultiq.app.util.UserPreferences
import kotlinx.coroutines.delay

class LockoutActivity : ComponentActivity() {

    companion object {
        private const val EXTRA_MODE = "mode"
        private const val EXTRA_SESSION_STARTED_AT = "session_started_at"

        fun launchIntent(
            context: Context,
            mode: LockoutMode,
            sessionStartedAt: Long,
        ): Intent = Intent(context, LockoutActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(EXTRA_MODE, mode.name)
            putExtra(EXTRA_SESSION_STARTED_AT, sessionStartedAt)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }

        val mode = runCatching {
            LockoutMode.valueOf(intent.getStringExtra(EXTRA_MODE) ?: LockoutMode.FOCUS.name)
        }.getOrDefault(LockoutMode.FOCUS)

        val sessionStartedAt = intent.getLongExtra(
            EXTRA_SESSION_STARTED_AT,
            System.currentTimeMillis(),
        )

        val userPrefs = UserPreferences(applicationContext)
        val themePref = ThemePreference(applicationContext)

        setContent {
            val themeMode by themePref.themeMode.collectAsState(initial = ThemeMode.SYSTEM)
            val settings by userPrefs.settings.collectAsState(initial = null)

            val unlockCount by remember(mode) {
                when (mode) {
                    LockoutMode.FOCUS -> FocusTrackingService.unlockCount
                    LockoutMode.SLEEP -> SleepTrackingService.sessionUnlockCount
                }
            }.collectAsState()
            val plannedWorkMinutes by FocusTrackingService.plannedWorkMinutes.collectAsState()

            var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
            LaunchedEffect(Unit) {
                while (true) {
                    delay(1_000)
                    now = System.currentTimeMillis()
                }
            }

            UltiqTheme(themeMode = themeMode) {
                LockoutScreen(
                    mode = mode,
                    elapsedMillis = (now - sessionStartedAt).coerceAtLeast(0),
                    plannedWorkMinutes = if (mode == LockoutMode.FOCUS) plannedWorkMinutes else 0,
                    unlockCount = unlockCount,
                    showUnlockCount = settings?.showPickupCountOnLockout ?: true,
                    allowEndSession = settings?.allowEndSessionFromLockout ?: true,
                    onCancel = { dismiss() },
                    onConfirm = { dismiss() },
                    onEndSession = { openAppOnSessionScreen() },
                )
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        dismiss()
    }

    private fun dismiss() {
        LockoutNotifier.cancel(applicationContext)
        finishAndRemoveTask()
    }

    private fun openAppOnSessionScreen() {
        LockoutNotifier.cancel(applicationContext)
        startActivity(
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            },
        )
        finishAndRemoveTask()
    }
}
