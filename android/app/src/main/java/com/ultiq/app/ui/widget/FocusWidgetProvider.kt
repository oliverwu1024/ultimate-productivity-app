package com.ultiq.app.ui.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import com.ultiq.app.MainActivity
import com.ultiq.app.R
import com.ultiq.app.data.local.AppDatabase
import com.ultiq.app.data.remote.RetrofitClient
import com.ultiq.app.data.repository.SessionRepository
import com.ultiq.app.service.FocusTrackingService
import com.ultiq.app.service.LiveFocusSessionStore
import com.ultiq.app.service.SleepTrackingService
import com.ultiq.app.util.NotificationHelper
import com.ultiq.app.util.TokenManager
import com.ultiq.app.util.UserPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch

/**
 * Classic RemoteViews Focus widget. Unlike Glance — whose updates route through a
 * background `SessionWorker` that this device defers 15-50s — RemoteViews repaint
 * SYNCHRONOUSLY via `AppWidgetManager.updateAppWidget`, so Start/Stop reflect on the
 * home screen instantly. State is the durable [LiveFocusSessionStore]; the live
 * elapsed timer is a native `Chronometer` the OS ticks itself.
 */
class FocusWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        ids.forEach { render(context, mgr, it) }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        val app = context.applicationContext
        when (intent.action) {
            ACTION_START -> {
                if (SleepTrackingService.isRunning.value) {
                    app.startActivity(openAppIntent(app).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    return
                }
                val pending = goAsync()
                bgScope.launch {
                    try {
                        val workDuration = UserPreferences(app).snapshot().defaultWorkDuration
                        LiveFocusSessionStore(app).save(
                            LiveFocusSessionStore.Snapshot(System.currentTimeMillis(), workDuration),
                        )
                        updateAll(app) // instant repaint → shows timer + Stop
                        ContextCompat.startForegroundService(
                            app,
                            Intent(app, FocusTrackingService::class.java)
                                .putExtra(FocusTrackingService.EXTRA_WORK_DURATION_MIN, workDuration),
                        )
                        // Durable session row for the in-app timer/history.
                        val db = AppDatabase.getInstance(app)
                        val repo = SessionRepository(db.sessionDao(), RetrofitClient.create(TokenManager(app)))
                        val uid = TokenManager(app).getUserId().firstOrNull() ?: ""
                        repo.createSession(tag = "Focus", workDuration = workDuration, userId = uid)
                    } finally {
                        pending.finish()
                    }
                }
            }
        }
    }

    companion object {
        private const val ACTION_START = "com.ultiq.app.widget.FOCUS_START"
        private val bgScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /** Repaint every placed Focus widget from current state (synchronous). */
        fun updateAll(context: Context) {
            val mgr = AppWidgetManager.getInstance(context)
            val ids = mgr.getAppWidgetIds(ComponentName(context, FocusWidgetProvider::class.java))
            ids.forEach { render(context, mgr, it) }
        }

        private fun render(context: Context, mgr: AppWidgetManager, id: Int) {
            val live = LiveFocusSessionStore(context).load()
            val rv = RemoteViews(context.packageName, R.layout.widget_focus_rv)
            rv.setOnClickPendingIntent(
                R.id.focus_root,
                PendingIntent.getActivity(
                    context, 0, openAppIntent(context),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            if (live != null) {
                // Active: show the live timer only. Stopping is app-only (tap the
                // widget to open Focus), so there is no Stop button on the widget.
                rv.setViewVisibility(R.id.focus_chrono, View.VISIBLE)
                rv.setViewVisibility(R.id.focus_button, View.GONE)
                if (live.pausedElapsedMs >= 0L) {
                    // Paused in-app — freeze the timer at the paused elapsed.
                    rv.setTextViewText(R.id.focus_title, "Focus · paused")
                    rv.setChronometer(
                        R.id.focus_chrono,
                        SystemClock.elapsedRealtime() - live.pausedElapsedMs, null, false,
                    )
                } else {
                    rv.setTextViewText(R.id.focus_title, "Focus")
                    val elapsed = (System.currentTimeMillis() - live.startMs).coerceAtLeast(0L)
                    rv.setChronometer(R.id.focus_chrono, SystemClock.elapsedRealtime() - elapsed, null, true)
                }
            } else {
                rv.setViewVisibility(R.id.focus_chrono, View.GONE)
                rv.setViewVisibility(R.id.focus_button, View.VISIBLE)
                rv.setTextViewText(R.id.focus_button, "▶  Start")
                rv.setOnClickPendingIntent(R.id.focus_button, buttonIntent(context, ACTION_START))
            }
            mgr.updateAppWidget(id, rv)
        }

        private fun buttonIntent(context: Context, action: String): PendingIntent =
            PendingIntent.getBroadcast(
                context, action.hashCode(),
                Intent(context, FocusWidgetProvider::class.java).setAction(action),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        private fun openAppIntent(context: Context): Intent =
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(NotificationHelper.EXTRA_DEEP_LINK, NotificationHelper.DEEP_LINK_SESSIONS)
            }
    }
}
