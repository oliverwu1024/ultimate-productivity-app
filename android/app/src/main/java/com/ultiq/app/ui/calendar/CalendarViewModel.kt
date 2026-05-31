package com.ultiq.app.ui.calendar

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ultiq.app.data.local.AppDatabase
import com.ultiq.app.data.local.entity.CalendarEventEntity
import com.ultiq.app.data.remote.RetrofitClient
import com.ultiq.app.data.remote.dto.CreateCalendarEventDto
import com.ultiq.app.data.remote.dto.ParseEventRequestDto
import com.ultiq.app.data.repository.CalendarRepository
import com.ultiq.app.data.repository.RecurringScope
import com.ultiq.app.util.AlarmScheduler
import com.ultiq.app.util.TokenManager
import com.ultiq.app.util.toUserMessage
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
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
    /// §9.5 — AI-parsed values handed off to the AddEventDialog as initial
    /// state when the user came in via the AI quick-add flow. Cleared as
    /// soon as the dialog dismisses so a follow-up manual add starts blank.
    val aiPrefill: CreateCalendarEventDto? = null,
    /// §9.5 — Mutually exclusive with `showAddDialog`. True while the user
    /// is typing in the "describe what you want" dialog and during the
    /// `parse-event` round-trip.
    val showAiDialog: Boolean = false,
    val aiLoading: Boolean = false,
    val aiError: String? = null,
    val isLoading: Boolean = false,
    val error: String? = null,
)

class CalendarViewModel(application: Application) : AndroidViewModel(application) {
    private val tokenManager = TokenManager(application)
    private val api = RetrofitClient.create(tokenManager)
    private val db = AppDatabase.getInstance(application)
    private val alarmScheduler = AlarmScheduler(application)
    private val repository = CalendarRepository(
        db.calendarEventDao(),
        api,
        alarmScheduler,
        syncStateStore = com.ultiq.app.data.repository.SyncStateStore(application),
    )

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

    /** v2.12.0 — Jump back to current month + select today. Triggered by
     *  the "Today" pill on the month header (only visible when off-month). */
    fun jumpToToday() {
        val today = LocalDate.now()
        val ym = YearMonth.from(today)
        val changedMonth = _uiState.value.currentMonth != ym
        _uiState.value = _uiState.value.copy(
            currentMonth = ym,
            selectedDate = today,
        )
        if (changedMonth) loadMonth(ym)
    }

    fun showAddDialog() {
        _uiState.value = _uiState.value.copy(
            showAddDialog = true,
            editingEvent = null,
            aiPrefill = null,
        )
    }

    fun showEditDialog(event: CalendarEventEntity) {
        _uiState.value = _uiState.value.copy(
            showAddDialog = true,
            editingEvent = event,
            aiPrefill = null,
        )
    }

