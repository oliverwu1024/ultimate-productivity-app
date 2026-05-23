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

        val syncManager = SyncManager(
            sleepRepo = SleepRepository(
                sleepDao = db.sleepDao(),
                apiService = api,
                sleepAudioEventDao = db.sleepAudioEventDao(),
            ),
            sessionRepo = SessionRepository(db.sessionDao(), api),
            calendarRepo = CalendarRepository(db.calendarEventDao(), api, AlarmScheduler(applicationContext)),
            alarmRepo = AlarmRepository(applicationContext, db.alarmDao(), api),
        )

        return try {
            syncManager.syncAll().getOrThrow()
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }
}
