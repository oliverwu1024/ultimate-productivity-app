package com.ultiq.app.ui.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.RemoteViews
import com.ultiq.app.R
import com.ultiq.app.util.NotificationHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId

/**
 * Interactive RemoteViews Calendar widget — today's events as a scrollable list; tap
 * a row (checkbox on the left) to mark it done and it drops off. Events stay until
 * ticked (no time-based auto-hide); unticking in the app brings them back.
 *
 * Mirrors ChecklistWidgetProvider: the full [render] on onUpdate attaches the
 * collection adapter + row-tap template + open-app click; routine refreshes and taps
 * use the lightweight [refresh] (reliable header repaint via partiallyUpdateAppWidget
 * + row reload via notifyAppWidgetViewDataChanged). Re-attaching the adapter in a
 * background pass would clobber the header repaint, so only onUpdate does that.
 */
class CalendarWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        // Full structural build (attaches the collection adapter). Routine refreshes
        // go through updateAll (lightweight) so the header count repaints reliably.
        rebuild(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action != ACTION_TOGGLE) return
        val id = intent.getStringExtra(EXTRA_EVENT_ID) ?: return
        val startTime = intent.getLongExtra(EXTRA_START, 0L)
        val recurring = intent.getBooleanExtra(EXTRA_RECURRING, false)
        val app = context.applicationContext
        val pending = goAsync()
        bgScope.launch {
            try {
                val ds = WidgetDataSource(app)
                val userId = ds.userId()
                // Per-occurrence for recurring events (the occurrence's local date),
                // master-row flip for one-offs — identical to the app's mark-done.
                val occurrenceDate = if (recurring) {
                    Instant.ofEpochMilli(startTime).atZone(ZoneId.systemDefault()).toLocalDate()
                } else {
                    null
                }
                // Optimistic: local write first so the row drops off instantly, then
                // sync to the server (setEventDone re-applies the same state + PUTs;
                // idempotent, and its offline fallback keeps it unsynced for retry).
                ds.calendarRepo.markDoneLocally(id, isDone = true, occurrenceDate = occurrenceDate)
                refresh(app) // event drops off the list + header recount — instant
                ds.calendarRepo.setEventDone(id, userId, isDone = true, occurrenceDate = occurrenceDate)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val ACTION_TOGGLE = "com.ultiq.app.widget.CALENDAR_TOGGLE"
        /** Fill-in extras (set per row in the factory) → read back in [onReceive]. */
        const val EXTRA_EVENT_ID = "event_id"
        const val EXTRA_START = "event_start"
        const val EXTRA_RECURRING = "event_recurring"
        // Stable, provider-unique request code for the row-tap template intent.
        private const val TEMPLATE_REQUEST_CODE = 0xCA1E
        private val bgScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /**
         * Routine refresh — count (via the reliable partial path) + row reload,
         * WITHOUT re-attaching the RemoteAdapter (which clobbers the header repaint
         * and resets scroll). Runs on app foreground/background, sync, clock changes.
         */
        fun updateAll(context: Context) {
            val app = context.applicationContext
            bgScope.launch { refresh(app) }
        }

        /**
         * Full structural (re)build — attaches the collection adapter/template/click.
         * Runs on onUpdate (widget added / app updated); routine refreshes use
         * [updateAll] instead.
         */
        fun rebuild(context: Context) {
            val app = context.applicationContext
            bgScope.launch { render(app) }
        }

        /**
         * Full structural (re)build: attach the collection adapter + row-tap template
         * + open-app click, set the counts, and reload the rows. Runs on onUpdate only
         * — re-attaching the adapter is what makes a following partial header repaint
         * unreliable, so routine refreshes go through [refresh].
         */
        private suspend fun render(context: Context) {
            val snapshot = WidgetDataSource(context).todayCalendar()
            val mgr = AppWidgetManager.getInstance(context)
            val ids = mgr.getAppWidgetIds(ComponentName(context, CalendarWidgetProvider::class.java))
            if (ids.isEmpty()) return

            val emptyText = emptyTextFor(snapshot)
            for (id in ids) {
                val rv = RemoteViews(context.packageName, R.layout.widget_calendar_rv)
                rv.setTextViewText(R.id.cal_count, "${snapshot.doneCount}/${snapshot.total}")
                rv.setTextViewText(R.id.cal_left, "${snapshot.open.size} left")
                rv.setTextViewText(R.id.cal_empty, emptyText)
                // Header bar (and the card as a whole) open the Calendar tab. The
                // ListView owns its own row/scroll touches, so the header is the
                // widget's open-app affordance.
                val openApp = widgetOpenPendingIntent(context, NotificationHelper.DEEP_LINK_CALENDAR)
                rv.setOnClickPendingIntent(R.id.calendar_header, openApp)
                rv.setOnClickPendingIntent(R.id.calendar_root, openApp)
                rv.setEmptyView(R.id.cal_list, R.id.cal_empty)

                // Point the ListView at the collection service. A per-id data URI keeps
                // the launcher from reusing one widget's factory for another.
                val svc = Intent(context, CalendarRemoteViewsService::class.java).apply {
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                    this.data = Uri.parse(this.toUri(Intent.URI_INTENT_SCHEME))
                }
                rv.setRemoteAdapter(R.id.cal_list, svc)

                // One mutable template; each row fills in its own event id (fill-in intent).
                rv.setPendingIntentTemplate(R.id.cal_list, toggleTemplate(context))

                mgr.updateAppWidget(id, rv)
            }
            // Adapter attached above → force the factory to rebuild its rows.
            mgr.notifyAppWidgetViewDataChanged(ids, R.id.cal_list)
        }

        /**
         * Routine/tap refresh — push the counts (reliable partial merge) + reload rows,
         * WITHOUT re-attaching the RemoteAdapter. Assumes the adapter was already
         * attached by a prior onUpdate → [render] (persisted by the launcher).
         */
        private suspend fun refresh(context: Context) {
            val snapshot = WidgetDataSource(context).todayCalendar()
            val mgr = AppWidgetManager.getInstance(context)
            val ids = mgr.getAppWidgetIds(ComponentName(context, CalendarWidgetProvider::class.java))
            if (ids.isEmpty()) return
            pushCount(mgr, ids, snapshot, context)
            mgr.notifyAppWidgetViewDataChanged(ids, R.id.cal_list)
        }

        /**
         * Repaint the header counts + empty-state text via partiallyUpdateAppWidget —
         * the only path that reliably repaints the sibling header from a background
         * pass (a full updateAppWidget that re-sets the adapter does not). Both the
         * routine and tap paths funnel the counts through here.
         */
        private fun pushCount(
            mgr: AppWidgetManager,
            ids: IntArray,
            snapshot: CalendarSnapshot,
            context: Context,
        ) {
            val emptyText = emptyTextFor(snapshot)
            for (id in ids) {
                val partial = RemoteViews(context.packageName, R.layout.widget_calendar_rv)
                partial.setTextViewText(R.id.cal_count, "${snapshot.doneCount}/${snapshot.total}")
                partial.setTextViewText(R.id.cal_left, "${snapshot.open.size} left")
                partial.setTextViewText(R.id.cal_empty, emptyText)
                mgr.partiallyUpdateAppWidget(id, partial)
            }
        }

        private fun emptyTextFor(snapshot: CalendarSnapshot): String =
            if (snapshot.total == 0) "Nothing scheduled today" else "All done for today 🎉"

        private fun toggleTemplate(context: Context): PendingIntent =
            PendingIntent.getBroadcast(
                context,
                TEMPLATE_REQUEST_CODE,
                Intent(context, CalendarWidgetProvider::class.java).setAction(ACTION_TOGGLE),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
    }
}
