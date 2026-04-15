use axum::extract::{Path, Query, State};
use axum::http::StatusCode;
use axum::routing::{get, post};
use axum::{Json, Router};
use chrono::{NaiveDate, Utc};
use uuid::Uuid;

use crate::config::AppState;
use crate::error::AppError;
use crate::middleware::auth::Claims;
use crate::models::session::{
    CreateSession, ProductivitySession, SessionStats, TagStat, UpdateSession,
};

pub fn router() -> Router<AppState> {
    Router::new()
        .route("/sessions", post(create).get(list))
        .route("/sessions/stats", get(stats))
        .route("/sessions/{id}", get(get_one).put(update).delete(remove))
}

#[derive(serde::Deserialize)]
struct SessionQuery {
    start: Option<NaiveDate>,
    end: Option<NaiveDate>,
    tag: Option<String>,
}

#[derive(serde::Deserialize)]
struct StatsQuery {
    range: Option<String>,
}

fn parse_user_id(claims: &Claims) -> Result<Uuid, AppError> {
    claims
        .sub
        .parse::<Uuid>()
        .map_err(|_| AppError::new(StatusCode::UNAUTHORIZED, "Invalid token"))
}

async fn create(
    State(state): State<AppState>,
    claims: Claims,
    Json(input): Json<CreateSession>,
) -> Result<(StatusCode, Json<ProductivitySession>), AppError> {
    let user_id = parse_user_id(&claims)?;

    if input.tag.trim().is_empty() {
        return Err(AppError::new(
            StatusCode::BAD_REQUEST,
            "tag must not be empty",
        ));
    }
    if input.work_duration <= 0 {
        return Err(AppError::new(
            StatusCode::BAD_REQUEST,
            "work_duration must be > 0",
        ));
    }
    if input.break_duration < 0 {
        return Err(AppError::new(
            StatusCode::BAD_REQUEST,
            "break_duration must be >= 0",
        ));
    }

    let session = sqlx::query_as::<_, ProductivitySession>(
        "INSERT INTO productivity_sessions
            (user_id, tag, duration_minutes, work_duration, break_duration, started_at)
         VALUES ($1, $2, 0, $3, $4, NOW())
         RETURNING *",
    )
    .bind(user_id)
    .bind(input.tag.trim())
    .bind(input.work_duration)
    .bind(input.break_duration)
    .fetch_one(&state.pool)
    .await?;

    Ok((StatusCode::CREATED, Json(session)))
}

async fn list(
    State(state): State<AppState>,
    claims: Claims,
    Query(params): Query<SessionQuery>,
) -> Result<Json<Vec<ProductivitySession>>, AppError> {
    let user_id = parse_user_id(&claims)?;

    let end = params
        .end
        .unwrap_or_else(|| Utc::now().date_naive())
        .and_hms_opt(23, 59, 59)
        .unwrap()
        .and_utc();

    let start = params
        .start
        .unwrap_or_else(|| (Utc::now() - chrono::Duration::days(30)).date_naive())
        .and_hms_opt(0, 0, 0)
        .unwrap()
        .and_utc();

    let sessions = if let Some(ref tag) = params.tag {
        sqlx::query_as::<_, ProductivitySession>(
            "SELECT * FROM productivity_sessions
             WHERE user_id = $1 AND started_at BETWEEN $2 AND $3 AND tag = $4
             ORDER BY started_at DESC",
        )
        .bind(user_id)
        .bind(start)
        .bind(end)
        .bind(tag)
        .fetch_all(&state.pool)
        .await?
    } else {
        sqlx::query_as::<_, ProductivitySession>(
            "SELECT * FROM productivity_sessions
             WHERE user_id = $1 AND started_at BETWEEN $2 AND $3
             ORDER BY started_at DESC",
        )
        .bind(user_id)
        .bind(start)
        .bind(end)
        .fetch_all(&state.pool)
        .await?
    };

    Ok(Json(sessions))
}

