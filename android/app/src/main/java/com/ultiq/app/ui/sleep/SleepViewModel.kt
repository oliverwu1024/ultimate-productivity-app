package com.ultiq.app.ui.sleep

import android.app.Application
import android.content.Intent
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ultiq.app.data.local.AppDatabase
import com.ultiq.app.data.local.entity.SleepRecordEntity
import com.ultiq.app.data.remote.RetrofitClient
import com.ultiq.app.data.remote.dto.CreateSleepRecordDto
import com.ultiq.app.data.remote.dto.SleepStats
import com.ultiq.app.data.remote.dto.toLocalStats
import com.ultiq.app.data.repository.SleepRepository
import com.ultiq.app.data.achievements.AchievementChecker
import com.ultiq.app.data.achievements.AchievementEvents
import com.ultiq.app.data.achievements.AchievementId
import com.ultiq.app.service.FocusTrackingService
import com.ultiq.app.service.PickupEvent
import com.ultiq.app.service.SleepTrackingService
import com.ultiq.app.util.TokenManager
import com.ultiq.app.util.UserPreferences
import java.time.LocalTime
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

enum class TimeRange { WEEK, MONTH }

data class SleepUiState(
    val records: List<SleepRecordEntity> = emptyList(),
    val stats: SleepStats? = null,
    val isLoading: Boolean = false,
    val error: String? = null,
    val selectedTimeRange: TimeRange = TimeRange.WEEK,
    // Session
    val isSessionActive: Boolean = false,
    val sessionStartTime: Long = 0L,
    val pickupEvents: List<PickupEvent> = emptyList(),
    // Dialogs
    val showSetTargetDialog: Boolean = false,
    val showEndSleepDialog: Boolean = false,
    val showManualLogDialog: Boolean = false,
    // Snapshot of session data at end (for EndSleepDialog)
    val endedSessionStart: Long = 0L,
    val endedPickupEvents: List<PickupEvent> = emptyList(),
    // Per-session targets, captured when user confirms the pre-sleep dialog.
    val sessionTargetBedtime: LocalTime = LocalTime.now(),
    val sessionTargetWakeTime: LocalTime = LocalTime.now().plusHours(8),
    // Defaults for the manual-log dialog only.
    val targetBedtime: LocalTime = LocalTime.of(22, 0),
    val targetWakeTime: LocalTime = LocalTime.of(6, 0),
    // Earned moment — populated when an achievement unlocks during this session.
    val celebratedAchievement: AchievementId? = null,
)

class SleepViewModel(application: Application) : AndroidViewModel(application) {
    private val tokenManager = TokenManager(application)
    private val api = RetrofitClient.create(tokenManager)
    private val db = AppDatabase.getInstance(application)
    private val userPreferences = UserPreferences(application)
    private val achievementChecker = AchievementChecker(
        db.achievementDao(), db.sleepDao(), db.sessionDao(), userPreferences,
    )
    private val repository = SleepRepository(db.sleepDao(), api, achievementChecker)

    private val _uiState = MutableStateFlow(SleepUiState())
    val uiState: StateFlow<SleepUiState> = _uiState

    private var userId: String = ""
    private var recordsJob: Job? = null

