package com.ultiq.app.ui.widget

import androidx.compose.runtime.Composable
import androidx.glance.GlanceTheme
import androidx.glance.material3.ColorProviders
import com.ultiq.app.ui.theme.DarkScheme
import com.ultiq.app.ui.theme.LightScheme

/**
 * Glance can't consume the Compose [androidx.compose.material3.MaterialTheme],
 * so map the app's existing Material 3 schemes (ui/theme/Theme.kt) into Glance
 * [ColorProviders] once and reuse across every widget. Home-screen surfaces
 * then match the in-app palette and follow the system light/dark setting.
 */
private val UltiqWidgetColors = ColorProviders(light = LightScheme, dark = DarkScheme)

@Composable
fun UltiqGlanceTheme(content: @Composable () -> Unit) {
    GlanceTheme(colors = UltiqWidgetColors, content = content)
}
