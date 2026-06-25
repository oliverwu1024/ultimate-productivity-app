package com.ultiq.app.ui.sessions

import android.app.Application
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.JsonObject
import com.ultiq.app.data.local.AppDatabase
import com.ultiq.app.data.local.entity.ChecklistEntity
import com.ultiq.app.data.local.entity.SessionEntity
import com.ultiq.app.data.remote.RetrofitClient
import com.ultiq.app.data.repository.ChecklistRepository
import com.ultiq.app.data.repository.SessionRepository
import com.ultiq.app.data.achievements.AchievementChecker
import com.ultiq.app.service.FocusTrackingService
import com.ultiq.app.service.SleepTrackingService
import com.ultiq.app.ui.sleep.TimeRange
import com.ultiq.app.util.PhoneUsageTracker
import com.ultiq.app.util.TokenManager
import com.ultiq.app.util.UserPreferences
import com.ultiq.app.util.UserSettings
import com.ultiq.app.util.toUserMessage
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

enum class TimerState { IDLE, RUNNING, PAUSED, FINISHED }

data class TodayStats(
    val totalFocusMinutes: Int,
    val sessionsCompleted: Int,
    val currentStreak: Int,
    val phonePickupsToday: Int,
)

data class SessionsUiState(
    val timerState: TimerState = TimerState.IDLE,
    /** Seconds remaining in the planned work block. Stays at 0 once overtime begins. */
    val timeRemainingSeconds: Int = 0,
    /** Length of the planned work block in seconds — used by the progress ring. */
    val totalTimeSeconds: Int = 0,
    val workDuration: Int = 25,
    val tag: String = "",
    /** True once the user has crossed past their planned work duration. */
    val isOvertime: Boolean = false,
    /** Seconds spent past the planned work duration. Counts up. */
    val overtimeSeconds: Int = 0,
    val phonePickups: Int = 0,
    val todayStats: TodayStats? = null,
    val recentSessions: List<SessionEntity> = emptyList(),
    /// §focus-period — Week/Month toggle for the recent-sessions list,
    /// mirroring the Sleep tab. The list is grouped into This/Last week or
    /// This/Last month sections in the screen.
    val selectedTimeRange: TimeRange = TimeRange.WEEK,
    /** Resolved checklist titles for sessions that linked a task — keyed by checklistItemId. */
    val checklistTitleById: Map<String, String> = emptyMap(),
    val hasUsagePermission: Boolean = false,
    val error: String? = null,
    val openChecklistItems: List<ChecklistEntity> = emptyList(),
    val selectedChecklistItemId: String? = null,
    val completionPrompt: ChecklistCompletionPrompt? = null,
    /// §9.7 — set when a focus session just ended; nulled on submit/skip.
    val debriefPrompt: SessionDebriefPrompt? = null,
    // Live UserPreferences snapshot, used by the FOCUS PREFERENCES section.
    val settings: UserSettings? = null,
    // §v2.16.18 — Lazily-loaded per-session pickup timeline, keyed by
    // session id. Populated on first expansion via fetchRecordDetails;
    // a per-session Flow keeps the cache live for any post-save server
    // refresh.
    val recordDetails: Map<String, SessionRecordDetails> = emptyMap(),
)

/// §v2.16.18 — Expanded detail for a single past session on the Focus tab.
/// Mirror of SleepRecordDetails — same shape so the Composable rendering
/// is identical.
data class SessionRecordDetails(
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val pickups: List<RecordPickupDetail> = emptyList(),
)

data class RecordPickupDetail(
    val pickedUpAt: Long,
    val durationSeconds: Int,
)

data class ChecklistCompletionPrompt(
    val itemId: String,
    val title: String,
)

/// §9.7 — Post-session "what did you work on?" prompt. The Haiku call
/// happens server-side on submit; until then state is Idle. Tagged carries
/// the bucket the server assigned (deep_work / meetings / admin / other).
sealed class DebriefSubmitState {
    object Idle : DebriefSubmitState()
    object Submitting : DebriefSubmitState()
    data class Tagged(val tag: String) : DebriefSubmitState()
    data class Error(val message: String) : DebriefSubmitState()
}

