use axum::async_trait;
use axum::extract::{FromRequestParts, Path, State};
use axum::http::request::Parts;
use axum::http::StatusCode;
use axum::routing::{get, post};
use axum::{Json, Router};
use chrono::{DateTime, NaiveDate, Utc};
use serde::{Deserialize, Serialize};
use sqlx::PgPool;
use uuid::Uuid;

use crate::config::AppState;
use crate::error::AppError;
use crate::middleware::auth::Claims;

pub fn router() -> Router<AppState> {
    Router::new()
        .route("/admin/stats", get(admin_stats))
        .route("/admin/users", get(admin_users))
        .route("/admin/users/:id/summary", get(admin_user_summary))
        .route("/admin/metrics", get(admin_metrics))
        // §9.8 — Debug endpoint to manually verify the FCM end-to-end path
        // before the daily anomaly scheduler is wired. Keep gated to admins.
        .route("/admin/test-push", post(admin_test_push))
        .route("/admin/users/:id/revoke-tokens", post(admin_revoke_tokens))
        .route("/admin/users/:id/disable-2fa", post(admin_disable_2fa))
}

pub struct AdminUser {
    pub id: Uuid,
    pub ip: Option<String>,
}

#[async_trait]
impl FromRequestParts<AppState> for AdminUser {
    type Rejection = AppError;

    async fn from_request_parts(
        parts: &mut Parts,
        state: &AppState,
    ) -> Result<Self, Self::Rejection> {
        let claims = Claims::from_request_parts(parts, state)
            .await
            .map_err(|(status, msg)| AppError::new(status, msg))?;
        let user_id = claims
            .sub
            .parse::<Uuid>()
            .map_err(|_| AppError::new(StatusCode::UNAUTHORIZED, "Invalid token"))?;
        let row: Option<(bool,)> = sqlx::query_as("SELECT is_admin FROM users WHERE id = $1")
            .bind(user_id)
            .fetch_optional(&state.pool)
            .await?;
        match row {
            Some((true,)) => Ok(Self {
                id: user_id,
                ip: extract_client_ip(parts),
            }),
            Some(_) => Err(AppError::new(StatusCode::FORBIDDEN, "Admin access required")),
            None => Err(AppError::new(StatusCode::UNAUTHORIZED, "User not found")),
        }
    }
}

fn extract_client_ip(parts: &Parts) -> Option<String> {
    parts
        .headers
        .get("x-forwarded-for")
        .and_then(|v| v.to_str().ok())
        .and_then(|s| s.split(',').next())
        .map(|s| s.trim().to_string())
        .filter(|s| !s.is_empty())
}

async fn record_admin_action(
    pool: &PgPool,
    admin_id: Uuid,
    action: &str,
    target_user_id: Option<Uuid>,
    payload: Option<serde_json::Value>,
    ip: Option<&str>,
) {
    let result = sqlx::query(
        "INSERT INTO admin_audit_log (admin_id, action, target_user_id, payload, ip)
         VALUES ($1, $2, $3, $4, $5)",
    )
    .bind(admin_id)
    .bind(action)
    .bind(target_user_id)
    .bind(payload)
    .bind(ip)
    .execute(pool)
    .await;
    if let Err(e) = result {
        tracing::error!("admin audit log write failed: {}", e);
    }
}

#[derive(Debug, Serialize)]
pub struct SignupCount {
    pub date: NaiveDate,
    pub count: i64,
}

#[derive(Debug, Serialize)]
pub struct AdminStats {
    pub total_users: i64,
    pub signups_last_7d: i64,
    pub signups_last_30d: i64,
    pub signups_by_day: Vec<SignupCount>,
}

