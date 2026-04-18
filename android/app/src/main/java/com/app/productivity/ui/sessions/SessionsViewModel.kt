package com.app.productivity.ui.sessions

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.app.productivity.data.local.AppDatabase
import com.app.productivity.data.local.entity.SessionEntity
import com.app.productivity.data.remote.RetrofitClient
import com.app.productivity.data.repository.SessionRepository
import com.app.productivity.data.achievements.AchievementChecker
import com.app.productivity.util.PhoneUsageTracker
import com.app.productivity.util.TokenManager
import com.app.productivity.util.UserPreferences
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

enum class TimerState { IDLE, RUNNING, PAUSED, BREAK, FINISHED }
enum class Phase { WORK, BREAK }

data class TodayStats(
    val totalFocusMinutes: Int,
    val sessionsCompleted: Int,
    val currentStreak: Int,
    val phonePickupsToday: Int,
)

data class SessionsUiState(
    val timerState: TimerState = TimerState.IDLE,
    val currentPhase: Phase = Phase.WORK,
    val timeRemainingSeconds: Int = 0,
    val totalTimeSeconds: Int = 0,
    val workDuration: Int = 25,
    val breakDuration: Int = 5,
    val tag: String = "",
    val completedPomodoros: Int = 0,
    val phonePickups: Int = 0,
    val todayStats: TodayStats? = null,
    val recentSessions: List<SessionEntity> = emptyList(),
    val hasUsagePermission: Boolean = false,
    val error: String? = null,
)

class SessionsViewModel(application: Application) : AndroidViewModel(application) {
    private val tokenManager = TokenManager(application)
    private val api = RetrofitClient.create(tokenManager)
    private val db = AppDatabase.getInstance(application)
    private val sessionDao = db.sessionDao()
    private val userPreferences = UserPreferences(application)
    private val achievementChecker = AchievementChecker(
        db.achievementDao(), db.sleepDao(), db.sessionDao(), userPreferences,
    )
    private val repository = SessionRepository(sessionDao, api, achievementChecker)

    private val usageTracker = PhoneUsageTracker(application)

    private val _uiState = MutableStateFlow(SessionsUiState())
    val uiState: StateFlow<SessionsUiState> = _uiState

    private var userId: String = ""
    private var timerJob: Job? = null
    private var pickupPollJob: Job? = null
    private var currentSessionId: String? = null

    init {
        _uiState.value = _uiState.value.copy(hasUsagePermission = usageTracker.hasPermission())
        viewModelScope.launch {
            userId = tokenManager.getUserId().firstOrNull() ?: ""
            val settings = userPreferences.snapshot()
            _uiState.value = _uiState.value.copy(
                workDuration = settings.defaultWorkDuration,
                breakDuration = settings.defaultBreakDuration,
            )
            loadSessionsAndStats()
            sync()
        }
    }

    fun checkUsagePermission() {
        _uiState.value = _uiState.value.copy(hasUsagePermission = usageTracker.hasPermission())
    }

    fun openUsageSettings() {
        usageTracker.openPermissionSettings()
    }

    private fun loadSessionsAndStats() {
        viewModelScope.launch {
            repository.getAllSessions().collect { allSessions ->
                val recent = allSessions.filter { it.completed }.take(10)

                val zone = ZoneId.systemDefault()
                val todayStart = LocalDate.now().atStartOfDay(zone).toInstant().toEpochMilli()
                val todayEnd = todayStart + 86_400_000 - 1

                val todayCompleted = allSessions.filter {
                    it.completed && it.startedAt in todayStart..todayEnd
                }

                val dates = sessionDao.getCompletedSessionDates(0, todayEnd)
                val streak = computeStreak(dates, todayStart)

                _uiState.value = _uiState.value.copy(
                    recentSessions = recent,
                    todayStats = TodayStats(
                        totalFocusMinutes = todayCompleted.sumOf { it.durationMinutes },
                        sessionsCompleted = todayCompleted.size,
                        currentStreak = streak,
                        phonePickupsToday = todayCompleted.sumOf { it.phonePickups }
                    )
                )
            }
        }
    }

    fun updateTag(tag: String) {
        _uiState.value = _uiState.value.copy(tag = tag)
    }

    fun updateWorkDuration(minutes: Int) {
        _uiState.value = _uiState.value.copy(workDuration = minutes.coerceIn(5, 60))
    }

    fun updateBreakDuration(minutes: Int) {
        _uiState.value = _uiState.value.copy(breakDuration = minutes.coerceIn(1, 30))
    }

