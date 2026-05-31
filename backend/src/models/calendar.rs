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
    /// Minutes-before-start_time offsets for each reminder notification.
    /// v2.13.1 widened this from a single Option<i32> to an array so a user
    /// can attach multiple reminders to one event ("1 day before AND 1 hour
    /// before"). NULL = client default (single 15-min reminder); empty array
    /// = explicit opt-out; non-empty = explicit list.
    pub reminder_minutes: Option<Vec<i32>>,
    /// §029 — Comma-separated YYYY-MM-DD list of occurrence dates that
    /// the user has explicitly marked done. Meaningful only when
    /// is_recurring is true. Expansion (`expand_recurrence`) sets
    /// is_done=true on instances whose local date is in this set.
    /// NULL = no per-occurrence done state (every occurrence inherits
    /// is_done from the master row).
    pub done_dates: Option<String>,
    /// §029 — Comma-separated YYYY-MM-DD list of dates to skip entirely
    /// when expanding (iCal EXDATE). Drives the "Just this one" delete
    /// + "Just this one" edit paths.
    pub excluded_dates: Option<String>,
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
    /// Same Optional contract as is_done: omitted = preserve existing stored
    /// value (server COALESCEs); explicit Some(_) = update. v2.13.1 widened
    /// from Option<i32> to Option<Vec<i32>> for multi-reminder support.
    #[serde(default)]
    pub reminder_minutes: Option<Vec<i32>>,
}
