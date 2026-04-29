package com.ultiq.app.data.remote.dto

import com.ultiq.app.data.local.entity.SleepRecordEntity
import java.time.Instant
import java.time.ZoneId

data class CreateSleepRecordDto(
    val target_bedtime: String,
    val target_wake_time: String,
    val actual_bedtime: String,
    val actual_wake_time: String,
    val quality_rating: Int,
    val phone_pickups: Int,
    val total_phone_minutes: Int?,
    val notes: String?
)

data class SleepRecordDto(
    val id: String,
    val user_id: String,
    val target_bedtime: String,
    val target_wake_time: String,
    val actual_bedtime: String,
    val actual_wake_time: String,
    val quality_rating: Int,
    val phone_pickups: Int,
    val total_phone_minutes: Int?,
    val notes: String?,
    val created_at: String,
    val updated_at: String
)

data class SleepStatsDto(
    val avg_duration_minutes: Double,
    val avg_quality: Double,
    val total_records: Long,
    val sleep_debt_minutes: Double,
    val avg_phone_pickups: Double,
    val best_quality_day: String?,
    val worst_quality_day: String?
)

data class SleepStats(
    val avgDurationMinutes: Double = 0.0,
    val avgQuality: Double = 0.0,
    val totalRecords: Long = 0,
    val sleepDebtMinutes: Double = 0.0,
    val avgPhonePickups: Double = 0.0,
    val bestQualityDay: String? = null,
    val worstQualityDay: String? = null
)

fun SleepRecordDto.toEntity(): SleepRecordEntity {
    return SleepRecordEntity(
        id = id,
        userId = user_id,
        targetBedtime = target_bedtime.take(5),
        targetWakeTime = target_wake_time.take(5),
        actualBedtime = Instant.parse(actual_bedtime).toEpochMilli(),
        actualWakeTime = Instant.parse(actual_wake_time).toEpochMilli(),
        qualityRating = quality_rating,
        phonePickups = phone_pickups,
        totalPhoneMinutes = total_phone_minutes,
        notes = notes,
        createdAt = Instant.parse(created_at).toEpochMilli(),
        updatedAt = Instant.parse(updated_at).toEpochMilli(),
        isSynced = true
    )
}

fun SleepRecordEntity.toCreateDto(): CreateSleepRecordDto {
    return CreateSleepRecordDto(
        target_bedtime = "$targetBedtime:00",
        target_wake_time = "$targetWakeTime:00",
        actual_bedtime = Instant.ofEpochMilli(actualBedtime).toString(),
        actual_wake_time = Instant.ofEpochMilli(actualWakeTime).toString(),
        quality_rating = qualityRating,
        phone_pickups = phonePickups,
        total_phone_minutes = totalPhoneMinutes,
        notes = notes
    )
}

fun SleepStatsDto.toStats(): SleepStats {
    return SleepStats(
        avgDurationMinutes = avg_duration_minutes,
        avgQuality = avg_quality,
        totalRecords = total_records,
        sleepDebtMinutes = sleep_debt_minutes,
        avgPhonePickups = avg_phone_pickups,
        bestQualityDay = best_quality_day,
        worstQualityDay = worst_quality_day
    )
}

fun List<SleepRecordEntity>.toLocalStats(): SleepStats {
    if (isEmpty()) return SleepStats()

    val count = size.toDouble()
    var totalDurationMins = 0.0
    var totalQuality = 0.0
    var totalPickups = 0.0
    var totalDebtMins = 0.0
    var best: Pair<Int, String>? = null
    var worst: Pair<Int, String>? = null

    for (r in this) {
        val actualMins = (r.actualWakeTime - r.actualBedtime).toDouble() / 60_000
        totalDurationMins += actualMins
        totalQuality += r.qualityRating
        totalPickups += r.phonePickups

        val parts = r.targetBedtime.split(":")
        val bedSecs = parts[0].toInt() * 3600 + parts[1].toInt() * 60
        val wakeParts = r.targetWakeTime.split(":")
        val wakeSecs = wakeParts[0].toInt() * 3600 + wakeParts[1].toInt() * 60
        val targetSecs = if (wakeSecs >= bedSecs) wakeSecs - bedSecs else 86400 + wakeSecs - bedSecs
        totalDebtMins += targetSecs.toDouble() / 60 - actualMins

        val day = Instant.ofEpochMilli(r.actualBedtime)
            .atZone(ZoneId.systemDefault()).toLocalDate().toString()
        if (best == null || r.qualityRating > best.first) best = r.qualityRating to day
        if (worst == null || r.qualityRating < worst.first) worst = r.qualityRating to day
    }

    return SleepStats(
        avgDurationMinutes = totalDurationMins / count,
        avgQuality = totalQuality / count,
        totalRecords = size.toLong(),
        sleepDebtMinutes = totalDebtMins / count,
        avgPhonePickups = totalPickups / count,
        bestQualityDay = best?.second,
        worstQualityDay = worst?.second
    )
}
