use axum::extract::{Path, Query, State};
use axum::http::StatusCode;
use axum::routing::{get, post};
use axum::{Json, Router};
use chrono::{Datelike, NaiveDate, Utc};
use uuid::Uuid;

use crate::config::AppState;
use crate::error::AppError;
use crate::event_bus::SyncEvent;
use crate::middleware::auth::Claims;
use crate::models::sleep::{CreateSleepRecord, SleepRecord, SleepStats};

pub fn router() -> Router<AppState> {
    Router::new()
        .route("/sleep", post(create).get(list))
        .route("/sleep/stats", get(stats))
        .route("/sleep/:id", get(get_one).put(update).delete(remove))
}

#[derive(serde::Deserialize)]
struct SleepQuery {
    start: Option<NaiveDate>,
    end: Option<NaiveDate>,
}

#[derive(serde::Deserialize)]
struct StatsQuery {
    range: Option<String>,
    start: Option<NaiveDate>,
    end: Option<NaiveDate>,
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
    Json(input): Json<CreateSleepRecord>,
) -> Result<(StatusCode, Json<SleepRecord>), AppError> {
    let user_id = parse_user_id(&claims)?;

    if input.actual_bedtime >= input.actual_wake_time {
        return Err(AppError::new(
            StatusCode::BAD_REQUEST,
            "actual_bedtime must be before actual_wake_time",
        ));
    }
    if input.quality_rating < 1 || input.quality_rating > 5 {
        return Err(AppError::new(
            StatusCode::BAD_REQUEST,
            "quality_rating must be between 1 and 5",
        ));
    }
    if input.phone_pickups < 0 {
        return Err(AppError::new(
            StatusCode::BAD_REQUEST,
            "phone_pickups must be >= 0",
        ));
    }
    crate::routes::validation::cap_chars_opt(
        &input.notes,
        crate::routes::validation::MAX_NOTES_CHARS,
        "notes",
    )?;

    let record = sqlx::query_as::<_, SleepRecord>(
        "INSERT INTO sleep_records
            (user_id, target_bedtime, target_wake_time, actual_bedtime, actual_wake_time,
             quality_rating, phone_pickups, total_phone_minutes, notes)
         VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9)
         RETURNING *",
    )
    .bind(user_id)
    .bind(input.target_bedtime)
    .bind(input.target_wake_time)
    .bind(input.actual_bedtime)
    .bind(input.actual_wake_time)
    .bind(input.quality_rating)
    .bind(input.phone_pickups)
    .bind(input.total_phone_minutes)
    .bind(&input.notes)
    .fetch_one(&state.pool)
    .await?;

    state
        .events
        .publish(user_id, SyncEvent::SleepCreated(record.clone()));

    Ok((StatusCode::CREATED, Json(record)))
}

async fn list(
    State(state): State<AppState>,
    claims: Claims,
    Query(params): Query<SleepQuery>,
) -> Result<Json<Vec<SleepRecord>>, AppError> {
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

    let records = sqlx::query_as::<_, SleepRecord>(
        "SELECT * FROM sleep_records
         WHERE user_id = $1 AND actual_bedtime BETWEEN $2 AND $3
         ORDER BY actual_bedtime DESC",
    )
    .bind(user_id)
    .bind(start)
    .bind(end)
    .fetch_all(&state.pool)
    .await?;

    Ok(Json(records))
}

async fn get_one(
    State(state): State<AppState>,
    claims: Claims,
    Path(id): Path<Uuid>,
) -> Result<Json<SleepRecord>, AppError> {
    let user_id = parse_user_id(&claims)?;

    let record = sqlx::query_as::<_, SleepRecord>(
        "SELECT * FROM sleep_records WHERE id = $1 AND user_id = $2",
    )
    .bind(id)
    .bind(user_id)
    .fetch_optional(&state.pool)
    .await?
    .ok_or_else(|| AppError::new(StatusCode::NOT_FOUND, "Sleep record not found"))?;

    Ok(Json(record))
}

async fn update(
    State(state): State<AppState>,
    claims: Claims,
    Path(id): Path<Uuid>,
    Json(input): Json<CreateSleepRecord>,
) -> Result<Json<SleepRecord>, AppError> {
    let user_id = parse_user_id(&claims)?;

    if input.actual_bedtime >= input.actual_wake_time {
        return Err(AppError::new(
            StatusCode::BAD_REQUEST,
            "actual_bedtime must be before actual_wake_time",
        ));
    }
    if input.quality_rating < 1 || input.quality_rating > 5 {
        return Err(AppError::new(
            StatusCode::BAD_REQUEST,
            "quality_rating must be between 1 and 5",
        ));
    }
    if input.phone_pickups < 0 {
        return Err(AppError::new(
            StatusCode::BAD_REQUEST,
            "phone_pickups must be >= 0",
        ));
    }
    crate::routes::validation::cap_chars_opt(
        &input.notes,
        crate::routes::validation::MAX_NOTES_CHARS,
        "notes",
    )?;

    let record = sqlx::query_as::<_, SleepRecord>(
        "UPDATE sleep_records
         SET target_bedtime = $1, target_wake_time = $2,
             actual_bedtime = $3, actual_wake_time = $4,
             quality_rating = $5, phone_pickups = $6,
             total_phone_minutes = $7, notes = $8, updated_at = NOW()
         WHERE id = $9 AND user_id = $10
         RETURNING *",
    )
    .bind(input.target_bedtime)
    .bind(input.target_wake_time)
    .bind(input.actual_bedtime)
    .bind(input.actual_wake_time)
    .bind(input.quality_rating)
    .bind(input.phone_pickups)
    .bind(input.total_phone_minutes)
    .bind(&input.notes)
    .bind(id)
    .bind(user_id)
    .fetch_optional(&state.pool)
    .await?
    .ok_or_else(|| AppError::new(StatusCode::NOT_FOUND, "Sleep record not found"))?;

    state
        .events
        .publish(user_id, SyncEvent::SleepUpdated(record.clone()));

    Ok(Json(record))
}

