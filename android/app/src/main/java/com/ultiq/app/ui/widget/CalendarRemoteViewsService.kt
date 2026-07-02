package com.ultiq.app.ui.widget

import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import com.ultiq.app.R
import kotlinx.coroutines.runBlocking

/**
 * Backs the Calendar widget's scrollable ListView — today's not-done events, each a
 * tap-to-mark-done row ([checkbox] time title). Mirrors ChecklistRemoteViewsService:
 * the launcher binds this (BIND_REMOTEVIEWS) and calls the factory on a binder
 * thread, so the blocking Room read in [onDataSetChanged] is allowed.
 */
class CalendarRemoteViewsService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory =
        CalendarRemoteViewsFactory(applicationContext)
}

private class CalendarRemoteViewsFactory(
    private val context: Context,
) : RemoteViewsService.RemoteViewsFactory {

    @Volatile
    private var items: List<CalendarEventView> = emptyList()

    override fun onCreate() {}

    /** Runs on a binder thread; blocking IO is allowed (and expected) here. */
    override fun onDataSetChanged() {
        items = runBlocking { WidgetDataSource(context).todayCalendar().open }
    }

    override fun onDestroy() {
        items = emptyList()
    }

    override fun getCount(): Int = items.size

    override fun getViewAt(position: Int): RemoteViews {
        val rv = RemoteViews(context.packageName, R.layout.widget_calendar_row)
        val e = items.getOrNull(position) ?: return rv
        rv.setTextViewText(R.id.cal_row_time, formatClock(e.startTime))
        rv.setTextViewText(R.id.cal_row_title, e.title)
        // Merged into the list's pending-intent template → broadcasts ACTION_TOGGLE
        // with this occurrence's id/start/recurring, read back in onReceive.
        rv.setOnClickFillInIntent(
            R.id.cal_row_root,
            Intent()
                .putExtra(CalendarWidgetProvider.EXTRA_EVENT_ID, e.id)
                .putExtra(CalendarWidgetProvider.EXTRA_START, e.startTime)
                .putExtra(CalendarWidgetProvider.EXTRA_RECURRING, e.isRecurring),
        )
        return rv
    }

    override fun getLoadingView(): RemoteViews? = null

    override fun getViewTypeCount(): Int = 1

    override fun getItemId(position: Int): Long =
        items.getOrNull(position)?.let { (it.id + it.startTime).hashCode().toLong() }
            ?: position.toLong()

    override fun hasStableIds(): Boolean = true
}
