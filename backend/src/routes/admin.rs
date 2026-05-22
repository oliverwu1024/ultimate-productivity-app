use axum::async_trait;
use axum::extract::{FromRequestParts, State};
use axum::http::request::Parts;
use axum::http::StatusCode;
use axum::routing::{get, post};
use axum::{Json, Router};
use chrono::{DateTime, NaiveDate, Utc};
use serde::{Deserialize, Serialize};
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

pub struct AdminUser;

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
            Some((true,)) => Ok(Self),
            Some(_) => Err(AppError::new(StatusCode::FORBIDDEN, "Admin access required")),
            None => Err(AppError::new(StatusCode::UNAUTHORIZED, "User not found")),
        }
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
    _admin: AdminUser,
) -> Result<Json<AdminStats>, AppError> {
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

/// Body for `POST /admin/test-push`. `target_user_id` optional — when
/// omitted, sends to the calling admin's own registered devices, which is
/// the typical "is FCM wired correctly?" flow.
#[derive(Debug, Deserialize)]
pub struct TestPushRequest {
    pub target_user_id: Option<Uuid>,
    pub title: String,
    pub body: String,
}

#[derive(Debug, Serialize)]
pub struct TestPushResponse {
    pub delivered: usize,
}

async fn admin_test_push(
    State(state): State<AppState>,
    _admin: AdminUser,
    claims: Claims,
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
    let target = match input.target_user_id {
        Some(id) => id,
        None => claims.sub.parse::<Uuid>().map_err(|_| {
            AppError::new(StatusCode::UNAUTHORIZED, "Invalid token subject")
        })?,
    };
    let delivered = fcm
        .send_to_user(&state.pool, target, input.title.trim(), input.body.trim(), None)
        .await?;
    Ok(Json(TestPushResponse { delivered }))
}

async fn admin_users(
    State(state): State<AppState>,
    _admin: AdminUser,
) -> Result<Json<Vec<AdminUserEntry>>, AppError> {
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
