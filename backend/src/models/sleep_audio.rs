use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use uuid::Uuid;

/// One on-device YAMNet event (snore / cough / sleep_talk). `clip_s3_key`
/// is the canonical owner of "does this row have a Pro clip attached"; the
/// public response struct below downgrades it to a boolean so the key
/// itself never leaves the backend (clients only ever see presigned URLs).
#[derive(Debug, Clone, sqlx::FromRow)]
pub struct SleepAudioEvent {
    pub id: Uuid,
    pub user_id: Uuid,
    pub sleep_record_id: Uuid,
    pub event_type: String,
    pub started_at: DateTime<Utc>,
    pub ended_at: DateTime<Utc>,
    pub peak_confidence: f32,
    pub created_at: DateTime<Utc>,
    /// §10.x — Set when the user is Pro AND had the "Record events" master
    /// toggle on AND the per-type filter allowed this `event_type`. NULL on
    /// every other row, including all rows from v2.11.x.
    pub clip_s3_key: Option<String>,
    pub clip_duration_ms: Option<i32>,
}

/// Public-facing event payload. Strips `clip_s3_key` (server-internal) and
/// surfaces `has_clip` so clients can decide whether to render the ▶
/// affordance without learning the S3 key itself.
#[derive(Debug, Clone, Serialize)]
pub struct SleepAudioEventResponse {
    pub id: Uuid,
    pub user_id: Uuid,
    pub sleep_record_id: Uuid,
    pub event_type: String,
    pub started_at: DateTime<Utc>,
    pub ended_at: DateTime<Utc>,
    pub peak_confidence: f32,
    pub created_at: DateTime<Utc>,
    pub has_clip: bool,
    pub clip_duration_ms: Option<i32>,
}

impl From<SleepAudioEvent> for SleepAudioEventResponse {
    fn from(e: SleepAudioEvent) -> Self {
        let has_clip = e.clip_s3_key.is_some();
        Self {
            id: e.id,
            user_id: e.user_id,
            sleep_record_id: e.sleep_record_id,
            event_type: e.event_type,
            started_at: e.started_at,
            ended_at: e.ended_at,
            peak_confidence: e.peak_confidence,
            created_at: e.created_at,
            has_clip,
            clip_duration_ms: e.clip_duration_ms,
        }
    }
}

#[derive(Debug, Deserialize)]
pub struct CreateSleepAudioEvent {
    /// §10.x-fix — Client-generated UUID. Honoured so the row's id is the
    /// same on phone and server, which is what makes the clip-upload + the
    /// per-clip delete + the post-upload UI refresh actually agree on
    /// which row they're talking about. Optional for back-compat with any
    /// older client; backend default fires when absent.
    pub id: Option<Uuid>,
    pub event_type: String,
    pub started_at: DateTime<Utc>,
    pub ended_at: DateTime<Utc>,
    pub peak_confidence: f32,
}

#[derive(Debug, Deserialize)]
pub struct BatchCreateSleepAudioEvents {
    pub sleep_record_id: Uuid,
    pub events: Vec<CreateSleepAudioEvent>,
}

/// §10.x — Body for `POST /sleep-audio-events/:id/clip`. The phone has just
/// finished a successful S3 PUT against the key the backend handed out on
/// the prior `clip-upload-url` call, and now tells the backend to bind that
/// key + the measured clip duration to the event row.
#[derive(Debug, Deserialize)]
pub struct AttachClipRequest {
    pub s3_key: String,
    pub duration_ms: i32,
}

/// Response for `POST /sleep-audio-events/clip-upload-url`.
#[derive(Debug, Serialize)]
pub struct ClipUploadUrlResponse {
    pub put_url: String,
    pub s3_key: String,
    pub content_type: &'static str,
    pub max_bytes: i64,
    pub expires_in_secs: u64,
}

/// Response for `GET /sleep-audio-events/:id/clip-url`.
#[derive(Debug, Serialize)]
pub struct ClipPlaybackUrlResponse {
    pub get_url: String,
    pub expires_in_secs: u64,
}
