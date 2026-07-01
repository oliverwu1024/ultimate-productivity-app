package com.ultiq.app.ui.widget

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.updateAll
import com.ultiq.app.MainActivity
import com.ultiq.app.data.local.entity.ChecklistCompletionEntity
import com.ultiq.app.data.repository.ChecklistEchoSuppressor
import com.ultiq.app.service.FocusTrackingService
import com.ultiq.app.service.LiveFocusSessionStore
import com.ultiq.app.service.SleepTrackingService
import com.ultiq.app.util.NotificationHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

/** Passes the tapped checklist row id from the widget into [ToggleChecklistItem]. */
val checklistItemIdKey = ActionParameters.Key<String>("checklist_item_id")

/** Detached scope for best-effort background server pushes after a widget tap,
 *  kept OFF the tap→repaint path. If the process dies mid-push the local row stays
 *  isSynced=false and the next SyncWorker / foreground sync reconciles it. */
private val widgetBgScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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
        val ds = WidgetDataSource(context)
        val epochDay = LocalDate.now().toEpochDay()
        val now = System.currentTimeMillis()
        val recurring = withContext(Dispatchers.IO) {
            val item = ds.db.checklistDao().getById(id)
            val isRecurring = item != null && item.recurrenceDaysMask != 0
            // 1) Optimistic LOCAL write so the widget re-read reflects the tick
            //    instantly. The repo's network call is the slow part and must NOT
            //    sit in the tap→repaint path — that was the "huge lag" / tap that
            //    looked unresponsive (the write had happened; only the repaint lagged).
            ChecklistEchoSuppressor.noteLocalChange(id)
            if (isRecurring) {
                ds.db.checklistCompletionDao().insert(ChecklistCompletionEntity(id, epochDay, now))
            } else {
                ds.db.checklistDao().markCompletedLocally(id, completedAt = now, updatedAt = now)
            }
            isRecurring
        }
        // 2) Repaint NOW — the ticked item drops off the open list immediately.
        //    updateAll (not update(glanceId)): the per-instance update was
        //    unreliable from a callback; updateAll matches the working refresh path.
        ChecklistWidget().updateAll(context)
        // 3) Best-effort server push in the background. On failure/death the local
        //    row stays isSynced=false and the next sync pushes it.
        widgetBgScope.launch {
            if (recurring) ds.checklistRepo.markRecurringCompletedOn(id, epochDay)
            else ds.checklistRepo.markCompleted(id)
        }
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
        // Optimistic durable active-flag FIRST so the widget flips to the timer
        // instantly (the service also writes it, but that write is async).
        LiveFocusSessionStore(app).save(
            LiveFocusSessionStore.Snapshot(System.currentTimeMillis(), workDuration),
        )
        ContextCompat.startForegroundService(
            app,
            Intent(app, FocusTrackingService::class.java)
                .putExtra(FocusTrackingService.EXTRA_WORK_DURATION_MIN, workDuration),
        )
        // Durable session row for the in-app timer/history — off the repaint path.
        widgetBgScope.launch {
            ds.sessionRepo.createSession(tag = "Focus", workDuration = workDuration, userId = ds.userId())
        }
        FocusWidget().updateAll(app)
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
        // Clear the durable flag FIRST so the widget flips to idle immediately.
        LiveFocusSessionStore(app).clear()
        // Complete the session + stop the service off the repaint path.
        widgetBgScope.launch {
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
        FocusWidget().updateAll(app)
    }
}
