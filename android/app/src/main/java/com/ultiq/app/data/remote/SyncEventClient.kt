package com.ultiq.app.data.remote

import android.util.Log
import com.ultiq.app.BuildConfig
import com.ultiq.app.alarm.WakeAlarmScheduler
import com.ultiq.app.data.local.dao.AlarmDao
import com.ultiq.app.data.local.dao.CalendarEventDao
import com.ultiq.app.data.local.dao.ChecklistCompletionDao
import com.ultiq.app.data.local.dao.ChecklistDao
import com.ultiq.app.data.local.dao.SessionDao
import com.ultiq.app.data.local.dao.SleepDao
import com.ultiq.app.data.remote.dto.ChecklistItemDto
import com.ultiq.app.data.remote.dto.SyncEvent
import com.ultiq.app.data.remote.dto.completionEntities
import com.ultiq.app.data.remote.dto.parseSyncEvent
import com.ultiq.app.data.remote.dto.toEntity
import com.ultiq.app.data.repository.ChecklistEchoSuppressor
import java.time.Instant
import com.ultiq.app.util.AlarmScheduler
import com.ultiq.app.util.TokenManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.util.concurrent.TimeUnit

/**
 * Subscribes to backend SSE at `/sync/events` while the app is in the foreground
 * and applies incoming events to Room directly. Auto-reconnects on transient
 * disconnects with a 5-second backoff.
 *
 * Lifecycle is controlled by [com.ultiq.app.UltiqApp] via ProcessLifecycleOwner —
 * connect on ON_START, disconnect on ON_STOP. Battery-friendly: no persistent
 * socket while backgrounded; we rely on the existing `SyncManager.syncAll()` +
 * delete reconciliation in each repo to catch up missed events on next start.
 */