    fun startSession() {
        val state = _uiState.value
        if (state.tag.isBlank()) {
            _uiState.value = state.copy(error = "Enter a tag to start")
            return
        }

        _uiState.value = state.copy(
            timerState = TimerState.RUNNING,
            currentPhase = Phase.WORK,
            timeRemainingSeconds = state.workDuration * 60,
            totalTimeSeconds = state.workDuration * 60,
            completedPomodoros = 0,
            phonePickups = 0,
        )

        viewModelScope.launch {
            val result = repository.createSession(
                state.tag.trim(), state.workDuration, state.breakDuration, userId
            )
            result.onSuccess { session ->
                currentSessionId = session.id
            }.onFailure {
                _uiState.value = _uiState.value.copy(error = it.message)
            }
        }

        startTimer()
        startPickupPolling()
    }

    fun pauseTimer() {
        timerJob?.cancel()
        _uiState.value = _uiState.value.copy(timerState = TimerState.PAUSED)
    }

    fun resumeTimer() {
        _uiState.value = _uiState.value.copy(
            timerState = if (_uiState.value.currentPhase == Phase.WORK) TimerState.RUNNING else TimerState.BREAK
        )
        startTimer()
    }

    fun skipBreak() {
        timerJob?.cancel()
        val state = _uiState.value
        _uiState.value = state.copy(
            currentPhase = Phase.WORK,
            timerState = TimerState.RUNNING,
            timeRemainingSeconds = state.workDuration * 60,
            totalTimeSeconds = state.workDuration * 60,
        )
        startTimer()
    }

    fun cancelSession() {
        timerJob?.cancel()
        pickupPollJob?.cancel()
        currentSessionId?.let { id ->
            viewModelScope.launch { repository.deleteSession(id) }
        }
        currentSessionId = null
        _uiState.value = _uiState.value.copy(
            timerState = TimerState.IDLE,
            currentPhase = Phase.WORK,
            timeRemainingSeconds = 0,
            totalTimeSeconds = 0,
            completedPomodoros = 0,
            phonePickups = 0,
        )
    }

    fun completeSession() {
        timerJob?.cancel()
        pickupPollJob?.cancel()
        val state = _uiState.value

        // Count full pomodoros + partial work time
        val partialMinutes = if (state.currentPhase == Phase.WORK) {
            (state.totalTimeSeconds - state.timeRemainingSeconds) / 60
        } else {
            0
        }
        val totalMinutes = state.completedPomodoros * state.workDuration + partialMinutes

        viewModelScope.launch {
            currentSessionId?.let { id ->
                repository.completeSession(id, totalMinutes, state.phonePickups)
            }
            currentSessionId = null
            _uiState.value = _uiState.value.copy(
                timerState = TimerState.IDLE,
                currentPhase = Phase.WORK,
                timeRemainingSeconds = 0,
                totalTimeSeconds = 0,
                completedPomodoros = 0,
                phonePickups = 0,
            )
        }
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    private fun startTimer() {
        timerJob?.cancel()
        timerJob = viewModelScope.launch {
            while (_uiState.value.timeRemainingSeconds > 0) {
                delay(1000)
                _uiState.value = _uiState.value.copy(
                    timeRemainingSeconds = _uiState.value.timeRemainingSeconds - 1
                )
            }
            onTimerFinished()
        }
    }

    private fun startPickupPolling() {
        pickupPollJob?.cancel()
        pickupPollJob = viewModelScope.launch {
            var lastPollTime = System.currentTimeMillis()
            while (true) {
                delay(30_000)
                val newPickups = usageTracker.getPickupsSince(lastPollTime)
                lastPollTime = System.currentTimeMillis()
                if (newPickups > 0) {
                    _uiState.value = _uiState.value.copy(
                        phonePickups = _uiState.value.phonePickups + newPickups
                    )
                }
            }
        }
    }

    private fun onTimerFinished() {
        val state = _uiState.value
        if (state.currentPhase == Phase.WORK) {
            _uiState.value = state.copy(
                currentPhase = Phase.BREAK,
                timerState = TimerState.BREAK,
                completedPomodoros = state.completedPomodoros + 1,
                timeRemainingSeconds = state.breakDuration * 60,
                totalTimeSeconds = state.breakDuration * 60,
            )
            startTimer()
        } else {
            _uiState.value = state.copy(
                currentPhase = Phase.WORK,
                timerState = TimerState.RUNNING,
                timeRemainingSeconds = state.workDuration * 60,
                totalTimeSeconds = state.workDuration * 60,
            )
            startTimer()
        }
    }

    private fun sync() {
        viewModelScope.launch {
            try { repository.sync() } catch (_: Exception) { }
        }
    }

    private fun computeStreak(dateDayMillis: List<Long>, todayStartMillis: Long): Int {
        if (dateDayMillis.isEmpty()) return 0

        val todayDay = todayStartMillis / 86_400_000
        val days = dateDayMillis.map { it / 86_400_000 }

        if (days.first() != todayDay) return 0

        var streak = 1
        for (i in 1 until days.size) {
            if (days[i - 1] - days[i] == 1L) {
                streak++
            } else {
                break
            }
        }
        return streak
    }
}
