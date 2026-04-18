package com.app.productivity.ui.dashboard

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.app.productivity.data.local.AppDatabase
import com.app.productivity.data.local.entity.CalendarEventEntity
import com.app.productivity.data.remote.RetrofitClient
import com.app.productivity.data.repository.CalendarRepository
import com.app.productivity.data.repository.SessionRepository
import com.app.productivity.data.repository.SleepRepository
import com.app.productivity.data.repository.SyncManager
import com.app.productivity.util.AlarmScheduler
import com.app.productivity.util.TokenManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class SleepSummary(
    val duration: String,
    val quality: Int,
    val vsTarget: String,
    val phonePickups: Int,
)

data class FocusSummary(
    val totalMinutesToday: Int,
    val sessionsToday: Int,
    val currentStreak: Int,
    val phonePickupsToday: Int,
)

data class WeeklyHighlights(
    val avgSleepDuration: String,
    val avgSleepQuality: Double,
    val totalFocusHours: Double,
    val eventsCompleted: Int,
    val eventsTotal: Int,
)

data class DashboardUiState(
    val lastNightSleep: SleepSummary? = null,
    val todayFocus: FocusSummary? = null,
    val upcomingEvents: List<CalendarEventEntity> = emptyList(),
    val weeklyHighlights: WeeklyHighlights? = null,
    val isLoading: Boolean = false,
    val isSyncing: Boolean = false,
    val lastSyncTime: Long = 0L,
)

class DashboardViewModel(application: Application) : AndroidViewModel(application) {
    private val tokenManager = TokenManager(application)
    private val api = RetrofitClient.create(tokenManager)
    private val db = AppDatabase.getInstance(application)
    private val sleepDao = db.sleepDao()
    private val sessionDao = db.sessionDao()
    private val sleepRepo = SleepRepository(sleepDao, api)
    private val sessionRepo = SessionRepository(sessionDao, api)
    private val calendarRepo = CalendarRepository(db.calendarEventDao(), api, AlarmScheduler(application))
    private val syncManager = SyncManager(sleepRepo, sessionRepo, calendarRepo)

    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = _uiState

    init {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            sync()
            loadAll()
            _uiState.value = _uiState.value.copy(isLoading = false)
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSyncing = true)
            sync()
            loadAll()
            _uiState.value = _uiState.value.copy(isSyncing = false)
        }
    }

    private suspend fun loadAll() {
        loadSleepSummary()
        loadFocusSummary()
        loadUpcomingEvents()
        loadWeeklyHighlights()
    }

    private suspend fun loadSleepSummary() {
        val now = System.currentTimeMillis()
        val lookback = now - 48 * 3600_000L
        val records = sleepDao.getRecordsBetween(lookback, now).firstOrNull() ?: emptyList()
        val last = records.firstOrNull()
        _uiState.value = _uiState.value.copy(
            lastNightSleep = last?.let {
                val durationMs = it.actualWakeTime - it.actualBedtime
                val durationMins = (durationMs / 60_000).toInt()

                // Target duration
                val bedParts = it.targetBedtime.split(":")
                val wakeParts = it.targetWakeTime.split(":")
                val bedSecs = bedParts[0].toInt() * 3600 + bedParts[1].toInt() * 60
                val wakeSecs = wakeParts[0].toInt() * 3600 + wakeParts[1].toInt() * 60
                val targetSecs = if (wakeSecs >= bedSecs) wakeSecs - bedSecs else 86400 + wakeSecs - bedSecs
                val targetMins = targetSecs / 60
                val diffMins = durationMins - targetMins

                SleepSummary(
                    duration = formatDuration(durationMins),
                    quality = it.qualityRating,
                    vsTarget = if (diffMins >= 0) "+${formatDuration(diffMins)}" else "-${formatDuration(-diffMins)}",
                    phonePickups = it.phonePickups
                )
            }
        )
    }

    private suspend fun loadFocusSummary() {
        val zone = ZoneId.systemDefault()
        val todayStart = LocalDate.now().atStartOfDay(zone).toInstant().toEpochMilli()
        val todayEnd = todayStart + 86_400_000 - 1

        val sessions = sessionDao.getSessionsBetween(todayStart, todayEnd).firstOrNull() ?: emptyList()
        val completed = sessions.filter { it.completed }

        val dates = sessionDao.getCompletedSessionDates(0, todayEnd)
        val streak = computeStreak(dates, todayStart)

        _uiState.value = _uiState.value.copy(
            todayFocus = FocusSummary(
                totalMinutesToday = completed.sumOf { it.durationMinutes },
                sessionsToday = completed.size,
                currentStreak = streak,
                phonePickupsToday = completed.sumOf { it.phonePickups }
            )
        )
    }

    private suspend fun loadUpcomingEvents() {
        val today = LocalDate.now()
        val events = calendarRepo.getEventsForRange(today, today.plusDays(7)).firstOrNull() ?: emptyList()
        val now = System.currentTimeMillis()
        _uiState.value = _uiState.value.copy(
            upcomingEvents = events.filter { it.startTime >= now }.take(5)
        )
    }

    private suspend fun loadWeeklyHighlights() {
        val now = System.currentTimeMillis()
        val weekAgo = now - 7 * 86_400_000L
        val today = LocalDate.now()

        // Sleep
        val sleepRecords = sleepDao.getRecordsBetween(weekAgo, now).firstOrNull() ?: emptyList()
        val avgSleepMins = if (sleepRecords.isEmpty()) 0.0
        else sleepRecords.map { (it.actualWakeTime - it.actualBedtime).toDouble() / 60_000 }.average()
        val avgQuality = if (sleepRecords.isEmpty()) 0.0
        else sleepRecords.map { it.qualityRating.toDouble() }.average()

        // Sessions
        val sessions = sessionDao.getSessionsBetween(weekAgo, now).firstOrNull() ?: emptyList()
        val totalFocusMins = sessions.filter { it.completed }.sumOf { it.durationMinutes }

        // Events
        val events = calendarRepo.getEventsForRange(today.minusDays(7), today).firstOrNull() ?: emptyList()
        val eventsCompleted = events.count { it.endTime < now }

        _uiState.value = _uiState.value.copy(
            weeklyHighlights = WeeklyHighlights(
                avgSleepDuration = if (sleepRecords.isEmpty()) "-" else formatDuration(avgSleepMins.toInt()),
                avgSleepQuality = avgQuality,
                totalFocusHours = totalFocusMins / 60.0,
                eventsCompleted = eventsCompleted,
                eventsTotal = events.size
            )
        )
    }

    private suspend fun sync() {
        try {
            syncManager.syncAll().getOrThrow()
            _uiState.value = _uiState.value.copy(lastSyncTime = System.currentTimeMillis())
        } catch (_: Exception) { }
    }

    private fun computeStreak(dateDayMillis: List<Long>, todayStartMillis: Long): Int {
        if (dateDayMillis.isEmpty()) return 0
        val todayDay = todayStartMillis / 86_400_000
        val days = dateDayMillis.map { it / 86_400_000 }
        if (days.first() != todayDay) return 0
        var streak = 1
        for (i in 1 until days.size) {
            if (days[i - 1] - days[i] == 1L) streak++ else break
        }
        return streak
    }

    private fun formatDuration(minutes: Int): String {
        val h = minutes / 60
        val m = minutes % 60
        return if (h > 0) "${h}h ${m}m" else "${m}m"
    }
}
