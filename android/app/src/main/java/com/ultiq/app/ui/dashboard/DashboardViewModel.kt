package com.ultiq.app.ui.dashboard

import android.app.Application
import android.provider.Settings
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ultiq.app.data.achievements.AchievementId
import com.ultiq.app.data.local.AppDatabase
import com.ultiq.app.data.local.entity.CalendarEventEntity
import com.ultiq.app.data.local.entity.ChecklistEntity
import com.ultiq.app.data.local.entity.SleepRecordEntity
import com.ultiq.app.data.remote.RetrofitClient
import com.ultiq.app.data.repository.CalendarRepository
import com.ultiq.app.data.repository.ChecklistRepository
import com.ultiq.app.data.repository.SessionRepository
import com.ultiq.app.data.repository.SleepRepository
import com.ultiq.app.data.repository.SyncManager
import com.ultiq.app.ui.lockout.LockoutAdmin
import com.ultiq.app.util.AlarmScheduler
import com.ultiq.app.util.Comparisons
import com.ultiq.app.util.TokenManager
import com.ultiq.app.util.UserPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import kotlin.math.roundToInt

/// What gets cached on the Dashboard's last-night card. `vsTarget` is
/// formatted relative to the user's optimal sleep time (set in Sleep
/// preferences); `lastWeekDailyAvgMinutes` is the avg duration over the
/// preceding 7 nights (today excluded), used by the card to render
/// "Last week's daily average was Xh Ym" as an absolute reference.
data class SleepSummary(
    val duration: String,
    val durationMinutes: Int,
    val quality: Int,
    val vsTarget: String,
    val vsTargetMinutes: Int,
    /// Last week's daily-average sleep duration in minutes. Null when the
    /// past week had no logged nights — the card suppresses the subline
    /// in that case rather than rendering "0m".
    val lastWeekDailyAvgMinutes: Int? = null,
    val phonePickups: Int,
    val vsLastWeek: String? = null,
    val rankPhrase: String? = null,
)

data class FocusSummary(
    val totalMinutesToday: Int,
    val sessionsToday: Int,
    val currentStreak: Int,
    val longestStreak: Int,
    val phonePickupsToday: Int,
    val vsLastWeek: String? = null,
    /// Last week's daily-average focus minutes. Null when last week had
    /// no sessions (suppress the subline rather than showing "0m").
    val lastWeekDailyAvgMinutes: Int? = null,
)

data class WeeklyHighlights(
    val avgSleepDuration: String,
    val avgSleepQuality: Double,
    val totalFocusHours: Double,
    val eventsTotal: Int,
    val sleepDebtMinutes: Int = 0,
    val sleepExtraMinutes: Int = 0,
    val sleepTargetMinutes: Int = 480,
    val avgSleepDeltaMinutes: Int? = null,
    val avgQualityDelta: Double? = null,
    /// §last-week-absolute — Absolute prior-week values, surfaced as
    /// "Last week: …" subtitles under each stat instead of +/- deltas
    /// (which read as value judgements while this week is partial). For
    /// Avg sleep + Avg quality the subtitle is colored by direction
    /// (green ↑, amber ↓); Total focus stays neutral. Null when last
    /// week had no records for that stat.
    val lastWeekAvgSleepMinutes: Int? = null,
    val lastWeekQuality: Double? = null,
    val lastWeekFocusHours: Double? = null,
)

data class TodayChecklistSummary(
    val openItems: List<ChecklistEntity>,
    val completedCount: Int,
    val totalCount: Int,
)

data class AchievementBadge(
    val id: String,
    val name: String,
    val icon: ImageVector,
    val earnedAt: Long,
)

/// §9.4 — Weekly Insight card state machine. Server-side 24h cache means
/// the first call after dashboard mount is usually free (cached row); the
/// model only runs once per ~day per user.
sealed class WeeklyInsightState {
    object Idle : WeeklyInsightState()
    object Loading : WeeklyInsightState()
    data class Loaded(
        val content: String,
        val generatedAt: String,
        /// §insight-timestamps (v2.13.15) — When the cached server row
        /// expires; the next Dashboard mount after this instant will
        /// generate a fresh summary via Sonnet. Rendered as a "Refreshes
        /// at …" caption under the card content so users understand
        /// they're looking at the same summary until that time.
        val expiresAt: String,
        val cached: Boolean,
    ) : WeeklyInsightState()
    data class Error(val message: String) : WeeklyInsightState()
}

