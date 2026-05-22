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
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

class UltiqApp : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var syncEventClient: SyncEventClient

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
                }
            }
        )
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
        val db = AppDatabase.getInstance(this)
        syncEventClient = SyncEventClient(
            tokenManager = TokenManager(this),
            calendarDao = db.calendarEventDao(),
            checklistDao = db.checklistDao(),
            sleepDao = db.sleepDao(),
            sessionDao = db.sessionDao(),
            alarmDao = db.alarmDao(),
            alarmScheduler = AlarmScheduler(this),
            wakeScheduler = com.ultiq.app.alarm.WakeAlarmScheduler(this),
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
