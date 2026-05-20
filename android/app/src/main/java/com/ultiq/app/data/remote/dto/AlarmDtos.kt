package com.ultiq.app.data.remote.dto

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.ultiq.app.data.local.entity.AlarmEntity
import com.ultiq.app.data.local.entity.AlarmEventEntity
import java.time.Instant

// ── Alarm ─────────────────────────────────────────────────────────────────

/**
 * Wire-format alarm payload. `trigger_time_local` is a `HH:MM:SS` string
 * (PostgreSQL TIME). `mission_config` is a Gson [JsonObject], not Any? — see
 * §M9: typing as `Any?` made Gson default to LinkedTreeMap with Double-typed
 * numbers, which made the round-trip through `JSONObject.toString` fragile.
 *
 * §H1/M4: when [id] is non-null, the server upserts on it (preserving the
 * id across offline-create → online-sync), so child rows in `alarm_events`
 * keep their `alarm_id` reference. Server still mints a UUID if [id] is null.
 */
data class CreateAlarmDto(
    val id: String?,
    val label: String?,
    val trigger_time_local: String,
    val days_of_week: Int,
    val enabled: Boolean,
    val sound_uri: String?,
    val volume_pct: Int,
    val volume_escalates: Boolean,
    val vibration: Boolean,
    val snooze_minutes: Int,
    val snooze_max: Int,
    val mission_kind: String,
    val mission_config: JsonObject?,
)

data class AlarmDto(
    val id: String,
    val user_id: String,
    val label: String?,
    val trigger_time_local: String,
    val days_of_week: Int,
    val enabled: Boolean,
    val sound_uri: String?,
    val volume_pct: Int,
    val volume_escalates: Boolean,
    val vibration: Boolean,
    val snooze_minutes: Int,
    val snooze_max: Int,
    val mission_kind: String,
    val mission_config: JsonObject?,
    val created_at: String,
    val updated_at: String,
)

fun AlarmDto.toEntity(): AlarmEntity {
    val (hh, mm) = trigger_time_local.split(":").let { parts ->
        val h = parts.getOrNull(0)?.toIntOrNull() ?: 7
        val m = parts.getOrNull(1)?.toIntOrNull() ?: 0
        h to m
    }
    return AlarmEntity(
        id = id,
        userId = user_id,
        label = label,
        triggerHour = hh,
        triggerMinute = mm,
        daysOfWeekMask = days_of_week,
        enabled = enabled,
        soundUri = sound_uri,
        volumePct = volume_pct,
        volumeEscalates = volume_escalates,
        vibration = vibration,
        snoozeMinutes = snooze_minutes,
        snoozeMax = snooze_max,
        missionKind = mission_kind,
        // Gson's JsonObject.toString() is the canonical JSON serialisation;
        // safe to store directly in Room without re-parsing through org.json.
        missionConfigJson = mission_config?.toString() ?: "{}",
        createdAt = Instant.parse(created_at).toEpochMilli(),
        updatedAt = Instant.parse(updated_at).toEpochMilli(),
        isSynced = true,
    )
}

fun AlarmEntity.toCreateDto(): CreateAlarmDto {
    val timeStr = "%02d:%02d:00".format(triggerHour, triggerMinute)
    val configJson: JsonObject = runCatching {
        JsonParser.parseString(missionConfigJson).asJsonObject
    }.getOrDefault(JsonObject())

    return CreateAlarmDto(
        id = id,
        label = label,
        trigger_time_local = timeStr,
        days_of_week = daysOfWeekMask,
        enabled = enabled,
        sound_uri = soundUri,
        volume_pct = volumePct,
        volume_escalates = volumeEscalates,
        vibration = vibration,
        snooze_minutes = snoozeMinutes,
        snooze_max = snoozeMax,
        mission_kind = missionKind,
        mission_config = configJson,
    )
}

// ── Alarm events ──────────────────────────────────────────────────────────

data class CreateAlarmEventDto(
    val fired_at: String,
    val dismissed_at: String?,
    val dismiss_method: String?,
    val snooze_count: Int,
    val mission_kind: String?,
    val mission_attempts: Int,
    val mission_duration_ms: Int?,
)

data class AlarmEventDto(
    val id: String,
    val user_id: String,
    val alarm_id: String?,
    val fired_at: String,
    val dismissed_at: String?,
    val dismiss_method: String?,
    val snooze_count: Int,
    val mission_kind: String?,
    val mission_attempts: Int,
    val mission_duration_ms: Int?,
    val created_at: String,
)

fun AlarmEventEntity.toCreateDto(): CreateAlarmEventDto = CreateAlarmEventDto(
    fired_at = Instant.ofEpochMilli(firedAt).toString(),
    dismissed_at = dismissedAt?.let { Instant.ofEpochMilli(it).toString() },
    dismiss_method = dismissMethod,
    snooze_count = snoozeCount,
    mission_kind = missionKind,
    mission_attempts = missionAttempts,
    mission_duration_ms = missionDurationMs,
)

fun AlarmEventDto.toEntity(): AlarmEventEntity = AlarmEventEntity(
    id = id,
    userId = user_id,
    alarmId = alarm_id,
    firedAt = Instant.parse(fired_at).toEpochMilli(),
    dismissedAt = dismissed_at?.let { Instant.parse(it).toEpochMilli() },
    dismissMethod = dismiss_method,
    snoozeCount = snooze_count,
    missionKind = mission_kind,
    missionAttempts = mission_attempts,
    missionDurationMs = mission_duration_ms,
    createdAt = Instant.parse(created_at).toEpochMilli(),
    isSynced = true,
)
