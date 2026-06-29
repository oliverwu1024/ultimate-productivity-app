package com.ultiq.app.ui.reports

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ultiq.app.data.achievements.AchievementId
import com.ultiq.app.data.local.AppDatabase
import com.ultiq.app.data.local.entity.AchievementEntity
import com.ultiq.app.data.local.entity.SessionEntity
import com.ultiq.app.data.local.entity.SleepRecordEntity
import com.ultiq.app.data.remote.RetrofitClient
import com.ultiq.app.data.repository.CalendarRepository
import com.ultiq.app.util.AlarmScheduler
import com.ultiq.app.util.TokenManager
import com.ultiq.app.util.UserPreferences
import com.ultiq.app.util.UserSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.abs

data class EarnedAchievement(
    val id: AchievementId,
    val earnedAt: Long,
)

data class SleepDaySummary(
    val date: LocalDate,
    val durationMinutes: Int?,
    val qualityRating: Int?,
    val phonePickups: Int?,
)

data class FocusDaySummary(
    val date: LocalDate,
    val totalMinutes: Int,
)

data class TagStat(
    val tag: String,
    val totalMinutes: Int,
    val sessionCount: Int,
)

data class CategoryStat(
    val category: String,
    val count: Int,
)

data class WeeklyReportUiState(
    val weekStart: LocalDate = weekStartOf(LocalDate.now()),
    // Sleep
    val sleepByDay: List<SleepDaySummary> = emptyList(),
    val avgSleepDurationMinutes: Double = 0.0,
    val avgSleepQuality: Double = 0.0,
    val bestNight: SleepDaySummary? = null,
    val worstNight: SleepDaySummary? = null,
    val avgPhonePickupsPerNight: Double = 0.0,
    val phonePickupsTrend: Double = 0.0, // pos = got worse, neg = improved
    val sleepDebtMinutes: Int = 0,
    val sleepExtraMinutes: Int = 0,
    val sleepTargetMinutes: Int = 480,
    // Focus
    val focusByDay: List<FocusDaySummary> = emptyList(),
    val totalFocusMinutes: Int = 0,
    val sessionsCompleted: Int = 0,
    val topTags: List<TagStat> = emptyList(),
    val totalSessionPickups: Int = 0,
    val avgPickupsPerSession: Double = 0.0,
    val bestDistractionFreeStreak: Int = 0,
    // Calendar
    val eventsTotal: Int = 0,
    val eventsCompleted: Int = 0,
    val busiestDay: LocalDate? = null,
    val busiestDayCount: Int = 0,
    val categoryBreakdown: List<CategoryStat> = emptyList(),
    // Streaks (all-time)
    val sleepTargetStreak: Int = 0,
    val focusStreak: Int = 0,
    // Achievements
    val achievements: List<EarnedAchievement> = emptyList(),
    val isLoading: Boolean = true,
)

private const val TARGET_WINDOW_MINUTES = 30

class WeeklyReportViewModel(application: Application) : AndroidViewModel(application) {
    private val tokenManager = TokenManager(application)
    private val api = RetrofitClient.create(tokenManager)
    private val db = AppDatabase.getInstance(application)
    private val sleepDao = db.sleepDao()
    private val sessionDao = db.sessionDao()
    private val achievementDao = db.achievementDao()
    private val calendarRepo = CalendarRepository(
        db.calendarEventDao(), api, AlarmScheduler(application),
    )
    private val userPreferences = UserPreferences(application)
    private val zone: ZoneId = ZoneId.systemDefault()

    private val _uiState = MutableStateFlow(WeeklyReportUiState())
    val uiState: StateFlow<WeeklyReportUiState> = _uiState

    init {
        loadWeek(_uiState.value.weekStart)
    }

    fun previousWeek() {
        loadWeek(_uiState.value.weekStart.minusWeeks(1))
    }

    fun nextWeek() {
        val candidate = _uiState.value.weekStart.plusWeeks(1)
        val thisWeek = weekStartOf(LocalDate.now(zone))
        if (!candidate.isAfter(thisWeek)) loadWeek(candidate)
    }

