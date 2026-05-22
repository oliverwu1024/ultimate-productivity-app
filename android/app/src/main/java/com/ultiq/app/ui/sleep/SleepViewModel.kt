package com.ultiq.app.ui.sleep

import android.app.Application
import android.content.Intent
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.JsonObject
import com.ultiq.app.alarm.WakeAlarmScheduler
import com.ultiq.app.data.local.AppDatabase
import com.ultiq.app.data.local.entity.AlarmEntity
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
import com.ultiq.app.util.AlarmScheduler
import com.ultiq.app.util.ReminderPreferences
import com.ultiq.app.util.TokenManager
import com.ultiq.app.util.UserPreferences
import com.ultiq.app.util.UserSettings
import com.ultiq.app.util.toUserMessage
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

/// Counts of nights logged in each calendar period. Displayed as a small
/// 2×2 grid above the Sleep stats card so the user can eyeball weekly /
/// monthly consistency at a glance without scrolling through records.
data class SleepPeriodCounts(
    val thisWeek: Int = 0,
    val lastWeek: Int = 0,
    val thisMonth: Int = 0,
    val lastMonth: Int = 0,
)

data class SleepUiState(
    val records: List<SleepRecordEntity> = emptyList(),
    val stats: SleepStats? = null,
    val periodCounts: SleepPeriodCounts = SleepPeriodCounts(),
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
    val showNoAlarmDialog: Boolean = false,
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
    // First-visit explainer card.
    val showSleepExplainer: Boolean = false,
    // Live UserPreferences snapshot, used by the SLEEP PREFERENCES section.
    val settings: UserSettings? = null,
)

private const val NEAREST_ALARM_HORIZON_HOURS = 24

