package com.ultiq.app.ui.sessions

import android.app.Application
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ultiq.app.data.local.AppDatabase
import com.ultiq.app.data.local.entity.ChecklistEntity
import com.ultiq.app.data.local.entity.SessionEntity
import com.ultiq.app.data.remote.RetrofitClient
import com.ultiq.app.data.repository.ChecklistRepository
import com.ultiq.app.data.repository.SessionRepository
import com.ultiq.app.data.achievements.AchievementChecker
import com.ultiq.app.data.achievements.AchievementEvents
import com.ultiq.app.data.achievements.AchievementId
import com.ultiq.app.service.FocusTrackingService
import com.ultiq.app.service.SleepTrackingService
import com.ultiq.app.util.PhoneUsageTracker
import com.ultiq.app.util.TokenManager
import com.ultiq.app.util.UserPreferences
import com.ultiq.app.util.toUserMessage
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
    val openChecklistItems: List<ChecklistEntity> = emptyList(),
    val selectedChecklistItemId: String? = null,
    val completionPrompt: ChecklistCompletionPrompt? = null,
    val celebratedAchievement: AchievementId? = null,
)

data class ChecklistCompletionPrompt(
    val itemId: String,
    val title: String,
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
    private val checklistRepository = ChecklistRepository(db.checklistDao(), api)

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
            observeTodayChecklist()
            sync()
        }
        observeAchievementEvents()
    }

    private fun observeAchievementEvents() {
        viewModelScope.launch {
            AchievementEvents.newlyEarned.collect { earned ->
                earned.firstOrNull()?.let { id ->
                    _uiState.value = _uiState.value.copy(celebratedAchievement = id)
                }
            }
        }
    }

    fun dismissAchievementCelebration() {
        _uiState.value = _uiState.value.copy(celebratedAchievement = null)
    }

    private fun observeTodayChecklist() {
        viewModelScope.launch {
            val todayEpochDay = LocalDate.now().toEpochDay()
            android.util.Log.d("SessionsViewModel", "observing checklist for epochDay=$todayEpochDay")
            checklistRepository.getOpenForDate(todayEpochDay).collect { items ->
                android.util.Log.d("SessionsViewModel", "checklist update: ${items.size} open item(s) for today")
                _uiState.value = _uiState.value.copy(openChecklistItems = items)
            }
        }
    }

    fun selectChecklistItem(item: ChecklistEntity?) {
        _uiState.value = _uiState.value.copy(
            selectedChecklistItemId = item?.id,
            tag = item?.title ?: _uiState.value.tag,
        )
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
                val recent = allSessions.filter { it.completed }

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
        // Manual typing clears any previously selected checklist item.
        _uiState.value = _uiState.value.copy(
            tag = tag,
            selectedChecklistItemId = null,
        )
    }

    fun updateWorkDuration(minutes: Int) {
        _uiState.value = _uiState.value.copy(workDuration = minutes.coerceIn(5, 240))
    }

    fun updateBreakDuration(minutes: Int) {
        // 0 = no break (timer chains straight back into another work block on phase rollover).
        _uiState.value = _uiState.value.copy(breakDuration = minutes.coerceIn(0, 60))
    }

    fun startSession() {
        val state = _uiState.value
        if (state.tag.isBlank()) {
            _uiState.value = state.copy(error = "Enter a tag to start")
            return
        }
        if (SleepTrackingService.isRunning.value) {
            _uiState.value = state.copy(
                error = "End your sleep session before starting a focus session",
            )
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
                tag = state.tag.trim(),
                workDuration = state.workDuration,
                breakDuration = state.breakDuration,
                userId = userId,
                checklistItemId = state.selectedChecklistItemId,
            )
            result.onSuccess { session ->
                currentSessionId = session.id
            }.onFailure {
                _uiState.value = _uiState.value.copy(error = it.toUserMessage("Couldn't start session. Try again."))
            }
        }

        startFocusTrackingService()
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

    fun deletePastSession(id: String) {
        viewModelScope.launch {
            repository.deleteSession(id)
        }
    }

    fun cancelSession() {
        timerJob?.cancel()
        pickupPollJob?.cancel()
        stopFocusTrackingService()
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
        stopFocusTrackingService()
        val state = _uiState.value

        // Count full pomodoros + partial work time
        val partialMinutes = if (state.currentPhase == Phase.WORK) {
            (state.totalTimeSeconds - state.timeRemainingSeconds) / 60
        } else {
            0
        }
        val totalMinutes = state.completedPomodoros * state.workDuration + partialMinutes
        val linkedItemId = state.selectedChecklistItemId
        val linkedItemTitle = state.openChecklistItems.firstOrNull { it.id == linkedItemId }?.title

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
                selectedChecklistItemId = null,
                completionPrompt = if (linkedItemId != null && linkedItemTitle != null) {
                    ChecklistCompletionPrompt(itemId = linkedItemId, title = linkedItemTitle)
                } else null,
            )
        }
    }

    fun confirmChecklistCompletion() {
        val prompt = _uiState.value.completionPrompt ?: return
        viewModelScope.launch {
            checklistRepository.markCompleted(prompt.itemId)
            _uiState.value = _uiState.value.copy(completionPrompt = null)
        }
    }

    fun dismissChecklistCompletion() {
        _uiState.value = _uiState.value.copy(completionPrompt = null)
    }

    private fun startFocusTrackingService() {
        val app = getApplication<Application>()
        val intent = Intent(app, FocusTrackingService::class.java)
        ContextCompat.startForegroundService(app, intent)
    }

    private fun stopFocusTrackingService() {
        val app = getApplication<Application>()
        app.stopService(Intent(app, FocusTrackingService::class.java))
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
            // Always credit the completed pomodoro before deciding what's next.
            val completed = state.completedPomodoros + 1
            if (state.breakDuration <= 0) {
                // No break configured — chain straight into another work block.
                _uiState.value = state.copy(
                    currentPhase = Phase.WORK,
                    timerState = TimerState.RUNNING,
                    completedPomodoros = completed,
                    timeRemainingSeconds = state.workDuration * 60,
                    totalTimeSeconds = state.workDuration * 60,
                )
            } else {
                _uiState.value = state.copy(
                    currentPhase = Phase.BREAK,
                    timerState = TimerState.BREAK,
                    completedPomodoros = completed,
                    timeRemainingSeconds = state.breakDuration * 60,
                    totalTimeSeconds = state.breakDuration * 60,
                )
            }
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
