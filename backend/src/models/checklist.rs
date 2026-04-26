use chrono::{DateTime, NaiveDate, Utc};
use serde::{Deserialize, Serialize};
use uuid::Uuid;

#[derive(Debug, Serialize, sqlx::FromRow)]
pub struct ChecklistItem {
    pub id: Uuid,
    pub user_id: Uuid,
    pub title: String,
    pub description: Option<String>,
    pub due_date: NaiveDate,
    pub estimated_minutes: Option<i32>,
    pub priority: i16,
    pub completed: bool,
    pub completed_at: Option<DateTime<Utc>>,
    pub created_at: DateTime<Utc>,
    pub updated_at: DateTime<Utc>,
}

#[derive(Debug, Deserialize)]
pub struct CreateChecklistItem {
    pub title: String,
    #[serde(default)]
    pub description: Option<String>,
    pub due_date: NaiveDate,
    #[serde(default)]
    pub estimated_minutes: Option<i32>,
    #[serde(default = "default_priority")]
    pub priority: i16,
}

fn default_priority() -> i16 {
    1
}

#[derive(Debug, Deserialize)]
pub struct UpdateChecklistItem {
    pub title: Option<String>,
    pub description: Option<String>,
    pub due_date: Option<NaiveDate>,
    pub estimated_minutes: Option<i32>,
    pub priority: Option<i16>,
    pub completed: Option<bool>,
}
