package com.ultiq.app.ui.checklist

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ultiq.app.data.local.AppDatabase
import com.ultiq.app.data.local.entity.ChecklistEntity
import com.ultiq.app.data.remote.RetrofitClient
import com.ultiq.app.data.remote.dto.ParseEventRequestDto
import com.ultiq.app.data.repository.ChecklistRepository
import com.ultiq.app.util.TokenManager
import com.ultiq.app.util.UserPreferences
import com.ultiq.app.util.toUserMessage
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.OffsetDateTime
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
    /** Open items dated yesterday (only meaningful when selectedDate == today
     *  and the user hasn't dismissed the carry-over banner today). */
    val yesterdayOpenItems: List<ChecklistEntity> = emptyList(),
    // §9.5 — AI quick-add state. `aiPrefill` is handed to the existing
    // ChecklistEditDialog as initial state when set.
    val showAiDialog: Boolean = false,
    val aiLoading: Boolean = false,
    val aiError: String? = null,
    val aiPrefill: AiChecklistPrefill? = null,
)

/// §9.5 — Minimal carrier of the AI-parsed fields the edit dialog needs as
/// initial state. Stays internal to the checklist surface (separate from the
/// network DTO) so the dialog can stay un-aware of remote types.
data class AiChecklistPrefill(
    val title: String,
    val description: String?,
    val dueDate: LocalDate,
    val priority: Int?,
    val estimatedMinutes: Int?,
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
    private var yesterdayJob: Job? = null

    init {
        viewModelScope.launch {
            userId = tokenManager.getUserId().firstOrNull() ?: ""
            sync()
            observeSelectedDate()
            observeYesterdayCarryOver()
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
        observeYesterdayCarryOver()
    }

    fun selectPreviousDay() = selectDate(_uiState.value.selectedDate.minusDays(1))
    fun selectNextDay() = selectDate(_uiState.value.selectedDate.plusDays(1))
    fun jumpToToday() = selectDate(LocalDate.now())

    fun openAddDialog() {
        _uiState.value = _uiState.value.copy(
            showAddDialog = true,
            editingItem = null,
            aiPrefill = null,
        )
    }

    fun openEditDialog(item: ChecklistEntity) {
        _uiState.value = _uiState.value.copy(
            showAddDialog = true,
            editingItem = item,
            aiPrefill = null,
        )
    }

    fun dismissDialog() {
        _uiState.value = _uiState.value.copy(
            showAddDialog = false,
            editingItem = null,
            aiPrefill = null,
        )
    }

    // ── §9.5 — AI quick-add ─────────────────────────────────────────────

    fun showAiDialog() {
        _uiState.value = _uiState.value.copy(
            showAiDialog = true,
            aiError = null,
            aiLoading = false,
        )
    }

    fun dismissAiDialog() {
        _uiState.value = _uiState.value.copy(
            showAiDialog = false,
            aiError = null,
            aiLoading = false,
        )
    }

    fun submitAiParse(text: String) {
        if (text.isBlank()) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(aiLoading = true, aiError = null)
            runCatching {
                api.parseEvent(
                    ParseEventRequestDto(
                        text = text,
                        hint = "checklist",
                        now_local = OffsetDateTime.now().toString(),
                    )
                )
            }.onSuccess { resp ->
                val cl = resp.checklist
                if (cl == null) {
                    _uiState.value = _uiState.value.copy(
                        aiLoading = false,
                        aiError = "Couldn't parse that — try rephrasing.",
                    )
                    return@onSuccess
                }
                val due = runCatching { LocalDate.parse(cl.due_date) }
                    .getOrDefault(_uiState.value.selectedDate)
                _uiState.value = _uiState.value.copy(
                    aiLoading = false,
                    showAiDialog = false,
                    showAddDialog = true,
                    editingItem = null,
                    aiPrefill = AiChecklistPrefill(
                        title = cl.title,
                        description = cl.description,
                        dueDate = due,
                        priority = cl.priority,
                        estimatedMinutes = cl.estimated_minutes,
                    ),
                )
            }.onFailure { e ->
                _uiState.value = _uiState.value.copy(
                    aiLoading = false,
                    aiError = e.toUserMessage("Couldn't reach the AI service. Try again."),
                )
            }
        }
    }

    fun saveItem(
        title: String,
        description: String?,
        dueDate: LocalDate,
        estimatedMinutes: Int?,
        priority: Int,
        recurrenceDaysMask: Int,
        showUntilDue: Boolean,
        /// §4 — when true, also creates a separate one-off task for today,
        /// independent from the recurring schedule. Only used on new
        /// recurring items whose weekday mask doesn't cover today.
        alsoCreateTodayOneOff: Boolean = false,
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
                    recurrenceDaysMask = recurrenceDaysMask,
                    showUntilDue = showUntilDue,
                )
            } else {
                repository.update(
                    editing.copy(
                        title = title.trim(),
                        description = description?.trim()?.ifEmpty { null },
                        dueDateEpochDay = dueDate.toEpochDay(),
                        estimatedMinutes = estimatedMinutes,
                        priority = priority,
                        recurrenceDaysMask = recurrenceDaysMask,
                        showUntilDue = showUntilDue,
                    ),
                )
            }
            result.onSuccess {
                if (alsoCreateTodayOneOff && editing == null) {
                    // §4 — add a one-off for today alongside the recurring
                    // task. Independent row, no recurrence, due today.
                    repository.create(
                        userId = userId,
                        title = title.trim(),
                        description = description?.trim()?.ifEmpty { null },
                        dueDate = LocalDate.now(),
                        estimatedMinutes = estimatedMinutes,
                        priority = priority,
                        recurrenceDaysMask = 0,
                        showUntilDue = false,
                    )
                }
                _uiState.value = _uiState.value.copy(showAddDialog = false, editingItem = null)
            }.onFailure {
                _uiState.value = _uiState.value.copy(error = it.toUserMessage("Couldn't save task. Try again."))
            }
        }
    }

    fun toggleCompleted(item: ChecklistEntity) {
        viewModelScope.launch {
            if (item.recurrenceDaysMask != 0) {
                // Recurring: stamp / unstamp lastCompletedEpochDay for the
                // currently-selected day so the row reopens next occurrence.
                val day = _uiState.value.selectedDate.toEpochDay()
                val doneToday = item.lastCompletedEpochDay == day
                if (doneToday) {
                    repository.markRecurringIncompleteOn(item.id)
                } else {
                    repository.markRecurringCompletedOn(item.id, day)
                }
            } else if (item.completed) {
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
            val day = _uiState.value.selectedDate.toEpochDay()
            // java.time.DayOfWeek is 1=Monday..7=Sunday; convert to a Sun=0..Sat=6
            // bit index that matches recurrenceDaysMask's encoding.
            val dow = _uiState.value.selectedDate.dayOfWeek.value % 7
            val dayBit = 1 shl dow
            repository.getByDate(day, dayBit).collectLatest { items ->
                val (open, done) = items.partition { item ->
                    if (item.recurrenceDaysMask != 0) {
                        item.lastCompletedEpochDay != day
                    } else {
                        !item.completed
                    }
                }
                _uiState.value = _uiState.value.copy(
                    openItems = open,
                    completedItems = done,
                )
            }
        }
    }

    /** Stream yesterday's open items, but only when looking at today and the user
     *  hasn't already dismissed the carry-over banner today. The screen renders
     *  the banner whenever `yesterdayOpenItems` is non-empty.
     *
     *  §fix-carryover-recurring — the query now mixes two kinds:
     *  one-offs due yesterday + recurring rows whose mask covers yesterday
     *  but NOT today (so today's view wouldn't show them naturally). */
    private fun observeYesterdayCarryOver() {
        yesterdayJob?.cancel()
        if (_uiState.value.selectedDate != LocalDate.now()) {
            _uiState.value = _uiState.value.copy(yesterdayOpenItems = emptyList())
            return
        }
        yesterdayJob = viewModelScope.launch {
            val todayDate = LocalDate.now()
            val today = todayDate.toEpochDay()
            val dismissedDay = userPreferences.snapshot().lastCarryOverDismissedEpochDay
            if (dismissedDay == today) {
                _uiState.value = _uiState.value.copy(yesterdayOpenItems = emptyList())
                return@launch
            }
            val yesterdayDate = todayDate.minusDays(1)
            val yesterday = yesterdayDate.toEpochDay()
            // Sun=0..Sat=6 to match recurrence_days_mask encoding.
            val yesterdayBit = 1 shl (yesterdayDate.dayOfWeek.value % 7)
            val todayBit = 1 shl (todayDate.dayOfWeek.value % 7)
            repository.getCarryoverCandidates(yesterday, yesterdayBit, todayBit)
                .collectLatest { items ->
                    _uiState.value = _uiState.value.copy(yesterdayOpenItems = items)
                }
        }
    }

    /** Move every yesterday-open item forward to today.
     *
     *  §fix-carryover-recurring — recurring rows get a separate one-off
     *  *copy* due today rather than having their start date mutated.
     *  Mutating dueDateEpochDay on a recurring row would shift the
     *  recurrence pattern (since dueDate doubles as the start date) —
     *  unwanted side effect. One-offs are mutated in place as before. */
    fun bringYesterdayForward() {
        val items = _uiState.value.yesterdayOpenItems
        if (items.isEmpty()) return
        val todayEpochDay = LocalDate.now().toEpochDay()
        viewModelScope.launch {
            for (item in items) {
                if (item.recurrenceDaysMask != 0) {
                    // Spawn a one-off copy due today; leave the recurring
                    // row untouched so its schedule keeps working.
                    repository.create(
                        userId = userId,
                        title = item.title,
                        description = item.description,
                        dueDate = LocalDate.ofEpochDay(todayEpochDay),
                        estimatedMinutes = item.estimatedMinutes,
                        priority = item.priority,
                        recurrenceDaysMask = 0,
                        showUntilDue = false,
                    )
                } else {
                    repository.update(item.copy(dueDateEpochDay = todayEpochDay))
                }
            }
        }
    }

    /** Hide the banner for the rest of today. Persisted so it doesn't re-appear
     *  on the next app launch within the same day. */
    fun dismissYesterdayBanner() {
        viewModelScope.launch {
            userPreferences.setLastCarryOverDismissedEpochDay(LocalDate.now().toEpochDay())
            _uiState.value = _uiState.value.copy(yesterdayOpenItems = emptyList())
            yesterdayJob?.cancel()
        }
    }
}
