package com.ultiq.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.ultiq.app.data.local.AppDatabase
import com.ultiq.app.data.remote.RetrofitClient
import com.ultiq.app.data.repository.SessionRepository
import com.ultiq.app.service.FocusTrackingService
import com.ultiq.app.ui.widget.WidgetUpdater
import com.ultiq.app.util.TokenManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Handles notification actions for active-session controls (lockscreen + shade).
 * Currently: "Stop focus". Runs headless — no Activity, no unlock — so the user
 * can end a focus session straight from the lockscreen. Mirrors the widget's
 * StopFocus callback: complete the active session (elapsed from the service's
 * start anchor), stop the service, refresh the widgets.
 */
class SessionActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_STOP_FOCUS) return
        val app = context.applicationContext
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val db = AppDatabase.getInstance(app)
                val repo = SessionRepository(db.sessionDao(), RetrofitClient.create(TokenManager(app)))
                val active = repo.getActiveSessions().first().firstOrNull()
                val startedAt = FocusTrackingService.sessionStartTime.value
                    .takeIf { it > 0L } ?: active?.startedAt ?: 0L
                val minutes = if (startedAt > 0L) {
                    ((System.currentTimeMillis() - startedAt) / 60_000L).toInt().coerceAtLeast(0)
                } else 0
                if (active != null) repo.completeSession(active.id, minutes, phonePickups = 0)
                app.stopService(Intent(app, FocusTrackingService::class.java))
                // Let the service teardown clear isRunning before repaint so the
                // Focus widget flips to idle (its focus() reader gates on isRunning).
                withTimeoutOrNull(2_000) { FocusTrackingService.isRunning.first { !it } }
                WidgetUpdater.updateAll(app)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_STOP_FOCUS = "com.ultiq.app.action.STOP_FOCUS"
    }
}
