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
import com.ultiq.app.data.local.entity.ChecklistCompletionEntity
import com.ultiq.app.data.repository.ChecklistEchoSuppressor
import com.ultiq.app.util.NotificationHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * Classic RemoteViews Checklist widget — repaints promptly via AppWidgetManager
 * (Glance's update goes through a background SessionWorker this device defers for
 * tens of seconds). The list is a scrollable ListView backed by
 * [ChecklistRemoteViewsService], so it shows EVERY open item (not a fixed six).
 * Row taps ride a single mutable pending-intent template + a per-row fill-in
 * intent carrying the item id.
 */
class ChecklistWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        // Full structural build (attaches the collection adapter). Routine refreshes
        // go through updateAll (lightweight) so the header count repaints reliably.
        rebuild(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action != ACTION_TOGGLE) return
        val id = intent.getStringExtra(EXTRA_ITEM_ID) ?: return
        val app = context.applicationContext
        val pending = goAsync()
        bgScope.launch {
            try {
                val ds = WidgetDataSource(app)
                val item = ds.db.checklistDao().getById(id)
                val epochDay = LocalDate.now().toEpochDay()
                val now = System.currentTimeMillis()
                // The widget lists only OPEN items, so a tap always completes.
                ChecklistEchoSuppressor.noteLocalChange(id)
                val recurring = item != null && item.recurrenceDaysMask != 0
                if (recurring) {
                    ds.db.checklistCompletionDao().insert(ChecklistCompletionEntity(id, epochDay, now))
                } else {
                    ds.db.checklistDao().markCompletedLocally(id, completedAt = now, updatedAt = now)
                }
                refresh(app) // count (reliable partial) + row reload
                // Best-effort server push (offline → stays isSynced=false for next sync).
                if (recurring) ds.checklistRepo.markRecurringCompletedOn(id, epochDay)
                else ds.checklistRepo.markCompleted(id)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val ACTION_TOGGLE = "com.ultiq.app.widget.CHECKLIST_TOGGLE"
        /** Fill-in extra (set per row in the factory) → read back in [onReceive]. */
        const val EXTRA_ITEM_ID = "item_id"
        // Stable, provider-unique request code for the row-tap template intent.
        private const val TEMPLATE_REQUEST_CODE = 0xC137
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
         * + open-app click, set the count, and reload the rows. Runs on onUpdate only
         * (widget add / app update) — re-attaching the adapter here is exactly what
         * makes a following partial header repaint unreliable, so routine refreshes go
         * through [refresh] instead.
         */
        private suspend fun render(context: Context) {
            val snapshot = WidgetDataSource(context).todayChecklist()
            val mgr = AppWidgetManager.getInstance(context)
            val ids = mgr.getAppWidgetIds(ComponentName(context, ChecklistWidgetProvider::class.java))
            if (ids.isEmpty()) return

            val emptyText = if (snapshot.total == 0) "Nothing due today" else "All done for today 🎉"
            for (id in ids) {
                val rv = RemoteViews(context.packageName, R.layout.widget_checklist_rv)
                rv.setTextViewText(R.id.checklist_count, "${snapshot.doneCount}/${snapshot.total}")
                rv.setTextViewText(R.id.checklist_left, "${snapshot.open.size} left")
                rv.setTextViewText(R.id.checklist_empty, emptyText)
                // Header bar (and the card as a whole) open the checklist tab. The
                // ListView owns its own row/scroll touches, so the header is the
                // widget's open-app affordance.
                val openApp = widgetOpenPendingIntent(context, NotificationHelper.DEEP_LINK_CHECKLIST)
                rv.setOnClickPendingIntent(R.id.checklist_header, openApp)
                rv.setOnClickPendingIntent(R.id.checklist_root, openApp)
                rv.setEmptyView(R.id.checklist_list, R.id.checklist_empty)

                // Point the ListView at the collection service. A per-id data URI keeps
                // the launcher from reusing one widget's factory for another.
                val svc = Intent(context, ChecklistRemoteViewsService::class.java).apply {
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                    this.data = Uri.parse(this.toUri(Intent.URI_INTENT_SCHEME))
                }
                rv.setRemoteAdapter(R.id.checklist_list, svc)

                // One mutable template; each row fills in its own item id (fill-in intent).
                rv.setPendingIntentTemplate(R.id.checklist_list, toggleTemplate(context))

                mgr.updateAppWidget(id, rv)
            }
            // Adapter attached above → force the factory to rebuild its rows.
            mgr.notifyAppWidgetViewDataChanged(ids, R.id.checklist_list)
        }

        /**
         * Routine/tap refresh — push the count (reliable partial merge) + reload rows,
         * WITHOUT re-attaching the RemoteAdapter. This is the ONLY path that reliably
         * repaints the sibling header count from a background pass (see [pushCount]);
         * [render]'s adapter re-set clobbers it. Assumes the adapter was already
         * attached by a prior onUpdate → [render] (persisted by the launcher).
         */
        private suspend fun refresh(context: Context) {
            val snapshot = WidgetDataSource(context).todayChecklist()
            val mgr = AppWidgetManager.getInstance(context)
            val ids = mgr.getAppWidgetIds(ComponentName(context, ChecklistWidgetProvider::class.java))
            if (ids.isEmpty()) return
            pushCount(mgr, ids, snapshot, context)
            mgr.notifyAppWidgetViewDataChanged(ids, R.id.checklist_list)
        }

        /**
         * Repaint the header count + empty-state text via partiallyUpdateAppWidget.
         * A full updateAppWidget that re-sets the RemoteAdapter does NOT reliably
         * repaint the sibling header from a background pass on some launchers — the
         * rows refresh via notify, but the count stays stale until the page is
         * revisited. A partial merge repaints it live, so BOTH refresh paths (tap and
         * updateAll) funnel the count through here.
         */
        private fun pushCount(
            mgr: AppWidgetManager,
            ids: IntArray,
            snapshot: ChecklistSnapshot,
            context: Context,
        ) {
            val emptyText = if (snapshot.total == 0) "Nothing due today" else "All done for today 🎉"
            for (id in ids) {
                val partial = RemoteViews(context.packageName, R.layout.widget_checklist_rv)
                partial.setTextViewText(R.id.checklist_count, "${snapshot.doneCount}/${snapshot.total}")
                partial.setTextViewText(R.id.checklist_left, "${snapshot.open.size} left")
                partial.setTextViewText(R.id.checklist_empty, emptyText)
                mgr.partiallyUpdateAppWidget(id, partial)
            }
        }

        private fun toggleTemplate(context: Context): PendingIntent =
            PendingIntent.getBroadcast(
                context,
                TEMPLATE_REQUEST_CODE,
                Intent(context, ChecklistWidgetProvider::class.java).setAction(ACTION_TOGGLE),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
    }
}
