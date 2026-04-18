package com.app.productivity

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.app.productivity.navigation.AppNavigation
import com.app.productivity.ui.theme.ProductivityTheme
import com.app.productivity.util.NotificationHelper
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {

    private val deepLink = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        deepLink.value = intent?.getStringExtra(NotificationHelper.EXTRA_DEEP_LINK)
        enableEdgeToEdge()
        setContent {
            ProductivityTheme {
                val target by deepLink.collectAsState()
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
