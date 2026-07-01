package com.ultiq.app.ui.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalContext
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.ultiq.app.util.NotificationHelper

/** Read-only: next alarm + last night's sleep (or a live session). Tap → Sleep. */
class SleepAlarmWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val data = WidgetDataSource(context).sleepAlarm()
        provideContent {
            UltiqGlanceTheme {
                SleepAlarmContent(data)
            }
        }
    }
}

@Composable
private fun SleepAlarmContent(data: SleepAlarmSnapshot) {
    val context = LocalContext.current
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(GlanceTheme.colors.surface)
            .clickable(actionStartActivity(widgetOpenIntent(context, NotificationHelper.DEEP_LINK_SLEEP)))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatRow(
            label = "⏰  Next alarm",
            value = data.nextAlarm?.let { formatClock(it.triggerAt) } ?: "None set",
        )
        Spacer(GlanceModifier.height(10.dp))
        if (data.sleepingElapsedMinutes != null) {
            StatRow(
                label = "😴  Sleeping",
                value = formatDuration(data.sleepingElapsedMinutes),
            )
        } else {
            val ln = data.lastNight
            val value = if (ln != null) {
                val q = qualityLabel(ln.quality)
                formatDuration(ln.durationMinutes) + if (q != null) " · $q" else ""
            } else {
                "—"
            }
            StatRow(label = "😴  Last night", value = value)
        }
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(
        modifier = GlanceModifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 13.sp),
            modifier = GlanceModifier.defaultWeight(),
        )
        Text(
            text = value,
            style = TextStyle(
                color = GlanceTheme.colors.onSurface,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
            ),
        )
    }
}
