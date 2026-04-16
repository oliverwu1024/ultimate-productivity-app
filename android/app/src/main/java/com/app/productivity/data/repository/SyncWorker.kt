package com.app.productivity.data.repository

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.app.productivity.data.local.AppDatabase
import com.app.productivity.data.remote.RetrofitClient
import com.app.productivity.util.TokenManager

class SyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val tokenManager = TokenManager(applicationContext)
        val api = RetrofitClient.create(tokenManager)
        val db = AppDatabase.getInstance(applicationContext)

        val syncManager = SyncManager(
            sleepRepo = SleepRepository(db.sleepDao(), api),
            sessionRepo = SessionRepository(db.sessionDao(), api),
            calendarRepo = CalendarRepository(db.calendarEventDao(), api)
        )

        return try {
            syncManager.syncAll().getOrThrow()
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }
}