async fn admin_stats(
    State(state): State<AppState>,
    admin: AdminUser,
) -> Result<Json<AdminStats>, AppError> {
    record_admin_action(
        &state.pool,
        admin.id,
        "GET /admin/stats",
        None,
        None,
        admin.ip.as_deref(),
    )
    .await;

    let total: (i64,) = sqlx::query_as("SELECT COUNT(*)::BIGINT FROM users")
        .fetch_one(&state.pool)
        .await?;

    let last_7d: (i64,) = sqlx::query_as(
        "SELECT COUNT(*)::BIGINT FROM users WHERE created_at >= NOW() - INTERVAL '7 days'",
    )
    .fetch_one(&state.pool)
    .await?;

    let last_30d: (i64,) = sqlx::query_as(
        "SELECT COUNT(*)::BIGINT FROM users WHERE created_at >= NOW() - INTERVAL '30 days'",
    )
    .fetch_one(&state.pool)
    .await?;

    let signups: Vec<(NaiveDate, i64)> = sqlx::query_as(
        "SELECT day::DATE AS date, COALESCE(c.count, 0)::BIGINT AS count
         FROM generate_series(
                  (CURRENT_DATE - INTERVAL '89 days')::DATE,
                  CURRENT_DATE,
                  '1 day'::INTERVAL
              ) AS day
         LEFT JOIN (
            SELECT date_trunc('day', created_at)::DATE AS d, COUNT(*) AS count
            FROM users
            WHERE created_at >= CURRENT_DATE - INTERVAL '89 days'
            GROUP BY d
         ) c ON c.d = day::DATE
         ORDER BY day",
    )
    .fetch_all(&state.pool)
    .await?;

    let signups_by_day = signups
        .into_iter()
        .map(|(date, count)| SignupCount { date, count })
        .collect();

    Ok(Json(AdminStats {
        total_users: total.0,
        signups_last_7d: last_7d.0,
        signups_last_30d: last_30d.0,
        signups_by_day,
    }))
}

#[derive(Debug, Serialize)]
pub struct AdminUserEntry {
    pub id: Uuid,
    pub email: String,
    pub created_at: DateTime<Utc>,
    pub is_admin: bool,
}

// ── §9.8 — Test push ─────────────────────────────────────────────────────

#[derive(Debug, Deserialize)]
pub struct TestPushRequest {
    pub title: String,
    pub body: String,
}

#[derive(Debug, Serialize)]
pub struct TestPushResponse {
    pub delivered: usize,
}

async fn admin_test_push(
    State(state): State<AppState>,
    admin: AdminUser,
    Json(input): Json<TestPushRequest>,
) -> Result<Json<TestPushResponse>, AppError> {
    let Some(fcm) = state.fcm.as_ref() else {
        return Err(AppError::new(
            StatusCode::SERVICE_UNAVAILABLE,
            "FCM not configured on this backend",
        ));
    };
    if input.title.trim().is_empty() || input.body.trim().is_empty() {
        return Err(AppError::new(
            StatusCode::BAD_REQUEST,
            "title and body must not be empty",
        ));
    }
    record_admin_action(
        &state.pool,
        admin.id,
        "POST /admin/test-push",
        Some(admin.id),
        Some(serde_json::json!({ "title": input.title })),
        admin.ip.as_deref(),
    )
    .await;
    let delivered = fcm
        .send_to_user(&state.pool, admin.id, input.title.trim(), input.body.trim(), None)
        .await?;
    Ok(Json(TestPushResponse { delivered }))
}

/// Emergency lockout recovery — force-disables a user's TOTP enrollment
/// and wipes their stored secret. Use case: user lost their authenticator
/// device and has no backup. Once cleared, they log in with just password
/// and can re-enroll via /auth/2fa/setup.
///
/// Audited the same way as revoke-tokens — there's no user-side action
/// here so the audit log is the only record this happened.
async fn admin_disable_2fa(
    State(state): State<AppState>,
    admin: AdminUser,
    Path(target_id): Path<Uuid>,
) -> Result<StatusCode, AppError> {
    let result = sqlx::query(
        "UPDATE users SET totp_enabled = false, totp_secret_b32 = NULL WHERE id = $1",
    )
    .bind(target_id)
    .execute(&state.pool)
    .await?;
    if result.rows_affected() == 0 {
        return Err(AppError::new(StatusCode::NOT_FOUND, "User not found"));
    }
    record_admin_action(
        &state.pool,
        admin.id,
        "POST /admin/users/:id/disable-2fa",
        Some(target_id),
        None,
        admin.ip.as_deref(),
    )
    .await;
    Ok(StatusCode::NO_CONTENT)
}

