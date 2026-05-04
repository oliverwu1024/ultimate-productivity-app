use axum::async_trait;
use axum::extract::{FromRequestParts, State};
use axum::http::request::Parts;
use axum::http::StatusCode;
use axum::routing::get;
use axum::{Json, Router};
use chrono::NaiveDate;
use serde::Serialize;
use uuid::Uuid;

use crate::config::AppState;
use crate::error::AppError;
use crate::middleware::auth::Claims;

pub fn router() -> Router<AppState> {
    Router::new().route("/admin/stats", get(admin_stats))
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
