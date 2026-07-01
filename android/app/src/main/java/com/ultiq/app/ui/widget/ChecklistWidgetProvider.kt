package com.ultiq.app.ui.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
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
 * Classic RemoteViews Checklist widget — repaints SYNCHRONOUSLY via
 * AppWidgetManager, so ticking an item updates instantly. (Glance's update goes
 * through a background SessionWorker this device defers for tens of seconds.)
 * Uses fixed row slots (row_0..row_5) rather than a RemoteViewsService collection
 * to keep it simple; the list is already capped short.
 */
class ChecklistWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        updateAll(context)
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
                render(app) // instant repaint — the ticked item drops off the list
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
        private const val EXTRA_ITEM_ID = "item_id"
        private const val MAX_ROWS = 6
        private val bgScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        private val ROW_IDS = intArrayOf(
            R.id.row_0, R.id.row_1, R.id.row_2, R.id.row_3, R.id.row_4, R.id.row_5,
        )
        private val TITLE_IDS = intArrayOf(
            R.id.title_0, R.id.title_1, R.id.title_2, R.id.title_3, R.id.title_4, R.id.title_5,
        )

        /** Read today's checklist + repaint all placed widgets (async — Room read is suspend). */
        fun updateAll(context: Context) {
            val app = context.applicationContext
            bgScope.launch { render(app) }
        }

        private suspend fun render(context: Context) {
            val data = WidgetDataSource(context).todayChecklist()
            val mgr = AppWidgetManager.getInstance(context)
            val ids = mgr.getAppWidgetIds(ComponentName(context, ChecklistWidgetProvider::class.java))
            if (ids.isEmpty()) return

            val rv = RemoteViews(context.packageName, R.layout.widget_checklist_rv)
            rv.setTextViewText(R.id.checklist_count, "${data.doneCount}/${data.total}")
            rv.setOnClickPendingIntent(
                R.id.checklist_root,
                widgetOpenPendingIntent(context, NotificationHelper.DEEP_LINK_CHECKLIST),
            )

            val open = data.open
            if (open.isEmpty()) {
                rv.setViewVisibility(R.id.checklist_empty, View.VISIBLE)
                rv.setTextViewText(
                    R.id.checklist_empty,
                    if (data.total == 0) "Nothing due today" else "All done for today 🎉",
                )
                for (i in 0 until MAX_ROWS) rv.setViewVisibility(ROW_IDS[i], View.GONE)
            } else {
                rv.setViewVisibility(R.id.checklist_empty, View.GONE)
                for (i in 0 until MAX_ROWS) {
                    if (i < open.size) {
                        val item = open[i]
                        rv.setViewVisibility(ROW_IDS[i], View.VISIBLE)
                        rv.setTextViewText(TITLE_IDS[i], item.title)
                        rv.setOnClickPendingIntent(ROW_IDS[i], togglePi(context, item.id))
                    } else {
                        rv.setViewVisibility(ROW_IDS[i], View.GONE)
                    }
                }
            }
            ids.forEach { mgr.updateAppWidget(it, rv) }
        }

        private fun togglePi(context: Context, itemId: String): PendingIntent =
            PendingIntent.getBroadcast(
                context,
                itemId.hashCode(),
                Intent(context, ChecklistWidgetProvider::class.java)
                    .setAction(ACTION_TOGGLE)
                    .putExtra(EXTRA_ITEM_ID, itemId),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
    }
}