/// Force-revoke every session token for a target user. Increments their
/// token_version, which the auth middleware compares to the version baked
/// into each JWT — any pre-existing token immediately fails the next call.
/// Use cases: leaked credentials response, abusive-account isolation,
/// support tooling.
async fn admin_revoke_tokens(
    State(state): State<AppState>,
    admin: AdminUser,
    Path(target_id): Path<Uuid>,
) -> Result<StatusCode, AppError> {
    let result = sqlx::query("UPDATE users SET token_version = token_version + 1 WHERE id = $1")
        .bind(target_id)
        .execute(&state.pool)
        .await?;
    if result.rows_affected() == 0 {
        return Err(AppError::new(StatusCode::NOT_FOUND, "User not found"));
    }
    record_admin_action(
        &state.pool,
        admin.id,
        "POST /admin/users/:id/revoke-tokens",
        Some(target_id),
        None,
        admin.ip.as_deref(),
    )
    .await;
    Ok(StatusCode::NO_CONTENT)
}

// ── Per-user activity summary ──────────────────────────────────────────

#[derive(Debug, Serialize)]
pub struct AdminUserSummary {
    pub id: Uuid,
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
    /// Estimated $ spent on Bedrock for this user. Uses Sonnet 4.6 rates as
    /// the upper bound (we don't store which model each call hit), so this
    /// reads as a conservative ceiling — actual spend is usually lower.
    pub ai_estimated_cost_usd: f64,
    /// Latest timestamp across activity tables. None = signed up but never
    /// did anything tracked.
    pub last_activity_at: Option<DateTime<Utc>>,
}

async fn admin_user_summary(
    State(state): State<AppState>,
    admin: AdminUser,
    Path(target_id): Path<Uuid>,
) -> Result<Json<AdminUserSummary>, AppError> {
    record_admin_action(
        &state.pool,
        admin.id,
        "GET /admin/users/:id/summary",
        Some(target_id),
        None,
        admin.ip.as_deref(),
    )
    .await;

    let user: Option<(String, DateTime<Utc>, bool, bool, bool, String)> = sqlx::query_as(
        "SELECT email, created_at, is_admin, is_pro, email_verified, timezone
         FROM users WHERE id = $1",
    )
    .bind(target_id)
    .fetch_optional(&state.pool)
    .await?;
    let (email, created_at, is_admin, is_pro, email_verified, timezone) =
        user.ok_or_else(|| AppError::new(StatusCode::NOT_FOUND, "User not found"))?;

    let counts: (i64, i64, i64, i64, i64) = sqlx::query_as(
        "SELECT
            (SELECT COUNT(*)::BIGINT FROM sleep_records         WHERE user_id = $1),
            (SELECT COUNT(*)::BIGINT FROM productivity_sessions WHERE user_id = $1),
            (SELECT COUNT(*)::BIGINT FROM checklist_items       WHERE user_id = $1),
            (SELECT COUNT(*)::BIGINT FROM calendar_events       WHERE user_id = $1),
            (SELECT COUNT(*)::BIGINT FROM alarms                WHERE user_id = $1)",
    )
    .bind(target_id)
    .fetch_one(&state.pool)
    .await?;

    let ai: Option<(i64, i64, i64, i64, i64)> = sqlx::query_as(
        "SELECT
            COALESCE(SUM(requests_used)::BIGINT, 0),
            COALESCE(SUM(input_tokens_used)::BIGINT, 0),
            COALESCE(SUM(output_tokens_used)::BIGINT, 0),
            COALESCE(SUM(cache_read_tokens)::BIGINT, 0),
            COALESCE(SUM(cache_write_tokens)::BIGINT, 0)
         FROM ai_quota WHERE user_id = $1",
    )
    .bind(target_id)
    .fetch_optional(&state.pool)
    .await?;
    let (req, inp, out, cache_read, cache_write) = ai.unwrap_or((0, 0, 0, 0, 0));

    let last_activity: Option<(Option<DateTime<Utc>>,)> = sqlx::query_as(
        "SELECT MAX(ts) FROM (
            SELECT MAX(created_at)    AS ts FROM sleep_records         WHERE user_id = $1
            UNION ALL
            SELECT MAX(started_at)    AS ts FROM productivity_sessions WHERE user_id = $1
            UNION ALL
            SELECT MAX(created_at)    AS ts FROM checklist_items       WHERE user_id = $1
            UNION ALL
            SELECT MAX(created_at)    AS ts FROM calendar_events       WHERE user_id = $1
            UNION ALL
            SELECT MAX(updated_at)    AS ts FROM ai_quota              WHERE user_id = $1
        ) AS t",
    )
    .bind(target_id)
    .fetch_optional(&state.pool)
    .await?;
    let last_activity_at = last_activity.and_then(|(ts,)| ts);

    Ok(Json(AdminUserSummary {
        id: target_id,
        email,
        created_at,
        is_admin,
        is_pro,
        email_verified,
        timezone,
        sleep_record_count: counts.0,
        session_count: counts.1,
        checklist_item_count: counts.2,
        calendar_event_count: counts.3,
        alarm_count: counts.4,
        ai_requests_total: req,
        ai_input_tokens_total: inp,
        ai_output_tokens_total: out,
        ai_estimated_cost_usd: estimate_bedrock_cost(inp, out, cache_read, cache_write),
        last_activity_at,
    }))
}

