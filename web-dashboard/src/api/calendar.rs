use chrono::{DateTime, NaiveDate, Utc};
use serde::{Deserialize, Serialize};

use crate::api::client::{delete, get, post, put, ApiError};

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq, Hash)]
#[serde(rename_all = "lowercase")]
pub enum EventCategory {
    Study,
    Project,
    Exercise,
    Personal,
    Other,
}

impl EventCategory {
    pub const ALL: [EventCategory; 5] = [
        Self::Study,
        Self::Project,
        Self::Exercise,
        Self::Personal,
        Self::Other,
    ];

    pub fn label(&self) -> &'static str {
        match self {
            Self::Study => "Study",
            Self::Project => "Project",
            Self::Exercise => "Exercise",
            Self::Personal => "Personal",
            Self::Other => "Other",
        }
    }

    pub fn color(&self) -> &'static str {
        match self {
            Self::Study => "#4A90D9",
            Self::Project => "#E67E22",
            Self::Exercise => "#2ECC71",
            Self::Personal => "#9B59B6",
            Self::Other => "#95A5A6",
        }
    }

    pub fn from_str(s: &str) -> Option<Self> {
        match s {
            "Study" => Some(Self::Study),
            "Project" => Some(Self::Project),
            "Exercise" => Some(Self::Exercise),
            "Personal" => Some(Self::Personal),
            "Other" => Some(Self::Other),
            _ => None,
        }
    }

    pub fn variant_str(&self) -> &'static str {
        match self {
            Self::Study => "Study",
            Self::Project => "Project",
            Self::Exercise => "Exercise",
            Self::Personal => "Personal",
            Self::Other => "Other",
        }
    }
}

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq, Hash)]
#[serde(rename_all = "lowercase")]
pub enum EventPriority {
    High,
    Medium,
    Low,
}

impl EventPriority {
    pub const ALL: [EventPriority; 3] = [Self::High, Self::Medium, Self::Low];

    pub fn label(&self) -> &'static str {
        match self {
            Self::High => "High",
            Self::Medium => "Medium",
            Self::Low => "Low",
        }
    }

    pub fn from_str(s: &str) -> Option<Self> {
        match s {
            "High" => Some(Self::High),
            "Medium" => Some(Self::Medium),
            "Low" => Some(Self::Low),
            _ => None,
        }
    }

    pub fn variant_str(&self) -> &'static str {
        match self {
            Self::High => "High",
            Self::Medium => "Medium",
            Self::Low => "Low",
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CalendarEvent {
    pub id: String,
    pub user_id: String,
    pub title: String,
    pub description: Option<String>,
    pub start_time: DateTime<Utc>,
    pub end_time: DateTime<Utc>,
    pub category: EventCategory,
    pub priority: EventPriority,
    pub is_recurring: bool,
    pub recurrence_rule: Option<String>,
    pub color: String,
    /// Defaults to false on older payloads that don't include the field, so
    /// the dashboard keeps working against a backend that hasn't shipped the
    /// is_done migration yet.
    #[serde(default)]
    pub is_done: bool,
    /// v2.13.1 — Per-event reminder offsets (minutes-before-start), as an
    /// array so a single event can carry multiple reminders ("1 day +
    /// 1 hour"). Null = use client default; empty array = explicit
    /// opt-out; non-empty = the user's explicit picks.
    #[serde(default)]
    pub reminder_minutes: Option<Vec<i32>>,
    pub created_at: DateTime<Utc>,
    pub updated_at: DateTime<Utc>,
}

#[derive(Debug, Clone, Serialize)]
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
    /// Optional on send so updates that don't intend to flip the flag preserve
    /// the stored value (the backend COALESCEs it).
    #[serde(skip_serializing_if = "Option::is_none")]
    pub is_done: Option<bool>,
    /// Same Optional contract: omitted = backend preserves stored value
    /// (COALESCE). Some(vec![]) = explicit opt-out; Some(vec![15, 60]) =
    /// two reminders 15 min and 1 hr before. v2.13.1 widened from
    /// Option<i32> to Option<Vec<i32>> for multi-reminder support.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub reminder_minutes: Option<Vec<i32>>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum RecurringScope {
    JustThis,
    ThisAndFollowing,
    All,
}

pub async fn list_events(start: NaiveDate, end: NaiveDate) -> Result<Vec<CalendarEvent>, ApiError> {
    let path = format!("/calendar?start={}&end={}", start, end);
    get(&path).await
}

pub async fn create_event(input: &CreateCalendarEvent) -> Result<CalendarEvent, ApiError> {
    post("/calendar", input).await
}

pub async fn update_event(
    id: &str,
    input: &CreateCalendarEvent,
) -> Result<CalendarEvent, ApiError> {
    let path = format!("/calendar/{}", id);
    put(&path, input).await
}

pub async fn delete_event(id: &str) -> Result<(), ApiError> {
    let path = format!("/calendar/{}", id);
    delete(&path).await
}

/// v2.16.0 — Per-occurrence toggle. Backend only honours `is_done` from
/// the body when occurrence_date is present; every other field is
/// ignored, so callers can build a minimal placeholder body and let the
/// server preserve everything else.
pub async fn update_occurrence(
    id: &str,
    occurrence_date: NaiveDate,
    input: &CreateCalendarEvent,
) -> Result<CalendarEvent, ApiError> {
    let path = format!(
        "/calendar/{}?occurrence_date={}",
        id,
        occurrence_date.format("%Y-%m-%d"),
    );
    put(&path, input).await
}

/// v2.16.0 — Per-occurrence delete. Appends the date to the master
/// row's excluded_dates; the master row itself stays.
pub async fn delete_occurrence(id: &str, occurrence_date: NaiveDate) -> Result<(), ApiError> {
    let path = format!(
        "/calendar/{}?occurrence_date={}",
        id,
        occurrence_date.format("%Y-%m-%d"),
    );
    delete(&path).await
}