    init {
        viewModelScope.launch {
            userId = tokenManager.getUserId().firstOrNull() ?: ""
            val settings = userPreferences.snapshot()
            _uiState.value = _uiState.value.copy(
                targetBedtime = settings.targetBedtime,
                targetWakeTime = settings.targetWakeTime,
            )
            observeServiceState()
            loadRecords()
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

    private fun observeServiceState() {
        viewModelScope.launch {
            combine(
                SleepTrackingService.isRunning,
                SleepTrackingService.sessionStartTime,
                SleepTrackingService.pickupEvents
            ) { running, startTime, events ->
                _uiState.value = _uiState.value.copy(
                    isSessionActive = running,
                    sessionStartTime = startTime,
                    pickupEvents = events
                )
            }.collect {}
        }
    }

    fun startSleepSession() {
        if (FocusTrackingService.isRunning.value) {
            _uiState.value = _uiState.value.copy(
                error = "End your focus session before starting sleep tracking",
            )
            return
        }
        // Show the per-session target dialog before actually starting tracking.
        _uiState.value = _uiState.value.copy(
            showSetTargetDialog = true,
            sessionTargetBedtime = LocalTime.now(),
            sessionTargetWakeTime = LocalTime.now().plusHours(8),
        )
    }

    fun dismissSetTargetDialog() {
        _uiState.value = _uiState.value.copy(showSetTargetDialog = false)
    }

    fun confirmStartSleepSession(targetWakeTime: LocalTime) {
        _uiState.value = _uiState.value.copy(
            showSetTargetDialog = false,
            sessionTargetBedtime = LocalTime.now(),
            sessionTargetWakeTime = targetWakeTime,
        )
        val context = getApplication<Application>()
        val intent = Intent(context, SleepTrackingService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    fun endSleepSession() {
        val context = getApplication<Application>()
        // Snapshot the data before stopping
        _uiState.value = _uiState.value.copy(
            endedSessionStart = SleepTrackingService.sessionStartTime.value,
            endedPickupEvents = SleepTrackingService.pickupEvents.value,
            showEndSleepDialog = true
        )
        context.stopService(Intent(context, SleepTrackingService::class.java))
    }

    fun saveSessionRecord(qualityRating: Int, notes: String?) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, showEndSleepDialog = false)

            val state = _uiState.value
            val bedtime = Instant.ofEpochMilli(state.endedSessionStart)
            val wakeTime = Instant.now()
            val totalPhoneSeconds = state.endedPickupEvents.sumOf { it.durationSeconds }
            val phoneMinutes = totalPhoneSeconds / 60

            val dto = CreateSleepRecordDto(
                target_bedtime = formatTargetTime(state.sessionTargetBedtime),
                target_wake_time = formatTargetTime(state.sessionTargetWakeTime),
                actual_bedtime = bedtime.toString(),
                actual_wake_time = wakeTime.toString(),
                quality_rating = qualityRating,
                phone_pickups = state.endedPickupEvents.size,
                total_phone_minutes = if (phoneMinutes > 0) phoneMinutes else null,
                notes = notes
            )

            val result = repository.createSleepRecord(dto, userId)
            _uiState.value = _uiState.value.copy(
                isLoading = false,
                error = result.exceptionOrNull()?.message
            )
        }
    }

    fun dismissEndSleepDialog() {
        _uiState.value = _uiState.value.copy(showEndSleepDialog = false)
    }

    fun showManualLog() {
        _uiState.value = _uiState.value.copy(showManualLogDialog = true)
    }

    fun hideManualLog() {
        _uiState.value = _uiState.value.copy(showManualLogDialog = false)
    }

    fun addManualRecord(record: CreateSleepRecordDto) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, showManualLogDialog = false)
            val result = repository.createSleepRecord(record, userId)
            _uiState.value = _uiState.value.copy(
                isLoading = false,
                error = result.exceptionOrNull()?.message
            )
        }
    }

    private fun loadRecords() {
        recordsJob?.cancel()
        recordsJob = viewModelScope.launch {
            val (start, end) = getRange(_uiState.value.selectedTimeRange)
            repository.getSleepRecordsBetween(start, end).collect { records ->
                _uiState.value = _uiState.value.copy(
                    records = records,
                    stats = records.toLocalStats()
                )
            }
        }
    }

    fun setTimeRange(range: TimeRange) {
        _uiState.value = _uiState.value.copy(selectedTimeRange = range)
        loadRecords()
    }

    fun deleteRecord(id: String) {
        viewModelScope.launch {
            repository.deleteSleepRecord(id)
        }
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    private fun sync() {
        viewModelScope.launch {
            try { repository.sync() } catch (_: Exception) { }
        }
    }

    private fun getRange(range: TimeRange): Pair<Long, Long> {
        val now = Instant.now()
        val start = when (range) {
            TimeRange.WEEK -> now.minus(7, ChronoUnit.DAYS).toEpochMilli()
            TimeRange.MONTH -> now.minus(30, ChronoUnit.DAYS).toEpochMilli()
        }
        // Open-ended upper bound so newly logged records (bedtime ≥ now-at-load) are picked up
        // by the active Room flow without needing the user to leave and return to the screen.
        return start to Long.MAX_VALUE
    }

    private fun formatTargetTime(time: LocalTime): String =
        "%02d:%02d:00".format(time.hour, time.minute)
}
