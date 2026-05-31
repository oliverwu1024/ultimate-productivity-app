package com.ultiq.app.data.repository

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.ultiq.app.data.local.AppDatabase
import com.ultiq.app.data.remote.RetrofitClient
import com.ultiq.app.util.AlarmScheduler
import com.ultiq.app.util.TokenManager

class SyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val tokenManager = TokenManager(applicationContext)
        val api = RetrofitClient.create(tokenManager)
        val db = AppDatabase.getInstance(applicationContext)
        // §v2.15.10 — Shared sync-state store so all three guarded
        // repositories see the same persistent empty-streak counter.
        val syncStateStore = SyncStateStore(applicationContext)

        val syncManager = SyncManager(
            sleepRepo = SleepRepository(
                sleepDao = db.sleepDao(),
                apiService = api,
                sleepAudioEventDao = db.sleepAudioEventDao(),
                syncStateStore = syncStateStore,
            ),
            sessionRepo = SessionRepository(db.sessionDao(), api),
            calendarRepo = CalendarRepository(
                db.calendarEventDao(),
                api,
                AlarmScheduler(applicationContext),
                syncStateStore = syncStateStore,
            ),
            alarmRepo = AlarmRepository(applicationContext, db.alarmDao(), api),
            checklistRepo = ChecklistRepository(
                db.checklistDao(),
                db.checklistCompletionDao(),
                api,
                syncStateStore = syncStateStore,
            ),
        )

        return try {
            syncManager.syncAll().getOrThrow()
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }
}