async fn remove(
    State(state): State<AppState>,
    claims: Claims,
    Path(id): Path<Uuid>,
) -> Result<StatusCode, AppError> {
    let user_id = parse_user_id(&claims)?;

    let result = sqlx::query("DELETE FROM sleep_records WHERE id = $1 AND user_id = $2")
        .bind(id)
        .bind(user_id)
        .execute(&state.pool)
        .await?;

    if result.rows_affected() == 0 {
        return Err(AppError::new(StatusCode::NOT_FOUND, "Sleep record not found"));
    }

    state
        .events
        .publish(user_id, SyncEvent::SleepDeleted { id });

    Ok(StatusCode::NO_CONTENT)
}

async fn stats(
    State(state): State<AppState>,
    claims: Claims,
    Query(params): Query<StatsQuery>,
) -> Result<Json<SleepStats>, AppError> {
    let user_id = parse_user_id(&claims)?;
    let now = Utc::now();

    let range = params.range.as_deref().unwrap_or("month");

    let (start, end) = match range {
        "week" => (now - chrono::Duration::days(7), now),
        "calendar_week" => {
            // Current ISO week: Monday 00:00 UTC → now
            let today = now.date_naive();
            let days_since_monday = today.weekday().num_days_from_monday() as i64;
            let monday = today - chrono::Duration::days(days_since_monday);
            let s = monday.and_hms_opt(0, 0, 0).unwrap().and_utc();
            (s, now)
        }
        "custom" => {
            let s = params
                .start
                .ok_or_else(|| AppError::new(StatusCode::BAD_REQUEST, "start date required for custom range"))?
                .and_hms_opt(0, 0, 0)
                .unwrap()
                .and_utc();
            let e = params
                .end
                .ok_or_else(|| AppError::new(StatusCode::BAD_REQUEST, "end date required for custom range"))?
                .and_hms_opt(23, 59, 59)
                .unwrap()
                .and_utc();
            (s, e)
        }
        _ => (now - chrono::Duration::days(30), now),
    };

    // Fetch the user's personal sleep target — applied uniformly across all records in the window.
    let target_minutes: i32 = sqlx::query_scalar(
        "SELECT sleep_target_minutes FROM users WHERE id = $1",
    )
    .bind(user_id)
    .fetch_optional(&state.pool)
    .await?
    .ok_or_else(|| AppError::new(StatusCode::UNAUTHORIZED, "User not found"))?;

    let records = sqlx::query_as::<_, SleepRecord>(
        "SELECT * FROM sleep_records
         WHERE user_id = $1 AND actual_bedtime BETWEEN $2 AND $3
         ORDER BY actual_bedtime DESC",
    )
    .bind(user_id)
    .bind(start)
    .bind(end)
    .fetch_all(&state.pool)
    .await?;

    if records.is_empty() {
        return Ok(Json(SleepStats {
            avg_duration_minutes: 0.0,
            avg_quality: 0.0,
            total_records: 0,
            debt_minutes: 0.0,
            extra_minutes: 0.0,
            sleep_target_minutes: target_minutes,
            avg_phone_pickups: 0.0,
            best_quality_day: None,
            worst_quality_day: None,
        }));
    }

    let count = records.len() as f64;
    let target_mins = target_minutes as f64;
    let mut total_duration_mins = 0.0;
    let mut total_quality = 0.0;
    let mut total_pickups = 0.0;
    let mut debt_mins = 0.0;
    let mut extra_mins = 0.0;
    let mut best: Option<(i16, String)> = None;
    let mut worst: Option<(i16, String)> = None;

    for r in &records {
        let actual_mins = (r.actual_wake_time - r.actual_bedtime).num_minutes() as f64;
        total_duration_mins += actual_mins;
        total_quality += r.quality_rating as f64;
        total_pickups += r.phone_pickups as f64;

        // Asymmetric: undersleeping accrues debt; oversleeping fills the "extra" bucket.
        // The two never cancel — they're separate metrics.
        let delta = actual_mins - target_mins;
        if delta < 0.0 {
            debt_mins += -delta;
        } else {
            extra_mins += delta;
        }

        let day = r.actual_bedtime.format("%Y-%m-%d").to_string();
        match &best {
            None => best = Some((r.quality_rating, day.clone())),
            Some((q, _)) if r.quality_rating > *q => best = Some((r.quality_rating, day.clone())),
            _ => {}
        }
        match &worst {
            None => worst = Some((r.quality_rating, day.clone())),
            Some((q, _)) if r.quality_rating < *q => worst = Some((r.quality_rating, day.clone())),
            _ => {}
        }
    }

    Ok(Json(SleepStats {
        avg_duration_minutes: total_duration_mins / count,
        avg_quality: total_quality / count,
        total_records: records.len() as i64,
        debt_minutes: debt_mins,
        extra_minutes: extra_mins,
        sleep_target_minutes: target_minutes,
        avg_phone_pickups: total_pickups / count,
        best_quality_day: best.map(|(_, d)| d),
        worst_quality_day: worst.map(|(_, d)| d),
    }))
}
