package com.ultiq.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
