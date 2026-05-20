use chrono::{DateTime, NaiveTime, Utc};
use serde::{Deserialize, Serialize};
use serde_json::Value as JsonValue;
use uuid::Uuid;

// ── Alarm ─────────────────────────────────────────────────────────────────

#[derive(Debug, Clone, Serialize, sqlx::FromRow)]
pub struct Alarm {
    pub id: Uuid,
    pub user_id: Uuid,
    pub label: Option<String>,
    pub trigger_time_local: NaiveTime,
    pub days_of_week: i16,
    pub enabled: bool,
    pub sound_uri: Option<String>,
    pub volume_pct: i16,
    pub volume_escalates: bool,
    pub vibration: bool,
    pub snooze_minutes: i16,
    pub snooze_max: i16,
    pub mission_kind: String,
    pub mission_config: JsonValue,
    pub created_at: DateTime<Utc>,
    pub updated_at: DateTime<Utc>,
}

#[derive(Debug, Deserialize)]
pub struct CreateAlarm {
    /// §H1/M4: when present, the server upserts on this id, so an alarm
    /// created offline keeps its UUID across the sync round-trip. Child
    /// rows in `alarm_events` retain their FK reference.
    pub id: Option<Uuid>,
    pub label: Option<String>,
    pub trigger_time_local: NaiveTime,
    pub days_of_week: i16,
    pub enabled: Option<bool>,
    pub sound_uri: Option<String>,
    pub volume_pct: Option<i16>,
    pub volume_escalates: Option<bool>,
    pub vibration: Option<bool>,
    pub snooze_minutes: Option<i16>,
    pub snooze_max: Option<i16>,
    pub mission_kind: String,
    pub mission_config: Option<JsonValue>,
}

// ── Alarm event (firing telemetry) ────────────────────────────────────────

#[derive(Debug, Clone, Serialize, sqlx::FromRow)]
pub struct AlarmEvent {
    pub id: Uuid,
    pub user_id: Uuid,
    pub alarm_id: Option<Uuid>,
    pub fired_at: DateTime<Utc>,
    pub dismissed_at: Option<DateTime<Utc>>,
    pub dismiss_method: Option<String>,
    pub snooze_count: i16,
    pub mission_kind: Option<String>,
    pub mission_attempts: i16,
    pub mission_duration_ms: Option<i32>,
    pub created_at: DateTime<Utc>,
}

#[derive(Debug, Deserialize)]
pub struct CreateAlarmEvent {
    pub fired_at: DateTime<Utc>,
    pub dismissed_at: Option<DateTime<Utc>>,
    pub dismiss_method: Option<String>,
    pub snooze_count: Option<i16>,
    pub mission_kind: Option<String>,
    pub mission_attempts: Option<i16>,
    pub mission_duration_ms: Option<i32>,
}
