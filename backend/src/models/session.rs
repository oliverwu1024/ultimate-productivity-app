use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use uuid::Uuid;

// Database row
#[derive(Debug, Clone, Serialize, sqlx::FromRow)]
pub struct ProductivitySession {
    pub id: Uuid,
    pub user_id: Uuid,
    pub tag: String,
    pub duration_minutes: i32,
    pub work_duration: i32,
    pub break_duration: i32,
    pub phone_pickups: i32,
    pub started_at: DateTime<Utc>,
    pub ended_at: Option<DateTime<Utc>>,
    pub completed: bool,
    pub checklist_item_id: Option<Uuid>,
    /// §9.7 user-written "what did you work on?" line (≤ 240 chars).
    pub debrief: Option<String>,
    /// §9.7 Haiku-assigned tag — `deep_work` | `meetings` | `admin` | `other`.
    pub debrief_tag: Option<String>,
    /// §tz-anchor — IANA timezone the session was started in. Renders its
    /// wall-clock independent of the user's current tz. NULL → current tz.
    pub recorded_tz: Option<String>,
    pub created_at: DateTime<Utc>,
    pub updated_at: DateTime<Utc>,
}

// Request: create session
#[derive(Debug, Deserialize)]
pub struct CreateSession {
    pub tag: String,
    pub work_duration: i32,
    pub break_duration: i32,
    #[serde(default)]
    pub checklist_item_id: Option<Uuid>,
}

// Request: update session (partial)
#[derive(Debug, Deserialize)]
pub struct UpdateSession {
    pub ended_at: Option<DateTime<Utc>>,
    pub completed: Option<bool>,
    pub phone_pickups: Option<i32>,
    pub duration_minutes: Option<i32>,
}

// Response: session statistics
#[derive(Debug, Serialize)]
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

#[derive(Debug, Serialize, sqlx::FromRow)]
pub struct TagStat {
    pub tag: String,
    pub total_minutes: i64,
    pub session_count: i64,
}
