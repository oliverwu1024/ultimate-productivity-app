package com.ultiq.app.data.remote.dto

import com.ultiq.app.data.local.entity.SleepAudioEventEntity
import java.time.Instant

data class SleepAudioEventDto(
    val id: String,
    val user_id: String,
    val sleep_record_id: String,
    val event_type: String,
    val started_at: String,
    val ended_at: String,
    val peak_confidence: Float,
    val created_at: String,
)

data class CreateSleepAudioEventDto(
    val event_type: String,
    val started_at: String,
    val ended_at: String,
    val peak_confidence: Float,
)

data class BatchCreateSleepAudioEventsDto(
    val sleep_record_id: String,
    val events: List<CreateSleepAudioEventDto>,
)

fun SleepAudioEventEntity.toCreateDto(): CreateSleepAudioEventDto {
    return CreateSleepAudioEventDto(
        event_type = eventType,
        started_at = Instant.ofEpochMilli(startedAt).toString(),
        ended_at = Instant.ofEpochMilli(endedAt).toString(),
        peak_confidence = peakConfidence,
    )
}

fun SleepAudioEventDto.toEntity(): SleepAudioEventEntity {
    return SleepAudioEventEntity(
        id = id,
        userId = user_id,
        sleepRecordId = sleep_record_id,
        eventType = event_type,
        startedAt = Instant.parse(started_at).toEpochMilli(),
        endedAt = Instant.parse(ended_at).toEpochMilli(),
        peakConfidence = peak_confidence,
        createdAt = Instant.parse(created_at).toEpochMilli(),
        isSynced = true,
    )
}
