use chrono::{DateTime, NaiveDate, NaiveTime, Utc};
use serde::Deserialize;

use crate::api::client::{delete, get, ApiError};

#[derive(Debug, Clone, Deserialize)]
pub struct SleepRecord {
    pub id: String,
    pub user_id: String,
    pub target_bedtime: NaiveTime,
    pub target_wake_time: NaiveTime,
    pub actual_bedtime: DateTime<Utc>,
    pub actual_wake_time: DateTime<Utc>,
    pub quality_rating: i16,
    pub phone_pickups: i32,
    pub total_phone_minutes: Option<i32>,
    pub notes: Option<String>,
    /// §last-night — daytime nap / short test session. `serde(default)` so
    /// responses from a backend that predates the column still deserialize.
    #[serde(default)]
    pub is_nap: bool,
    pub created_at: DateTime<Utc>,
    pub updated_at: DateTime<Utc>,
}

#[derive(Debug, Clone, Deserialize)]
pub struct SleepStats {
    pub avg_duration_minutes: f64,
    pub avg_quality: f64,
    pub total_records: i64,
    pub debt_minutes: f64,
    pub extra_minutes: f64,
    pub sleep_target_minutes: i32,
    pub avg_phone_pickups: f64,
    pub best_quality_day: Option<String>,
    pub worst_quality_day: Option<String>,
}

pub async fn list_records(
    start: NaiveDate,
    end: NaiveDate,
) -> Result<Vec<SleepRecord>, ApiError> {
    let path = format!("/sleep?start={}&end={}", start, end);
    get(&path).await
}

pub async fn fetch_stats(range: &str) -> Result<SleepStats, ApiError> {
    let path = format!("/sleep/stats?range={}", range);
    get(&path).await
}

/// §10 — On-device YAMNet event row, one per debounced snore / cough / sleep_talk
/// episode during a sleep session. Labels + timestamps are always uploaded;
/// the raw audio clip (`has_clip = true`) is Pro-tier only and fetched via
/// a separate presigned-URL call.
#[derive(Debug, Clone, Deserialize)]
pub struct SleepAudioEvent {
    pub id: String,
    pub user_id: String,
    pub sleep_record_id: String,
    pub event_type: String,
    pub started_at: DateTime<Utc>,
    pub ended_at: DateTime<Utc>,
    pub peak_confidence: f32,
    pub created_at: DateTime<Utc>,
    /// §10.x — True when this event has a Pro-tier audio clip in S3.
    /// `serde(default)` so a backend running pre-023 (before the column
    /// existed) still deserialises to `false` — safe rollback path.
    #[serde(default)]
    pub has_clip: bool,
    #[serde(default)]
    pub clip_duration_ms: Option<i32>,
}

pub async fn list_audio_events_for_record(
    sleep_record_id: &str,
) -> Result<Vec<SleepAudioEvent>, ApiError> {
    let path = format!("/sleep-audio-events?sleep_record_id={}", sleep_record_id);
    get(&path).await
}

/// §10.x — Short-lived presigned GET for the AAC clip behind a specific
/// event. Fetched fresh on every ▶ tap; the URL is intentionally
/// single-use-shaped (1 min TTL, server-side).
#[derive(Debug, Clone, Deserialize)]
pub struct ClipPlaybackUrl {
    pub get_url: String,
    pub expires_in_secs: u64,
}

pub async fn fetch_clip_playback_url(event_id: &str) -> Result<ClipPlaybackUrl, ApiError> {
    let path = format!("/sleep-audio-events/{}/clip-url", event_id);
    get(&path).await
}

pub async fn delete_audio_clip(event_id: &str) -> Result<(), ApiError> {
    let path = format!("/sleep-audio-events/{}/clip", event_id);
    delete(&path).await
}
