use chrono::{DateTime, NaiveTime, Utc};
use serde::{Deserialize, Serialize};
use uuid::Uuid;

// Database row
#[derive(Debug, Clone, Serialize, sqlx::FromRow)]
pub struct SleepRecord {
    pub id: Uuid,
    pub user_id: Uuid,
    pub target_bedtime: NaiveTime,
    pub target_wake_time: NaiveTime,
    pub actual_bedtime: DateTime<Utc>,
    pub actual_wake_time: DateTime<Utc>,
    pub quality_rating: i16,
    pub phone_pickups: i32,
    pub total_phone_minutes: Option<i32>,
    pub notes: Option<String>,
    pub created_at: DateTime<Utc>,
    pub updated_at: DateTime<Utc>,
}

// Request: create/update
#[derive(Debug, Deserialize)]
pub struct CreateSleepRecord {
    pub target_bedtime: NaiveTime,
    pub target_wake_time: NaiveTime,
    pub actual_bedtime: DateTime<Utc>,
    pub actual_wake_time: DateTime<Utc>,
    pub quality_rating: i16,
    pub phone_pickups: i32,
    pub total_phone_minutes: Option<i32>,
    pub notes: Option<String>,
}

// Response: sleep stats
#[derive(Debug, Serialize)]
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
