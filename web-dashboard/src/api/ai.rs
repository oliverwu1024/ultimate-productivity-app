// §9.4 — Weekly insight client. Server-side 24h cache; subsequent calls
// inside that window return the stored row without hitting Bedrock.

use serde::Deserialize;

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
