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
    // §10.x — Pro-tier clip attached. `has_clip` defaults false so pre-023
    // backends (no column) deserialise cleanly; `clip_duration_ms` is null
    // until a clip is uploaded + attached.
    val has_clip: Boolean = false,
    val clip_duration_ms: Int? = null,
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
        hasClip = has_clip,
        clipDurationMs = clip_duration_ms,
    )
}

// §10.x — Pro-tier clip flow DTOs. The phone first requests a presigned
// PUT URL; uploads the AAC bytes directly to S3; then POSTs the s3_key +
// duration back to attach the clip to its event row.

data class ClipUploadUrlRequestDto(
    val event_id: String,
)

data class ClipUploadUrlResponseDto(
    val put_url: String,
    val s3_key: String,
    val content_type: String,
    val max_bytes: Long,
    val expires_in_secs: Long,
)

data class AttachClipRequestDto(
    val s3_key: String,
    val duration_ms: Int,
)

data class ClipPlaybackUrlResponseDto(
    val get_url: String,
    val expires_in_secs: Long,
)
