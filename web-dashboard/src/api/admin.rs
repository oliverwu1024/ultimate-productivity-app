use chrono::{DateTime, NaiveDate, Utc};
use serde::{Deserialize, Serialize};

use crate::api::client::{get, ApiError};

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SignupCount {
    pub date: NaiveDate,
    pub count: i64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AdminStats {
    pub total_users: i64,
    pub signups_last_7d: i64,
    pub signups_last_30d: i64,
    pub signups_by_day: Vec<SignupCount>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AdminUserEntry {
    pub id: String,
    pub email: String,
    pub created_at: DateTime<Utc>,
    pub is_admin: bool,
}

pub async fn fetch_stats() -> Result<AdminStats, ApiError> {
    get("/admin/stats").await
}

pub async fn fetch_users() -> Result<Vec<AdminUserEntry>, ApiError> {
    get("/admin/users").await
}
