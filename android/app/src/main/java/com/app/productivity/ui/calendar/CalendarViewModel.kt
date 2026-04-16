package com.app.productivity.ui.calendar

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.app.productivity.data.local.AppDatabase
import com.app.productivity.data.local.entity.CalendarEventEntity
import com.app.productivity.data.remote.RetrofitClient
import com.app.productivity.data.remote.dto.CreateCalendarEventDto
import com.app.productivity.data.repository.CalendarRepository
import com.app.productivity.util.TokenManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset

enum class ViewMode { MONTH, DAY }

data class CalendarUiState(
    val selectedDate: LocalDate = LocalDate.now(),
    val currentMonth: YearMonth = YearMonth.now(),
    val monthEvents: Map<LocalDate, List<CalendarEventEntity>> = emptyMap(),
    val selectedDayEvents: List<CalendarEventEntity> = emptyList(),
    val viewMode: ViewMode = ViewMode.MONTH,
    val showAddDialog: Boolean = false,
    val editingEvent: CalendarEventEntity? = null,
    val isLoading: Boolean = false,
    val error: String? = null,
)

class CalendarViewModel(application: Application) : AndroidViewModel(application) {
    private val tokenManager = TokenManager(application)
    private val api = RetrofitClient.create(tokenManager)
    private val db = AppDatabase.getInstance(application)
    private val repository = CalendarRepository(db.calendarEventDao(), api)

    private val _uiState = MutableStateFlow(CalendarUiState())
    val uiState: StateFlow<CalendarUiState> = _uiState

    private var userId: String = ""
    private var monthJob: Job? = null

    init {
        viewModelScope.launch {
            userId = tokenManager.getUserId().firstOrNull() ?: ""
            loadMonth(_uiState.value.currentMonth)
            sync()
        }
    }

    fun selectDate(date: LocalDate) {
        _uiState.value = _uiState.value.copy(
            selectedDate = date,
            selectedDayEvents = _uiState.value.monthEvents[date] ?: emptyList()
        )
    }

    fun changeMonth(delta: Int) {
        val newMonth = _uiState.value.currentMonth.plusMonths(delta.toLong())
        _uiState.value = _uiState.value.copy(currentMonth = newMonth)
        loadMonth(newMonth)
    }

    fun showAddDialog() {
        _uiState.value = _uiState.value.copy(showAddDialog = true, editingEvent = null)
    }

    fun showEditDialog(event: CalendarEventEntity) {
        _uiState.value = _uiState.value.copy(showAddDialog = true, editingEvent = event)
    }

    fun hideDialog() {
        _uiState.value = _uiState.value.copy(showAddDialog = false, editingEvent = null)
    }

    fun createEvent(dto: CreateCalendarEventDto) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            repository.createEvent(dto, userId)
                .onFailure { _uiState.value = _uiState.value.copy(error = it.message) }
            _uiState.value = _uiState.value.copy(isLoading = false, showAddDialog = false, editingEvent = null)
        }
    }

    fun updateEvent(id: String, dto: CreateCalendarEventDto) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            repository.updateEvent(id, dto, userId)
                .onFailure { _uiState.value = _uiState.value.copy(error = it.message) }
            _uiState.value = _uiState.value.copy(isLoading = false, showAddDialog = false, editingEvent = null)
        }
    }

    fun deleteEvent(id: String) {
        viewModelScope.launch {
            repository.deleteEvent(id)
            hideDialog()
        }
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    private fun loadMonth(yearMonth: YearMonth) {
        monthJob?.cancel()
        monthJob = viewModelScope.launch {
            repository.getEventsForMonth(yearMonth.year, yearMonth.monthValue).collect { events ->
                val grouped = events.groupBy { entity ->
                    Instant.ofEpochMilli(entity.startTime)
                        .atOffset(ZoneOffset.UTC)
                        .toLocalDate()
                }
                _uiState.value = _uiState.value.copy(
                    monthEvents = grouped,
                    selectedDayEvents = grouped[_uiState.value.selectedDate] ?: emptyList()
                )
            }
        }
    }

    private fun sync() {
        viewModelScope.launch {
            try { repository.sync() } catch (_: Exception) { }
        }
    }
}
