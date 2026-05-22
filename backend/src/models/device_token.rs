use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use uuid::Uuid;

/// §9.8 — FCM device-token row. One row per token globally; the same token
/// can transfer between users when a phone is logged out and back in.
#[derive(Debug, Clone, Serialize, sqlx::FromRow)]
pub struct DeviceToken {
    pub id: Uuid,
    pub user_id: Uuid,
    pub token: String,
    pub platform: String,
    pub last_seen_at: DateTime<Utc>,
    pub created_at: DateTime<Utc>,
}

/// Body for `POST /devices/register`. Mobile sends the FCM token it receives
/// from `FirebaseMessaging.getInstance().token` (and on token rotation via
/// `onNewToken`).
#[derive(Debug, Deserialize)]
pub struct RegisterDeviceTokenRequest {
    pub token: String,
    pub platform: String,
}