async fn get_one(
    State(state): State<AppState>,
    claims: Claims,
    Path(id): Path<Uuid>,
) -> Result<Json<ProductivitySession>, AppError> {
    let user_id = parse_user_id(&claims)?;

    let session = sqlx::query_as::<_, ProductivitySession>(
        "SELECT * FROM productivity_sessions WHERE id = $1 AND user_id = $2",
    )
    .bind(id)
    .bind(user_id)
    .fetch_optional(&state.pool)
    .await?
    .ok_or_else(|| AppError::new(StatusCode::NOT_FOUND, "Session not found"))?;

    Ok(Json(session))
}

async fn update(
    State(state): State<AppState>,
    claims: Claims,
    Path(id): Path<Uuid>,
    Json(input): Json<UpdateSession>,
) -> Result<Json<ProductivitySession>, AppError> {
    let user_id = parse_user_id(&claims)?;

    // Fetch existing session (also verifies ownership)
    let existing = sqlx::query_as::<_, ProductivitySession>(
        "SELECT * FROM productivity_sessions WHERE id = $1 AND user_id = $2",
    )
    .bind(id)
    .bind(user_id)
    .fetch_optional(&state.pool)
    .await?
    .ok_or_else(|| AppError::new(StatusCode::NOT_FOUND, "Session not found"))?;

    if let Some(pickups) = input.phone_pickups {
        if pickups < 0 {
            return Err(AppError::new(
                StatusCode::BAD_REQUEST,
                "phone_pickups must be >= 0",
            ));
        }
    }
    if let Some(minutes) = input.duration_minutes {
        if minutes < 0 {
            return Err(AppError::new(
                StatusCode::BAD_REQUEST,
                "duration_minutes must be >= 0",
            ));
        }
    }

    // Merge provided fields with existing values
    let completed = input.completed.unwrap_or(existing.completed);
    let phone_pickups = input.phone_pickups.unwrap_or(existing.phone_pickups);

    // If completing and no ended_at provided or existing, set to now
    let ended_at = if let Some(ea) = input.ended_at {
        Some(ea)
    } else if completed && existing.ended_at.is_none() {
        Some(Utc::now())
    } else {
        existing.ended_at
    };

    // Calculate duration from time span, unless client explicitly provided it
    let duration_minutes = if let Some(ea) = ended_at {
        input
            .duration_minutes
            .unwrap_or((ea - existing.started_at).num_minutes() as i32)
    } else {
        input.duration_minutes.unwrap_or(existing.duration_minutes)
    };

    let session = sqlx::query_as::<_, ProductivitySession>(
        "UPDATE productivity_sessions
         SET ended_at = $1, completed = $2, phone_pickups = $3,
             duration_minutes = $4, updated_at = NOW()
         WHERE id = $5 AND user_id = $6
         RETURNING *",
    )
    .bind(ended_at)
    .bind(completed)
    .bind(phone_pickups)
    .bind(duration_minutes)
    .bind(id)
    .bind(user_id)
    .fetch_one(&state.pool)
    .await?;

    Ok(Json(session))
}

async fn remove(
    State(state): State<AppState>,
    claims: Claims,
    Path(id): Path<Uuid>,
) -> Result<StatusCode, AppError> {
    let user_id = parse_user_id(&claims)?;

    let result = sqlx::query("DELETE FROM productivity_sessions WHERE id = $1 AND user_id = $2")
        .bind(id)
        .bind(user_id)
        .execute(&state.pool)
        .await?;

    if result.rows_affected() == 0 {
        return Err(AppError::new(StatusCode::NOT_FOUND, "Session not found"));
    }

    Ok(StatusCode::NO_CONTENT)
}

