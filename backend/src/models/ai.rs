// §9.3 scaffolding — the structs below are consumed by the Phase-9 feature
// handlers (§9.4–§9.8). Drop this allow when the first handler lands.
#![allow(dead_code)]

use chrono::{DateTime, NaiveDate, Utc};
use serde::{Deserialize, Serialize};
use serde_json::Value as JsonValue;
use uuid::Uuid;

// ── ai_quota ──────────────────────────────────────────────────────────────

#[derive(Debug, Clone, Serialize, sqlx::FromRow)]
pub struct AiQuota {
    pub user_id: Uuid,
    pub day: NaiveDate,
    pub requests_used: i32,
    pub input_tokens_used: i64,
    pub output_tokens_used: i64,
    pub cache_read_tokens: i64,
    pub cache_write_tokens: i64,
    pub updated_at: DateTime<Utc>,
}

// ── ai_insights ───────────────────────────────────────────────────────────

#[derive(Debug, Clone, Serialize, sqlx::FromRow)]
pub struct AiInsight {
    pub id: Uuid,
    pub user_id: Uuid,
    pub kind: String,
    pub content: String,
    pub source_data: JsonValue,
    pub model: String,
    pub generated_at: DateTime<Utc>,
    pub expires_at: Option<DateTime<Utc>>,
}

#[derive(Debug, Deserialize)]
pub struct CreateAiInsight {
    pub kind: String,
    pub content: String,
    pub source_data: Option<JsonValue>,
    pub model: String,
    pub expires_at: Option<DateTime<Utc>>,
}

// ── ai_conversations ──────────────────────────────────────────────────────

#[derive(Debug, Clone, Serialize, sqlx::FromRow)]
pub struct AiConversation {
    pub id: Uuid,
    pub user_id: Uuid,
    pub title: Option<String>,
    pub created_at: DateTime<Utc>,
    pub updated_at: DateTime<Utc>,
}

// ── ai_messages ───────────────────────────────────────────────────────────

#[derive(Debug, Clone, Serialize, sqlx::FromRow)]
pub struct AiMessage {
    pub id: Uuid,
    pub conversation_id: Uuid,
    pub role: String,
    pub content: String,
    pub input_tokens: Option<i32>,
    pub output_tokens: Option<i32>,
    pub created_at: DateTime<Utc>,
}
