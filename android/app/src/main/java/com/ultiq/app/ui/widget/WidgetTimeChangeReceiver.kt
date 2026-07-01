package com.ultiq.app.ui.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Forces a widget refresh on date / clock / timezone changes so "Today", the
 * checklist count, and the focus elapsed timer recompute at local midnight, on a
 * manual clock change, and on travel. SSE is foreground-only, so without this a
 * backgrounded widget could keep showing yesterday's data after midnight.
 */
class WidgetTimeChangeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_DATE_CHANGED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED -> Unit
            else -> return
        }
        val appContext = context.applicationContext
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                WidgetUpdater.updateAll(appContext)
            } finally {
                pending.finish()
            }
        }
    }
}
