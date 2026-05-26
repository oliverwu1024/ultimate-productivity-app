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
import com.ultiq.app.data.local.entity.SleepAudioEventEntity
import com.ultiq.app.data.local.entity.SleepRecordEntity
import com.ultiq.app.data.remote.RetrofitClient
import com.ultiq.app.data.remote.dto.CreateSleepRecordDto
import com.ultiq.app.data.remote.dto.SleepRatingRequestDto
import com.ultiq.app.data.remote.dto.SleepStats
import com.ultiq.app.data.remote.dto.toLocalStats
import com.ultiq.app.data.repository.SleepRepository
import com.ultiq.app.data.achievements.AchievementChecker
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
    // First-visit explainer card.
    val showSleepExplainer: Boolean = false,
    // Live UserPreferences snapshot, used by the SLEEP PREFERENCES section.
    val settings: UserSettings? = null,
    // §10 — counts of debounced YAMNet events for the most-recent sleep_record,
    // rendered as the "Tonight's sounds" card on the Sleep tab when at least
    // one event was captured.
    val tonightSnoreCount: Int = 0,
    val tonightCoughCount: Int = 0,
    val tonightSleepRecordId: String? = null,
    val tonightSleepBedtimeMs: Long = 0L,
    // §10 — Audio events snapshotted at session end for the End Sleep dialog
    // so it can render snore/cough counts alongside the pickup summary.
    val endedAudioEvents: List<SleepAudioEventEntity> = emptyList(),
    // §10 — AI sleep rating state for the End Sleep dialog. Loading flips
    // true between the button tap and the response; on success, result holds
    // (rating, reasoning); on failure, error holds a user-facing message.
    val aiRatingLoading: Boolean = false,
    val aiRatingResult: AiSleepRating? = null,
    val aiRatingError: String? = null,
    // §10 — Lazily-loaded detail for each past sleep_record, keyed by record id.
    // Populated on first expansion in the records list; subsequent expansions
    // hit the cache. Loading + audio events + pickup events are all bundled
    // so SleepRecordItem only reads one entry.
    val recordDetails: Map<String, SleepRecordDetails> = emptyMap(),
)

/// §10 — Haiku's 1-5 rating + one-line reasoning for the just-ended session.
data class AiSleepRating(
    val rating: Int,
    val reasoning: String,
)

/// §10 — Expanded detail for a single sleep_record on the Sleep tab. Pickup
/// events come from the backend (`/phone-pickups?sleep_id=…`), audio events
/// come from Room. Both populate together on first expansion.
data class SleepRecordDetails(
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val pickups: List<RecordPickupDetail> = emptyList(),
    val audioEvents: List<SleepAudioEventEntity> = emptyList(),
)