data class SessionDebriefPrompt(
    val sessionId: String,
    val text: String = "",
    val submitState: DebriefSubmitState = DebriefSubmitState.Idle,
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
    private val repository = SessionRepository(
        sessionDao,
        api,
        achievementChecker,
        phonePickupDao = db.phonePickupDao(),
    )
    private val checklistRepository = ChecklistRepository(
        db.checklistDao(),
        db.checklistCompletionDao(),
        api,
        database = db,
    )

    private val usageTracker = PhoneUsageTracker(application)

    private val _uiState = MutableStateFlow(SessionsUiState())
    val uiState: StateFlow<SessionsUiState> = _uiState

    private var userId: String = ""
    private var timerJob: Job? = null
    private var pickupPollJob: Job? = null
    private var currentSessionId: String? = null

    // §v2.16.18 — Per-sessionId Flow subscriptions for the past-session
    // pickup timeline. Started by fetchRecordDetails on first expansion,
    // kept alive for the lifetime of the ViewModel so the post-save
    // refresh emits an update too.
    private val recordPickupJobs = mutableMapOf<String, Job>()

    // Wall-clock anchors. The timer derives display values from these on every tick,
    // so duration stays accurate even when Android throttles the coroutine in the
    // background (where a pure tick counter would lose time).
    private var sessionStartMillis: Long = 0L
    private var pausedAccumMillis: Long = 0L
    private var pauseStartMillis: Long = 0L

    init {
        _uiState.value = _uiState.value.copy(hasUsagePermission = usageTracker.hasPermission())
        viewModelScope.launch {
            userId = tokenManager.getUserId().firstOrNull() ?: ""
            val settings = userPreferences.snapshot()
            _uiState.value = _uiState.value.copy(
                workDuration = settings.defaultWorkDuration,
                settings = settings,
            )
            // The activity may be created after a session has already started
            // (cold open from notification, process death + restore). Pick the
            // running session back up before showing the IDLE controls.
            if (FocusTrackingService.isRunning.value) {
                restoreActiveSession()
            }
            loadSessionsAndStats()
            observeTodayChecklist()
            sync()
        }
        // Continuous mirror so the FOCUS PREFERENCES cards stay in sync when
        // the user changes them.
        viewModelScope.launch {
            userPreferences.settings.collect { s ->
                _uiState.value = _uiState.value.copy(settings = s)
            }
        }
        // v2.13.3 — Dropped observeAchievementEvents(). Achievements still
        // record to Room; the Achievements screen in Settings is where
        // the user reviews them. No mid-session popup.
    }

    // ── Preference setters (moved out of SettingsViewModel) ──────────────────

    fun setDefaultWorkDuration(minutes: Int) = viewModelScope.launch {
        userPreferences.setDefaultWorkDuration(minutes)
        pushPrefs { addProperty("default_work_duration", minutes) }
    }

    fun setLockoutForFocus(enabled: Boolean) = viewModelScope.launch {
        userPreferences.setLockoutForFocus(enabled)
        pushPrefs { addProperty("lockout_for_focus", enabled) }
    }

    fun setShowPickupCountOnLockout(enabled: Boolean) = viewModelScope.launch {
        userPreferences.setShowPickupCountOnLockout(enabled)
        pushPrefs { addProperty("show_pickup_count_on_lockout", enabled) }
    }

    fun setAllowEndSessionFromLockout(enabled: Boolean) = viewModelScope.launch {
        userPreferences.setAllowEndSessionFromLockout(enabled)
        pushPrefs { addProperty("allow_end_session_from_lockout", enabled) }
    }

    fun setFocusLockoutGraceMinutes(minutes: Int) = viewModelScope.launch {
        userPreferences.setFocusLockoutGraceMinutes(minutes)
        pushPrefs { addProperty("focus_lockout_grace_minutes", minutes) }
    }

    fun dismissFocusPrefsHint() = viewModelScope.launch {
        userPreferences.setFocusPrefsHintSeen(true)
    }

    /** §sync-prefs: fire-and-forget partial preferences push. */
    private fun pushPrefs(build: JsonObject.() -> Unit) {
        viewModelScope.launch {
            runCatching {
                api.updateProfile(
                    com.ultiq.app.data.remote.dto.UpdateProfileRequest(
                        preferences = JsonObject().apply(build),
                    ),
                )
            }
        }
    }

    private suspend fun restoreActiveSession() {
        val startMillis = FocusTrackingService.sessionStartTime.value
        if (startMillis <= 0L) return

        val active = sessionDao.getActiveSessions().firstOrNull()?.firstOrNull() ?: return
        val planned = FocusTrackingService.plannedWorkMinutes.value
            .takeIf { it > 0 } ?: active.workDuration
        val plannedSec = planned * 60
        val elapsedSec = ((System.currentTimeMillis() - startMillis) / 1000L)
            .toInt()
            .coerceAtLeast(0)
        val overtime = elapsedSec >= plannedSec

        sessionStartMillis = startMillis
        pausedAccumMillis = 0L
        pauseStartMillis = 0L
        currentSessionId = active.id

        _uiState.value = _uiState.value.copy(
            timerState = TimerState.RUNNING,
            workDuration = planned,
            tag = active.tag,
            selectedChecklistItemId = active.checklistItemId,
            timeRemainingSeconds = if (overtime) 0 else plannedSec - elapsedSec,
            totalTimeSeconds = plannedSec,
            isOvertime = overtime,
            overtimeSeconds = if (overtime) elapsedSec - plannedSec else 0,
            phonePickups = 0,
        )

        startTimer()
        startPickupPolling()
    }


    private fun observeTodayChecklist() {
        viewModelScope.launch {
            val today = LocalDate.now()
            val todayEpochDay = today.toEpochDay()
            // Sun=0..Sat=6 to match recurrence_days_mask packing.
            val todayBit = 1 shl (today.dayOfWeek.value % 7)
            android.util.Log.d("SessionsViewModel", "observing checklist for epochDay=$todayEpochDay")
            // §024 — Recurring "done today" is now stored per (item, day)
            // in checklist_completions, so we join the items flow with the
            // completion log instead of reading the single column on the
            // row.
            kotlinx.coroutines.flow.combine(
                db.checklistDao().getByDate(todayEpochDay, todayBit),
                db.checklistCompletionDao().observeAll(),
            ) { items, completions ->
                val doneIdsForToday = completions
                    .asSequence()
                    .filter { it.epochDay == todayEpochDay }
                    .map { it.itemId }
                    .toHashSet()
                items.filter { item ->
                    if (item.recurrenceDaysMask != 0) {
                        item.id !in doneIdsForToday
                    } else {
                        !item.completed
                    }
                }
            }.collect { open ->
                android.util.Log.d("SessionsViewModel", "checklist update: ${open.size} open item(s) for today")
                _uiState.value = _uiState.value.copy(openChecklistItems = open)
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

    /// §focus-period — Switch the recent-sessions window between Week and
    /// Month. Pure UI state: the list is grouped client-side in the screen
    /// (we already hold every completed session in memory for the stats +
    /// streak), so unlike SleepViewModel there's no re-query to fire.
    fun setTimeRange(range: TimeRange) {
        _uiState.value = _uiState.value.copy(selectedTimeRange = range)
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

                // Resolve linked checklist titles for the expanded session card.
                // Only sessions with a checklistItemId trigger a lookup.
                val checklistDao = db.checklistDao()
                val titleMap = recent
                    .mapNotNull { it.checklistItemId }
                    .distinct()
                    .mapNotNull { id -> checklistDao.getById(id)?.let { id to it.title } }
                    .toMap()

                _uiState.value = _uiState.value.copy(
                    recentSessions = recent,
                    checklistTitleById = titleMap,
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

        sessionStartMillis = System.currentTimeMillis()
        pausedAccumMillis = 0L
        pauseStartMillis = 0L

        _uiState.value = state.copy(
            timerState = TimerState.RUNNING,
            timeRemainingSeconds = state.workDuration * 60,
            totalTimeSeconds = state.workDuration * 60,
            isOvertime = false,
            overtimeSeconds = 0,
            phonePickups = 0,
        )

        viewModelScope.launch {
            val result = repository.createSession(
                tag = state.tag.trim(),
                workDuration = state.workDuration,
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
        if (pauseStartMillis == 0L) {
            pauseStartMillis = System.currentTimeMillis()
        }
        _uiState.value = _uiState.value.copy(timerState = TimerState.PAUSED)
    }

    fun resumeTimer() {
        if (pauseStartMillis > 0L) {
            pausedAccumMillis += System.currentTimeMillis() - pauseStartMillis
            pauseStartMillis = 0L
        }
        _uiState.value = _uiState.value.copy(timerState = TimerState.RUNNING)
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
        sessionStartMillis = 0L
        pausedAccumMillis = 0L
        pauseStartMillis = 0L
        _uiState.value = _uiState.value.copy(
            timerState = TimerState.IDLE,
            timeRemainingSeconds = 0,
            totalTimeSeconds = 0,
            isOvertime = false,
            overtimeSeconds = 0,
            phonePickups = 0,
        )
    }

    /**
     * §v2.16.18 — Lazy fetch + Flow subscribe for the past-session
     * pickup timeline. Mirror of `SleepViewModel.fetchRecordDetails`
     * (v2.16.17 form): subscribe to Room first so any later write
     * triggers a recomposition, then kick a one-shot network refresh
     * in parallel to populate the server canonical view.
     *
     * No-op if the session's details are already subscribed; the Flow
     * keeps streaming for the ViewModel's lifetime so we don't need
     * to re-subscribe on subsequent expansions.
     */
    fun fetchRecordDetails(sessionId: String) {
        val existing = _uiState.value.recordDetails[sessionId]
        if (existing?.loaded == true || existing?.loading == true) return

        _uiState.value = _uiState.value.copy(
            recordDetails = _uiState.value.recordDetails +
                (sessionId to SessionRecordDetails(loading = true)),
        )

        recordPickupJobs[sessionId]?.cancel()
        recordPickupJobs[sessionId] = viewModelScope.launch {
            db.phonePickupDao().observeBySessionId(sessionId).collect { rows ->
                val current = _uiState.value.recordDetails[sessionId]
                    ?: SessionRecordDetails(loading = true)
                _uiState.value = _uiState.value.copy(
                    recordDetails = _uiState.value.recordDetails +
                        (sessionId to current.copy(
                            loading = false,
                            loaded = true,
                            pickups = rows.map { e ->
                                RecordPickupDetail(
                                    pickedUpAt = e.pickedUpAt,
                                    durationSeconds = e.durationSeconds,
                                )
                            },
                        )),
                )
            }
        }

        // One-shot network refresh in parallel. Mirrors backend canonical
        // state into Room; the Flow above re-emits, UI updates.
        viewModelScope.launch {
            runCatching { repository.getPickupsForSession(sessionId) }
        }
    }

    fun completeSession() {
        timerJob?.cancel()
        pickupPollJob?.cancel()
        stopFocusTrackingService()
        val state = _uiState.value

        // Wall-clock duration minus any time spent paused. Folds in the current
        // pause interval if the user hit Complete while paused.
        val now = System.currentTimeMillis()
        val livePauseMillis = if (pauseStartMillis > 0L) now - pauseStartMillis else 0L
        val totalPausedMillis = pausedAccumMillis + livePauseMillis
        val totalMillis = (now - sessionStartMillis - totalPausedMillis).coerceAtLeast(0L)
        val totalMinutes = (totalMillis / 60_000L).toInt()

        val linkedItemId = state.selectedChecklistItemId
        val linkedItemTitle = state.openChecklistItems.firstOrNull { it.id == linkedItemId }?.title

        val completedSessionId = currentSessionId
        // §v2.16.18 — Snapshot the session window before we null it out;
        // savePickupEvents queries PhoneUsageTracker for the per-event
        // detail across the same range that produced the count.
        val pickupWindowStart = sessionStartMillis
        viewModelScope.launch {
            completedSessionId?.let { id ->
                repository.completeSession(id, totalMinutes, state.phonePickups)
                // §v2.16.18 — Save the per-pickup timeline so the past
                // session card can render it. Best-effort: even if upload
                // fails, the session row already carries the aggregate
                // count. PhoneUsageTracker reads from UsageStatsManager
                // which keeps history, so we can capture the whole
                // window in one shot rather than long-polling.
                if (pickupWindowStart > 0L) {
                    runCatching {
                        val events = usageTracker.getPickupEventsSince(pickupWindowStart)
                        if (events.isNotEmpty()) {
                            repository.savePickupEvents(id, userId, events)
                        }
                    }
                }
            }
            currentSessionId = null
            sessionStartMillis = 0L
            pausedAccumMillis = 0L
            pauseStartMillis = 0L
            _uiState.value = _uiState.value.copy(
                timerState = TimerState.IDLE,
                timeRemainingSeconds = 0,
                totalTimeSeconds = 0,
                isOvertime = false,
                overtimeSeconds = 0,
                phonePickups = 0,
                selectedChecklistItemId = null,
                completionPrompt = if (linkedItemId != null && linkedItemTitle != null) {
                    ChecklistCompletionPrompt(itemId = linkedItemId, title = linkedItemTitle)
                } else null,
                // §9.7 prompt for a 1-line debrief on every completed session.
                // Only show if the session actually got persisted (real ID).
                debriefPrompt = completedSessionId?.let { SessionDebriefPrompt(sessionId = it) },
            )
        }
    }

    fun confirmChecklistCompletion() {
        val prompt = _uiState.value.completionPrompt ?: return
        viewModelScope.launch {
            // v2.13.2 — Branch on recurrence. The Checklist tab's "open
            // today" query filters by lastCompletedEpochDay != today, so
            // a plain markCompleted (which only flips the `completed`
            // boolean) leaves a recurring item visible in today's list
            // even after the focus session ends. Use the same recurring
            // path the Checklist tab's checkbox uses so behaviour matches.
            val item = db.checklistDao().getById(prompt.itemId)
            val isRecurring = item?.recurrenceDaysMask != null && item.recurrenceDaysMask != 0
            if (isRecurring) {
                val todayEpochDay = java.time.LocalDate.now().toEpochDay()
                checklistRepository.markRecurringCompletedOn(prompt.itemId, todayEpochDay)
            } else {
                checklistRepository.markCompleted(prompt.itemId)
            }
            _uiState.value = _uiState.value.copy(completionPrompt = null)
        }
    }

    fun dismissChecklistCompletion() {
        _uiState.value = _uiState.value.copy(completionPrompt = null)
    }

    // ── §9.7 debrief prompt ────────────────────────────────────────────────

    fun updateDebriefText(text: String) {
        val cur = _uiState.value.debriefPrompt ?: return
        // Hard-cap at 240 to match the backend validator and keep the field
        // from accepting more than will fit.
        val trimmed = if (text.length > 240) text.take(240) else text
        _uiState.value = _uiState.value.copy(
            debriefPrompt = cur.copy(text = trimmed),
        )
    }

    fun submitDebrief() {
        val cur = _uiState.value.debriefPrompt ?: return
        val text = cur.text.trim()
        if (text.isEmpty() || cur.submitState is DebriefSubmitState.Submitting) return

        _uiState.value = _uiState.value.copy(
            debriefPrompt = cur.copy(submitState = DebriefSubmitState.Submitting),
        )
        viewModelScope.launch {
            try {
                val resp = api.submitSessionDebrief(
                    cur.sessionId,
                    com.ultiq.app.data.remote.dto.SessionDebriefRequestDto(text = text),
                )
                // Server is the source of truth — next sync will pull the
                // saved fields into Room. We surface the tag in the dialog
                // immediately so the user gets the feedback now.
                _uiState.value = _uiState.value.copy(
                    debriefPrompt = cur.copy(
                        text = text,
                        submitState = DebriefSubmitState.Tagged(resp.debrief_tag),
                    ),
                )
            } catch (e: retrofit2.HttpException) {
                val msg = when (e.code()) {
                    429 -> "Daily AI limit reached — back tomorrow"
                    400 -> "Couldn't process that text"
                    else -> "Couldn't tag the session"
                }
                _uiState.value = _uiState.value.copy(
                    debriefPrompt = cur.copy(submitState = DebriefSubmitState.Error(msg)),
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    debriefPrompt = cur.copy(submitState = DebriefSubmitState.Error("Couldn't tag the session")),
                )
            }
        }
    }

    fun dismissDebrief() {
        _uiState.value = _uiState.value.copy(debriefPrompt = null)
    }

    private fun startFocusTrackingService() {
        val app = getApplication<Application>()
        val intent = Intent(app, FocusTrackingService::class.java).apply {
            putExtra(FocusTrackingService.EXTRA_WORK_DURATION_MIN, _uiState.value.workDuration)
        }
        ContextCompat.startForegroundService(app, intent)
    }

    private fun stopFocusTrackingService() {
        val app = getApplication<Application>()
        app.stopService(Intent(app, FocusTrackingService::class.java))
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    /**
     * Wall-clock anchored timer. Every tick we recompute remaining/overtime from
     * (now − sessionStart − pausedAccum); the coroutine's `delay` cadence only drives
     * how often the UI refreshes, not how time is measured. This keeps duration
     * accurate even when Android throttles the coroutine in the background.
     */
    private fun startTimer() {
        timerJob?.cancel()
        timerJob = viewModelScope.launch {
            while (true) {
                val current = _uiState.value
                if (current.timerState != TimerState.RUNNING) break

                val plannedSec = current.workDuration * 60
                val elapsedMillis = System.currentTimeMillis() - sessionStartMillis - pausedAccumMillis
                val elapsedSec = (elapsedMillis / 1000L).toInt().coerceAtLeast(0)

                _uiState.value = if (elapsedSec < plannedSec) {
                    current.copy(
                        timeRemainingSeconds = plannedSec - elapsedSec,
                        isOvertime = false,
                        overtimeSeconds = 0,
                    )
                } else {
                    current.copy(
                        timeRemainingSeconds = 0,
                        isOvertime = true,
                        overtimeSeconds = elapsedSec - plannedSec,
                    )
                }
                delay(500)
            }
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
