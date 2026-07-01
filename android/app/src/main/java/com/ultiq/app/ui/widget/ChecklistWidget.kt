package com.ultiq.app.ui.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalContext
import androidx.glance.action.actionParametersOf
import androidx.glance.appwidget.CheckBox
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Column
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.ultiq.app.util.NotificationHelper

/** Today's open checklist items — tap a row to tick it off in place. */
class ChecklistWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val data = WidgetDataSource(context).todayChecklist()
        provideContent {
            UltiqGlanceTheme {
                ChecklistContent(data)
            }
        }
    }
}

private const val CHECKLIST_MAX_ROWS = 6

@Composable
private fun ChecklistContent(data: ChecklistSnapshot) {
    val context = LocalContext.current
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(GlanceTheme.colors.surface)
            .padding(12.dp),
    ) {
        WidgetHeader(
            title = "Today",
            trailing = "${data.doneCount}/${data.total}",
            onClick = actionStartActivity(
                widgetOpenIntent(context, NotificationHelper.DEEP_LINK_CHECKLIST)
            ),
        )
        Spacer(GlanceModifier.height(8.dp))
        if (data.open.isEmpty()) {
            WidgetEmpty(if (data.total == 0) "Nothing due today" else "All done for today 🎉")
        } else {
            data.open.take(CHECKLIST_MAX_ROWS).forEach { item ->
                CheckBox(
                    checked = false,
                    onCheckedChange = actionRunCallback<ToggleChecklistItem>(
                        actionParametersOf(checklistItemIdKey to item.id)
                    ),
                    text = item.title,
                    style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 14.sp),
                    maxLines = 2,
                    modifier = GlanceModifier.padding(vertical = 2.dp),
                )
            }
            val overflow = data.open.size - CHECKLIST_MAX_ROWS
            if (overflow > 0) {
                Text(
                    text = "+$overflow more",
                    style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp),
                    modifier = GlanceModifier.padding(top = 4.dp, start = 4.dp),
                )
            }
        }
    }
}
