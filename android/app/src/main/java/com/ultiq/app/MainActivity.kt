package com.ultiq.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.ultiq.app.navigation.AppNavigation
import com.ultiq.app.ui.theme.UltiqTheme
import com.ultiq.app.ui.theme.ThemeMode
import com.ultiq.app.util.NotificationHelper
import com.ultiq.app.util.ThemePreference
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {

    private val deepLink = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        deepLink.value = intent?.getStringExtra(NotificationHelper.EXTRA_DEEP_LINK)
        enableEdgeToEdge()
        setContent {
            val themePreference = remember { ThemePreference(applicationContext) }
            val themeMode by themePreference.themeMode.collectAsState(initial = ThemeMode.SYSTEM)
            val target by deepLink.collectAsState()

            // Resolve the effective dark/light state from the user preference,
            // falling back to the system pref when ThemeMode.SYSTEM is selected.
            val effectiveDark = when (themeMode) {
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
            }

            // Sync the system status-bar / nav-bar icon color to our theme so
            // they stay readable under edge-to-edge content. Light icons on dark
            // bg, dark icons on light bg.
            val view = LocalView.current
            if (!view.isInEditMode) {
                SideEffect {
                    val window = (view.context as Activity).window
                    val controller = WindowCompat.getInsetsController(window, view)
                    controller.isAppearanceLightStatusBars = !effectiveDark
                    controller.isAppearanceLightNavigationBars = !effectiveDark
                }
            }

            UltiqTheme(themeMode = themeMode) {
                AppNavigation(
                    pendingDeepLink = target,
                    onDeepLinkConsumed = { deepLink.value = null }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getStringExtra(NotificationHelper.EXTRA_DEEP_LINK)?.let { deepLink.value = it }
    }
}
