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

// ─── §9.6 — Coach Chat ────────────────────────────────────────────────────

#[derive(Debug, Clone, Deserialize)]
pub struct ChatMessage {
    pub id: String,
    pub role: String,
    pub content: String,
    pub created_at: DateTime<Utc>,
}

#[derive(Debug, Clone, Serialize)]
pub struct ChatSendRequest {
    pub content: String,
    /// RFC-3339 with offset (e.g. "2026-05-22T15:30:00+10:00"). Anchors
    /// relative dates inside tool calls when the backend tool loop is on.
    /// Older builds can omit it; the backend falls back to UTC now.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub now_local: Option<String>,
}

#[derive(Debug, Clone, Deserialize)]
pub struct ChatSendResponse {
    pub user_message: ChatMessage,
    pub assistant_message: ChatMessage,
    /// Tool calls that ran inside the Coach loop. Empty when tools are
    /// disabled server-side or the model answered without any. `default`
    /// keeps old backend responses (no field) deserializable.
    #[serde(default)]
    pub tool_invocations: Vec<ToolInvocation>,
}

/// Mirrors backend `ToolInvocationSurface`. The same value drives the
/// read-tool pill, the committed-write undo affordance, and the inline
/// calendar proposal card.
#[derive(Debug, Clone, Deserialize)]
pub struct ToolInvocation {
    pub id: String,
    pub name: String,
    /// "ok" | "error" | "proposed".
    pub status: String,
    pub summary: String,
    #[serde(default)]
    pub committed: bool,
    #[serde(default)]
    pub committed_resource: Option<CommittedResource>,
    #[serde(default)]
    pub proposed_event: Option<ParsedCalendarFields>,
}

#[derive(Debug, Clone, Deserialize)]
pub struct CommittedResource {
    /// "checklist" (newly created) | "checklist_complete" (marked done).
    pub kind: String,
    pub id: String,
    #[serde(default)]
    pub title: Option<String>,
    #[serde(default)]
    pub due_date: Option<NaiveDate>,
}

#[derive(Debug, Clone, Deserialize)]
pub struct ChatResetResponse {
    pub conversation_id: String,
}

pub async fn list_chat_messages() -> Result<Vec<ChatMessage>, ApiError> {
    crate::api::client::get("/ai/chat/messages").await
}

pub async fn send_chat_message(
    content: String,
    now_local: Option<String>,
) -> Result<ChatSendResponse, ApiError> {
    let req = ChatSendRequest { content, now_local };
    post::<_, ChatSendResponse>("/ai/chat/messages", &req).await
}

pub async fn reset_chat() -> Result<ChatResetResponse, ApiError> {
    post::<_, ChatResetResponse>("/ai/chat/reset", &serde_json::json!({})).await
}
