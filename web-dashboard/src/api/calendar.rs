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
