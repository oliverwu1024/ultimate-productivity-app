package com.ultiq.app.data.remote

import android.util.Log
import com.ultiq.app.BuildConfig
import com.ultiq.app.data.local.dao.CalendarEventDao
import com.ultiq.app.data.local.dao.ChecklistDao
import com.ultiq.app.data.remote.dto.SyncEvent
import com.ultiq.app.data.remote.dto.parseSyncEvent
import com.ultiq.app.data.remote.dto.toEntity
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
    private val alarmScheduler: AlarmScheduler? = null,
) {
    private val scope = CoroutineScope(Dispatchers.IO)
    private var eventSource: EventSource? = null
    private var reconnectJob: Job? = null
    @Volatile private var wantConnected = false

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        // SSE: server keeps the response open. Don't time out reads.
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    fun connect() {
        wantConnected = true
        reconnectJob?.cancel()
        scope.launch {
            doConnect()
        }
    }

    fun disconnect() {
        wantConnected = false
        reconnectJob?.cancel()
        eventSource?.cancel()
        eventSource = null
    }

    private suspend fun doConnect() {
        val token = tokenManager.getToken().first()
        if (token.isNullOrBlank()) {
            // Logged out — nothing to subscribe to. Reconnect attempt will happen
            // when the app is in foreground after login.
            return
        }
        val request = Request.Builder()
            .url("${BuildConfig.API_BASE_URL.trimEnd('/')}/sync/events")
            .header("Authorization", "Bearer $token")
            .header("Accept", "text/event-stream")
            .build()
        val factory = EventSources.createFactory(client)
        eventSource = factory.newEventSource(request, listener)
    }

    private val listener = object : EventSourceListener() {
        override fun onOpen(eventSource: EventSource, response: Response) {
            Log.d(TAG, "SSE connected: ${response.code}")
        }

        override fun onEvent(
            eventSource: EventSource,
            id: String?,
            type: String?,
            data: String,
        ) {
            val event = parseSyncEvent(data) ?: return
            scope.launch { applyEvent(event) }
        }

        override fun onFailure(
            eventSource: EventSource,
            t: Throwable?,
            response: Response?,
        ) {
            Log.w(TAG, "SSE failure: code=${response?.code} ${t?.message}")
            // Reconnect with backoff if the user is still meant to be connected.
            if (wantConnected) {
                reconnectJob?.cancel()
                reconnectJob = scope.launch {
                    delay(5_000)
                    doConnect()
                }
            }
        }

        override fun onClosed(eventSource: EventSource) {
            Log.d(TAG, "SSE closed")
        }
    }

    private suspend fun applyEvent(event: SyncEvent) {
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
                checklistDao.insert(event.item.toEntity())
            }
            is SyncEvent.ChecklistUpdated -> {
                checklistDao.insert(event.item.toEntity())
            }
            is SyncEvent.ChecklistDeleted -> {
                checklistDao.deleteById(event.id)
            }
        }
    }

    companion object {
        private const val TAG = "SyncEventClient"
    }
}