class SleepViewModel(application: Application) : AndroidViewModel(application) {
    private val tokenManager = TokenManager(application)
    private val api = RetrofitClient.create(tokenManager)
    private val db = AppDatabase.getInstance(application)
    private val userPreferences = UserPreferences(application)
    private val reminderPreferences = ReminderPreferences(application)
    private val alarmScheduler = AlarmScheduler(application)
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
                showSleepExplainer = !settings.sleepExplainerSeen,
                settings = settings,
            )
            observeServiceState()
            loadRecords()
            sync()
        }
        // Continuous mirror so the SLEEP PREFERENCES cards stay in sync when
        // the user changes them.
        viewModelScope.launch {
            userPreferences.settings.collect { s ->
                _uiState.value = _uiState.value.copy(settings = s)
            }
        }
        observeAchievementEvents()
    }

    // ── Preference setters (moved out of SettingsViewModel) ──────────────────

    fun setTargetBedtime(time: LocalTime) = viewModelScope.launch {
        userPreferences.setTargetBedtime(time)
        // §fix-bedtime-unified — UserPreferences.targetBedtime is now the
        // single source of truth for the bedtime reminder; re-arm the
        // AlarmManager entry against the *new* value immediately so the
        // change takes effect tonight rather than the night after.
        alarmScheduler.applyDailyReminders(reminderPreferences.snapshot(), time)
        pushPrefs { addProperty("target_bedtime", "%02d:%02d".format(time.hour, time.minute)) }
    }

    fun setTargetWakeTime(time: LocalTime) = viewModelScope.launch {
        userPreferences.setTargetWakeTime(time)
        pushPrefs { addProperty("target_wake_time", "%02d:%02d".format(time.hour, time.minute)) }
    }

    fun setSleepTargetMinutes(minutes: Int) = viewModelScope.launch {
        userPreferences.setSleepTargetMinutes(minutes)
        runCatching {
            api.updateProfile(
                com.ultiq.app.data.remote.dto.UpdateProfileRequest(
                    sleep_target_minutes = minutes,
                ),
            )
        }
    }

    fun setLockoutForSleep(enabled: Boolean) = viewModelScope.launch {
        userPreferences.setLockoutForSleep(enabled)
        pushPrefs { addProperty("lockout_for_sleep", enabled) }
    }

    fun setSleepLockoutGraceMinutes(minutes: Int) = viewModelScope.launch {
        userPreferences.setSleepLockoutGraceMinutes(minutes)
        pushPrefs { addProperty("sleep_lockout_grace_minutes", minutes) }
    }

    /**
     * §sync-prefs: fire-and-forget push of a partial preferences blob to the
     * server. Failure is silent — the local DataStore is the source of truth
     * until the next successful sync replays the change.
     */
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

    fun dismissSleepPrefsHint() = viewModelScope.launch {
        userPreferences.setSleepPrefsHintSeen(true)
    }

    fun dismissSleepExplainer() {
        viewModelScope.launch {
            userPreferences.setSleepExplainerSeen(true)
            _uiState.value = _uiState.value.copy(showSleepExplainer = false)
        }
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
        viewModelScope.launch {
            val nearest = findNearestEnabledAlarmWithin(NEAREST_ALARM_HORIZON_HOURS)
            if (nearest != null) {
                // (b) Prefill the target-wake dialog with the user's own alarm
                // time instead of a hardcoded +8h. User can still override
                // for one-off early-wake nights.
                _uiState.value = _uiState.value.copy(
                    showSetTargetDialog = true,
                    sessionTargetBedtime = LocalTime.now(),
                    sessionTargetWakeTime = LocalTime.of(
                        nearest.triggerHour,
                        nearest.triggerMinute,
                    ),
                )
            } else {
                // (e) No alarm in the next 24h — prompt before sleeping. The
                // dialog has both [Set alarm] and [Sleep without one] so the
                // user is never blocked.
                _uiState.value = _uiState.value.copy(showNoAlarmDialog = true)
            }
        }
    }

    fun dismissSetTargetDialog() {
        _uiState.value = _uiState.value.copy(showSetTargetDialog = false)
    }

    fun dismissNoAlarmDialog() {
        _uiState.value = _uiState.value.copy(showNoAlarmDialog = false)
    }

    /**
     * "Sleep without one" path from the no-alarm dialog. Falls back to the
     * user's configured sleep target (e.g. 8h from now) as the suggested wake.
     */
    fun sleepWithoutAlarm() {
        viewModelScope.launch {
            val settings = userPreferences.snapshot()
            val fallbackWake = LocalTime.now().plusMinutes(settings.sleepTargetMinutes.toLong())
            _uiState.value = _uiState.value.copy(
                showNoAlarmDialog = false,
                showSetTargetDialog = true,
                sessionTargetBedtime = LocalTime.now(),
                sessionTargetWakeTime = fallbackWake,
            )
        }
    }

    /**
     * Find the enabled alarm whose next trigger is soonest within
     * [maxHours] from now. Returns null when no alarm will fire in that
     * window — that's the trigger for the no-alarm prompt.
     */
    private suspend fun findNearestEnabledAlarmWithin(maxHours: Int): AlarmEntity? {
        val now = System.currentTimeMillis()
        val cutoff = now + maxHours * 3_600_000L
        return db.alarmDao().getEnabledAlarmsSync()
            .mapNotNull { a ->
                WakeAlarmScheduler.computeNextTrigger(a, now)?.let { t -> a to t }
            }
            .filter { (_, t) -> t in (now + 1)..cutoff }
            .minByOrNull { (_, t) -> t }
            ?.first
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
                error = result.exceptionOrNull()?.toUserMessage("Couldn't save sleep. Try again.")
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
                error = result.exceptionOrNull()?.toUserMessage("Couldn't save sleep. Try again.")
            )
        }
    }

    private fun loadRecords() {
        recordsJob?.cancel()
        recordsJob = viewModelScope.launch {
            // §audit-2 — visible window is the calendar-period union of
            // "this {week,month}" + "last {week,month}". Week → 2 weeks
            // back from this Monday; Month → 2 months back from the 1st.
            // The SleepScreen section-grouping renders the two buckets
            // (with empty buckets hidden).
            val rangeStart = calendarRangeStartMillis(_uiState.value.selectedTimeRange)
            repository.getSleepRecordsBetween(rangeStart, Long.MAX_VALUE).collect { records ->
                val target = userPreferences.snapshot().sleepTargetMinutes
                _uiState.value = _uiState.value.copy(
                    records = records,
                    stats = records.toLocalStats(target),
                )
            }
        }
    }

    private fun calendarRangeStartMillis(range: TimeRange): Long {
        val zone = ZoneId.systemDefault()
        val today = java.time.LocalDate.now(zone)
        val date = when (range) {
            TimeRange.WEEK -> today
                .minusDays(today.dayOfWeek.value.toLong() - 1)
                .minusWeeks(1)
            TimeRange.MONTH -> today.withDayOfMonth(1).minusMonths(1)
        }
        return date.atStartOfDay(zone).toInstant().toEpochMilli()
    }

    /// Map a 60-day record list into the 4 period buckets the Sleep grid
    /// renders. Buckets use calendar-week (Mon..Sun) and calendar-month
    /// boundaries in the device's local timezone, matching the Mon-start
    /// week the rest of the app moved to in v2.10.2.
    @Suppress("unused")
    private fun computePeriodCounts(records: List<SleepRecordEntity>): SleepPeriodCounts {
        val zone = ZoneId.systemDefault()
        val today = java.time.LocalDate.now(zone)
        val mondayOfThisWeek = today.minusDays(today.dayOfWeek.value.toLong() - 1)
        val mondayOfLastWeek = mondayOfThisWeek.minusWeeks(1)
        val firstOfThisMonth = today.withDayOfMonth(1)
        val firstOfLastMonth = firstOfThisMonth.minusMonths(1)
        val lastDayOfLastMonth = firstOfThisMonth.minusDays(1)

        var thisWeek = 0
        var lastWeek = 0
        var thisMonth = 0
        var lastMonth = 0
        for (r in records) {
            val day = Instant.ofEpochMilli(r.actualBedtime).atZone(zone).toLocalDate()
            if (!day.isBefore(mondayOfThisWeek) && !day.isAfter(today)) thisWeek++
            if (!day.isBefore(mondayOfLastWeek) && day.isBefore(mondayOfThisWeek)) lastWeek++
            if (!day.isBefore(firstOfThisMonth) && !day.isAfter(today)) thisMonth++
            if (!day.isBefore(firstOfLastMonth) && !day.isAfter(lastDayOfLastMonth)) lastMonth++
        }
        return SleepPeriodCounts(thisWeek, lastWeek, thisMonth, lastMonth)
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
