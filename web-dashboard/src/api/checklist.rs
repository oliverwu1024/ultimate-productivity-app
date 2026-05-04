use chrono::{DateTime, NaiveDate, Utc};
use serde::{Deserialize, Serialize};

use crate::api::client::{delete, get, post, put, ApiError};

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Priority {
    Low,
    Medium,
    High,
}

impl Priority {
    pub const ALL: [Priority; 3] = [Self::Low, Self::Medium, Self::High];

    pub fn from_i16(n: i16) -> Self {
        match n {
            0 => Self::Low,
            2 => Self::High,
            _ => Self::Medium,
        }
    }

    pub fn to_i16(self) -> i16 {
        match self {
            Self::Low => 0,
            Self::Medium => 1,
            Self::High => 2,
        }
    }

    pub fn label(&self) -> &'static str {
        match self {
            Self::Low => "Low",
            Self::Medium => "Medium",
            Self::High => "High",
        }
    }

    pub fn from_str(s: &str) -> Option<Self> {
        match s {
            "Low" => Some(Self::Low),
            "Medium" => Some(Self::Medium),
            "High" => Some(Self::High),
            _ => None,
        }
    }

    pub fn variant_str(&self) -> &'static str {
        self.label()
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ChecklistItem {
    pub id: String,
    pub user_id: String,
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

impl ChecklistItem {
    pub fn priority_enum(&self) -> Priority {
        Priority::from_i16(self.priority)
    }
}

#[derive(Debug, Clone, Serialize)]
pub struct CreateChecklistItem {
    pub title: String,
    pub description: Option<String>,
    pub due_date: NaiveDate,
    pub estimated_minutes: Option<i32>,
    pub priority: i16,
}

#[derive(Debug, Clone, Serialize)]
pub struct UpdateChecklistItem {
    pub title: Option<String>,
    pub description: Option<String>,
    pub due_date: Option<NaiveDate>,
    pub estimated_minutes: Option<i32>,
    pub priority: Option<i16>,
    pub completed: Option<bool>,
}

pub async fn list_for_range(
    start: NaiveDate,
    end: NaiveDate,
) -> Result<Vec<ChecklistItem>, ApiError> {
    let path = format!("/checklist?start={}&end={}", start, end);
    get(&path).await
}

pub async fn create_item(input: &CreateChecklistItem) -> Result<ChecklistItem, ApiError> {
    post("/checklist", input).await
}

pub async fn update_item(
    id: &str,
    input: &UpdateChecklistItem,
) -> Result<ChecklistItem, ApiError> {
    let path = format!("/checklist/{}", id);
    put(&path, input).await
}

pub async fn complete_item(id: &str) -> Result<ChecklistItem, ApiError> {
    let path = format!("/checklist/{}/complete", id);
    let empty = serde_json::json!({});
    post(&path, &empty).await
}

pub async fn delete_item(id: &str) -> Result<(), ApiError> {
    let path = format!("/checklist/{}", id);
    delete(&path).await
}
