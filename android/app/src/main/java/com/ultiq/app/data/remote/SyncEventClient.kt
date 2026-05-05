package com.ultiq.app.data.remote

import android.util.Log
import com.ultiq.app.BuildConfig
import com.ultiq.app.data.local.dao.CalendarEventDao
import com.ultiq.app.data.local.dao.ChecklistDao
import com.ultiq.app.data.local.dao.SessionDao
import com.ultiq.app.data.local.dao.SleepDao
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
    private val sleepDao: SleepDao,
    private val sessionDao: SessionDao,
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
            Log.d(TAG, "SSE recv: ${data.take(160)}${if (data.length > 160) "…" else ""}")
            val event = parseSyncEvent(data)
            if (event == null) {
                Log.w(TAG, "SSE parse failed for: ${data.take(200)}")
                return
            }
            Log.d(TAG, "SSE parsed: ${event.javaClass.simpleName}")
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
        try {
            when (event) {
                is SyncEvent.CalendarCreated -> {
                    val entity = event.event.toEntity()
                    Log.d(TAG, "applyEvent: CalendarCreated id=${entity.id} title='${entity.title}' startTime=${entity.startTime}")
                    calendarDao.insert(entity)
                    alarmScheduler?.scheduleEventReminder(entity)
                }
                is SyncEvent.CalendarUpdated -> {
                    val entity = event.event.toEntity()
                    Log.d(TAG, "applyEvent: CalendarUpdated id=${entity.id}")
                    calendarDao.insert(entity)
                    alarmScheduler?.cancelEventReminder(entity.id)
                    alarmScheduler?.scheduleEventReminder(entity)
                }
                is SyncEvent.CalendarDeleted -> {
                    Log.d(TAG, "applyEvent: CalendarDeleted id=${event.id}")
                    calendarDao.deleteById(event.id)
                    alarmScheduler?.cancelEventReminder(event.id)
                }
                is SyncEvent.ChecklistCreated -> {
                    val entity = event.item.toEntity()
                    Log.d(TAG, "applyEvent: ChecklistCreated id=${entity.id}")
                    checklistDao.insert(entity)
                }
                is SyncEvent.ChecklistUpdated -> {
                    val entity = event.item.toEntity()
                    Log.d(TAG, "applyEvent: ChecklistUpdated id=${entity.id}")
                    checklistDao.insert(entity)
                }
                is SyncEvent.ChecklistDeleted -> {
                    Log.d(TAG, "applyEvent: ChecklistDeleted id=${event.id}")
                    checklistDao.deleteById(event.id)
                }
                is SyncEvent.SleepCreated -> {
                    val entity = event.record.toEntity()
                    Log.d(TAG, "applyEvent: SleepCreated id=${entity.id}")
                    sleepDao.insert(entity)
                }
                is SyncEvent.SleepUpdated -> {
                    val entity = event.record.toEntity()
                    Log.d(TAG, "applyEvent: SleepUpdated id=${entity.id}")
                    sleepDao.insert(entity)
                }
                is SyncEvent.SleepDeleted -> {
                    Log.d(TAG, "applyEvent: SleepDeleted id=${event.id}")
                    sleepDao.deleteById(event.id)
                }
                is SyncEvent.SessionCreated -> {
                    val entity = event.session.toEntity()
                    Log.d(TAG, "applyEvent: SessionCreated id=${entity.id}")
                    sessionDao.insert(entity)
                }
                is SyncEvent.SessionUpdated -> {
                    val entity = event.session.toEntity()
                    Log.d(TAG, "applyEvent: SessionUpdated id=${entity.id}")
                    sessionDao.insert(entity)
                }
                is SyncEvent.SessionDeleted -> {
                    Log.d(TAG, "applyEvent: SessionDeleted id=${event.id}")
                    sessionDao.deleteById(event.id)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "applyEvent failed for ${event.javaClass.simpleName}", e)
        }
    }

    companion object {
        private const val TAG = "SyncEventClient"
    }
}
