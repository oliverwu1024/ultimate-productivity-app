use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use uuid::Uuid;

#[derive(Debug, Clone, Serialize, Deserialize, sqlx::Type)]
#[serde(rename_all = "lowercase")]
#[sqlx(type_name = "event_category", rename_all = "lowercase")]
pub enum EventCategory {
    Study,
    Project,
    Exercise,
    Personal,
    Other,
}

impl EventCategory {
    pub fn as_str(&self) -> &str {
        match self {
            Self::Study => "study",
            Self::Project => "project",
            Self::Exercise => "exercise",
            Self::Personal => "personal",
            Self::Other => "other",
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize, sqlx::Type)]
#[serde(rename_all = "lowercase")]
#[sqlx(type_name = "event_priority", rename_all = "lowercase")]
pub enum EventPriority {
    High,
    Medium,
    Low,
}

impl EventPriority {
    pub fn as_str(&self) -> &str {
        match self {
            Self::High => "high",
            Self::Medium => "medium",
            Self::Low => "low",
        }
    }
}

// Database row
#[derive(Debug, Clone, Serialize, sqlx::FromRow)]
pub struct CalendarEvent {
    pub id: Uuid,
    pub user_id: Uuid,
    pub title: String,
    pub description: Option<String>,
    pub start_time: DateTime<Utc>,
    pub end_time: DateTime<Utc>,
    pub category: EventCategory,
    pub priority: EventPriority,
    pub is_recurring: bool,
    pub recurrence_rule: Option<String>,
    pub color: String,
    pub is_done: bool,
    pub created_at: DateTime<Utc>,
    pub updated_at: DateTime<Utc>,
}

// Request: create/update event
#[derive(Debug, Deserialize)]
pub struct CreateCalendarEvent {
    pub title: String,
    pub description: Option<String>,
    pub start_time: DateTime<Utc>,
    pub end_time: DateTime<Utc>,
    pub category: EventCategory,
    pub priority: EventPriority,
    pub is_recurring: bool,
    pub recurrence_rule: Option<String>,
    pub color: Option<String>,
    /// Optional on input so older clients (without the field) preserve the
    /// stored value on update. New clients always send it.
    pub is_done: Option<bool>,
}
