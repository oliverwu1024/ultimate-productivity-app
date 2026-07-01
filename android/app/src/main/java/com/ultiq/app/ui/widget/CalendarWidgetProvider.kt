package com.ultiq.app.ui.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.view.View
import android.widget.RemoteViews
import com.ultiq.app.R
import com.ultiq.app.util.NotificationHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Read-only RemoteViews Calendar widget: today's next few events. Tap opens Calendar. */
class CalendarWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        updateAll(context)
    }

    companion object {
        private const val MAX_ROWS = 3
        private val bgScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val ROW_IDS = intArrayOf(R.id.cal_row_0, R.id.cal_row_1, R.id.cal_row_2)
        private val TIME_IDS = intArrayOf(R.id.cal_time_0, R.id.cal_time_1, R.id.cal_time_2)
        private val TITLE_IDS = intArrayOf(R.id.cal_title_0, R.id.cal_title_1, R.id.cal_title_2)

        fun updateAll(context: Context) {
            val app = context.applicationContext
            bgScope.launch { render(app) }
        }

        private suspend fun render(context: Context) {
            val data = WidgetDataSource(context).calendar()
            val mgr = AppWidgetManager.getInstance(context)
            val ids = mgr.getAppWidgetIds(ComponentName(context, CalendarWidgetProvider::class.java))
            if (ids.isEmpty()) return

            val rv = RemoteViews(context.packageName, R.layout.widget_calendar_rv)
            rv.setOnClickPendingIntent(
                R.id.calendar_root,
                widgetOpenPendingIntent(context, NotificationHelper.DEEP_LINK_CALENDAR),
            )

            val up = data.upcoming
            if (up.isEmpty()) {
                rv.setViewVisibility(R.id.cal_empty, View.VISIBLE)
                rv.setTextViewText(R.id.cal_empty, "No more events today")
                for (i in 0 until MAX_ROWS) rv.setViewVisibility(ROW_IDS[i], View.GONE)
            } else {
                rv.setViewVisibility(R.id.cal_empty, View.GONE)
                for (i in 0 until MAX_ROWS) {
                    if (i < up.size) {
                        val e = up[i]
                        rv.setViewVisibility(ROW_IDS[i], View.VISIBLE)
                        rv.setTextViewText(TIME_IDS[i], formatClock(e.startTime))
                        rv.setTextViewText(TITLE_IDS[i], e.title)
                    } else {
                        rv.setViewVisibility(ROW_IDS[i], View.GONE)
                    }
                }
            }
            ids.forEach { mgr.updateAppWidget(it, rv) }
        }
    }
}
