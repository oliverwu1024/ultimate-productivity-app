// §9.4 — Weekly insight client. Server-side 24h cache; subsequent calls
// inside that window return the stored row without hitting Bedrock.

use chrono::{DateTime, NaiveDate, Utc};
use serde::{Deserialize, Serialize};

use crate::api::client::{post, ApiError};

#[derive(Debug, Clone, Deserialize)]
pub struct WeeklyInsight {
    pub id: String,
    pub content: String,
    pub model: String,
    pub generated_at: String,
    pub expires_at: String,
    pub cached: bool,
}

pub async fn fetch_weekly_insight() -> Result<WeeklyInsight, ApiError> {
    // No request body — backend reads user_id from the JWT. Use an empty
    // JSON object to satisfy the typed `post` helper.
    post::<_, WeeklyInsight>("/ai/weekly-insight", &serde_json::json!({})).await
}

// ─── §9.5 — NL parse client ─────────────────────────────────────────────

#[derive(Debug, Clone, Serialize)]
pub struct ParseEventRequest {
    pub text: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub hint: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub now_local: Option<String>,
}

#[derive(Debug, Clone, Deserialize)]
pub struct ParseEventResponse {
    pub kind: String,
    #[serde(default)]
    pub calendar: Option<ParsedCalendarFields>,
    #[serde(default)]
    pub checklist: Option<ParsedChecklistFields>,
}

#[derive(Debug, Clone, Deserialize)]
pub struct ParsedCalendarFields {
    pub title: String,
    pub description: Option<String>,
    pub start_time: DateTime<Utc>,
    pub end_time: DateTime<Utc>,
    /// One of: study | project | exercise | personal | other
    pub category: String,
    /// One of: high | medium | low
    pub priority: String,
}

#[derive(Debug, Clone, Deserialize)]
pub struct ParsedChecklistFields {
    pub title: String,
    pub description: Option<String>,
    pub due_date: NaiveDate,
    /// 0=low, 1=medium, 2=high. None = leave at form default.
    pub priority: Option<i32>,
    pub estimated_minutes: Option<i32>,
}

pub async fn parse_event(req: &ParseEventRequest) -> Result<ParseEventResponse, ApiError> {
    post::<_, ParseEventResponse>("/ai/parse-event", req).await
}
