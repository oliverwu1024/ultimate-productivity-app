package com.ultiq.app.data.remote.dto

import com.ultiq.app.data.local.entity.SessionEntity
import java.time.Instant

data class CreateSessionDto(
    val tag: String,
    val work_duration: Int,
    val break_duration: Int,
    val checklist_item_id: String? = null,
)

data class UpdateSessionDto(
    val ended_at: String?,
    val completed: Boolean?,
    val phone_pickups: Int?,
    val duration_minutes: Int?
)

data class SessionDto(
    val id: String,
    val user_id: String,
    val tag: String,
    val duration_minutes: Int,
    val work_duration: Int,
    val break_duration: Int,
    val phone_pickups: Int,
    val started_at: String,
    val ended_at: String?,
    val completed: Boolean,
    val checklist_item_id: String? = null,
    val created_at: String,
    val updated_at: String,
    // §9.7 / 2026-06-06 — Server already serialises these from
    // ProductivitySession; previously ignored client-side. Default = null
    // because skipping the debrief dialog is supported.
    val debrief: String? = null,
    val debrief_tag: String? = null,
    val recorded_tz: String? = null,
)

data class SessionStatsDto(
    val total_focus_minutes_today: Long,
    val total_focus_minutes_week: Long,
    val sessions_completed_today: Long,
    val sessions_completed_week: Long,
    val current_streak_days: Long,
    val longest_streak_days: Long,
    val avg_phone_pickups_per_session: Double,
    val total_phone_pickups_today: Long,
    val top_tags: List<TagStatDto>
)

data class TagStatDto(
    val tag: String,
    val total_minutes: Long,
    val session_count: Long
)

data class SessionStats(
    val totalFocusMinutesToday: Long = 0,
    val totalFocusMinutesWeek: Long = 0,
    val sessionsCompletedToday: Long = 0,
    val sessionsCompletedWeek: Long = 0,
    val currentStreakDays: Long = 0,
    val longestStreakDays: Long = 0,
    val avgPhonePickupsPerSession: Double = 0.0,
    val totalPhonePickupsToday: Long = 0,
    val topTags: List<TagStat> = emptyList()
)

data class TagStat(
    val tag: String,
    val totalMinutes: Long,
    val sessionCount: Long
)

fun SessionDto.toEntity(): SessionEntity {
    return SessionEntity(
        id = id,
        userId = user_id,
        tag = tag,
        durationMinutes = duration_minutes,
        workDuration = work_duration,
        breakDuration = break_duration,
        phonePickups = phone_pickups,
        startedAt = Instant.parse(started_at).toEpochMilli(),
        endedAt = ended_at?.let { Instant.parse(it).toEpochMilli() },
        completed = completed,
        createdAt = Instant.parse(created_at).toEpochMilli(),
        updatedAt = Instant.parse(updated_at).toEpochMilli(),
        checklistItemId = checklist_item_id,
        isSynced = true,
        debrief = debrief,
        debriefTag = debrief_tag,
        recordedTz = recorded_tz,
    )
}

fun SessionEntity.toCreateDto(): CreateSessionDto {
    return CreateSessionDto(
        tag = tag,
        work_duration = workDuration,
        break_duration = breakDuration,
        checklist_item_id = checklistItemId,
    )
}

fun SessionEntity.toUpdateDto(): UpdateSessionDto {
    return UpdateSessionDto(
        ended_at = endedAt?.let { Instant.ofEpochMilli(it).toString() },
        completed = completed,
        phone_pickups = phonePickups,
        duration_minutes = durationMinutes
    )
}

fun SessionStatsDto.toStats(): SessionStats {
    return SessionStats(
        totalFocusMinutesToday = total_focus_minutes_today,
        totalFocusMinutesWeek = total_focus_minutes_week,
        sessionsCompletedToday = sessions_completed_today,
        sessionsCompletedWeek = sessions_completed_week,
        currentStreakDays = current_streak_days,
        longestStreakDays = longest_streak_days,
        avgPhonePickupsPerSession = avg_phone_pickups_per_session,
        totalPhonePickupsToday = total_phone_pickups_today,
        topTags = top_tags.map { TagStat(it.tag, it.total_minutes, it.session_count) }
    )
}
