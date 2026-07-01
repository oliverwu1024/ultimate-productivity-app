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
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.ultiq.app.util.NotificationHelper

/** Read-only: today's next few events. Tap to open the Calendar screen. */
class CalendarWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val data = WidgetDataSource(context).calendar()
        provideContent {
            UltiqGlanceTheme {
                CalendarContent(data)
            }
        }
    }
}

@Composable
private fun CalendarContent(data: CalendarSnapshot) {
    val context = LocalContext.current
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(GlanceTheme.colors.surface)
            .clickable(actionStartActivity(widgetOpenIntent(context, NotificationHelper.DEEP_LINK_CALENDAR)))
            .padding(12.dp),
    ) {
        WidgetHeader(title = "Next up")
        Spacer(GlanceModifier.height(8.dp))
        if (data.upcoming.isEmpty()) {
            WidgetEmpty("No more events today")
        } else {
            data.upcoming.forEach { event ->
                Row(
                    modifier = GlanceModifier.fillMaxWidth().padding(vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = formatClock(event.startTime),
                        style = TextStyle(
                            color = GlanceTheme.colors.primary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                        ),
                        modifier = GlanceModifier.width(76.dp),
                    )
                    Text(
                        text = event.title,
                        style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 14.sp),
                        maxLines = 1,
                    )
                }
            }
        }
    }
}
