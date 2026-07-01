package com.ultiq.app.ui.widget

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import com.ultiq.app.MainActivity
import com.ultiq.app.service.FocusTrackingService
import com.ultiq.app.service.SleepTrackingService
import com.ultiq.app.util.NotificationHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.time.LocalDate

/** Passes the tapped checklist row id from the widget into [ToggleChecklistItem]. */
val checklistItemIdKey = ActionParameters.Key<String>("checklist_item_id")

/** A MainActivity intent that deep-links to a tab (reuses the notification deep-link plumbing). */
fun widgetOpenIntent(context: Context, deepLink: String): Intent =
    Intent(context.applicationContext, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        putExtra(NotificationHelper.EXTRA_DEEP_LINK, deepLink)
    }

/**
 * Tick today's checklist item from the widget. The widget lists only OPEN items,
 * so a tap always completes: recurring rows log a per-day completion, one-offs
 * flip `completed`. Goes through ChecklistRepository (optimistic + echo-suppressed),
 * matching SessionsViewModel.confirmChecklistCompletion.
 */
class ToggleChecklistItem : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val id = parameters[checklistItemIdKey] ?: return
        withContext(Dispatchers.IO) {
            val ds = WidgetDataSource(context)
            val item = ds.db.checklistDao().getById(id)
            val epochDay = LocalDate.now().toEpochDay()
            if (item != null && item.recurrenceDaysMask != 0) {
                ds.checklistRepo.markRecurringCompletedOn(id, epochDay)
            } else {
                ds.checklistRepo.markCompleted(id)
            }
        }
        ChecklistWidget().update(context, glanceId)
    }
}

/**
 * Start a focus session from the widget. Mirrors SessionsViewModel.startSession:
 * blocked while a sleep session runs; uses the default work duration + a "Focus"
 * tag (a widget can't prompt for one). The foreground service is started first so
 * the widget-interaction FGS-start exemption isn't spent waiting on the
 * create-session network call.
 */
class StartFocus : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val app = context.applicationContext
        if (SleepTrackingService.isRunning.value) {
            // Focus + sleep can't run together. Open the app so the user gets the
            // real guard message instead of a silent no-op.
            app.startActivity(
                widgetOpenIntent(app, NotificationHelper.DEEP_LINK_SESSIONS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return
        }
        val ds = WidgetDataSource(context)
        val workDuration = ds.prefs.snapshot().defaultWorkDuration
        ContextCompat.startForegroundService(
            app,
            Intent(app, FocusTrackingService::class.java)
                .putExtra(FocusTrackingService.EXTRA_WORK_DURATION_MIN, workDuration),
        )
        // Durable session row for the in-app timer/history + process-death restore.
        withContext(Dispatchers.IO) {
            ds.sessionRepo.createSession(tag = "Focus", workDuration = workDuration, userId = ds.userId())
        }
        FocusWidget().update(context, glanceId)
    }
}

/**
 * Stop the running focus session from the widget. Completes the active session
 * (elapsed derived from the service's start anchor) and stops the service. Skips
 * the in-app debrief prompt + pickup timeline — accepted widget limitation.
 */
class StopFocus : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val app = context.applicationContext
        val ds = WidgetDataSource(context)
        withContext(Dispatchers.IO) {
            val active = ds.sessionRepo.getActiveSessions().first().firstOrNull()
            val startedAt = FocusTrackingService.sessionStartTime.value
                .takeIf { it > 0L } ?: active?.startedAt ?: 0L
            val minutes = if (startedAt > 0L) {
                ((System.currentTimeMillis() - startedAt) / 60_000L).toInt().coerceAtLeast(0)
            } else 0
            if (active != null) {
                ds.sessionRepo.completeSession(active.id, minutes, phonePickups = 0)
            }
        }
        app.stopService(Intent(app, FocusTrackingService::class.java))
        FocusWidget().update(context, glanceId)
    }
}