    private fun loadWeek(start: LocalDate) {
        _uiState.value = _uiState.value.copy(weekStart = start, isLoading = true)
        viewModelScope.launch {
            val settings = userPreferences.snapshot()
            val weekEnd = start.plusDays(6)
            val weekStartMs = start.atStartOfDay(zone).toInstant().toEpochMilli()
            val weekEndMs = weekEnd.atTime(23, 59, 59).atZone(zone).toInstant().toEpochMilli()
            val priorStart = start.minusWeeks(1)
            val priorStartMs = priorStart.atStartOfDay(zone).toInstant().toEpochMilli()

            val sleepRecordsAll = sleepDao.getAllRecords().firstOrNull().orEmpty()
            val sessionsAll = sessionDao.getAllSessions().firstOrNull().orEmpty()
            val completedAll = sessionsAll.filter { it.completed }
            val eventsThisWeek = calendarRepo.getEventsForRange(start, weekEnd)
                .firstOrNull().orEmpty()

            val weekSleep = sleepRecordsAll.filter { it.actualWakeTime in weekStartMs..weekEndMs && !it.isNap }
            val priorSleep = sleepRecordsAll.filter { it.actualWakeTime in priorStartMs until weekStartMs && !it.isNap }
            val weekSessions = completedAll.filter { it.startedAt in weekStartMs..weekEndMs }

            val sleepByDay = buildSleepByDay(start, weekSleep)
            val focusByDay = buildFocusByDay(start, weekSessions)
            val topTags = weekSessions
                .groupBy { it.tag.ifBlank { "Untagged" } }
                .map { (tag, list) ->
                    TagStat(tag, list.sumOf { it.durationMinutes }, list.size)
                }
                .sortedByDescending { it.totalMinutes }
                .take(3)

            val eventsTotal = eventsThisWeek.size
            val now = System.currentTimeMillis()
            val eventsCompleted = eventsThisWeek.count { it.endTime < now }

            val eventsByDay = eventsThisWeek.groupBy {
                Instant.ofEpochMilli(it.startTime).atZone(zone).toLocalDate()
            }
            val busiest = eventsByDay.maxByOrNull { it.value.size }
            val categoryBreakdown = eventsThisWeek.groupingBy { it.category }
                .eachCount()
                .map { CategoryStat(it.key, it.value) }
                .sortedByDescending { it.count }

            val nightsWithData = sleepByDay.mapNotNull { it.durationMinutes?.let { d -> it.copy(durationMinutes = d) } }
            val avgDuration = nightsWithData.map { it.durationMinutes!!.toDouble() }.average().orZero()
            val avgQuality = nightsWithData.mapNotNull { it.qualityRating?.toDouble() }.average().orZero()
            val best = nightsWithData.maxByOrNull { it.qualityRating ?: 0 }
            val worst = nightsWithData.minByOrNull { it.qualityRating ?: 6 }
            val avgPickupsNight = nightsWithData.mapNotNull { it.phonePickups?.toDouble() }.average().orZero()
            val priorAvgPickups = priorSleep.map { it.phonePickups.toDouble() }.average().orZero()
            val pickupsTrend = avgPickupsNight - priorAvgPickups

            // Asymmetric balance: shortfalls accrue debt, surpluses go to "extra".
            val target = settings.sleepTargetMinutes
            var debtAcc = 0
            var extraAcc = 0
            for (n in nightsWithData) {
                val delta = (n.durationMinutes ?: 0) - target
                if (delta < 0) debtAcc += -delta else extraAcc += delta
            }

            val totalSessionPickups = weekSessions.sumOf { it.phonePickups }
            val avgPickupsSession =
                if (weekSessions.isEmpty()) 0.0
                else totalSessionPickups.toDouble() / weekSessions.size
            val bestDistractionFreeStreak = longestZeroPickupRun(completedAll)

            val sleepStreak = sleepTargetStreakNights(
                records = sleepRecordsAll.filter { !it.isNap },
                targetBedtime = settings.targetBedtime,
                targetWakeTime = settings.targetWakeTime,
            )
            val focusStreak = focusStreakDays(completedAll)

            val achievements = achievementDao.getAllSnapshot()
                .mapNotNull { it.toEarned() }
                .sortedByDescending { it.earnedAt }

            _uiState.value = WeeklyReportUiState(
                weekStart = start,
                sleepByDay = sleepByDay,
                avgSleepDurationMinutes = avgDuration,
                avgSleepQuality = avgQuality,
                bestNight = best,
                worstNight = worst,
                avgPhonePickupsPerNight = avgPickupsNight,
                phonePickupsTrend = pickupsTrend,
                sleepDebtMinutes = debtAcc,
                sleepExtraMinutes = extraAcc,
                sleepTargetMinutes = settings.sleepTargetMinutes,
                focusByDay = focusByDay,
                totalFocusMinutes = weekSessions.sumOf { it.durationMinutes },
                sessionsCompleted = weekSessions.size,
                topTags = topTags,
                totalSessionPickups = totalSessionPickups,
                avgPickupsPerSession = avgPickupsSession,
                bestDistractionFreeStreak = bestDistractionFreeStreak,
                eventsTotal = eventsTotal,
                eventsCompleted = eventsCompleted,
                busiestDay = busiest?.key,
                busiestDayCount = busiest?.value?.size ?: 0,
                categoryBreakdown = categoryBreakdown,
                sleepTargetStreak = sleepStreak,
                focusStreak = focusStreak,
                achievements = achievements,
                isLoading = false,
            )
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private fun buildSleepByDay(
        start: LocalDate,
        records: List<SleepRecordEntity>,
    ): List<SleepDaySummary> {
        val byDay = records.associateBy {
            Instant.ofEpochMilli(it.actualWakeTime).atZone(zone).toLocalDate()
        }
        return (0..6).map { offset ->
            val d = start.plusDays(offset.toLong())
            val r = byDay[d]
            SleepDaySummary(
                date = d,
                durationMinutes = r?.let { ((it.actualWakeTime - it.actualBedtime) / 60_000L).toInt() },
                qualityRating = r?.qualityRating,
                phonePickups = r?.phonePickups,
            )
        }
    }

    private fun buildFocusByDay(
        start: LocalDate,
        sessions: List<SessionEntity>,
    ): List<FocusDaySummary> {
        val byDay = sessions.groupBy {
            Instant.ofEpochMilli(it.startedAt).atZone(zone).toLocalDate()
        }
        return (0..6).map { offset ->
            val d = start.plusDays(offset.toLong())
            FocusDaySummary(
                date = d,
                totalMinutes = byDay[d]?.sumOf { it.durationMinutes } ?: 0,
            )
        }
    }

    private fun longestZeroPickupRun(sessions: List<SessionEntity>): Int {
        if (sessions.isEmpty()) return 0
        val chronological = sessions.sortedBy { it.startedAt }
        var max = 0
        var current = 0
        for (s in chronological) {
            if (s.phonePickups == 0) {
                current++
                if (current > max) max = current
            } else current = 0
        }
        return max
    }

    private fun sleepTargetStreakNights(
        records: List<SleepRecordEntity>,
        targetBedtime: LocalTime,
        targetWakeTime: LocalTime,
    ): Int {
        val byDay = records.associateBy {
            Instant.ofEpochMilli(it.actualWakeTime).atZone(zone).toLocalDate()
        }
        var day = LocalDate.now(zone).minusDays(1)
        var count = 0
        while (true) {
            val r = byDay[day] ?: break
            val bedOk = withinWindow(timeOfDay(r.actualBedtime), targetBedtime)
            val wakeOk = withinWindow(timeOfDay(r.actualWakeTime), targetWakeTime)
            if (!(bedOk && wakeOk)) break
            count++
            day = day.minusDays(1)
        }
        return count
    }

    private fun focusStreakDays(completed: List<SessionEntity>): Int {
        if (completed.isEmpty()) return 0
        val days = completed.map {
            Instant.ofEpochMilli(it.startedAt).atZone(zone).toLocalDate()
        }.toSet()
        val today = LocalDate.now(zone)
        val start = if (days.contains(today)) today else today.minusDays(1)
        var day = start
        var count = 0
        while (days.contains(day)) {
            count++
            day = day.minusDays(1)
        }
        return count
    }

    private fun withinWindow(actual: LocalTime, target: LocalTime): Boolean {
        val a = actual.hour * 60 + actual.minute
        val t = target.hour * 60 + target.minute
        val raw = abs(a - t)
        val diff = minOf(raw, 24 * 60 - raw)
        return diff <= TARGET_WINDOW_MINUTES
    }

    private fun timeOfDay(millis: Long): LocalTime =
        Instant.ofEpochMilli(millis).atZone(zone).toLocalTime()
}

// ── Module helpers ───────────────────────────────────────────────────────

fun weekStartOf(date: LocalDate): LocalDate =
    date.with(java.time.temporal.TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

private fun Double.orZero(): Double = if (this.isNaN()) 0.0 else this

private fun AchievementEntity.toEarned(): EarnedAchievement? = try {
    EarnedAchievement(AchievementId.valueOf(this.id), this.earnedAt)
} catch (_: Exception) {
    null
}
