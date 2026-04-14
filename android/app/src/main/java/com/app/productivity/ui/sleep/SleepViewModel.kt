package com.app.productivity.ui.sleep

import android.app.Application
import android.content.Intent
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.app.productivity.data.local.AppDatabase
import com.app.productivity.data.local.entity.SleepRecordEntity
import com.app.productivity.data.remote.RetrofitClient
import com.app.productivity.data.remote.dto.CreateSleepRecordDto
import com.app.productivity.data.remote.dto.SleepStats
import com.app.productivity.data.remote.dto.toLocalStats
import com.app.productivity.data.repository.SleepRepository
import com.app.productivity.service.PickupEvent
import com.app.productivity.service.SleepTrackingService
import com.app.productivity.util.TokenManager
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
    val showEndSleepDialog: Boolean = false,
    val showManualLogDialog: Boolean = false,
    // Snapshot of session data at end (for EndSleepDialog)
    val endedSessionStart: Long = 0L,
    val endedPickupEvents: List<PickupEvent> = emptyList()
)

class SleepViewModel(application: Application) : AndroidViewModel(application) {
    private val tokenManager = TokenManager(application)
    private val api = RetrofitClient.create(tokenManager)
    private val db = AppDatabase.getInstance(application)
    private val repository = SleepRepository(db.sleepDao(), api)

    private val _uiState = MutableStateFlow(SleepUiState())
    val uiState: StateFlow<SleepUiState> = _uiState

    private var userId: String = ""

    init {
        viewModelScope.launch {
            userId = tokenManager.getUserId().firstOrNull() ?: ""
            observeServiceState()
            loadRecords()
            sync()
        }
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
                target_bedtime = "22:00:00",
                target_wake_time = "06:00:00",
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
        viewModelScope.launch {
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
        val end = now.toEpochMilli()
        val start = when (range) {
            TimeRange.WEEK -> now.minus(7, ChronoUnit.DAYS).toEpochMilli()
            TimeRange.MONTH -> now.minus(30, ChronoUnit.DAYS).toEpochMilli()
        }
        return start to end
    }
}
