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

/// §10 — Inline pickup row inside a batch. The parent (sleep_record_id or
/// session_id) is carried at the batch level.
///
/// §v2.16.18 — Optional `id` for client-supplied UUIDs. When present the
/// backend's `ON CONFLICT (id) DO NOTHING` collapses retries of the same
/// logical pickup onto one row — same idempotency pattern as v2.16.15
/// sleep_records. Pre-v2.16.18 clients omit it; backend mints one.
#[derive(Debug, Deserialize)]
pub struct BatchPickupItem {
    pub id: Option<Uuid>,
    pub picked_up_at: DateTime<Utc>,
    pub duration_seconds: i32,
    pub app_category: Option<String>,
}

/// §10 — Body for POST /phone-pickups/batch.
///
/// §v2.16.18 — `sleep_record_id` is now optional and `session_id` was
/// added. Exactly one of the two must be set. Sleep sessions stay on
/// `sleep_record_id`; focus sessions (the new path) use `session_id`.
/// Both parent types live in the same `phone_pickups` table with
/// mutually-exclusive FK columns, so the timeline UX is identical.
#[derive(Debug, Deserialize)]
pub struct BatchCreatePhonePickups {
    pub sleep_record_id: Option<Uuid>,
    pub session_id: Option<Uuid>,
    pub events: Vec<BatchPickupItem>,
}
