use chrono::{DateTime, NaiveDate, Utc};
use serde::Deserialize;

use crate::api::client::{get, ApiError};

#[derive(Debug, Clone, Deserialize)]
pub struct ProductivitySession {
    pub id: String,
    pub user_id: String,
    pub tag: String,
    pub duration_minutes: i32,
    pub work_duration: i32,
    pub break_duration: i32,
    pub phone_pickups: i32,
    pub started_at: DateTime<Utc>,
    pub ended_at: Option<DateTime<Utc>>,
    pub completed: bool,
    pub checklist_item_id: Option<String>,
    pub created_at: DateTime<Utc>,
    pub updated_at: DateTime<Utc>,
}

#[derive(Debug, Clone, Deserialize)]
pub struct TagStat {
    pub tag: String,
    pub total_minutes: i64,
    pub session_count: i64,
}

#[derive(Debug, Clone, Deserialize)]
pub struct SessionStats {
    pub total_focus_minutes_today: i64,
    pub total_focus_minutes_week: i64,
    pub sessions_completed_today: i64,
    pub sessions_completed_week: i64,
    pub current_streak_days: i64,
    pub longest_streak_days: i64,
    pub avg_phone_pickups_per_session: f64,
    pub total_phone_pickups_today: i64,
    pub top_tags: Vec<TagStat>,
}

pub async fn list_sessions(
    start: NaiveDate,
    end: NaiveDate,
) -> Result<Vec<ProductivitySession>, ApiError> {
    let path = format!("/sessions?start={}&end={}", start, end);
    get(&path).await
}

pub async fn fetch_stats(range: &str) -> Result<SessionStats, ApiError> {
    let path = format!("/sessions/stats?range={}", range);
    get(&path).await
}
