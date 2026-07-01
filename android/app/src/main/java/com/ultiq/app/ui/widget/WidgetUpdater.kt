package com.ultiq.app.ui.widget

import android.content.Context
import androidx.glance.appwidget.updateAll

/**
 * Refresh every Ultiq home-screen widget. Called after an interactive write,
 * after `SyncWorker.syncAll()`, on app foreground/background (UltiqApp), and on
 * clock/date/timezone changes (WidgetTimeChangeReceiver). Safe to call from any
 * suspend context; a no-op when no widgets are placed.
 */
object WidgetUpdater {
    suspend fun updateAll(context: Context) {
        val appContext = context.applicationContext
        ChecklistWidgetProvider.updateAll(appContext)
        FocusWidgetProvider.updateAll(appContext)
        CalendarWidget().updateAll(appContext)
        SleepAlarmWidget().updateAll(appContext)
    }
}