// ── Global metrics ─────────────────────────────────────────────────────

#[derive(Debug, Serialize)]
pub struct AdminMetrics {
    pub user_counts: UserCounts,
    pub active_users: ActiveUsers,
    pub sleep_adoption_pct: f64,
    pub ai_requests_7d: i64,
    pub ai_estimated_cost_7d_usd: f64,
    pub top_ai_users_7d: Vec<TopAiUser>,
}

#[derive(Debug, Serialize)]
pub struct UserCounts {
    pub total: i64,
    pub verified: i64,
    pub pro: i64,
    pub admin: i64,
    pub signups_24h: i64,
    pub signups_7d: i64,
}

#[derive(Debug, Serialize)]
pub struct ActiveUsers {
    pub dau: i64,
    pub wau: i64,
    pub mau: i64,
}

#[derive(Debug, Serialize)]
pub struct TopAiUser {
    pub user_id: Uuid,
    pub email: String,
    pub requests: i64,
    pub estimated_cost_usd: f64,
}

async fn admin_metrics(
    State(state): State<AppState>,
    admin: AdminUser,
) -> Result<Json<AdminMetrics>, AppError> {
    record_admin_action(
        &state.pool,
        admin.id,
        "GET /admin/metrics",
        None,
        None,
        admin.ip.as_deref(),
    )
    .await;

    let user_counts: (i64, i64, i64, i64, i64, i64) = sqlx::query_as(
        "SELECT
            COUNT(*)::BIGINT,
            COUNT(*) FILTER (WHERE email_verified)::BIGINT,
            COUNT(*) FILTER (WHERE is_pro)::BIGINT,
            COUNT(*) FILTER (WHERE is_admin)::BIGINT,
            COUNT(*) FILTER (WHERE created_at >= NOW() - INTERVAL '24 hours')::BIGINT,
            COUNT(*) FILTER (WHERE created_at >= NOW() - INTERVAL '7 days')::BIGINT
         FROM users",
    )
    .fetch_one(&state.pool)
    .await?;

    // Active user = at least one row in any activity table within the window.
    // ai_quota is included because some users use Coach without any other
    // tracked surface; sleep_records is the most common single signal.
    let active: (i64, i64, i64) = sqlx::query_as(
        "WITH activity AS (
            SELECT user_id, created_at::TIMESTAMPTZ AS ts FROM sleep_records
            UNION ALL SELECT user_id, started_at FROM productivity_sessions
            UNION ALL SELECT user_id, created_at FROM checklist_items
            UNION ALL SELECT user_id, created_at FROM calendar_events
            UNION ALL SELECT user_id, updated_at FROM ai_quota
         )
         SELECT
            COUNT(DISTINCT user_id) FILTER (WHERE ts >= NOW() - INTERVAL '1 day')::BIGINT,
            COUNT(DISTINCT user_id) FILTER (WHERE ts >= NOW() - INTERVAL '7 days')::BIGINT,
            COUNT(DISTINCT user_id) FILTER (WHERE ts >= NOW() - INTERVAL '30 days')::BIGINT
         FROM activity",
    )
    .fetch_one(&state.pool)
    .await?;

    let sleep_adoption: (f64,) = sqlx::query_as(
        "SELECT CASE WHEN COUNT(u.id) = 0 THEN 0.0
                     ELSE COUNT(DISTINCT s.user_id)::FLOAT / COUNT(u.id)::FLOAT * 100.0
                END
         FROM users u
         LEFT JOIN sleep_records s ON s.user_id = u.id",
    )
    .fetch_one(&state.pool)
    .await?;

    let ai_7d: (i64, i64, i64, i64, i64) = sqlx::query_as(
        "SELECT
            COALESCE(SUM(requests_used)::BIGINT, 0),
            COALESCE(SUM(input_tokens_used)::BIGINT, 0),
            COALESCE(SUM(output_tokens_used)::BIGINT, 0),
            COALESCE(SUM(cache_read_tokens)::BIGINT, 0),
            COALESCE(SUM(cache_write_tokens)::BIGINT, 0)
         FROM ai_quota WHERE day >= CURRENT_DATE - INTERVAL '7 days'",
    )
    .fetch_one(&state.pool)
    .await?;
    let ai_cost_7d = estimate_bedrock_cost(ai_7d.1, ai_7d.2, ai_7d.3, ai_7d.4);

    let top: Vec<(Uuid, String, i64, i64, i64, i64, i64)> = sqlx::query_as(
        "SELECT u.id, u.email,
                COALESCE(SUM(q.requests_used)::BIGINT, 0),
                COALESCE(SUM(q.input_tokens_used)::BIGINT, 0),
                COALESCE(SUM(q.output_tokens_used)::BIGINT, 0),
                COALESCE(SUM(q.cache_read_tokens)::BIGINT, 0),
                COALESCE(SUM(q.cache_write_tokens)::BIGINT, 0)
         FROM users u
         JOIN ai_quota q ON q.user_id = u.id
         WHERE q.day >= CURRENT_DATE - INTERVAL '7 days'
         GROUP BY u.id, u.email
         ORDER BY 3 DESC
         LIMIT 10",
    )
    .fetch_all(&state.pool)
    .await?;
    let top_ai_users_7d = top
        .into_iter()
        .map(|(uid, em, req, inp, out, cr, cw)| TopAiUser {
            user_id: uid,
            email: em,
            requests: req,
            estimated_cost_usd: estimate_bedrock_cost(inp, out, cr, cw),
        })
        .collect();

    Ok(Json(AdminMetrics {
        user_counts: UserCounts {
            total: user_counts.0,
            verified: user_counts.1,
            pro: user_counts.2,
            admin: user_counts.3,
            signups_24h: user_counts.4,
            signups_7d: user_counts.5,
        },
        active_users: ActiveUsers {
            dau: active.0,
            wau: active.1,
            mau: active.2,
        },
        sleep_adoption_pct: sleep_adoption.0,
        ai_requests_7d: ai_7d.0,
        ai_estimated_cost_7d_usd: ai_cost_7d,
        top_ai_users_7d,
    }))
}

