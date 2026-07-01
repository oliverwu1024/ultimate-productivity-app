package com.ultiq.app.ui.widget

import android.content.Context
import android.os.SystemClock
import android.widget.RemoteViews
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalContext
import androidx.glance.action.clickable
import androidx.glance.appwidget.AndroidRemoteViews
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.ultiq.app.R
import com.ultiq.app.util.NotificationHelper

/** Focus timer: Start when idle, or live elapsed + Stop while a session runs. */
class FocusWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val data = WidgetDataSource(context).focus()
        provideContent {
            UltiqGlanceTheme {
                FocusContent(data)
            }
        }
    }
}

@Composable
private fun FocusContent(data: FocusSnapshot) {
    val context = LocalContext.current
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(GlanceTheme.colors.surface)
            .clickable(actionStartActivity(widgetOpenIntent(context, NotificationHelper.DEEP_LINK_SESSIONS)))
            .padding(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (!data.active) {
            Text(
                text = "Focus",
                style = TextStyle(
                    color = GlanceTheme.colors.onSurface,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                ),
            )
            Spacer(GlanceModifier.height(10.dp))
            WidgetPillButton(label = "▶  Start", onClick = actionRunCallback<StartFocus>())
        } else {
            Text(
                text = if (data.tag.isBlank()) "Focus" else data.tag,
                style = TextStyle(
                    color = GlanceTheme.colors.onSurfaceVariant,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                ),
                maxLines = 1,
            )
            Spacer(GlanceModifier.height(4.dp))
            FocusChronometer(startedAt = data.startedAt)
            Spacer(GlanceModifier.height(10.dp))
            WidgetPillButton(label = "■  Stop", onClick = actionRunCallback<StopFocus>())
        }
    }
}

/**
 * Live-ticking elapsed timer hosted in a RemoteViews [android.widget.Chronometer].
 * The OS ticks it every second with the app asleep — Glance itself can't
 * re-render per second.
 */
@Composable
private fun FocusChronometer(startedAt: Long) {
    val context = LocalContext.current
    val remoteViews = RemoteViews(context.packageName, R.layout.widget_focus_chronometer).apply {
        val elapsed = (System.currentTimeMillis() - startedAt).coerceAtLeast(0L)
        setChronometer(R.id.focus_chrono, SystemClock.elapsedRealtime() - elapsed, null, true)
    }
    AndroidRemoteViews(remoteViews)
}
