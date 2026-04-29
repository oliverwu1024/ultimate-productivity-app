package com.ultiq.app.data.achievements

import com.ultiq.app.data.local.dao.AchievementDao
import com.ultiq.app.data.local.dao.SessionDao
import com.ultiq.app.data.local.dao.SleepDao
import com.ultiq.app.data.local.entity.AchievementEntity
import com.ultiq.app.data.local.entity.SessionEntity
import com.ultiq.app.data.local.entity.SleepRecordEntity
import com.ultiq.app.util.UserPreferences
import kotlinx.coroutines.flow.firstOrNull
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.math.abs

private const val TARGET_WINDOW_MINUTES = 30
private const val FOCUS_MASTER_HOURS = 50
private const val CENTURY_SESSIONS = 100
private const val ZEN_MODE_COUNT = 5
private const val IRON_STREAK_DAYS = 30
private const val SLEEP_CHAMPION_NIGHTS = 14
private const val MARATHON_MINUTES = 240
private const val STREAK_DAYS_7 = 7

class AchievementChecker(
    private val achievementDao: AchievementDao,
    private val sleepDao: SleepDao,
    private val sessionDao: SessionDao,
    private val userPreferences: UserPreferences,
) {

    /**
     * Evaluate every achievement against current data. Returns any newly earned.
     * Cheap enough to run after each save — all queries are local.
     */
    suspend fun checkAndStore(): List<AchievementId> {
        val alreadyEarned = achievementDao.getEarnedIds().toSet()
        val settings = userPreferences.snapshot()
        val zone = ZoneId.systemDefault()

        val sleepRecords = sleepDao.getAllRecords().firstOrNull().orEmpty()
        val allSessions = sessionDao.getAllSessions().firstOrNull().orEmpty()
        val completed = allSessions.filter { it.completed }

        val newlyEarned = mutableListOf<AchievementId>()
        val now = System.currentTimeMillis()

        AchievementId.entries.forEach { id ->
            if (id.name in alreadyEarned) return@forEach

            val earned = when (id) {
                AchievementId.EARLY_BIRD -> consecutiveWakeHits(
                    records = sleepRecords,
                    target = settings.targetWakeTime,
                    zone = zone,
                ) >= STREAK_DAYS_7

                AchievementId.NIGHT_OWL_NO_MORE -> consecutiveBedtimeHits(
                    records = sleepRecords,
                    target = settings.targetBedtime,
                    zone = zone,
                ) >= STREAK_DAYS_7

                AchievementId.FOCUS_MASTER ->
                    completed.sumOf { it.durationMinutes } >= FOCUS_MASTER_HOURS * 60

                AchievementId.CENTURY -> completed.size >= CENTURY_SESSIONS

                AchievementId.ZEN_MODE ->
                    completed.count { it.phonePickups == 0 } >= ZEN_MODE_COUNT

                AchievementId.IRON_STREAK -> focusStreakDays(completed, zone) >= IRON_STREAK_DAYS

                AchievementId.SLEEP_CHAMPION -> sleepTargetStreakNights(
                    records = sleepRecords,
                    targetBedtime = settings.targetBedtime,
                    targetWakeTime = settings.targetWakeTime,
                    zone = zone,
                ) >= SLEEP_CHAMPION_NIGHTS

                AchievementId.MARATHON -> completed
                    .groupBy { dayKey(it.startedAt, zone) }
                    .any { (_, v) -> v.sumOf { it.durationMinutes } >= MARATHON_MINUTES }
            }

            if (earned) {
                achievementDao.insert(AchievementEntity(id = id.name, earnedAt = now))
                newlyEarned += id
            }
        }

        AchievementEvents.emit(newlyEarned)
        return newlyEarned
    }

    // ── Streak helpers ──────────────────────────────────────────────────

    private fun consecutiveWakeHits(
        records: List<SleepRecordEntity>,
        target: LocalTime,
        zone: ZoneId,
    ): Int {
        val byDay = records.associateBy { wakeDayKey(it.actualWakeTime, zone) }
        return countConsecutiveFromYesterday(zone) { dayKey ->
            val record = byDay[dayKey] ?: return@countConsecutiveFromYesterday false
            withinWindow(timeOfDay(record.actualWakeTime, zone), target)
        }
    }

    private fun consecutiveBedtimeHits(
        records: List<SleepRecordEntity>,
        target: LocalTime,
        zone: ZoneId,
    ): Int {
        val byDay = records.associateBy { wakeDayKey(it.actualWakeTime, zone) }
        return countConsecutiveFromYesterday(zone) { dayKey ->
            val record = byDay[dayKey] ?: return@countConsecutiveFromYesterday false
            withinWindow(timeOfDay(record.actualBedtime, zone), target)
        }
    }

    private fun sleepTargetStreakNights(
        records: List<SleepRecordEntity>,
        targetBedtime: LocalTime,
        targetWakeTime: LocalTime,
        zone: ZoneId,
    ): Int {
        val byDay = records.associateBy { wakeDayKey(it.actualWakeTime, zone) }
        return countConsecutiveFromYesterday(zone) { dayKey ->
            val r = byDay[dayKey] ?: return@countConsecutiveFromYesterday false
            withinWindow(timeOfDay(r.actualBedtime, zone), targetBedtime) &&
                withinWindow(timeOfDay(r.actualWakeTime, zone), targetWakeTime)
        }
    }

    private fun focusStreakDays(completed: List<SessionEntity>, zone: ZoneId): Int {
        if (completed.isEmpty()) return 0
        val daysWithSessions = completed.map { dayKey(it.startedAt, zone) }.toSet()
        val today = LocalDate.now(zone)
        // Focus streak can count today (if any session) or start from yesterday otherwise.
        val start = if (daysWithSessions.contains(today)) today else today.minusDays(1)
        var day = start
        var count = 0
        while (daysWithSessions.contains(day)) {
            count++
            day = day.minusDays(1)
        }
        return count
    }

    private inline fun countConsecutiveFromYesterday(
        zone: ZoneId,
        met: (LocalDate) -> Boolean,
    ): Int {
        var day = LocalDate.now(zone).minusDays(1)
        var count = 0
        while (met(day)) {
            count++
            day = day.minusDays(1)
        }
        return count
    }

    private fun withinWindow(actual: LocalTime, target: LocalTime): Boolean {
        val actualMin = actual.hour * 60 + actual.minute
        val targetMin = target.hour * 60 + target.minute
        val rawDiff = abs(actualMin - targetMin)
        val diff = minOf(rawDiff, 24 * 60 - rawDiff) // wrap around midnight
        return diff <= TARGET_WINDOW_MINUTES
    }

    private fun timeOfDay(epochMillis: Long, zone: ZoneId): LocalTime =
        Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalTime()

    private fun wakeDayKey(epochMillis: Long, zone: ZoneId): LocalDate =
        Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate()

    private fun dayKey(epochMillis: Long, zone: ZoneId): LocalDate =
        Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate()
}