async fn stats(
    State(state): State<AppState>,
    claims: Claims,
    Query(params): Query<StatsQuery>,
) -> Result<Json<SessionStats>, AppError> {
    let user_id = parse_user_id(&claims)?;
    let now = Utc::now();
    let today = now.date_naive();

    let today_start = today.and_hms_opt(0, 0, 0).unwrap().and_utc();
    let today_end = today.and_hms_opt(23, 59, 59).unwrap().and_utc();
    let week_start = (now - chrono::Duration::days(7))
        .date_naive()
        .and_hms_opt(0, 0, 0)
        .unwrap()
        .and_utc();

    let range = params.range.as_deref().unwrap_or("week");
    let range_start = match range {
        "month" => (now - chrono::Duration::days(30))
            .date_naive()
            .and_hms_opt(0, 0, 0)
            .unwrap()
            .and_utc(),
        _ => week_start,
    };

    // Completed sessions in the broader range (covers both today and week)
    let sessions = sqlx::query_as::<_, ProductivitySession>(
        "SELECT * FROM productivity_sessions
         WHERE user_id = $1 AND completed = true AND started_at >= $2
         ORDER BY started_at DESC",
    )
    .bind(user_id)
    .bind(range_start)
    .fetch_all(&state.pool)
    .await?;

    // Aggregate today / week / range stats in one pass
    let mut total_focus_minutes_today: i64 = 0;
    let mut total_focus_minutes_week: i64 = 0;
    let mut sessions_completed_today: i64 = 0;
    let mut sessions_completed_week: i64 = 0;
    let mut total_phone_pickups_today: i64 = 0;
    let mut total_pickups_range: i64 = 0;

    for s in &sessions {
        let is_today = s.started_at >= today_start && s.started_at <= today_end;
        let is_week = s.started_at >= week_start;

        if is_today {
            total_focus_minutes_today += s.duration_minutes as i64;
            sessions_completed_today += 1;
            total_phone_pickups_today += s.phone_pickups as i64;
        }
        if is_week {
            total_focus_minutes_week += s.duration_minutes as i64;
            sessions_completed_week += 1;
        }
        total_pickups_range += s.phone_pickups as i64;
    }

    let avg_phone_pickups_per_session = if sessions.is_empty() {
        0.0
    } else {
        total_pickups_range as f64 / sessions.len() as f64
    };

    // Streaks: all distinct dates with at least 1 completed session (full history)
    let dates: Vec<NaiveDate> = sqlx::query_scalar(
        "SELECT DISTINCT started_at::date
         FROM productivity_sessions
         WHERE user_id = $1 AND completed = true
         ORDER BY 1 DESC",
    )
    .bind(user_id)
    .fetch_all(&state.pool)
    .await?;

    let (current_streak_days, longest_streak_days) = compute_streaks(&dates, today);

    // Top tags within the range
    let top_tags = sqlx::query_as::<_, TagStat>(
        "SELECT tag,
                COALESCE(SUM(duration_minutes), 0)::bigint AS total_minutes,
                COUNT(*)::bigint AS session_count
         FROM productivity_sessions
         WHERE user_id = $1 AND completed = true AND started_at >= $2
         GROUP BY tag
         ORDER BY total_minutes DESC
         LIMIT 10",
    )
    .bind(user_id)
    .bind(range_start)
    .fetch_all(&state.pool)
    .await?;

    Ok(Json(SessionStats {
        total_focus_minutes_today,
        total_focus_minutes_week,
        sessions_completed_today,
        sessions_completed_week,
        current_streak_days,
        longest_streak_days,
        avg_phone_pickups_per_session,
        total_phone_pickups_today,
        top_tags,
    }))
}

fn compute_streaks(dates: &[NaiveDate], today: NaiveDate) -> (i64, i64) {
    if dates.is_empty() {
        return (0, 0);
    }

    // Current streak: walk backwards from today counting consecutive days
    let current_streak = if dates[0] == today {
        let mut streak = 1i64;
        for i in 1..dates.len() {
            if dates[i - 1] - dates[i] == chrono::Duration::days(1) {
                streak += 1;
            } else {
                break;
            }
        }
        streak
    } else {
        0
    };

    // Longest streak: find the longest consecutive run in the full history
    let mut longest = 1i64;
    let mut run = 1i64;
    for i in 1..dates.len() {
        if dates[i - 1] - dates[i] == chrono::Duration::days(1) {
            run += 1;
            if run > longest {
                longest = run;
            }
        } else {
            run = 1;
        }
    }

    (current_streak, longest)
}
