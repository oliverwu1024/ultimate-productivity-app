use chrono::{DateTime, NaiveDate, Utc};
use gloo_net::http::Request;
use serde::{Deserialize, Serialize};

use crate::api::client::{api_base_url, get, ApiError};
use crate::auth::AuthContext;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SignupCount {
    pub date: NaiveDate,
    pub count: i64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AdminStats {
    pub total_users: i64,
    pub signups_last_7d: i64,
    pub signups_last_30d: i64,
    pub signups_by_day: Vec<SignupCount>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AdminUserEntry {
    pub id: String,
    pub email: String,
    pub created_at: DateTime<Utc>,
    pub is_admin: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AdminUserSummary {
    pub id: String,
    pub email: String,
    pub created_at: DateTime<Utc>,
    pub is_admin: bool,
    pub is_pro: bool,
    pub email_verified: bool,
    pub timezone: String,
    pub sleep_record_count: i64,
    pub session_count: i64,
    pub checklist_item_count: i64,
    pub calendar_event_count: i64,
    pub alarm_count: i64,
    pub ai_requests_total: i64,
    pub ai_input_tokens_total: i64,
    pub ai_output_tokens_total: i64,
    pub ai_estimated_cost_usd: f64,
    pub last_activity_at: Option<DateTime<Utc>>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct UserCounts {
    pub total: i64,
    pub verified: i64,
    pub pro: i64,
    pub admin: i64,
    pub signups_24h: i64,
    pub signups_7d: i64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ActiveUsers {
    pub dau: i64,
    pub wau: i64,
    pub mau: i64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct TopAiUser {
    pub user_id: String,
    pub email: String,
    pub requests: i64,
    pub estimated_cost_usd: f64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AdminMetrics {
    pub user_counts: UserCounts,
    pub active_users: ActiveUsers,
    pub sleep_adoption_pct: f64,
    pub ai_requests_7d: i64,
    pub ai_estimated_cost_7d_usd: f64,
    pub top_ai_users_7d: Vec<TopAiUser>,
}

pub async fn fetch_stats() -> Result<AdminStats, ApiError> {
    get("/admin/stats").await
}

pub async fn fetch_users() -> Result<Vec<AdminUserEntry>, ApiError> {
    get("/admin/users").await
}

pub async fn fetch_metrics() -> Result<AdminMetrics, ApiError> {
    get("/admin/metrics").await
}

pub async fn fetch_user_summary(user_id: &str) -> Result<AdminUserSummary, ApiError> {
    get(&format!("/admin/users/{}/summary", user_id)).await
}

/// POST /admin/users/:id/revoke-tokens. Backend returns 204 with no body,
/// so we bypass the standard `post()` helper (which requires a JSON body
/// in the response) and hit the endpoint directly.
pub async fn revoke_user_tokens(user_id: &str) -> Result<(), ApiError> {
    let url = format!("{}/admin/users/{}/revoke-tokens", api_base_url(), user_id);
    let mut req = Request::post(&url).header("Content-Type", "application/json");
    if let Some(token) = AuthContext::token() {
        req = req.header("Authorization", &format!("Bearer {}", token));
    }
    let resp = req
        .send()
        .await
        .map_err(|e| ApiError { status: 0, message: e.to_string() })?;
    let status = resp.status();
    if !(200..300).contains(&status) {
        let msg = resp
            .text()
            .await
            .ok()
            .and_then(|body| {
                serde_json::from_str::<serde_json::Value>(&body)
                    .ok()
                    .and_then(|v| v.get("error").and_then(|e| e.as_str()).map(|s| s.to_string()))
            })
            .unwrap_or_else(|| format!("HTTP {}", status));
        return Err(ApiError { status, message: msg });
    }
    Ok(())
}
