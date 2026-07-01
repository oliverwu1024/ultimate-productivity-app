package com.ultiq.app.ui.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.widget.RemoteViews
import com.ultiq.app.R
import com.ultiq.app.util.NotificationHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Read-only RemoteViews Sleep + Alarm widget: next alarm + last night / live session. Tap opens Sleep. */
class SleepAlarmWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        updateAll(context)
    }

    companion object {
        private val bgScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        fun updateAll(context: Context) {
            val app = context.applicationContext
            bgScope.launch { render(app) }
        }

        private suspend fun render(context: Context) {
            val data = WidgetDataSource(context).sleepAlarm()
            val mgr = AppWidgetManager.getInstance(context)
            val ids = mgr.getAppWidgetIds(ComponentName(context, SleepAlarmWidgetProvider::class.java))
            if (ids.isEmpty()) return

            val rv = RemoteViews(context.packageName, R.layout.widget_sleep_alarm_rv)
            rv.setOnClickPendingIntent(
                R.id.sa_root,
                widgetOpenPendingIntent(context, NotificationHelper.DEEP_LINK_SLEEP),
            )

            rv.setTextViewText(
                R.id.sa_alarm_value,
                data.nextAlarm?.let { formatClock(it.triggerAt) } ?: "None set",
            )
            if (data.sleepingElapsedMinutes != null) {
                rv.setTextViewText(R.id.sa_sleep_label, "😴  Sleeping")
                rv.setTextViewText(R.id.sa_sleep_value, formatDuration(data.sleepingElapsedMinutes))
            } else {
                rv.setTextViewText(R.id.sa_sleep_label, "😴  Last night")
                val ln = data.lastNight
                val value = if (ln != null) {
                    val q = qualityLabel(ln.quality)
                    formatDuration(ln.durationMinutes) + if (q != null) " · $q" else ""
                } else {
                    "—"
                }
                rv.setTextViewText(R.id.sa_sleep_value, value)
            }
            ids.forEach { mgr.updateAppWidget(it, rv) }
        }
    }
}