    fun hideDialog() {
        _uiState.value = _uiState.value.copy(
            showAddDialog = false,
            editingEvent = null,
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
                        hint = "calendar",
                        now_local = OffsetDateTime.now().toString(),
                    )
                )
            }.onSuccess { resp ->
                val cal = resp.calendar
                if (cal == null) {
                    _uiState.value = _uiState.value.copy(
                        aiLoading = false,
                        aiError = "Couldn't parse that — try rephrasing.",
                    )
                    return@onSuccess
                }
                // Hand the parsed fields to the existing AddEventDialog as
                // initial state. Caller still confirms before save.
                val prefill = CreateCalendarEventDto(
                    title = cal.title,
                    description = cal.description,
                    start_time = cal.start_time,
                    end_time = cal.end_time,
                    category = cal.category,
                    priority = cal.priority,
                    is_recurring = false,
                    recurrence_rule = null,
                    color = null,
                )
                _uiState.value = _uiState.value.copy(
                    aiLoading = false,
                    showAiDialog = false,
                    showAddDialog = true,
                    editingEvent = null,
                    aiPrefill = prefill,
                )
            }.onFailure { e ->
                _uiState.value = _uiState.value.copy(
                    aiLoading = false,
                    aiError = e.toUserMessage("Couldn't reach the AI service. Try again."),
                )
            }
        }
    }

    fun createEvent(dto: CreateCalendarEventDto) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            repository.createEvent(dto, userId)
                .onFailure { _uiState.value = _uiState.value.copy(error = it.toUserMessage("Couldn't save event. Try again.")) }
            _uiState.value = _uiState.value.copy(isLoading = false, showAddDialog = false, editingEvent = null)
        }
    }

    /** Edit a calendar event. For recurring events the `scope` decides
     *  whether the change hits one occurrence, this+following, or the
     *  whole series; for one-shot events the scope is ignored. The
     *  caller supplies `occurrenceDate` (the local date of the tapped
     *  instance) so JUST_THIS / THIS_AND_FOLLOWING can detach / cap
     *  at the right pivot. */
    fun updateEvent(
        id: String,
        dto: CreateCalendarEventDto,
        occurrenceDate: LocalDate? = null,
        scope: RecurringScope = RecurringScope.ALL,
    ) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            repository.updateEventWithScope(id, dto, userId, occurrenceDate, scope)
                .onFailure { _uiState.value = _uiState.value.copy(error = it.toUserMessage("Couldn't update event. Try again.")) }
            _uiState.value = _uiState.value.copy(isLoading = false, showAddDialog = false, editingEvent = null)
        }
    }

    /** Delete a calendar event. For recurring events the `scope` controls
     *  whether only the tapped occurrence is removed (JUST_THIS), the
     *  picked occurrence plus all future ones are removed
     *  (THIS_AND_FOLLOWING), or the entire series is removed (ALL).
     *  `occurrenceDate` is required for the first two — the local date
     *  of the instance the user tapped. */
    fun deleteEvent(
        id: String,
        occurrenceDate: LocalDate? = null,
        scope: RecurringScope = RecurringScope.ALL,
    ) {
        viewModelScope.launch {
            repository.deleteEvent(id, occurrenceDate, scope)
            hideDialog()
        }
    }

    /** Toggle the "I actually did this" flag on a past event. For recurring
     *  events, this is always per-occurrence — `occurrenceDate` is the
     *  local date of the tapped instance. For one-shot events, pass null
     *  (or any date) and the master row is flipped. */
    fun setEventDone(id: String, isDone: Boolean, occurrenceDate: LocalDate? = null) {
        viewModelScope.launch {
            repository.setEventDone(id, userId, isDone, occurrenceDate)
                .onFailure { _uiState.value = _uiState.value.copy(error = it.toUserMessage("Couldn't update event. Try again.")) }
        }
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    private fun loadMonth(yearMonth: YearMonth) {
        monthJob?.cancel()
        monthJob = viewModelScope.launch {
            repository.getEventsForMonth(yearMonth.year, yearMonth.monthValue).collect { events ->
                val zone = java.time.ZoneId.systemDefault()
                // v2.11.9 — Expand each event across every day it spans so
                // multi-day events appear on mid-days too (was: keyed only
                // by the start-date, so a Mon→Wed event was invisible on
                // Tue and Wed in both the month grid dots and the day list).
                // Each spanning entry shares the same entity object — the
                // EventItem renders adaptive time text based on the viewed
                // date so "today is day 2 of a 3-day event" reads correctly.
                val grouped = mutableMapOf<LocalDate, MutableList<CalendarEventEntity>>()
                for (event in events) {
                    val startDate = Instant.ofEpochMilli(event.startTime).atZone(zone).toLocalDate()
                    val endDate = Instant.ofEpochMilli(event.endTime).atZone(zone).toLocalDate()
                    var cur = startDate
                    while (!cur.isAfter(endDate)) {
                        grouped.getOrPut(cur) { mutableListOf() } += event
                        cur = cur.plusDays(1)
                    }
                }
                // Sort each day's list: multi-day events (spanning days) sort
                // first, then single-day events by startTime — mirrors the
                // "all-day band at the top" convention from Google Calendar.
                val sorted = grouped.mapValues { (_, list) ->
                    list.sortedWith(
                        compareByDescending<CalendarEventEntity> { it.endTime - it.startTime > 24L * 60L * 60L * 1000L }
                            .thenBy { it.startTime }
                    )
                }
                _uiState.value = _uiState.value.copy(
                    monthEvents = sorted,
                    selectedDayEvents = sorted[_uiState.value.selectedDate] ?: emptyList()
                )
            }
        }
    }

    private fun sync() {
        viewModelScope.launch {
            try { repository.sync() } catch (_: Exception) { }
        }
    }

    /** v2.11.8 — Called from CalendarScreen via LifecycleResumeEffect so that
     *  tabbing away + back (or backgrounding + foregrounding the app) pulls
     *  the latest server state. Without this, events added on web or by
     *  another device stayed invisible on the phone until the ViewModel was
     *  re-created (process death, etc.). Cheap: one HTTP GET + a Room
     *  insertAll on the response; Room invalidation handles the UI refresh. */
    fun refresh() {
        sync()
    }
}
