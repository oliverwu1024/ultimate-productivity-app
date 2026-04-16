package com.app.productivity.data.repository

class SyncManager(
    private val sleepRepo: SleepRepository,
    private val sessionRepo: SessionRepository,
    private val calendarRepo: CalendarRepository,
) {
    suspend fun syncAll(): Result<Unit> {
        return runCatching {
            sleepRepo.sync()
            sessionRepo.sync()
            calendarRepo.sync()
        }
    }
}
