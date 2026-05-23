use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use uuid::Uuid;

#[derive(Debug, Clone, Serialize, sqlx::FromRow)]
pub struct PhonePickup {
    pub id: Uuid,
    pub user_id: Uuid,
    pub sleep_record_id: Option<Uuid>,
    pub session_id: Option<Uuid>,
    pub picked_up_at: DateTime<Utc>,
    pub duration_seconds: i32,
    pub app_category: Option<String>,
    pub created_at: DateTime<Utc>,
}

#[derive(Debug, Deserialize)]
pub struct CreatePhonePickup {
    pub sleep_record_id: Option<Uuid>,
    pub session_id: Option<Uuid>,
    pub picked_up_at: DateTime<Utc>,
    pub duration_seconds: i32,
    pub app_category: Option<String>,
}

/// §10 — Inline pickup row inside a batch. Same shape as
/// [CreatePhonePickup] minus the sleep_record_id / session_id, which are
/// carried at the batch level (all events in one batch share a sleep_record).
#[derive(Debug, Deserialize)]
pub struct BatchPickupItem {
    pub picked_up_at: DateTime<Utc>,
    pub duration_seconds: i32,
    pub app_category: Option<String>,
}

/// §10 — Body for POST /phone-pickups/batch. The Android client buffers
/// per-pickup events in memory during a sleep session and uploads the lot
/// at End Sleep time. Server stores one row per event so the past-records
/// expansion can render the full timeline later.
#[derive(Debug, Deserialize)]
pub struct BatchCreatePhonePickups {
    pub sleep_record_id: Uuid,
    pub events: Vec<BatchPickupItem>,
}