class SyncEventClient(
    private val tokenManager: TokenManager,
    private val calendarDao: CalendarEventDao,
    private val checklistDao: ChecklistDao,
    /// §024 — Required so SSE ChecklistCreated/Updated events also
    /// reconcile the per-day completion log. Nullable for transitional
    /// safety; callers that haven't been updated to pass it lose the
    /// per-day sync but the main row still updates.
    private val checklistCompletionDao: ChecklistCompletionDao? = null,
    private val sleepDao: SleepDao,
    private val sessionDao: SessionDao,
    private val alarmDao: AlarmDao,
    /// Schedules calendar-event reminders.
    private val alarmScheduler: AlarmScheduler? = null,
    /// Schedules wake-up alarms (AlarmManager intents). Needed when a
    /// new alarm arrives via SSE so the local alarm queue stays in sync —
    /// otherwise a Coach-created alarm would show up in the UI but never
    /// actually fire.
    private val wakeScheduler: WakeAlarmScheduler? = null,
    /// v2.13.4 — Fired the moment SSE connects, so the caller can run a
    /// full sync to catch any events created during the ~15 s connect
    /// window (between app foreground and SSE handshake completing).
    /// Without this, web-added events in that window were silently
    /// missed until the next tab-resume / process restart.
    private val onConnected: (suspend () -> Unit)? = null,
) {
    private val scope = CoroutineScope(Dispatchers.IO)
    private var eventSource: EventSource? = null
    private var reconnectJob: Job? = null
    @Volatile private var wantConnected = false
    @Volatile private var reconnectAttempt = 0

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        // SSE: server keeps the response open. Don't time out reads.
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    fun connect() {
        Log.i(TAG, "connect() called")
        wantConnected = true
        reconnectJob?.cancel()
        scope.launch {
            doConnect()
        }
    }

    fun disconnect() {
        Log.i(TAG, "disconnect() called")
        wantConnected = false
        reconnectAttempt = 0
        reconnectJob?.cancel()
        eventSource?.cancel()
        eventSource = null
    }

    private suspend fun doConnect() {
        val token = tokenManager.getToken().first()
        if (token.isNullOrBlank()) {
            // Logged out — nothing to subscribe to. Reconnect attempt will happen
            // when the app is in foreground after login.
            Log.i(TAG, "doConnect skipped — no token (logged out)")
            return
        }
        val url = "${BuildConfig.API_BASE_URL.trimEnd('/')}/sync/events"
        Log.i(TAG, "doConnect opening SSE to $url")
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .header("Accept", "text/event-stream")
            .build()
        val factory = EventSources.createFactory(client)
        eventSource = factory.newEventSource(request, listener)
    }

    private val listener = object : EventSourceListener() {
        override fun onOpen(eventSource: EventSource, response: Response) {
            reconnectAttempt = 0
            // v2.13.0 — Log.i instead of debug-only Log.d so release-build
            // logcat shows SSE state. v2.12.x users reported web-added
            // calendar events not appearing on phone in real time even
            // though this code path was supposed to handle it; we
            // couldn't tell from a release-build logcat whether SSE was
            // even connecting because the logs were stripped by R8.
            Log.i(TAG, "SSE connected: HTTP ${response.code}")
            // v2.13.4 — Cold-start catch-up. Backend's ~15 s connect
            // window means any event added on web *between* foreground
            // and onOpen never streamed to us. Trigger a full sync now
            // so the local cache is correct from the first frame the
            // user sees. Idempotent — same code path WorkManager's
            // periodic sync runs.
            val cb = onConnected ?: return
            scope.launch { runCatching { cb() } }
        }

        override fun onEvent(
            eventSource: EventSource,
            id: String?,
            type: String?,
            data: String,
        ) {
            val event = parseSyncEvent(data)
            if (event == null) {
                Log.w(TAG, "SSE parse failed (type=$type, len=${data.length})")
                return
            }
            Log.i(TAG, "SSE event: ${event.javaClass.simpleName}")
            scope.launch { applyEvent(event) }
        }

        override fun onFailure(
            eventSource: EventSource,
            t: Throwable?,
            response: Response?,
        ) {
            val code = response?.code
            Log.w(TAG, "SSE failure: code=$code class=${t?.javaClass?.simpleName} msg=${t?.message}")
            // Auth failure: don't loop. Drop the token and let the UI route to login.
            if (code == 401 || code == 403) {
                wantConnected = false
                reconnectAttempt = 0
                scope.launch { tokenManager.clearToken() }
                return
            }
            if (!wantConnected) return
            // Exponential backoff: 5s, 10s, 20s, 40s, 60s cap.
            val attempt = (reconnectAttempt + 1).coerceAtMost(8)
            reconnectAttempt = attempt
            val delayMs = (5_000L * (1L shl (attempt - 1))).coerceAtMost(60_000L)
            reconnectJob?.cancel()
            reconnectJob = scope.launch {
                delay(delayMs)
                doConnect()
            }
        }

        override fun onClosed(eventSource: EventSource) {
            Log.i(TAG, "SSE closed")
        }
    }

    private suspend fun applyEvent(event: SyncEvent) {
        try {
            when (event) {
                is SyncEvent.CalendarCreated -> {
                    val entity = event.event.toEntity()
                    calendarDao.insert(entity)
                    alarmScheduler?.scheduleEventReminder(entity)
                }
                is SyncEvent.CalendarUpdated -> {
                    val entity = event.event.toEntity()
                    calendarDao.insert(entity)
                    alarmScheduler?.cancelEventReminder(entity.id)
                    alarmScheduler?.scheduleEventReminder(entity)
                }
                is SyncEvent.CalendarDeleted -> {
                    calendarDao.deleteById(event.id)
                    alarmScheduler?.cancelEventReminder(event.id)
                }
                is SyncEvent.ChecklistCreated -> {
                    if (ChecklistEchoSuppressor.shouldSuppress(event.item.id)) {
                        Log.i(TAG, "Suppressed ChecklistCreated echo for ${event.item.id}")
                    } else {
                        checklistDao.insert(event.item.toEntity())
                        applyChecklistCompletions(event.item)
                    }
                }
                is SyncEvent.ChecklistUpdated -> {
                    if (ChecklistEchoSuppressor.shouldSuppress(event.item.id)) {
                        Log.i(TAG, "Suppressed ChecklistUpdated echo for ${event.item.id}")
                    } else {
                        checklistDao.insert(event.item.toEntity())
                        applyChecklistCompletions(event.item)
                    }
                }
                is SyncEvent.ChecklistDeleted -> {
                    if (ChecklistEchoSuppressor.shouldSuppress(event.id)) {
                        Log.i(TAG, "Suppressed ChecklistDeleted echo for ${event.id}")
                    } else {
                        checklistCompletionDao?.deleteAllForItem(event.id)
                        checklistDao.deleteById(event.id)
                    }
                }
                is SyncEvent.SleepCreated -> sleepDao.insert(event.record.toEntity())
                is SyncEvent.SleepUpdated -> sleepDao.insert(event.record.toEntity())
                is SyncEvent.SleepDeleted -> sleepDao.deleteById(event.id)
                is SyncEvent.SessionCreated -> sessionDao.insert(event.session.toEntity())
                is SyncEvent.SessionUpdated -> sessionDao.insert(event.session.toEntity())
                is SyncEvent.SessionDeleted -> sessionDao.deleteById(event.id)
                is SyncEvent.AlarmCreated -> {
                    val entity = event.alarm.toEntity()
                    alarmDao.insertAlarm(entity)
                    // Schedule the AlarmManager intent locally — otherwise the
                    // alarm appears in the UI but never fires. Required when
                    // an alarm is created from another surface (Coach chat
                    // commit, or a different device) and arrives here via SSE.
                    wakeScheduler?.schedule(entity)
                }
                is SyncEvent.AlarmUpdated -> {
                    val entity = event.alarm.toEntity()
                    alarmDao.insertAlarm(entity)
                    wakeScheduler?.schedule(entity)
                }
                is SyncEvent.AlarmDeleted -> {
                    wakeScheduler?.cancel(event.id)
                    alarmDao.deleteAlarmById(event.id)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "applyEvent failed for ${event.javaClass.simpleName}", e)
        }
    }

    /// §024 — Mirror the server's `completed_epoch_days` snapshot into
    /// the local table. No-op on pre-024 payloads (field absent), so an
    /// older server can't accidentally erase local ticks.
    ///
    /// §flicker-fix — Diff-based: delete only the days no longer on the
    /// server, then upsert the rest. The previous wipe-and-reinsert
    /// caused the same Completed-bucket flicker the open-screen sync
    /// path had — see ChecklistRepository.persistCompletionsFromServer
    /// for the full rationale. Every SSE event for a recurring done-
    /// today row used to flip the row out of and back into the
    /// Completed section.
    private suspend fun applyChecklistCompletions(dto: ChecklistItemDto) {
        val dao = checklistCompletionDao ?: return
        val days = dto.completed_epoch_days ?: return
        if (days.isEmpty()) {
            dao.deleteAllForItem(dto.id)
            return
        }
        val ts = runCatching { Instant.parse(dto.updated_at).toEpochMilli() }
            .getOrDefault(System.currentTimeMillis())
        dao.deleteForItemExcept(dto.id, days)
        dao.insertAll(dto.completionEntities(ts))
    }

    companion object {
        private const val TAG = "SyncEventClient"
    }
}
