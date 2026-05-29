use axum::async_trait;
use axum::extract::{FromRequestParts, State};
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
        // §9.8 — Debug endpoint to manually verify the FCM end-to-end path
        // before the daily anomaly scheduler is wired. Keep gated to admins.
        .route("/admin/test-push", post(admin_test_push))
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
