use chrono::{DateTime, Datelike, NaiveDate, Utc};
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
    /// Bitmask Sun=bit 0..Sat=bit 6. 0 = non-recurring.
    #[serde(default)]
    pub recurrence_days_mask: i16,
    /// When true, the item shows every day from due_date until completed
    /// (or until its due_date passes). Distinct from recurrence.
    #[serde(default)]
    pub show_until_due: bool,
    /// Legacy single-day stamp (pre-§024). Kept on the wire for
    /// backward compat with older backend builds but UI no longer reads
    /// from it. Source of truth for recurring "done on day X" is
    /// `completed_epoch_days`.
    #[serde(default)]
    pub last_completed_epoch_day: Option<i64>,
    /// §024 — Every epoch day this recurring row has been ticked. Empty
    /// for non-recurring items. A tick on Tue no longer overwrites
    /// Mon's stamp, so navigating back to Mon still shows it as done.
    #[serde(default)]
    pub completed_epoch_days: Vec<i64>,
}

impl ChecklistItem {
    pub fn priority_enum(&self) -> Priority {
        Priority::from_i16(self.priority)
    }

    pub fn is_recurring(&self) -> bool {
        self.recurrence_days_mask != 0
    }

    /// True if this item is "done for [day]" — recurring items check the
    /// per-day completion log, non-recurring use the boolean. Falls back
    /// to the legacy `last_completed_epoch_day` when the new field is
    /// empty so users hitting a pre-§024 backend still see correct
    /// done-state (only the most-recent tick survives in that mode).
    pub fn is_done_on(&self, day: NaiveDate) -> bool {
        if self.is_recurring() {
            let epoch = naive_date_to_epoch_day(day);
            if !self.completed_epoch_days.is_empty() {
                self.completed_epoch_days.contains(&epoch)
            } else {
                self.last_completed_epoch_day == Some(epoch)
            }
        } else {
            self.completed
        }
    }

    /// True when the item should appear in the checklist for `day`:
    ///   - Recurring: weekday bit in mask AND start (due_date) <= day
    ///   - `show_until_due`: every day from now back to due_date, until done
    ///   - One-off: only on its due_date
    pub fn shows_on(&self, day: NaiveDate) -> bool {
        if self.is_recurring() {
            let bit = 1i16 << day.weekday().num_days_from_sunday();
            (self.recurrence_days_mask & bit) != 0 && self.due_date <= day
        } else if self.show_until_due {
            day <= self.due_date && !self.completed
        } else {
            self.due_date == day
        }
    }
}

/// Convert a chrono NaiveDate to epoch-day. Mirrors Java's
/// `LocalDate.toEpochDay()` for parity with the Android client.
pub fn naive_date_to_epoch_day(d: NaiveDate) -> i64 {
    d.num_days_from_ce() as i64 - 719_163
}

#[derive(Debug, Clone, Serialize)]
pub struct CreateChecklistItem {
    pub title: String,
    pub description: Option<String>,
    pub due_date: NaiveDate,
    pub estimated_minutes: Option<i32>,
    pub priority: i16,
    #[serde(default)]
    pub recurrence_days_mask: i16,
    #[serde(default)]
    pub show_until_due: bool,
}

#[derive(Debug, Clone, Serialize)]
pub struct UpdateChecklistItem {
    pub title: Option<String>,
    pub description: Option<String>,
    pub due_date: Option<NaiveDate>,
    pub estimated_minutes: Option<i32>,
    pub priority: Option<i16>,
    pub completed: Option<bool>,
    pub recurrence_days_mask: Option<i16>,
    pub show_until_due: Option<bool>,
    /// Setting Some(epoch_day) stamps the recurring "done for today" marker.
    /// The backend currently can't distinguish field-omitted from
    /// JSON-null, so sending None means "leave unchanged" — mirroring
    /// the Android client's behavior.
    pub last_completed_epoch_day: Option<i64>,
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

/// §recurring-uncomplete-fix — dedicated symmetric inverse of /complete.
/// Server clears `completed`, `completed_at`, and `last_completed_epoch_day`
/// atomically so recurring un-ticks survive the next sync.
pub async fn uncomplete_item(id: &str) -> Result<ChecklistItem, ApiError> {
    let path = format!("/checklist/{}/uncomplete", id);
    let empty = serde_json::json!({});
    post(&path, &empty).await
}

/// §024 — Tick a recurring item for a specific epoch day. Server
/// inserts a row into checklist_completions (idempotent). Use this
/// instead of the legacy PUT-with-last_completed_epoch_day path so a
/// tick on day X doesn't clobber a tick on day Y.
pub async fn complete_item_on(id: &str, epoch_day: i64) -> Result<ChecklistItem, ApiError> {
    let path = format!("/checklist/{}/complete-on/{}", id, epoch_day);
    let empty = serde_json::json!({});
    post(&path, &empty).await
}

/// §024 — Symmetric inverse of [complete_item_on]. Removes the
/// (item, day) row from checklist_completions. Idempotent.
pub async fn uncomplete_item_on(id: &str, epoch_day: i64) -> Result<ChecklistItem, ApiError> {
    let path = format!("/checklist/{}/uncomplete-on/{}", id, epoch_day);
    let empty = serde_json::json!({});
    post(&path, &empty).await
}

pub async fn delete_item(id: &str) -> Result<(), ApiError> {
    let path = format!("/checklist/{}", id);
    delete(&path).await
}
