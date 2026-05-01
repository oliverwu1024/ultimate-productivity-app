package com.ultiq.app.ui.checklist

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ultiq.app.data.local.AppDatabase
import com.ultiq.app.data.remote.RetrofitClient
import com.ultiq.app.data.remote.dto.CreateChecklistItemDto
import com.ultiq.app.data.repository.ChecklistRepository
import com.ultiq.app.util.TokenManager
import com.ultiq.app.util.toUserMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.UUID

data class PendingItem(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val priority: Int = 1,
    val estimatedMinutes: Int? = null,
)

data class WeeklyPlannerUiState(
    val weekStart: LocalDate = LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)),
    val itemsByDate: Map<LocalDate, List<PendingItem>> = emptyMap(),
    val isSaving: Boolean = false,
    val error: String? = null,
    val finished: Boolean = false,
)

class WeeklyPlannerViewModel(application: Application) : AndroidViewModel(application) {
    private val tokenManager = TokenManager(application)
    private val api = RetrofitClient.create(tokenManager)
    private val db = AppDatabase.getInstance(application)
    private val repository = ChecklistRepository(db.checklistDao(), api)

    private val _uiState = MutableStateFlow(WeeklyPlannerUiState())
    val uiState: StateFlow<WeeklyPlannerUiState> = _uiState

    private val isoFormatter: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE

    fun addItem(date: LocalDate, title: String, priority: Int = 1) {
        if (title.isBlank()) return
        val current = _uiState.value.itemsByDate[date].orEmpty()
        val newItem = PendingItem(title = title.trim(), priority = priority)
        _uiState.value = _uiState.value.copy(
            itemsByDate = _uiState.value.itemsByDate + (date to current + newItem),
        )
    }

    fun removeItem(date: LocalDate, id: String) {
        val current = _uiState.value.itemsByDate[date].orEmpty()
        val updated = current.filterNot { it.id == id }
        _uiState.value = _uiState.value.copy(
            itemsByDate = if (updated.isEmpty()) {
                _uiState.value.itemsByDate - date
            } else {
                _uiState.value.itemsByDate + (date to updated)
            },
        )
    }

    fun cyclePriority(date: LocalDate, id: String) {
        val current = _uiState.value.itemsByDate[date].orEmpty()
        val updated = current.map {
            if (it.id == id) it.copy(priority = (it.priority + 1) % 3) else it
        }
        _uiState.value = _uiState.value.copy(
            itemsByDate = _uiState.value.itemsByDate + (date to updated),
        )
    }

    fun save() {
        val flat = _uiState.value.itemsByDate.flatMap { (date, items) ->
            items.map { item ->
                CreateChecklistItemDto(
                    title = item.title,
                    description = null,
                    due_date = date.format(isoFormatter),
                    estimated_minutes = item.estimatedMinutes,
                    priority = item.priority,
                )
            }
        }
        if (flat.isEmpty()) {
            _uiState.value = _uiState.value.copy(finished = true)
            return
        }
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSaving = true)
            val result = repository.bulkCreate(flat)
            _uiState.value = result.fold(
                onSuccess = { _uiState.value.copy(isSaving = false, finished = true) },
                onFailure = { _uiState.value.copy(isSaving = false, error = it.toUserMessage("Couldn't save plan. Try again.")) },
            )
        }
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }
}
