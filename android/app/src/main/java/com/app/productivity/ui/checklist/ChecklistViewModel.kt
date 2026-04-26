package com.app.productivity.ui.checklist

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.app.productivity.data.local.AppDatabase
import com.app.productivity.data.local.entity.ChecklistEntity
import com.app.productivity.data.remote.RetrofitClient
import com.app.productivity.data.repository.ChecklistRepository
import com.app.productivity.util.TokenManager
import com.app.productivity.util.UserPreferences
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.IsoFields
import java.time.temporal.TemporalAdjusters

data class ChecklistUiState(
    val selectedDate: LocalDate = LocalDate.now(),
    val openItems: List<ChecklistEntity> = emptyList(),
    val completedItems: List<ChecklistEntity> = emptyList(),
    val showAddDialog: Boolean = false,
    val editingItem: ChecklistEntity? = null,
    val error: String? = null,
    val showWeeklyPrompt: Boolean = false,
)

class ChecklistViewModel(application: Application) : AndroidViewModel(application) {
    private val tokenManager = TokenManager(application)
    private val api = RetrofitClient.create(tokenManager)
    private val db = AppDatabase.getInstance(application)
    private val repository = ChecklistRepository(db.checklistDao(), api)
    private val userPreferences = UserPreferences(application)

    private val _uiState = MutableStateFlow(ChecklistUiState())
    val uiState: StateFlow<ChecklistUiState> = _uiState

    private var userId: String = ""
    private var collectJob: Job? = null

    init {
        viewModelScope.launch {
            userId = tokenManager.getUserId().firstOrNull() ?: ""
            sync()
            observeSelectedDate()
            maybeShowWeeklyPrompt()
        }
    }

    private suspend fun maybeShowWeeklyPrompt() {
        val today = LocalDate.now()
        if (today.dayOfWeek != DayOfWeek.SUNDAY && today.dayOfWeek != DayOfWeek.MONDAY) return

        val encoded = today.get(IsoFields.WEEK_BASED_YEAR) * 100 +
            today.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)
        val settings = userPreferences.snapshot()
        if (settings.lastPlanningPromptDismissedWeek == encoded) return

        // Check if any items already exist for the upcoming Mon..Sun
        val mondayOfWeek = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val sundayOfWeek = mondayOfWeek.plusDays(6)
        val itemsThisWeek = db.checklistDao()
            .getInRange(mondayOfWeek.toEpochDay(), sundayOfWeek.toEpochDay())
            .firstOrNull()
            .orEmpty()
        if (itemsThisWeek.isNotEmpty()) return

        _uiState.value = _uiState.value.copy(showWeeklyPrompt = true)
    }

    fun dismissWeeklyPrompt() {
        viewModelScope.launch {
            val today = LocalDate.now()
            val encoded = today.get(IsoFields.WEEK_BASED_YEAR) * 100 +
                today.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)
            userPreferences.setLastPlanningPromptDismissedWeek(encoded)
            _uiState.value = _uiState.value.copy(showWeeklyPrompt = false)
        }
    }

    fun selectDate(date: LocalDate) {
        if (date == _uiState.value.selectedDate) return
        _uiState.value = _uiState.value.copy(selectedDate = date)
        observeSelectedDate()
    }

    fun selectPreviousDay() = selectDate(_uiState.value.selectedDate.minusDays(1))
    fun selectNextDay() = selectDate(_uiState.value.selectedDate.plusDays(1))
    fun jumpToToday() = selectDate(LocalDate.now())

    fun openAddDialog() {
        _uiState.value = _uiState.value.copy(showAddDialog = true, editingItem = null)
    }

    fun openEditDialog(item: ChecklistEntity) {
        _uiState.value = _uiState.value.copy(showAddDialog = true, editingItem = item)
    }

    fun dismissDialog() {
        _uiState.value = _uiState.value.copy(showAddDialog = false, editingItem = null)
    }

    fun saveItem(
        title: String,
        description: String?,
        dueDate: LocalDate,
        estimatedMinutes: Int?,
        priority: Int,
    ) {
        if (title.isBlank()) {
            _uiState.value = _uiState.value.copy(error = "Title can't be empty")
            return
        }
        viewModelScope.launch {
            val editing = _uiState.value.editingItem
            val result = if (editing == null) {
                repository.create(
                    userId = userId,
                    title = title.trim(),
                    description = description?.trim()?.ifEmpty { null },
                    dueDate = dueDate,
                    estimatedMinutes = estimatedMinutes,
                    priority = priority,
                )
            } else {
                repository.update(
                    editing.copy(
                        title = title.trim(),
                        description = description?.trim()?.ifEmpty { null },
                        dueDateEpochDay = dueDate.toEpochDay(),
                        estimatedMinutes = estimatedMinutes,
                        priority = priority,
                    ),
                )
            }
            result.onSuccess {
                _uiState.value = _uiState.value.copy(showAddDialog = false, editingItem = null)
            }.onFailure {
                _uiState.value = _uiState.value.copy(error = it.message)
            }
        }
    }

    fun toggleCompleted(item: ChecklistEntity) {
        viewModelScope.launch {
            if (item.completed) {
                repository.markIncomplete(item.id)
            } else {
                repository.markCompleted(item.id)
            }
        }
    }

    fun deleteItem(item: ChecklistEntity) {
        viewModelScope.launch {
            val result = repository.delete(item.id)
            result.onFailure {
                _uiState.value = _uiState.value.copy(
                    error = "Couldn't delete '${item.title}' — check your connection",
                )
            }
        }
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    private fun sync() {
        viewModelScope.launch { runCatching { repository.sync() } }
    }

    private fun observeSelectedDate() {
        collectJob?.cancel()
        collectJob = viewModelScope.launch {
            repository.getByDate(_uiState.value.selectedDate.toEpochDay()).collectLatest { items ->
                _uiState.value = _uiState.value.copy(
                    openItems = items.filterNot { it.completed },
                    completedItems = items.filter { it.completed },
                )
            }
        }
    }
}