data class RecordPickupDetail(
    val pickedUpAt: Long,
    val durationSeconds: Int,
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
    private val repository = SleepRepository(
        sleepDao = db.sleepDao(),
        apiService = api,
        achievementChecker = achievementChecker,
        sleepAudioEventDao = db.sleepAudioEventDao(),
    )

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
        // v2.13.3 — Dropped observeAchievementEvents(). Achievements still
        // record to Room via AchievementChecker; the user reviews them in
        // Settings → Achievements instead of a mid-screen popup.
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
     * §10 — Toggle on-device snore + cough tracking during sleep sessions.
     * The Compose layer is responsible for ensuring RECORD_AUDIO is granted
     * before flipping this to true; the SleepTrackingService also re-checks
     * the permission at session start and silently skips audio capture if
     * the user revoked it via system settings.
     */
    fun setAudioTrackingEnabled(enabled: Boolean) = viewModelScope.launch {
        userPreferences.setAudioTrackingEnabled(enabled)
        pushPrefs { addProperty("audio_tracking_enabled", enabled) }
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
        // §10.x (v2.11.6) — Force the audio aggregator to finalise any
        // in-flight snore/cough event BEFORE we snapshot pendingAudioEvents.
        // Without this, the End Sleep dialog showed nothing for sessions
        // whose final snore/cough run hadn't yet hit its 5 s gap-close at
        // session-end (the run only got flushed later inside the service's
        // onDestroy, by which time the snapshot below was already done).
        // Persistence reads pendingAudioEvents fresh inside saveSessionRecord
        // so past records were correct — this is purely a dialog-refresh fix.
        SleepTrackingService.flushAudioNow()
        // Snapshot the data before stopping. §10 — audio events were
        // accumulated in the service's static state during the session; copy
        // them out here so the End Sleep dialog can render snore/cough counts
        // and so the AI sleep-rating call can use them in its stats prompt.
        _uiState.value = _uiState.value.copy(
            endedSessionStart = SleepTrackingService.sessionStartTime.value,
            endedPickupEvents = SleepTrackingService.pickupEvents.value,
            endedAudioEvents = SleepTrackingService.pendingAudioEvents.value,
            showEndSleepDialog = true,
            // Clear any leftover AI rating from a prior dialog session.
            aiRatingLoading = false,
            aiRatingResult = null,
            aiRatingError = null,
        )
        context.stopService(Intent(context, SleepTrackingService::class.java))
    }

    /**
     * §10 — Request a Haiku-generated 1-5 sleep rating for the session
     * captured in [SleepUiState.endedSessionStart] + [endedPickupEvents] +
     * [endedAudioEvents]. The result lives in [SleepUiState.aiRatingResult]
     * for the End Sleep dialog to render and offer as a one-tap fill of the
     * self-rate stars. Re-tapping while loading is a no-op.
     */
    fun requestAiSleepRating() {
        val state = _uiState.value
        if (state.aiRatingLoading) return

        val actualMinutes =
            ((System.currentTimeMillis() - state.endedSessionStart) / 60_000L).coerceAtLeast(0L)

        // Target duration from the session's target bedtime → target wake
        // window the user confirmed at session start. Wake earlier than
        // bedtime means the target crosses midnight, so wrap +24h.
        val bMinutes = state.sessionTargetBedtime.toSecondOfDay() / 60
        val wMinutes = state.sessionTargetWakeTime.toSecondOfDay() / 60
        val targetMinutes = if (wMinutes > bMinutes) {
            (wMinutes - bMinutes).toLong()
        } else {
            (wMinutes + 24 * 60 - bMinutes).toLong()
        }.coerceAtLeast(1L)

        val pickupCount = state.endedPickupEvents.size
        val pickupSeconds = state.endedPickupEvents.sumOf { it.durationSeconds }
        val pickupMinutes = pickupSeconds / 60
        val snoreCount = state.endedAudioEvents.count { it.eventType == "snore" }
        val coughCount = state.endedAudioEvents.count { it.eventType == "cough" }

        _uiState.value = _uiState.value.copy(aiRatingLoading = true, aiRatingError = null)
        viewModelScope.launch {
            runCatching {
                api.aiSleepRating(
                    SleepRatingRequestDto(
                        actual_minutes = actualMinutes,
                        target_minutes = targetMinutes,
                        pickup_count = pickupCount,
                        pickup_minutes = pickupMinutes,
                        snore_count = snoreCount,
                        cough_count = coughCount,
                    )
                )
            }.fold(
                onSuccess = { resp ->
                    _uiState.value = _uiState.value.copy(
                        aiRatingLoading = false,
                        aiRatingResult = AiSleepRating(resp.rating, resp.reasoning),
                        aiRatingError = null,
                    )
                },
                onFailure = { e ->
                    _uiState.value = _uiState.value.copy(
                        aiRatingLoading = false,
                        aiRatingResult = null,
                        aiRatingError = e.toUserMessage("AI rating unavailable. Try again or rate yourself."),
                    )
                },
            )
        }
    }

    fun clearAiRating() {
        _uiState.value = _uiState.value.copy(
            aiRatingLoading = false,
            aiRatingResult = null,
            aiRatingError = null,
        )
    }

    /**
     * §10 — Lazily fetch pickup + snore/cough detail for a past sleep_record
     * when the user taps to expand its card. Audio events come from Room
     * (already cached locally by Phase 10 §10.5); pickups come from the
     * backend over the wire. Subsequent expansions short-circuit on the
     * cached `loaded=true` flag.
     */
    fun fetchRecordDetails(recordId: String) {
        val existing = _uiState.value.recordDetails[recordId]
        if (existing?.loaded == true || existing?.loading == true) return

        _uiState.value = _uiState.value.copy(
            recordDetails = _uiState.value.recordDetails +
                (recordId to SleepRecordDetails(loading = true)),
        )

        viewModelScope.launch {
            // §10-backfill (v2.13.14) — Local Room is empty for older
            // records on a fresh install (we never used to pull audio
            // events back from the server). If the local query returns
            // nothing, fall through to a per-record server fetch which
            // also populates Room as a side-effect, so the same expansion
            // is instant next time.
            val localAudio = runCatching {
                db.sleepAudioEventDao().getBySleepRecord(recordId)
            }.getOrDefault(emptyList())
            val audioEvents = if (localAudio.isNotEmpty()) {
                localAudio
            } else {
                runCatching { repository.fetchAudioEventsForRecord(recordId) }
                    .getOrDefault(emptyList())
            }

            val pickups = runCatching {
                repository.getPickupsForSleep(recordId).map { dto ->
                    RecordPickupDetail(
                        pickedUpAt = Instant.parse(dto.picked_up_at).toEpochMilli(),
                        durationSeconds = dto.duration_seconds,
                    )
                }.sortedBy { it.pickedUpAt }
            }.getOrDefault(emptyList())

            _uiState.value = _uiState.value.copy(
                recordDetails = _uiState.value.recordDetails +
                    (recordId to SleepRecordDetails(
                        loading = false,
                        loaded = true,
                        pickups = pickups,
                        audioEvents = audioEvents,
                    )),
            )
        }
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

            // §10 — snapshot the audio events the service captured during
            // this session before they're cleared. We pass the freshly-
            // created sleep_record.id back to the repository so the events
            // can be stamped + persisted + uploaded as a batch.
            val capturedAudioEvents = SleepTrackingService.pendingAudioEvents.value

            val result = repository.createSleepRecord(dto, userId)

            result.getOrNull()?.let { record ->
                if (capturedAudioEvents.isNotEmpty()) {
                    runCatching {
                        repository.saveAudioEvents(
                            sleepRecordId = record.id,
                            userId = userId,
                            events = capturedAudioEvents,
                        )
                    }
                    // §10 — Refresh the "Sleep sounds" card immediately. The
                    // records Flow already emitted (when the sleep_record was
                    // inserted), and at that moment audio events weren't in
                    // Room yet, so the collector wrote counts=0. The Flow
                    // doesn't re-emit on audio-events-only writes, so without
                    // this explicit refresh the card stays stuck at 0 until
                    // some unrelated Flow trigger fires (next sync, etc.) —
                    // observed as a ~1 min delay during dogfood.
                    updateTonightAudioCounts(record)
                }
                // §10 — Upload the per-pickup detail so the past-records
                // expansion can render the full timeline. Failure here is
                // non-fatal: the sleep_record already carries the count +
                // total_minutes summary.
                if (state.endedPickupEvents.isNotEmpty()) {
                    runCatching {
                        repository.savePickupEvents(record.id, state.endedPickupEvents)
                    }
                }
            }
            SleepTrackingService.pendingAudioEvents.value = emptyList()

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
                updateTonightAudioCounts(records.firstOrNull())
            }
        }
    }

    /**
     * §10 — Refresh "Tonight's sounds" counts whenever the records list
     * changes. The "tonight" record is just the most-recent sleep_record;
     * if it has no audio events the card hides via the count == 0 check.
     */
    private suspend fun updateTonightAudioCounts(latest: SleepRecordEntity?) {
        if (latest == null) {
            _uiState.value = _uiState.value.copy(
                tonightSnoreCount = 0,
                tonightCoughCount = 0,
                tonightSleepRecordId = null,
                tonightSleepBedtimeMs = 0L,
            )
            return
        }
        val dao = db.sleepAudioEventDao()
        val snore = runCatching { dao.countByType(latest.id, "snore") }.getOrDefault(0)
        val cough = runCatching { dao.countByType(latest.id, "cough") }.getOrDefault(0)
        _uiState.value = _uiState.value.copy(
            tonightSnoreCount = snore,
            tonightCoughCount = cough,
            tonightSleepRecordId = latest.id,
            tonightSleepBedtimeMs = latest.actualBedtime,
        )
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
            // §sleep-day (v2.13.17) — Period bucketing matches the Dashboard
            // and chart: Tue 02:00 bedtimes count toward Monday's week.
            val day = com.ultiq.app.util.sleepDayFor(r.actualBedtime, zone)
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
