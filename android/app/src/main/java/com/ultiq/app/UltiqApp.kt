package com.ultiq.app

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.ultiq.app.data.local.AppDatabase
import com.ultiq.app.data.remote.SyncEventClient
import com.ultiq.app.data.repository.SleepAudioUploadWorker
import com.ultiq.app.data.repository.SyncWorker
import com.ultiq.app.util.AlarmScheduler
import com.ultiq.app.util.NotificationHelper
import com.ultiq.app.util.ReminderPreferences
import com.ultiq.app.util.UserPreferences
import com.ultiq.app.util.TokenManager
import com.ultiq.app.util.UpdateChecker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

class UltiqApp : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // Exposed so AuthViewModel can (a) trigger a blocking syncAll() right
    // after login lands a token — otherwise the first frame of Dashboard
    // would race the lazy per-screen fetches and render empty state — and
    // (b) tell SSE to (re)connect, since the lifecycle-driven connect()
    // already ran at app start when there was no token and never retries
    // on its own when one arrives later. Bug from 2026-05-24: fresh-install
    // login showed empty screens until the user closed and reopened the app.
    lateinit var syncManager: com.ultiq.app.data.repository.SyncManager
        private set
    lateinit var syncEventClient: SyncEventClient
        private set

    override fun onCreate() {
        super.onCreate()
        NotificationHelper.ensureChannels(this)
        scheduleSyncWork()
        applyReminderSchedules()
        wireRealtimeSync()
        // Run the update check now and on every subsequent foreground.
        UpdateChecker.checkOnce(this@UltiqApp)
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) {
                    UpdateChecker.checkOnce(this@UltiqApp)
                    // §v2.16.3 — Kick the audio-upload worker on foreground.
                    // After many failures yesterday, WorkManager backoff can
                    // push the next retry many minutes out. If the user has
                    // any audio events still pending upload, force a fresh
                    // pass instead of waiting for the scheduled retry — the
                    // worker is a no-op when there's nothing to do.
                    pulseAudioUploadWorker()
                    // §tz Phase B — if the device timezone changed since last
                    // sync (e.g. the user travelled), push it so the server's
                    // AI/scheduler math stops running on a stale zone — no
                    // logout/login needed.
                    syncTimezoneIfChanged()
                    // Reflect any background changes on the home-screen widgets
                    // when the app returns to foreground.
                    refreshWidgets()
                }

                override fun onStop(owner: LifecycleOwner) {
                    // Reflect anything the user just did in-app on the widgets as
                    // they leave (Glance doesn't observe Room itself).
                    refreshWidgets()
                }
            }
        )
    }

    /** Push a fresh snapshot to all home-screen widgets off the main thread. */
    private fun refreshWidgets() {
        appScope.launch {
            runCatching { com.ultiq.app.ui.widget.WidgetUpdater.updateAll(this@UltiqApp) }
        }
    }

    /**
     * Re-sync `users.timezone` when the device zone changed since we last
     * pushed it. Only fires when logged in; a no-op (no network) otherwise.
     */
    private fun syncTimezoneIfChanged() {
        appScope.launch {
            val tokenManager = TokenManager(this@UltiqApp)
            val token = tokenManager.getToken().firstOrNull()
            if (token.isNullOrBlank()) return@launch
            val prefs = UserPreferences(this@UltiqApp)
            val currentTz = java.time.ZoneId.systemDefault().id
            if (currentTz == prefs.getLastSyncedTimezone()) return@launch
            val api = com.ultiq.app.data.remote.RetrofitClient.create(tokenManager)
            runCatching {
                api.updateProfile(
                    com.ultiq.app.data.remote.dto.UpdateProfileRequest(timezone = currentTz)
                )
                prefs.setLastSyncedTimezone(currentTz)
            }
        }
    }

    private fun pulseAudioUploadWorker() {
        appScope.launch {
            val dao = AppDatabase.getInstance(this@UltiqApp).sleepAudioEventDao()
            val pending = try { dao.countUnsynced() } catch (_: Throwable) { 0 }
            if (pending > 0) {
                SleepAudioUploadWorker.enqueue(this@UltiqApp, replace = true)
            }
        }
    }

    private fun scheduleSyncWork() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val syncRequest = PeriodicWorkRequestBuilder<SyncWorker>(
            30, TimeUnit.MINUTES
        ).setConstraints(constraints).build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "periodic_sync",
            ExistingPeriodicWorkPolicy.KEEP,
            syncRequest
        )
    }

    private fun applyReminderSchedules() {
        appScope.launch {
            val settings = ReminderPreferences(this@UltiqApp).snapshot()
            val targetBedtime = UserPreferences(this@UltiqApp).snapshot().targetBedtime
            AlarmScheduler(this@UltiqApp).applyDailyReminders(settings, targetBedtime)
        }
    }

    /**
     * Connect SSE while the app is in the foreground; disconnect when backgrounded
     * to save battery. Offline catch-up is handled by SyncManager + the delete
     * reconciliation in each repository's sync().
     */
    private fun wireRealtimeSync() {
        android.util.Log.i("UltiqApp", "wireRealtimeSync() — initialising SSE client")
        val db = AppDatabase.getInstance(this)
        val tokenManager = TokenManager(this)
        val api = com.ultiq.app.data.remote.RetrofitClient.create(tokenManager)
        // v2.13.4 — Built here so SyncEventClient.onOpen can trigger a full
        // catch-up sync. Closes the ~15 s cold-start gap where events
        // added on web between app foreground and SSE handshake never
        // made it to local Room.
        syncManager = com.ultiq.app.data.repository.SyncManager(
            sleepRepo = com.ultiq.app.data.repository.SleepRepository(
                sleepDao = db.sleepDao(),
                apiService = api,
                sleepAudioEventDao = db.sleepAudioEventDao(),
            ),
            sessionRepo = com.ultiq.app.data.repository.SessionRepository(db.sessionDao(), api),
            calendarRepo = com.ultiq.app.data.repository.CalendarRepository(
                db.calendarEventDao(),
                api,
                AlarmScheduler(this),
            ),
            alarmRepo = com.ultiq.app.data.repository.AlarmRepository(this, db.alarmDao(), api),
            checklistRepo = com.ultiq.app.data.repository.ChecklistRepository(
                db.checklistDao(),
                db.checklistCompletionDao(),
                api,
                database = db,
            ),
        )
        syncEventClient = SyncEventClient(
            tokenManager = tokenManager,
            calendarDao = db.calendarEventDao(),
            checklistDao = db.checklistDao(),
            checklistCompletionDao = db.checklistCompletionDao(),
            sleepDao = db.sleepDao(),
            sessionDao = db.sessionDao(),
            alarmDao = db.alarmDao(),
            alarmScheduler = AlarmScheduler(this),
            wakeScheduler = com.ultiq.app.alarm.WakeAlarmScheduler(this),
            onConnected = {
                android.util.Log.i("UltiqApp", "SSE onConnected — running catch-up syncAll()")
                syncManager.syncAll()
            },
        )
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) {
                    syncEventClient.connect()
                }
                override fun onStop(owner: LifecycleOwner) {
                    syncEventClient.disconnect()
                }
            }
        )
    }
}
