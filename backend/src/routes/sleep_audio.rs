use axum::extract::{Query, State};
use axum::http::StatusCode;
use axum::routing::{get, post};
use axum::{Json, Router};
use uuid::Uuid;

use crate::config::AppState;
use crate::error::AppError;
use crate::middleware::auth::Claims;
use crate::models::sleep_audio::{
    BatchCreateSleepAudioEvents, CreateSleepAudioEvent, SleepAudioEvent,
};

// One sleep session usually produces 0-200 debounced events (snore-heavy
// users push the upper end). 2000 is the abuse cap — a malicious client
// shouldn't be able to flood the table from a single sync.
const MAX_BATCH: usize = 2000;

pub fn router() -> Router<AppState> {
    Router::new()
        .route("/sleep-audio-events/batch", post(batch_create))
        .route("/sleep-audio-events", get(list))
}

#[derive(serde::Deserialize)]
struct ListQuery {
    sleep_record_id: Uuid,
}

fn parse_user_id(claims: &Claims) -> Result<Uuid, AppError> {
    claims
        .sub
        .parse::<Uuid>()
        .map_err(|_| AppError::new(StatusCode::UNAUTHORIZED, "Invalid token"))
}

fn validate_event(e: &CreateSleepAudioEvent) -> Result<(), AppError> {
    if e.event_type != "snore" && e.event_type != "cough" {
        return Err(AppError::new(
            StatusCode::BAD_REQUEST,
            "event_type must be 'snore' or 'cough'",
        ));
    }
    if e.ended_at < e.started_at {
        return Err(AppError::new(
            StatusCode::BAD_REQUEST,
            "ended_at must be >= started_at",
        ));
    }
    if !(0.0..=1.0).contains(&e.peak_confidence) {
        return Err(AppError::new(
            StatusCode::BAD_REQUEST,
            "peak_confidence must be in [0.0, 1.0]",
        ));
    }
    Ok(())
}

async fn owns_sleep_record(
    pool: &sqlx::PgPool,
    user_id: Uuid,
    sleep_record_id: Uuid,
) -> Result<(), AppError> {
    let owns: Option<(Uuid,)> = sqlx::query_as(
        "SELECT id FROM sleep_records WHERE id = $1 AND user_id = $2",
    )
    .bind(sleep_record_id)
    .bind(user_id)
    .fetch_optional(pool)
    .await?;
    if owns.is_none() {
        return Err(AppError::new(
            StatusCode::FORBIDDEN,
            "Invalid sleep_record_id",
        ));
    }
    Ok(())
}

async fn batch_create(
    State(state): State<AppState>,
    claims: Claims,
    Json(input): Json<BatchCreateSleepAudioEvents>,
) -> Result<(StatusCode, Json<Vec<SleepAudioEvent>>), AppError> {
    let user_id = parse_user_id(&claims)?;

    if input.events.is_empty() {
        return Ok((StatusCode::CREATED, Json(Vec::new())));
    }
    if input.events.len() > MAX_BATCH {
        return Err(AppError::new(
            StatusCode::BAD_REQUEST,
            format!("batch too large (max {} events)", MAX_BATCH),
        ));
    }

    // Without this check a malicious client could attach events to another
    // user's sleep_record via the FK. Same pattern as phone_pickups.
    owns_sleep_record(&state.pool, user_id, input.sleep_record_id).await?;

    for e in &input.events {
        validate_event(e)?;
    }

    // Single multi-row INSERT — one round-trip to RDS regardless of batch
    // size. QueryBuilder is the SQLx-idiomatic way to do this; the per-row
    // alternative would be N+2 round-trips inside an explicit transaction.
    let mut qb = sqlx::QueryBuilder::<sqlx::Postgres>::new(
        "INSERT INTO sleep_audio_events \
         (user_id, sleep_record_id, event_type, started_at, ended_at, peak_confidence) ",
    );
    qb.push_values(input.events.iter(), |mut b, e| {
        b.push_bind(user_id)
            .push_bind(input.sleep_record_id)
            .push_bind(&e.event_type)
            .push_bind(e.started_at)
            .push_bind(e.ended_at)
            .push_bind(e.peak_confidence);
    });
    qb.push(" RETURNING *");

    let rows = qb
        .build_query_as::<SleepAudioEvent>()
        .fetch_all(&state.pool)
        .await?;

    Ok((StatusCode::CREATED, Json(rows)))
}

async fn list(
    State(state): State<AppState>,
    claims: Claims,
    Query(params): Query<ListQuery>,
) -> Result<Json<Vec<SleepAudioEvent>>, AppError> {
    let user_id = parse_user_id(&claims)?;

    owns_sleep_record(&state.pool, user_id, params.sleep_record_id).await?;

    let rows = sqlx::query_as::<_, SleepAudioEvent>(
        "SELECT * FROM sleep_audio_events
         WHERE user_id = $1 AND sleep_record_id = $2
         ORDER BY started_at ASC",
    )
    .bind(user_id)
    .bind(params.sleep_record_id)
    .fetch_all(&state.pool)
    .await?;

    Ok(Json(rows))
}
