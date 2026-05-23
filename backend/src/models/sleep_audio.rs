use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use uuid::Uuid;

#[derive(Debug, Clone, Serialize, sqlx::FromRow)]
pub struct SleepAudioEvent {
    pub id: Uuid,
    pub user_id: Uuid,
    pub sleep_record_id: Uuid,
    pub event_type: String,
    pub started_at: DateTime<Utc>,
    pub ended_at: DateTime<Utc>,
    pub peak_confidence: f32,
    pub created_at: DateTime<Utc>,
}

#[derive(Debug, Deserialize)]
pub struct CreateSleepAudioEvent {
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
