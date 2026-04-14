use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use uuid::Uuid;

#[derive(Debug, Serialize, sqlx::FromRow)]
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