/// §9.8 — Anomaly alert from the daily scheduler. Only renders when
/// `alert=true` AND the user hasn't dismissed this specific insight_id
/// locally (one alert per day max, dismiss is per-alert).
data class AnomalyAlertState(
    val reason: String,
    val insightId: String,
    val generatedAt: String,
)

/// One row in the Dashboard "Coming up" section. Calendar events sort by
/// `startTime`; checklist items have no clock time so we surface them
/// alongside calendar events for the same day with a dedicated icon.
sealed interface UpcomingItem {
    /// Used to sort the combined list. Calendar = real `start_time`;
    /// checklist = start-of-day epoch millis for the due_date so a
    /// "buy bananas today" item sorts before any later calendar event.
    val sortKey: Long
    data class Calendar(val event: CalendarEventEntity) : UpcomingItem {
        override val sortKey: Long get() = event.startTime
    }
    data class Checklist(val item: com.ultiq.app.data.local.entity.ChecklistEntity) : UpcomingItem {
        override val sortKey: Long
            get() = item.dueDateEpochDay * 86_400_000L
    }
}

data class DashboardUiState(
    val lastNightSleep: SleepSummary? = null,
    val todayFocus: FocusSummary? = null,
    val upcomingEvents: List<CalendarEventEntity> = emptyList(),
    val upcomingItems: List<UpcomingItem> = emptyList(),
    val weeklyHighlights: WeeklyHighlights? = null,
    val todayChecklist: TodayChecklistSummary? = null,
    val achievementsEarnedCount: Int = 0,
    val achievementsTotal: Int = AchievementId.entries.size,
    val recentAchievements: List<AchievementBadge> = emptyList(),
    val isLoading: Boolean = false,
    val isSyncing: Boolean = false,
    val lastSyncTime: Long = 0L,
    val showPrefsHint: Boolean = false,
    /** Live overlay-permission grant state — refreshed on screen resume. */
    val canDrawOverlays: Boolean = true,
    /** Live device-admin / strict-lock state — refreshed on screen resume. */
    val isStrictLockEnabled: Boolean = true,
    /** User dismissed the lock&overlay hint. */
    val lockOverlayHintSeen: Boolean = true,
    /** §9.4 AI weekly insight card. */
    val weeklyInsight: WeeklyInsightState = WeeklyInsightState.Idle,
    /** §9.8 anomaly alert card. Null = no active alert (or dismissed). */
    val anomalyAlert: AnomalyAlertState? = null,
) {
    /**
     * Show the lock&overlay hint on the dashboard when at least one of
     * those permissions is missing AND the user hasn't dismissed the
     * hint. Lives on the dashboard (not Settings) so the user sees it
     * immediately on app open rather than having to dig into Settings.
     */
    val showLockOverlayHint: Boolean
        get() = !lockOverlayHintSeen && (!canDrawOverlays || !isStrictLockEnabled)
}

class DashboardViewModel(application: Application) : AndroidViewModel(application) {
    private val tokenManager = TokenManager(application)
    private val api = RetrofitClient.create(tokenManager)
    private val db = AppDatabase.getInstance(application)
    private val sleepDao = db.sleepDao()
    private val sessionDao = db.sessionDao()
    private val achievementDao = db.achievementDao()
    private val sleepRepo = SleepRepository(
        sleepDao = sleepDao,
        apiService = api,
        sleepAudioEventDao = db.sleepAudioEventDao(),
    )
    private val sessionRepo = SessionRepository(sessionDao, api)
    private val calendarRepo = CalendarRepository(db.calendarEventDao(), api, AlarmScheduler(application))
    private val checklistRepo = ChecklistRepository(db.checklistDao(), api)
    private val alarmRepo = com.ultiq.app.data.repository.AlarmRepository(application, db.alarmDao(), api)
    private val syncManager = SyncManager(sleepRepo, sessionRepo, calendarRepo, alarmRepo, checklistRepo)
    private val userPreferences = UserPreferences(application)

    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = _uiState

