package com.ultiq.app.ui.sleep

import android.app.Application
import android.content.Intent
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.JsonObject
import com.ultiq.app.alarm.WakeAlarmScheduler
import com.ultiq.app.R
import com.ultiq.app.data.local.AppDatabase
import com.ultiq.app.data.local.entity.AlarmEntity
import com.ultiq.app.data.local.entity.SleepAudioEventEntity
import com.ultiq.app.data.local.entity.SleepRecordEntity
import com.ultiq.app.data.remote.RetrofitClient
import com.ultiq.app.data.remote.dto.CreateSleepRecordDto
import com.ultiq.app.data.remote.dto.SleepRatingRequestDto
import com.ultiq.app.data.remote.dto.SleepStats
import com.ultiq.app.data.remote.dto.toLocalStats
import com.ultiq.app.data.repository.SleepAudioUploadWorker
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
    val tonightSleepTalkCount: Int = 0,
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
    // §10.x-fix (banner) — Count of audio events still waiting to upload to
    // the backend. Drives the "Last night's sounds haven't synced yet"
    // banner on the Sleep tab. Excludes rows from a still-live session
    // (those use a pending-* placeholder sleepRecordId).
    val unsyncedAudioEventCount: Int = 0,
    // §10.x-fix — Set true when the in-session retry of the events batch
    // exhausted at session-end. Cleared the moment the unsynced count
    // hits zero (WorkManager succeeded). Drives an inline toast on the
    // End Sleep dialog / Sleep tab.
    val lastUploadFailed: Boolean = false,
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
        syncStateStore = com.ultiq.app.data.repository.SyncStateStore(application),
        phonePickupDao = db.phonePickupDao(),
    )

    private val _uiState = MutableStateFlow(SleepUiState())
    val uiState: StateFlow<SleepUiState> = _uiState

    private var userId: String = ""
    private var recordsJob: Job? = null
    // §v2.15.4 — Connectivity callback; nullable because device may refuse
    // to register (max-callbacks SecurityException is non-fatal).
    private var networkCallback: android.net.ConnectivityManager.NetworkCallback? = null

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
        // §10.x-fix (banner) — Reactive count of audio events still waiting
        // to upload. Banner appears whenever > 0, disappears whenever
        // WorkManager catches up.
        //
        // §v2.16.17 — Removed v2.16.16 `refreshLoadedRecordDetails` hook.
        // That hook fired on count decrease (worker step 2 markSyncedBatch)
        // but the has_clip flip lands at worker step 5
        // (fetchAudioEventsForRecord deleteBySleepRecord+insertAll). So the
        // hook always read stale Room state. fetchRecordDetails now
        // subscribes to `observeBySleepRecord` per cached recordId — any
        // Room write at step 2, step 5, or any future write path triggers
        // recomposition.
        viewModelScope.launch {
            repository.observeUnsyncedAudioEventCount()?.collect { n ->
                _uiState.value = _uiState.value.copy(
                    unsyncedAudioEventCount = n,
                    // Clear the louder "last upload failed" toast once
                    // everything's synced — the banner is the right level
                    // of UI urgency for the steady-state case.
                    lastUploadFailed = if (n == 0) false else _uiState.value.lastUploadFailed,
                )
            }
        }
        // §v2.15.4 — Listen for connectivity becoming available and force
        // a fresh worker enqueue (REPLACE) so any pending backoff timer
        // gets reset and the worker fires immediately. Without this, the
        // user disabling airplane mode could wait minutes for the next
        // WorkManager retry to elapse before the banner clears.
        registerNetworkCallback()
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

    // §10.x — Sleep-talk is an independent on/off that adds YAMNet's
    // `Speech` class to the detector. The classifier only extracts the
    // Speech score when this is true, so detection-wise it's "free" until
    // the user explicitly opts in.
    fun setSleepTalkDetectionEnabled(enabled: Boolean) = viewModelScope.launch {
        userPreferences.setSleepTalkDetectionEnabled(enabled)
        pushPrefs { addProperty("sleep_talk_detection_enabled", enabled) }
    }

    // §10.x — Pro-tier master toggle for storing audio clips of detected
    // events. Per-type sub-toggles below are AND-gated with this; turning
    // the master off effectively disables all three per-type flags.
    fun setSleepAudioRecordingEnabled(enabled: Boolean) = viewModelScope.launch {
        userPreferences.setSleepAudioRecordingEnabled(enabled)
        pushPrefs { addProperty("sleep_audio_recording_enabled", enabled) }
    }

    fun setSleepAudioRecordSnore(enabled: Boolean) = viewModelScope.launch {
        userPreferences.setSleepAudioRecordSnore(enabled)
        pushPrefs { addProperty("sleep_audio_record_snore", enabled) }
    }

    fun setSleepAudioRecordCough(enabled: Boolean) = viewModelScope.launch {
        userPreferences.setSleepAudioRecordCough(enabled)
        pushPrefs { addProperty("sleep_audio_record_cough", enabled) }
    }

    fun setSleepAudioRecordSleepTalk(enabled: Boolean) = viewModelScope.launch {
        userPreferences.setSleepAudioRecordSleepTalk(enabled)
        pushPrefs { addProperty("sleep_audio_record_sleep_talk", enabled) }
    }

    fun markSleepAudioRecordingConsentSeen() = viewModelScope.launch {
        userPreferences.setSleepAudioRecordingConsentSeen(true)
    }

    /**
     * §10.x — Fetch a short-lived presigned GET URL for a clip's playback.
     * Returns null on any error (auth, network, 404 because the clip
     * expired, 402 because the user is no longer Pro). The caller should
     * surface a short toast or skip silently rather than crashing — clips
     * are best-effort by design.
     */
    suspend fun fetchClipPlaybackUrl(eventId: String): String? {
        return runCatching {
            api.getSleepAudioClipPlaybackUrl(eventId).get_url
        }.getOrNull()
    }

    /**
     * §10.x — Delete a single clip from the backend (S3 + DB column).
     * The detection event row stays — stats are still accurate.
     *
     * §10.x-fix (v2.14.2) — Three updates have to land for the UI to
     * actually flip the row out of its expanded-player state and lose
     * the ▶ icon:
     *   1. Server: DELETE /sleep-audio-events/:id/clip (S3 + DB)
     *   2. Local Room: fetchAudioEventsForRecord re-pulls server truth
     *   3. ViewModel cache: recordDetails.audioEvents must reflect the
     *      new state, otherwise the cached snapshot keeps rendering the
     *      old hasClip=true row → the expanded player re-fires the
     *      clip-url fetch → backend now 404s → "Couldn't load clip".
     * The optimistic copy below patches (3) immediately (don't wait for
     * the network round-trip) so the user sees the row collapse + flip
     * to "no clip" the instant they tap Delete.
     */
    fun deleteClip(eventId: String, sleepRecordId: String, onDone: () -> Unit = {}) {
        // Optimistic local patch first so the UI updates instantly.
        _uiState.value = _uiState.value.copy(
            recordDetails = _uiState.value.recordDetails.let { map ->
                val existing = map[sleepRecordId] ?: return@let map
                val patched = existing.copy(
                    audioEvents = existing.audioEvents.map { ev ->
                        if (ev.id == eventId) ev.copy(hasClip = false, clipDurationMs = null) else ev
                    },
                )
                map + (sleepRecordId to patched)
            },
        )
        onDone()
        viewModelScope.launch {
            runCatching { api.deleteSleepAudioClip(eventId) }
            // Reconcile with server truth (covers the case where the
            // delete failed on the network — we'd otherwise be showing a
            // ghost no-clip state). Re-fetch wipes + re-inserts Room and
            // re-emits via fetchRecordDetails on the next collect.
            runCatching { repository.fetchAudioEventsForRecord(sleepRecordId) }
            val events = runCatching {
                db.sleepAudioEventDao().getBySleepRecord(sleepRecordId)
            }.getOrNull() ?: return@launch
            _uiState.value = _uiState.value.copy(
                recordDetails = _uiState.value.recordDetails + (
                    sleepRecordId to SleepRecordDetails(
                        loading = false,
                        loaded = true,
                        pickups = _uiState.value.recordDetails[sleepRecordId]?.pickups ?: emptyList(),
                        audioEvents = events,
                    )
                ),
            )
        }
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
                SleepTrackingService.pickupEvents,
                SleepTrackingService.sessionTargetBedtimeMin,
                SleepTrackingService.sessionTargetWakeMin,
            ) { running, startTime, events, bedMin, wakeMin ->
                val cur = _uiState.value
                _uiState.value = cur.copy(
                    isSessionActive = running,
                    sessionStartTime = startTime,
                    pickupEvents = events,
                    // §sleep-state-fix — Restore the target window from the
                    // service (the durable source of truth) whenever a session
                    // is live, so a ViewModel recreated mid-session — e.g. the
                    // app reopened in the morning to End Sleep — uses the
                    // ORIGINAL target instead of this ViewModel's
                    // LocalTime.now()/+8h construction defaults.
                    sessionTargetBedtime = if (running && bedMin >= 0)
                        minuteOfDayToLocalTime(bedMin) else cur.sessionTargetBedtime,
                    sessionTargetWakeTime = if (running && wakeMin >= 0)
                        minuteOfDayToLocalTime(wakeMin) else cur.sessionTargetWakeTime,
                )
            }.collect {}
        }
    }

    fun startSleepSession() {
        if (FocusTrackingService.isRunning.value) {
            _uiState.value = _uiState.value.copy(
                error = getApplication<Application>().getString(R.string.sleep_err_end_focus),
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
        // §sleep-state-fix — Capture bedtime once so the same value reaches
        // the UI, the start Intent, and the durable store.
        val targetBedtime = LocalTime.now()
        _uiState.value = _uiState.value.copy(
            showSetTargetDialog = false,
            sessionTargetBedtime = targetBedtime,
            sessionTargetWakeTime = targetWakeTime,
        )
        val context = getApplication<Application>()
        // §10.x-fix (4th piece) — Drop placeholder rows from any prior
        // session that never reached "End Sleep" (force-stop, crash,
        // forgotten session). Runs before the service starts so the new
        // session's pending-* rows don't accidentally get caught.
        viewModelScope.launch {
            repository.cleanupOrphanPendingEvents()
        }
        // §sleep-state-fix — Hand the target window to the service so it can
        // persist it durably and survive a process kill / ViewModel
        // recreation. The service restores these into the flows that
        // observeServiceState() reads.
        val intent = Intent(context, SleepTrackingService::class.java).apply {
            putExtra(SleepTrackingService.EXTRA_TARGET_BED_MIN, targetBedtime.toMinuteOfDay())
            putExtra(SleepTrackingService.EXTRA_TARGET_WAKE_MIN, targetWakeTime.toMinuteOfDay())
        }
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
        // §sleep-state-fix — Drop the durable snapshot BEFORE stopping the
        // service so a START_STICKY restart race can't resurrect a session
        // the user just ended.
        SleepTrackingService.clearLiveSession(context)
        context.stopService(Intent(context, SleepTrackingService::class.java))
    }

    /**
     * §10 — Request a Haiku-generated 1-5 sleep rating for the session
     * captured in [SleepUiState.endedSessionStart] + [endedPickupEvents] +
     * [endedAudioEvents]. The result lives in [SleepUiState.aiRatingResult]
     * for the End Sleep dialog to render and offer as a one-tap fill of the
     * self-rate stars. Re-tapping while loading is a no-op.
     */
    fun requestAiSleepRating(isNap: Boolean) {
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
        val sleepTalkCount = state.endedAudioEvents.count { it.eventType == "sleep_talk" }

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
                        sleep_talk_count = sleepTalkCount,
                        is_nap = isNap,
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

    // §v2.16.17 — Per-recordId Flow subscriptions. Without these, expanding
    // a record snapshotted Room exactly once and the cache stayed stale
    // even after the audio-upload worker (step 5 fetchAudioEventsForRecord)
    // or the pickup-save path (post-airplane refresh) updated the underlying
    // tables. Now any Room write to this record's audio events or pickups
    // triggers a recomposition with the new list. Cancelled when the
    // ViewModel scope dies.
    private val recordAudioEventJobs = mutableMapOf<String, Job>()
    private val recordPickupJobs = mutableMapOf<String, Job>()

    /**
     * §10 — Lazily fetch pickup + snore/cough detail for a past sleep_record
     * when the user taps to expand its card.
     *
     * §v2.16.17 — Restructured around Room Flows so post-load updates land
     * automatically. Both audio events AND pickups now subscribe per-record.
     * Pickup live data starts with the v2.16.17 PhonePickupEntity table —
     * older sessions saved before v2.16.17 have nothing in the local table
     * so the network refresh below is what populates them. Once populated
     * Room is the canonical local view and the Flow keeps the UI fresh.
     */
    fun fetchRecordDetails(recordId: String) {
        val existing = _uiState.value.recordDetails[recordId]
        if (existing?.loaded == true || existing?.loading == true) return

        _uiState.value = _uiState.value.copy(
            recordDetails = _uiState.value.recordDetails +
                (recordId to SleepRecordDetails(loading = true)),
        )

        // §v2.16.17 — Subscribe to audio events for this record. First emission
        // marks the entry as loaded; later emissions (worker writes, server
        // pulls, manual deletes) update audioEvents in place.
        recordAudioEventJobs[recordId]?.cancel()
        recordAudioEventJobs[recordId] = viewModelScope.launch {
            db.sleepAudioEventDao().observeBySleepRecord(recordId).collect { events ->
                val current = _uiState.value.recordDetails[recordId]
                    ?: SleepRecordDetails(loading = true)
                _uiState.value = _uiState.value.copy(
                    recordDetails = _uiState.value.recordDetails +
                        (recordId to current.copy(
                            audioEvents = events,
                            loading = false,
                            loaded = true,
                        )),
                )
            }
        }

        // §v2.16.17 — Subscribe to pickups for this record. Same shape as
        // audio events — Room is the local source of truth, the network
        // refresh below populates it from server canonical state.
        recordPickupJobs[recordId]?.cancel()
        recordPickupJobs[recordId] = viewModelScope.launch {
            db.phonePickupDao().observeBySleepRecord(recordId).collect { rows ->
                val current = _uiState.value.recordDetails[recordId]
                    ?: SleepRecordDetails(loading = true)
                _uiState.value = _uiState.value.copy(
                    recordDetails = _uiState.value.recordDetails +
                        (recordId to current.copy(
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

        // §v2.16.17 — Kick off a one-shot network refresh in parallel.
        // - Audio events: §10-backfill — pulls from server when local Room
        //   is empty for older records (fresh install scenario).
        // - Pickups: refresh from server so any rows that the offline
        //   save couldn't upload get a server-issued id + app_category.
        // Both writes land in Room → the Flow subscriptions above re-emit
        // → UI updates without any extra plumbing.
        viewModelScope.launch {
            val localAudioCount = runCatching {
                db.sleepAudioEventDao().getBySleepRecord(recordId).size
            }.getOrDefault(0)
            if (localAudioCount == 0) {
                runCatching { repository.fetchAudioEventsForRecord(recordId) }
            }
            runCatching { repository.getPickupsForSleep(recordId) }
        }
    }

    fun saveSessionRecord(qualityRating: Int, notes: String?, isNap: Boolean) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, showEndSleepDialog = false)

            val state = _uiState.value
            val bedtime = Instant.ofEpochMilli(state.endedSessionStart)
            val wakeTime = Instant.now()
            val totalPhoneSeconds = state.endedPickupEvents.sumOf { it.durationSeconds }
            val phoneMinutes = totalPhoneSeconds / 60

            val dto = CreateSleepRecordDto(
                // §v2.16.15 — Client-side UUID for the backend's
                // idempotent upsert path. If the POST is retried (network
                // blip, worker pass, etc.), the same UUID makes the
                // backend ON CONFLICT DO NOTHING return the existing
                // row instead of inserting a duplicate.
                id = java.util.UUID.randomUUID().toString(),
                target_bedtime = formatTargetTime(state.sessionTargetBedtime),
                target_wake_time = formatTargetTime(state.sessionTargetWakeTime),
                actual_bedtime = bedtime.toString(),
                actual_wake_time = wakeTime.toString(),
                quality_rating = qualityRating,
                phone_pickups = state.endedPickupEvents.size,
                total_phone_minutes = if (phoneMinutes > 0) phoneMinutes else null,
                notes = notes,
                is_nap = isNap
            )

            // §10 — snapshot the audio events the service captured during
            // this session before they're cleared. We pass the freshly-
            // created sleep_record.id back to the repository so the events
            // can be stamped + persisted + uploaded as a batch.
            val capturedAudioEvents = SleepTrackingService.pendingAudioEvents.value
            // §10.x-fix (4th piece) — Snapshot the placeholder id before
            // the service gets cleared. saveAudioEvents() uses it to
            // relink any rows the live session wrote to Room.
            val pendingSessionId = SleepTrackingService.currentPendingSessionId.value
                .takeIf { it.isNotEmpty() }

            val result = repository.createSleepRecord(dto, userId)

            result.getOrNull()?.let { record ->
                if (capturedAudioEvents.isNotEmpty() || pendingSessionId != null) {
                    // §10.x-fix — Stop swallowing the upload Result. If the
                    // in-session retry exhausted, set the lastUploadFailed
                    // flag so the End Sleep dialog can show an inline
                    // warning. WorkManager will keep retrying in the
                    // background regardless of this outcome.
                    val uploadResult = repository.saveAudioEvents(
                        sleepRecordId = record.id,
                        userId = userId,
                        events = capturedAudioEvents,
                        pendingSessionId = pendingSessionId,
                    )
                    if (uploadResult.isFailure) {
                        _uiState.value = _uiState.value.copy(lastUploadFailed = true)
                    }
                    // §10 — Refresh the "Sleep sounds" card immediately. The
                    // records Flow already emitted (when the sleep_record was
                    // inserted), and at that moment audio events weren't in
                    // Room yet, so the collector wrote counts=0. The Flow
                    // doesn't re-emit on audio-events-only writes, so without
                    // this explicit refresh the card stays stuck at 0 until
                    // some unrelated Flow trigger fires (next sync, etc.) —
                    // observed as a ~1 min delay during dogfood.
                    // §last-night — a just-saved nap shouldn't take over the
                    // "Sleep sounds" card; keep it on the latest overnight sleep.
                    val latestNonNap = if (!record.isNap) record
                        else _uiState.value.records.firstOrNull { !it.isNap }
                    updateTonightAudioCounts(latestNonNap)
                }
                // §10 — Upload the per-pickup detail so the past-records
                // expansion can render the full timeline. Failure here is
                // non-fatal: the sleep_record already carries the count +
                // total_minutes summary.
                if (state.endedPickupEvents.isNotEmpty()) {
                    runCatching {
                        repository.savePickupEvents(record.id, userId, state.endedPickupEvents)
                    }
                }
            }
            SleepTrackingService.pendingAudioEvents.value = emptyList()
            SleepTrackingService.currentPendingSessionId.value = ""
            // §10.x-fix (WorkManager) — Schedule the durable retry job
            // regardless of whether the in-session upload succeeded.
            // It's cheap when there's nothing to do (DAO count → 0 → no
            // work) and the safety net we need when there is.
            SleepAudioUploadWorker.enqueue(getApplication<Application>(), replace = true)

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

    /**
     * §v2.15.4 — Banner action. Force-fires the WorkManager job with
     * REPLACE policy: cancels any pending backoff timer, runs the
     * sleep-record sync + orphan sweep + events upload now (or as soon
     * as the network constraint is met). Idempotent — safe to spam.
     */
    fun retryUnsyncedNow() {
        SleepAudioUploadWorker.enqueue(
            getApplication<Application>(),
            replace = true,
        )
    }

    /**
     * §v2.15.4 — Register a ConnectivityManager callback that fires
     * whenever a network becomes available. Each fire re-enqueues the
     * sleep-audio worker with REPLACE so any in-flight exponential
     * backoff timer gets reset. Without this, the airplane-mode →
     * airplane-mode-off flow could wait minutes for the next scheduled
     * retry before the banner clears.
     */
    private fun registerNetworkCallback() {
        val app = getApplication<Application>()
        val cm = app.getSystemService(android.content.Context.CONNECTIVITY_SERVICE)
            as? android.net.ConnectivityManager ?: return
        val request = android.net.NetworkRequest.Builder()
            .addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            .build()
        networkCallback = object : android.net.ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) {
                // Only re-enqueue if there's something to retry — avoids
                // pinging WorkManager on every wifi switch in normal use.
                if (_uiState.value.unsyncedAudioEventCount > 0) {
                    SleepAudioUploadWorker.enqueue(app, replace = true)
                }
            }
        }
        try {
            cm.registerNetworkCallback(request, networkCallback!!)
        } catch (_: Throwable) {
            // Some devices throw SecurityException if too many callbacks
            // are registered; non-fatal — WorkManager backoff still
            // catches up eventually.
            networkCallback = null
        }
    }

    override fun onCleared() {
        super.onCleared()
        val cm = getApplication<Application>()
            .getSystemService(android.content.Context.CONNECTIVITY_SERVICE)
            as? android.net.ConnectivityManager
        networkCallback?.let { cb ->
            try { cm?.unregisterNetworkCallback(cb) } catch (_: Throwable) {}
        }
        networkCallback = null
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
                updateTonightAudioCounts(records.firstOrNull { !it.isNap })
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
                tonightSleepTalkCount = 0,
                tonightSleepRecordId = null,
                tonightSleepBedtimeMs = 0L,
            )
            return
        }
        val dao = db.sleepAudioEventDao()
        val snore = runCatching { dao.countByType(latest.id, "snore") }.getOrDefault(0)
        val cough = runCatching { dao.countByType(latest.id, "cough") }.getOrDefault(0)
        val talk = runCatching { dao.countByType(latest.id, "sleep_talk") }.getOrDefault(0)
        _uiState.value = _uiState.value.copy(
            tonightSnoreCount = snore,
            tonightCoughCount = cough,
            tonightSleepTalkCount = talk,
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

    // §sleep-state-fix — Compact minute-of-day encoding for the target window
    // carried through the start Intent + LiveSleepSessionStore.
    private fun LocalTime.toMinuteOfDay(): Int = hour * 60 + minute

    private fun minuteOfDayToLocalTime(min: Int): LocalTime =
        LocalTime.of((min / 60).coerceIn(0, 23), (min % 60).coerceIn(0, 59))
}
