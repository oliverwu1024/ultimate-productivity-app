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
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToInt

data class SleepSummary(
    val duration: String,
    val durationMinutes: Int,
    val quality: Int,
    val vsTarget: String,
    val vsTargetMinutes: Int,
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
)

data class WeeklyHighlights(
    val avgSleepDuration: String,
    val avgSleepQuality: Double,
    val totalFocusHours: Double,
    val eventsCompleted: Int,
    val eventsTotal: Int,
    val sleepDebtMinutes: Int = 0,
    val sleepExtraMinutes: Int = 0,
    val sleepTargetMinutes: Int = 480,
    val avgSleepDeltaMinutes: Int? = null,
    val totalFocusDeltaHours: Double? = null,
    val avgQualityDelta: Double? = null,
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
    private val sleepRepo = SleepRepository(sleepDao, api)
    private val sessionRepo = SessionRepository(sessionDao, api)
    private val calendarRepo = CalendarRepository(db.calendarEventDao(), api, AlarmScheduler(application))
    private val checklistRepo = ChecklistRepository(db.checklistDao(), api)
    private val alarmRepo = com.ultiq.app.data.repository.AlarmRepository(application, db.alarmDao(), api)
    private val syncManager = SyncManager(sleepRepo, sessionRepo, calendarRepo, alarmRepo)
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
        loadWeeklyInsight()
        loadAnomalyAlert()
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
        val targetMins = computeTargetMinutes(last)
        val diffMins = durationMins - targetMins

        // Comparison data: last 7 days excluding tonight, and last 30 days for ranking.
        val weekAgo = now - 7 * 86_400_000L
        val monthAgo = now - 30 * 86_400_000L
        val past7 = sleepDao.getRecordsBetween(weekAgo, now).firstOrNull().orEmpty()
            .filter { it.id != last.id }
        val past30 = sleepDao.getRecordsBetween(monthAgo, now).firstOrNull().orEmpty()
            .filter { it.id != last.id }

        val past7Minutes = past7.map { ((it.actualWakeTime - it.actualBedtime) / 60_000).toInt() }
        val vsLastWeek = Comparisons.vsLastWeekMinutes(durationMins, past7Minutes)

        val past30Minutes = past30.map { ((it.actualWakeTime - it.actualBedtime) / 60_000.0) }
        val rankPhrase = if (past30.size >= 4) {
            val rank = Comparisons.rankAmong(durationMins.toDouble(), past30Minutes, largerIsBetter = true)
            Comparisons.rankPhrase(rank, "this month")
        } else null

        _uiState.value = _uiState.value.copy(
            lastNightSleep = SleepSummary(
                duration = formatDuration(durationMins),
                durationMinutes = durationMins,
                quality = last.qualityRating,
                vsTarget = if (diffMins >= 0) "+${formatDuration(diffMins)}" else "-${formatDuration(-diffMins)}",
                vsTargetMinutes = diffMins,
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

        // Last 7 days (excluding today) average for comparison.
        val weekAgo = todayStart - 7 * 86_400_000L
        val past7Minutes = sessionDao.getTotalFocusMinutes(weekAgo, todayStart - 1) ?: 0
        val past7DailyAvg = past7Minutes / 7
        val vsLastWeek = if (past7Minutes > 0) {
            Comparisons.vsLastWeekMinutes(totalMinutesToday, listOf(past7DailyAvg))
        } else null

        _uiState.value = _uiState.value.copy(
            todayFocus = FocusSummary(
                totalMinutesToday = totalMinutesToday,
                sessionsToday = completed.size,
                currentStreak = streak,
                longestStreak = longestStreak,
                phonePickupsToday = completed.sumOf { it.phonePickups },
                vsLastWeek = vsLastWeek,
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
        val now = System.currentTimeMillis()
        val weekAgo = now - 7 * 86_400_000L
        val twoWeeksAgo = now - 14 * 86_400_000L
        val today = LocalDate.now()

        // This week sleep
        val sleepThis = sleepDao.getRecordsBetween(weekAgo, now).firstOrNull() ?: emptyList()
        val sleepPrev = sleepDao.getRecordsBetween(twoWeeksAgo, weekAgo).firstOrNull() ?: emptyList()
        val avgSleepThisMins = sleepThis.takeIf { it.isNotEmpty() }
            ?.map { ((it.actualWakeTime - it.actualBedtime) / 60_000).toInt() }?.average() ?: 0.0
        val avgSleepPrevMins = sleepPrev.takeIf { it.isNotEmpty() }
            ?.map { ((it.actualWakeTime - it.actualBedtime) / 60_000).toInt() }?.average() ?: 0.0
        val avgSleepDelta = if (sleepPrev.isNotEmpty()) (avgSleepThisMins - avgSleepPrevMins).roundToInt() else null

        val avgQuality = if (sleepThis.isEmpty()) 0.0 else sleepThis.map { it.qualityRating.toDouble() }.average()
        val avgQualityPrev = if (sleepPrev.isEmpty()) 0.0 else sleepPrev.map { it.qualityRating.toDouble() }.average()
        val avgQualityDelta = if (sleepPrev.isNotEmpty()) avgQuality - avgQualityPrev else null

        // Asymmetric balance over rolling 7-day window.
        val sleepTarget = userPreferences.snapshot().sleepTargetMinutes
        var sleepDebt = 0
        var sleepExtra = 0
        for (r in sleepThis) {
            val mins = ((r.actualWakeTime - r.actualBedtime) / 60_000).toInt()
            val delta = mins - sleepTarget
            if (delta < 0) sleepDebt += -delta else sleepExtra += delta
        }

        // Sessions
        val totalFocusThis = sessionDao.getTotalFocusMinutes(weekAgo, now) ?: 0
        val totalFocusPrev = sessionDao.getTotalFocusMinutes(twoWeeksAgo, weekAgo) ?: 0
        val totalFocusHours = totalFocusThis / 60.0
        val totalFocusDelta = if (totalFocusPrev > 0) (totalFocusThis - totalFocusPrev) / 60.0 else null

        // Events
        val events = calendarRepo.getEventsForRange(today.minusDays(7), today).firstOrNull() ?: emptyList()
        val eventsCompleted = events.count { it.endTime < now }

        _uiState.value = _uiState.value.copy(
            weeklyHighlights = WeeklyHighlights(
                avgSleepDuration = if (sleepThis.isEmpty()) "-" else formatDuration(avgSleepThisMins.toInt()),
                avgSleepQuality = avgQuality,
                totalFocusHours = totalFocusHours,
                eventsCompleted = eventsCompleted,
                eventsTotal = events.size,
                sleepDebtMinutes = sleepDebt,
                sleepExtraMinutes = sleepExtra,
                sleepTargetMinutes = sleepTarget,
                avgSleepDeltaMinutes = avgSleepDelta,
                totalFocusDeltaHours = totalFocusDelta,
                avgQualityDelta = avgQualityDelta,
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

    private fun computeTargetMinutes(record: SleepRecordEntity): Int {
        val bed = record.targetBedtime.split(":")
        val wake = record.targetWakeTime.split(":")
        val bedSecs = bed[0].toInt() * 3600 + bed[1].toInt() * 60
        val wakeSecs = wake[0].toInt() * 3600 + wake[1].toInt() * 60
        val targetSecs = if (wakeSecs >= bedSecs) wakeSecs - bedSecs else 86400 + wakeSecs - bedSecs
        return targetSecs / 60
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
