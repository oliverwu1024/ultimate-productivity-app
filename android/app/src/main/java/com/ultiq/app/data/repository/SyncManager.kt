package com.ultiq.app.data.repository

class SyncManager(
    private val sleepRepo: SleepRepository,
    private val sessionRepo: SessionRepository,
    private val calendarRepo: CalendarRepository,
    private val alarmRepo: AlarmRepository,
) {
    suspend fun syncAll(): Result<Unit> {
        return runCatching {
            sleepRepo.sync()
            sessionRepo.sync()
            calendarRepo.sync()
            alarmRepo.sync()
        }
    }
}