    init {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            sync()
            loadAll()
            _uiState.value = _uiState.value.copy(isLoading = false)
        }
        // Mirror the dashboard prefs-hint + lock-overlay dismissal so the
        // cards disappear immediately when the user taps × and stay gone
        // across launches.
        viewModelScope.launch {
            userPreferences.settings.collect { s ->
                _uiState.value = _uiState.value.copy(
                    showPrefsHint = !s.dashboardPrefsHintSeen,
                    lockOverlayHintSeen = s.lockOverlayHintSeen,
                )
            }
        }
        refreshLockOverlayState()
        observeTodayChecklist()
        observeAchievements()
        observeSleepUpdates()
        loadWeeklyInsight()
        loadAnomalyAlert()
    }

    /// §sleep-reactive — without this, `loadSleepSummary` + `loadWeeklyHighlights`
    /// only run on init / pull-to-refresh. A sleep saved from the Sleep tab
    /// would sit in Room but the Dashboard's "last night" + "this week"
    /// cards would stay on stale values until the user closed and reopened
    /// the app. Mirrors `observeTodayChecklist` / `observeAchievements`.
    /// The upper bound is open-ended (Long.MAX_VALUE) so Room's invalidation
    /// fires on any future insert; the two reload calls re-compute the
    /// actual week/48h windows inside themselves.
    private fun observeSleepUpdates() {
        viewModelScope.launch {
            val windowStart = System.currentTimeMillis() - 14L * 24 * 3600_000L
            sleepDao.getRecordsBetween(windowStart, Long.MAX_VALUE).collect {
                loadSleepSummary()
                loadWeeklyHighlights()
            }
        }
    }

    /// Fetch the AI weekly insight. Server returns a cached row if it's
    /// less than 24h old (free, no Bedrock call). Pass force=true to bypass
    /// the in-memory dedup; the server cache is still honored.
    fun loadWeeklyInsight(force: Boolean = false) {
        if (!force && _uiState.value.weeklyInsight is WeeklyInsightState.Loaded) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(weeklyInsight = WeeklyInsightState.Loading)
            try {
                val resp = api.getWeeklyInsight()
                _uiState.value = _uiState.value.copy(
                    weeklyInsight = WeeklyInsightState.Loaded(
                        content = resp.content,
                        generatedAt = resp.generated_at,
                        expiresAt = resp.expires_at,
                        cached = resp.cached,
                    )
                )
            } catch (e: retrofit2.HttpException) {
                val msg = when (e.code()) {
                    401 -> "Sign in to see your weekly insight"
                    429 -> "Daily AI limit reached — back tomorrow"
                    else -> "Couldn't load your weekly insight"
                }
                _uiState.value = _uiState.value.copy(weeklyInsight = WeeklyInsightState.Error(msg))
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    weeklyInsight = WeeklyInsightState.Error("Couldn't load your weekly insight")
                )
            }
        }
    }

    fun dismissPrefsHint() = viewModelScope.launch {
        userPreferences.setDashboardPrefsHintSeen(true)
    }

    fun dismissLockOverlayHint() = viewModelScope.launch {
        userPreferences.setLockOverlayHintSeen(true)
    }

    /// §9.8 — Pull the latest anomaly alert (if any) and surface it as a
    /// Dashboard card. Read-only — never triggers Bedrock. Honours the
    /// "user dismissed this specific insight" flag so the same alert
    /// doesn't keep reappearing within its 24h cache window.
    fun loadAnomalyAlert() {
        viewModelScope.launch {
            runCatching { api.getLatestAnomaly() }
                .onSuccess { resp ->
                    val id = resp.insight_id
                    val genAt = resp.generated_at
                    val dismissedId = userPreferences.snapshot().dismissedAnomalyInsightId
                    val show = resp.alert &&
                        id != null &&
                        genAt != null &&
                        resp.reason.isNotBlank() &&
                        dismissedId != id
                    _uiState.value = _uiState.value.copy(
                        anomalyAlert = if (show) {
                            AnomalyAlertState(resp.reason, id!!, genAt!!)
                        } else null,
                    )
                }
                .onFailure {
                    // Silent — anomaly card is non-critical; a transient
                    // network failure just hides it until next refresh.
                    _uiState.value = _uiState.value.copy(anomalyAlert = null)
                }
        }
    }

    fun dismissAnomalyAlert() {
        val alert = _uiState.value.anomalyAlert ?: return
        viewModelScope.launch {
            userPreferences.setDismissedAnomalyInsightId(alert.insightId)
            _uiState.value = _uiState.value.copy(anomalyAlert = null)
        }
    }

    /**
     * Recheck overlay + strict-lock permission state. Called from the
     * Dashboard screen's ON_RESUME observer so the hint disappears as
     * soon as the user comes back from granting permission in system
     * settings.
     */
    fun refreshLockOverlayState() {
        val app = getApplication<Application>()
        _uiState.value = _uiState.value.copy(
            canDrawOverlays = Settings.canDrawOverlays(app),
            isStrictLockEnabled = LockoutAdmin.isAdminActive(app),
        )
    }

    private fun observeTodayChecklist() {
        viewModelScope.launch {
            val today = LocalDate.now()
            val todayEpochDay = today.toEpochDay()
            // Sun=0..Sat=6 — match the bitmask packing used by the dialog.
            val dayBit = 1 shl (today.dayOfWeek.value % 7)
            db.checklistDao().getByDate(todayEpochDay, dayBit).collect { all ->
                // Recurring rows are "open today" iff the per-day stamp is not
                // today; non-recurring rows follow the completed flag.
                val open = all.filter { item ->
                    if (item.recurrenceDaysMask != 0) {
                        item.lastCompletedEpochDay != todayEpochDay
                    } else {
                        !item.completed
                    }
                }
                val doneCount = all.size - open.size
                _uiState.value = _uiState.value.copy(
                    todayChecklist = TodayChecklistSummary(
                        openItems = open,
                        completedCount = doneCount,
                        totalCount = all.size,
                    ),
                )
            }
        }
    }

    private fun observeAchievements() {
        viewModelScope.launch {
            achievementDao.getAll().collect { entities ->
                val byId = AchievementId.entries.associateBy { it.name }
                val badges = entities.mapNotNull { entity ->
                    val def = byId[entity.id] ?: return@mapNotNull null
                    AchievementBadge(
                        id = entity.id,
                        name = def.displayName,
                        icon = def.icon,
                        earnedAt = entity.earnedAt,
                    )
                }
                _uiState.value = _uiState.value.copy(
                    achievementsEarnedCount = badges.size,
                    recentAchievements = badges.take(4),
                )
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSyncing = true)
            sync()
            loadAll()
            _uiState.value = _uiState.value.copy(isSyncing = false)
        }
    }

    private suspend fun loadAll() {
        loadSleepSummary()
        loadFocusSummary()
        loadUpcomingEvents()
        loadWeeklyHighlights()
    }

    private suspend fun loadSleepSummary() {
        val now = System.currentTimeMillis()
        val twoDaysAgo = now - 48 * 3600_000L
        val recent = sleepDao.getRecordsBetween(twoDaysAgo, now).firstOrNull() ?: emptyList()
        val last = recent.firstOrNull()
        if (last == null) {
            _uiState.value = _uiState.value.copy(lastNightSleep = null)
            return
        }

        val durationMs = last.actualWakeTime - last.actualBedtime
        val durationMins = (durationMs / 60_000).toInt()
        // §optimal-sleep-target — compare against the user's "Optimal
        // sleep" preference (a single goal duration, e.g. 8h = 480 min),
        // NOT against the per-record `target_wake_time − target_bedtime`
        // window. The per-record window was the *planned* schedule for
        // that night and is often a partial-night value (an alarm at 5am
        // gave one user a target window of 5h 43m, which made an 8h 44m
        // sleep read as "+3h 1m vs optimal sleep" — wildly off).
        val targetMins = userPreferences.snapshot().sleepTargetMinutes
        val diffMins = durationMins - targetMins

        // §sleep-card-calc — "Last week" = previous calendar Mon..Sun, NOT
        // the rolling 7 days from now. User reported last calendar week
        // had ~14 minutes of focus but the card showed a 1h+ daily avg
        // because the rolling window pulled in this-week's sessions too.
        // Same bug bit the sleep daily-avg subline.
        // §sleep-day (v2.13.17) — Week boundaries are sleep-day boundaries
        // (6am local), not midnight. A Mon 02:00 bedtime is Sunday-night
        // sleep and now correctly drops into last week instead of leaking
        // into this week's "last week" baseline.
        val zoneCard = ZoneId.systemDefault()
        val todayLocal = LocalDate.now(zoneCard)
        val mondayThisWeek = todayLocal.minusDays(todayLocal.dayOfWeek.value.toLong() - 1)
        val mondayLastWeek = mondayThisWeek.minusWeeks(1)
        val lastWeekStartMs = com.ultiq.app.util.sleepDayStartMs(mondayLastWeek, zoneCard)
        val lastWeekEndMs = com.ultiq.app.util.sleepDayStartMs(mondayThisWeek, zoneCard) - 1

        val lastWeekRecords = sleepDao.getRecordsBetween(lastWeekStartMs, lastWeekEndMs)
            .firstOrNull().orEmpty()
            .filter { it.id != last.id }
        val lastWeekMinutes = lastWeekRecords.map { ((it.actualWakeTime - it.actualBedtime) / 60_000).toInt() }
        // Avg over logged nights (skips unlogged nights) — averaging
        // across all 7 nights would dilute the number if the user
        // missed a few nights of logging.
        val lastWeekDailyAvg = if (lastWeekMinutes.isNotEmpty()) lastWeekMinutes.average().toInt() else null

        // Past-30-day rolling window is fine for the rank phrase ("Top
        // quarter this month") — that one was always meant to be a
        // recency-weighted comparison, not a calendar one.
        val monthAgo = now - 30 * 86_400_000L
        val past30 = sleepDao.getRecordsBetween(monthAgo, now).firstOrNull().orEmpty()
            .filter { it.id != last.id }
        val past30Minutes = past30.map { ((it.actualWakeTime - it.actualBedtime) / 60_000.0) }
        val rankPhrase = if (past30.size >= 4) {
            val rank = Comparisons.rankAmong(durationMins.toDouble(), past30Minutes, largerIsBetter = true)
            Comparisons.rankPhrase(rank, "this month")
        } else null

        // Keep the delta string available for any other surface that still
        // wants it; the SleepCard no longer renders it.
        val vsLastWeek = Comparisons.vsLastWeekMinutes(durationMins, lastWeekMinutes)

        _uiState.value = _uiState.value.copy(
            lastNightSleep = SleepSummary(
                duration = formatDuration(durationMins),
                durationMinutes = durationMins,
                quality = last.qualityRating,
                vsTarget = if (diffMins >= 0) "+${formatDuration(diffMins)}" else "-${formatDuration(-diffMins)}",
                vsTargetMinutes = diffMins,
                lastWeekDailyAvgMinutes = lastWeekDailyAvg,
                phonePickups = last.phonePickups,
                vsLastWeek = vsLastWeek,
                rankPhrase = rankPhrase,
            )
        )
    }

    private suspend fun loadFocusSummary() {
        val zone = ZoneId.systemDefault()
        val todayStart = LocalDate.now().atStartOfDay(zone).toInstant().toEpochMilli()
        val todayEnd = todayStart + 86_400_000 - 1

        val sessions = sessionDao.getSessionsBetween(todayStart, todayEnd).firstOrNull() ?: emptyList()
        val completed = sessions.filter { it.completed }
        val totalMinutesToday = completed.sumOf { it.durationMinutes }

        val dates = sessionDao.getCompletedSessionDates(0, todayEnd)
        val streak = computeStreak(dates, todayStart)
        val longestStreak = computeLongestStreak(dates)

        // §focus-card-calc — "Last week" = previous calendar Mon..Sun
        // (NOT rolling 7 days from now). The previous code pulled the
        // last 7 days, which on a Wednesday included this-week's Mon
        // and Tue and inflated the average. Daily average = total /
        // 7 so unlogged days count as 0 (the user explicitly wanted
        // this — they reported "14 mins total last Mon-Sun" and
        // expected a tiny daily avg, not the 14m-per-logged-day
        // version).
        val todayLocal = LocalDate.now(zone)
        val mondayThisWeek = todayLocal.minusDays(todayLocal.dayOfWeek.value.toLong() - 1)
        val mondayLastWeek = mondayThisWeek.minusWeeks(1)
        val lastWeekStartMs = mondayLastWeek.atStartOfDay(zone).toInstant().toEpochMilli()
        val lastWeekEndMs = mondayThisWeek.atStartOfDay(zone).toInstant().toEpochMilli() - 1
        val lastWeekMinutes = sessionDao.getTotalFocusMinutes(lastWeekStartMs, lastWeekEndMs) ?: 0
        val lastWeekDailyAvg = lastWeekMinutes / 7
        val vsLastWeek = if (lastWeekMinutes > 0) {
            Comparisons.vsLastWeekMinutes(totalMinutesToday, listOf(lastWeekDailyAvg))
        } else null

        _uiState.value = _uiState.value.copy(
            todayFocus = FocusSummary(
                totalMinutesToday = totalMinutesToday,
                sessionsToday = completed.size,
                currentStreak = streak,
                longestStreak = longestStreak,
                phonePickupsToday = completed.sumOf { it.phonePickups },
                vsLastWeek = vsLastWeek,
                lastWeekDailyAvgMinutes = if (lastWeekMinutes > 0) lastWeekDailyAvg else null,
            )
        )
    }

    private suspend fun loadUpcomingEvents() {
        // §audit-2 — Coming up = TODAY's calendar events only. Checklist
        // items live in the existing "Today's plan" card above; mixing
        // them in here was wrong (and the previous window let tomorrow's
        // tasks leak in). Empty state says "no calendar events planned
        // today" via the WarmCopy helper.
        val today = LocalDate.now()
        val events = calendarRepo.getEventsForRange(today, today).firstOrNull() ?: emptyList()
        val now = System.currentTimeMillis()
        val visibleEvents = events.filter { it.startTime >= now }
        _uiState.value = _uiState.value.copy(
            upcomingEvents = visibleEvents,
            upcomingItems = visibleEvents.map(UpcomingItem::Calendar),
        )
    }

    private suspend fun loadWeeklyHighlights() {
        // v2.13.7 — Switched from a rolling 7-day window to a true calendar
        // week (Mon → today inclusive) with the baseline being the WHOLE
        // previous calendar week (Mon–Sun, full 7 days). Early in the week
        // the deltas will look big-negative for totals (focus hours, events)
        // because this-week-so-far is shorter than last-week-full — that's
        // expected per design discussion 2026-05-24. Averages (avg sleep,
        // quality) remain meaningful from day one of the week.
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now()
        val thisWeekStart = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val lastWeekStart = thisWeekStart.minusWeeks(1)
        // §sleep-day (v2.13.17) — Half-open ranges anchored to 6am local,
        // not midnight, so a Mon 02:00 bedtime drops into last week
        // (sleep_day = Sun) instead of bleeding into both columns.
        val thisWeekStartMs = com.ultiq.app.util.sleepDayStartMs(thisWeekStart, zone)
        val nowMs = System.currentTimeMillis()
        val lastWeekStartMs = com.ultiq.app.util.sleepDayStartMs(lastWeekStart, zone)
        val lastWeekEndMs = thisWeekStartMs // start of this week = end of last week

        // Sleep — this week so far
        val sleepThis = sleepDao.getRecordsBetween(thisWeekStartMs, nowMs).firstOrNull() ?: emptyList()
        val sleepPrev = sleepDao.getRecordsBetween(lastWeekStartMs, lastWeekEndMs).firstOrNull() ?: emptyList()
        val avgSleepThisMins = sleepThis.takeIf { it.isNotEmpty() }
            ?.map { ((it.actualWakeTime - it.actualBedtime) / 60_000).toInt() }?.average() ?: 0.0
        val avgSleepPrevMins = sleepPrev.takeIf { it.isNotEmpty() }
            ?.map { ((it.actualWakeTime - it.actualBedtime) / 60_000).toInt() }?.average() ?: 0.0
        // §empty-state — deltas only meaningful when BOTH windows have data;
        // otherwise rendering a "−8h" delta for "no records this week vs full
        // last week" is more confusing than helpful.
        val avgSleepDelta = if (sleepThis.isNotEmpty() && sleepPrev.isNotEmpty()) {
            (avgSleepThisMins - avgSleepPrevMins).roundToInt()
        } else null

        val avgQuality = if (sleepThis.isEmpty()) 0.0 else sleepThis.map { it.qualityRating.toDouble() }.average()
        val avgQualityPrev = if (sleepPrev.isEmpty()) 0.0 else sleepPrev.map { it.qualityRating.toDouble() }.average()
        val avgQualityDelta = if (sleepThis.isNotEmpty() && sleepPrev.isNotEmpty()) {
            avgQuality - avgQualityPrev
        } else null

        // Asymmetric balance — sum of (target − actual) across this week's
        // sleep records, split into nights short of target (debt) vs nights
        // over (extra). Reads against the user's `sleepTargetMinutes` pref,
        // NOT against last week — these are "vs goal" totals, not deltas.
        val sleepTarget = userPreferences.snapshot().sleepTargetMinutes
        var sleepDebt = 0
        var sleepExtra = 0
        for (r in sleepThis) {
            val mins = ((r.actualWakeTime - r.actualBedtime) / 60_000).toInt()
            val delta = mins - sleepTarget
            if (delta < 0) sleepDebt += -delta else sleepExtra += delta
        }

        // Sessions
        val totalFocusThis = sessionDao.getTotalFocusMinutes(thisWeekStartMs, nowMs) ?: 0
        val totalFocusPrev = sessionDao.getTotalFocusMinutes(lastWeekStartMs, lastWeekEndMs) ?: 0
        val totalFocusHours = totalFocusThis / 60.0

        // Events — total scheduled this week. Past/future agnostic; the
        // `isDone` column isn't surfaced here because mark-done is only
        // available on past events, which would bias the count toward early
        // in the week. "Planned" is honest in both directions. No
        // last-week comparison rendered — the absolute count is the whole
        // story per design discussion 2026-05-24.
        val eventsThis = calendarRepo
            .getEventsForRange(thisWeekStart, today)
            .firstOrNull() ?: emptyList()
        _uiState.value = _uiState.value.copy(
            weeklyHighlights = WeeklyHighlights(
                avgSleepDuration = if (sleepThis.isEmpty()) "-" else formatDuration(avgSleepThisMins.toInt()),
                avgSleepQuality = avgQuality,
                totalFocusHours = totalFocusHours,
                eventsTotal = eventsThis.size,
                sleepDebtMinutes = sleepDebt,
                sleepExtraMinutes = sleepExtra,
                sleepTargetMinutes = sleepTarget,
                avgSleepDeltaMinutes = avgSleepDelta,
                avgQualityDelta = avgQualityDelta,
                lastWeekAvgSleepMinutes = if (sleepPrev.isNotEmpty()) avgSleepPrevMins.roundToInt() else null,
                lastWeekQuality = if (sleepPrev.isNotEmpty()) avgQualityPrev else null,
                lastWeekFocusHours = if (totalFocusPrev > 0) totalFocusPrev / 60.0 else null,
            )
        )
    }

    private suspend fun sync() {
        try {
            syncManager.syncAll().getOrThrow()
            runCatching { checklistRepo.sync() }
            _uiState.value = _uiState.value.copy(lastSyncTime = System.currentTimeMillis())
        } catch (_: Exception) { }
    }

    private fun computeStreak(dateDayMillis: List<Long>, todayStartMillis: Long): Int {
        if (dateDayMillis.isEmpty()) return 0
        val todayDay = todayStartMillis / 86_400_000
        val days = dateDayMillis.map { it / 86_400_000 }
        if (days.first() != todayDay) return 0
        var streak = 1
        for (i in 1 until days.size) {
            if (days[i - 1] - days[i] == 1L) streak++ else break
        }
        return streak
    }

    private fun computeLongestStreak(dateDayMillis: List<Long>): Int {
        if (dateDayMillis.isEmpty()) return 0
        val days = dateDayMillis.map { it / 86_400_000 }.toSortedSet().toList()
        var longest = 1
        var current = 1
        for (i in 1 until days.size) {
            if (days[i] - days[i - 1] == 1L) {
                current++
                longest = maxOf(longest, current)
            } else {
                current = 1
            }
        }
        return longest
    }

    private fun formatDuration(minutes: Int): String {
        val h = minutes / 60
        val m = minutes % 60
        return if (h > 0) "${h}h ${m}m" else "${m}m"
    }
}