/// Sonnet 4.6 ap-southeast pricing (USD per 1M tokens). We don't store which
/// model each call hit, so this reads as a conservative upper bound — Haiku
/// calls are much cheaper. Update if Bedrock pricing changes.
fn estimate_bedrock_cost(input: i64, output: i64, cache_read: i64, cache_write: i64) -> f64 {
    const INPUT_PER_MTOK: f64 = 3.0;
    const OUTPUT_PER_MTOK: f64 = 15.0;
    const CACHE_READ_PER_MTOK: f64 = 0.30;
    const CACHE_WRITE_PER_MTOK: f64 = 3.75;
    let per_token = |n: i64, rate: f64| (n as f64) * rate / 1_000_000.0;
    per_token(input, INPUT_PER_MTOK)
        + per_token(output, OUTPUT_PER_MTOK)
        + per_token(cache_read, CACHE_READ_PER_MTOK)
        + per_token(cache_write, CACHE_WRITE_PER_MTOK)
}

async fn admin_users(
    State(state): State<AppState>,
    admin: AdminUser,
) -> Result<Json<Vec<AdminUserEntry>>, AppError> {
    record_admin_action(
        &state.pool,
        admin.id,
        "GET /admin/users",
        None,
        None,
        admin.ip.as_deref(),
    )
    .await;

    let rows: Vec<(Uuid, String, DateTime<Utc>, bool)> = sqlx::query_as(
        "SELECT id, email, created_at, is_admin FROM users ORDER BY created_at DESC",
    )
    .fetch_all(&state.pool)
    .await?;

    let users = rows
        .into_iter()
        .map(|(id, email, created_at, is_admin)| AdminUserEntry {
            id,
            email,
            created_at,
            is_admin,
        })
        .collect();

    Ok(Json(users))
}
