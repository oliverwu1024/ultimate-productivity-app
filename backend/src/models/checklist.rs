use chrono::{DateTime, NaiveDate, Utc};
use serde::{Deserialize, Serialize};
use uuid::Uuid;

/// Wire + DB representation of a single checklist item.
///
/// `completed_epoch_days` is populated by joining `checklist_completions`
/// at the query layer (see `routes::checklist::select_with_completions`).
/// For non-recurring items it's always empty; for recurring items it
/// holds every day the row has been ticked. The legacy
/// `last_completed_epoch_day` column is still echoed back for
/// backward-compat with pre-024 mobile clients but no server-side logic
/// reads it anymore.
#[derive(Debug, Clone, Serialize, sqlx::FromRow)]
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
    pub recurrence_days_mask: i16,
    pub show_until_due: bool,
    pub last_completed_epoch_day: Option<i64>,
    // sqlx(default) lets the legacy `RETURNING *` query paths in
    // routes/ai.rs keep working — they don't join checklist_completions
    // and would otherwise fail FromRow with "column not found". A freshly
    // inserted row has no completions anyway, so empty is correct.
    #[serde(default)]
    #[sqlx(default)]
    pub completed_epoch_days: Vec<i64>,
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
    #[serde(default)]
    pub recurrence_days_mask: i16,
    #[serde(default)]
    pub show_until_due: bool,
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
    pub recurrence_days_mask: Option<i16>,
    pub show_until_due: Option<bool>,
    /// Accepted for backward-compat with pre-024 clients; no server-side
    /// logic depends on it. New clients should use /complete-on /
    /// /uncomplete-on routes against `checklist_completions` instead.
    pub last_completed_epoch_day: Option<i64>,
}
