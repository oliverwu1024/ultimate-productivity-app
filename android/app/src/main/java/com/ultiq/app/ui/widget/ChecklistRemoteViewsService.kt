package com.ultiq.app.ui.widget

import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import com.ultiq.app.R
import kotlinx.coroutines.runBlocking

/**
 * Backs the Checklist widget's scrollable ListView. The launcher binds this
 * (BIND_REMOTEVIEWS in the manifest) and calls the factory on a binder thread —
 * so [ChecklistRemoteViewsFactory.onDataSetChanged]'s blocking Room read is
 * allowed. Replaces the old fixed six-row layout, so the widget now shows EVERY
 * open item, scrollable.
 */
class ChecklistRemoteViewsService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory =
        ChecklistRemoteViewsFactory(applicationContext)
}

private class ChecklistRemoteViewsFactory(
    private val context: Context,
) : RemoteViewsService.RemoteViewsFactory {

    @Volatile
    private var items: List<ChecklistItemView> = emptyList()

    override fun onCreate() {}

    /** Runs on a binder thread; blocking IO is allowed (and expected) here. */
    override fun onDataSetChanged() {
        items = runBlocking { WidgetDataSource(context).todayChecklist().open }
    }

    override fun onDestroy() {
        items = emptyList()
    }

    override fun getCount(): Int = items.size

    override fun getViewAt(position: Int): RemoteViews {
        val rv = RemoteViews(context.packageName, R.layout.widget_checklist_row)
        val item = items.getOrNull(position) ?: return rv
        rv.setTextViewText(R.id.row_title, item.title)
        // Merged into the list's pending-intent template → broadcasts ACTION_TOGGLE
        // with this item's id, which ChecklistWidgetProvider.onReceive reads back.
        rv.setOnClickFillInIntent(
            R.id.row_root,
            Intent().putExtra(ChecklistWidgetProvider.EXTRA_ITEM_ID, item.id),
        )
        return rv
    }

    override fun getLoadingView(): RemoteViews? = null

    override fun getViewTypeCount(): Int = 1

    override fun getItemId(position: Int): Long =
        items.getOrNull(position)?.id?.hashCode()?.toLong() ?: position.toLong()

    override fun hasStableIds(): Boolean = true
}
