package com.ultiq.app.ui.widget

import android.content.Context

/**
 * Refresh every Ultiq home-screen widget. Called after an interactive write, after
 * `SyncWorker.syncAll()`, on app foreground/background (UltiqApp), and on
 * clock/date/timezone changes (WidgetTimeChangeReceiver). All four widgets are
 * classic RemoteViews providers now, so each repaint is a synchronous
 * `AppWidgetManager.updateAppWidget` (the read is off the main thread).
 */
object WidgetUpdater {
    suspend fun updateAll(context: Context) {
        val appContext = context.applicationContext
        ChecklistWidgetProvider.updateAll(appContext)
        FocusWidgetProvider.updateAll(appContext)
        CalendarWidgetProvider.updateAll(appContext)
        SleepAlarmWidgetProvider.updateAll(appContext)
    }
}
