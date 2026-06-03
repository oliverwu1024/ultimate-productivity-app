use axum::extract::{Query, State};
use axum::http::StatusCode;
use axum::routing::post;
use axum::{Json, Router};
use uuid::Uuid;

use crate::config::AppState;
use crate::error::AppError;
use crate::middleware::auth::Claims;
use crate::models::phone_pickup::{BatchCreatePhonePickups, CreatePhonePickup, PhonePickup};

// One sleep session usually produces 0-20 pickups (heavy sleepers push the
// upper end). 1000 is the abuse cap — a malicious client shouldn't be able
// to flood the table from a single sync. Mirrors the sleep_audio batch cap.
const MAX_PICKUP_BATCH: usize = 1000;

pub fn router() -> Router<AppState> {
    Router::new()
        .route("/phone-pickups", post(create).get(list))
        .route("/phone-pickups/batch", post(batch_create))
}

#[derive(serde::Deserialize)]
struct PickupQuery {
    sleep_id: Option<Uuid>,
    session_id: Option<Uuid>,
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
    Json(input): Json<CreatePhonePickup>,
) -> Result<(StatusCode, Json<PhonePickup>), AppError> {
    let user_id = parse_user_id(&claims)?;

    // Verify any referenced sleep_record / session belongs to the caller
    // before allowing the FK insert. Without this, a malicious client can
    // attach pickups to another user's records.
    if let Some(sleep_id) = input.sleep_record_id {
        let owns: Option<(Uuid,)> = sqlx::query_as(
            "SELECT id FROM sleep_records WHERE id = $1 AND user_id = $2",
        )
        .bind(sleep_id)
        .bind(user_id)
        .fetch_optional(&state.pool)
        .await?;
        if owns.is_none() {
            return Err(AppError::new(StatusCode::FORBIDDEN, "Invalid sleep_record_id"));
        }
    }
    if let Some(session_id) = input.session_id {
        let owns: Option<(Uuid,)> = sqlx::query_as(
            "SELECT id FROM productivity_sessions WHERE id = $1 AND user_id = $2",
        )
        .bind(session_id)
        .bind(user_id)
        .fetch_optional(&state.pool)
        .await?;
        if owns.is_none() {
            return Err(AppError::new(StatusCode::FORBIDDEN, "Invalid session_id"));
        }
    }
    crate::routes::validation::cap_chars_opt(
        &input.app_category,
        crate::routes::validation::MAX_TITLE_CHARS,
        "app_category",
    )?;

    let pickup = sqlx::query_as::<_, PhonePickup>(
        "INSERT INTO phone_pickups
            (user_id, sleep_record_id, session_id, picked_up_at, duration_seconds, app_category)
         VALUES ($1, $2, $3, $4, $5, $6)
         RETURNING *",
    )
    .bind(user_id)
    .bind(input.sleep_record_id)
    .bind(input.session_id)
    .bind(input.picked_up_at)
    .bind(input.duration_seconds)
    .bind(&input.app_category)
    .fetch_one(&state.pool)
    .await?;

    Ok((StatusCode::CREATED, Json(pickup)))
}

async fn batch_create(
    State(state): State<AppState>,
    claims: Claims,
    Json(input): Json<BatchCreatePhonePickups>,
) -> Result<(StatusCode, Json<Vec<PhonePickup>>), AppError> {
    let user_id = parse_user_id(&claims)?;

    if input.events.is_empty() {
        return Ok((StatusCode::CREATED, Json(Vec::new())));
    }
    if input.events.len() > MAX_PICKUP_BATCH {
        return Err(AppError::new(
            StatusCode::BAD_REQUEST,
            format!("batch too large (max {} events)", MAX_PICKUP_BATCH),
        ));
    }

    // §v2.16.18 — Exactly one of sleep_record_id / session_id required.
    // Pre-v2.16.18 clients only set sleep_record_id; focus-session
    // clients (new in v2.16.18) only set session_id. Setting both is
    // an explicit error rather than a silent precedence rule so a buggy
    // client gets a 400 instead of silently double-attributing pickups.
    match (input.sleep_record_id, input.session_id) {
        (None, None) => {
            return Err(AppError::new(
                StatusCode::BAD_REQUEST,
                "exactly one of sleep_record_id or session_id is required",
            ));
        }
        (Some(_), Some(_)) => {
            return Err(AppError::new(
                StatusCode::BAD_REQUEST,
                "sleep_record_id and session_id are mutually exclusive",
            ));
        }
        _ => {}
    }

    // Ownership check against whichever parent was supplied.
    if let Some(sleep_id) = input.sleep_record_id {
        let owns: Option<(Uuid,)> = sqlx::query_as(
            "SELECT id FROM sleep_records WHERE id = $1 AND user_id = $2",
        )
        .bind(sleep_id)
        .bind(user_id)
        .fetch_optional(&state.pool)
        .await?;
        if owns.is_none() {
            return Err(AppError::new(
                StatusCode::FORBIDDEN,
                "Invalid sleep_record_id",
            ));
        }
    }
    if let Some(session_id) = input.session_id {
        let owns: Option<(Uuid,)> = sqlx::query_as(
            "SELECT id FROM productivity_sessions WHERE id = $1 AND user_id = $2",
        )
        .bind(session_id)
        .bind(user_id)
        .fetch_optional(&state.pool)
        .await?;
        if owns.is_none() {
            return Err(AppError::new(
                StatusCode::FORBIDDEN,
                "Invalid session_id",
            ));
        }
    }

    for e in &input.events {
        if e.duration_seconds < 0 {
            return Err(AppError::new(
                StatusCode::BAD_REQUEST,
                "duration_seconds must be non-negative",
            ));
        }
        crate::routes::validation::cap_chars_opt(
            &e.app_category,
            crate::routes::validation::MAX_TITLE_CHARS,
            "app_category",
        )?;
    }

    // §v2.16.18 — Honour client-supplied `id` so retries collapse on
    // ON CONFLICT (id) DO NOTHING. Same idempotency story as v2.16.15
    // sleep_records. Fresh server UUID when the client omits it
    // (pre-v2.16.18 callers). The conflict path silently drops the
    // duplicate; clients re-fetching via GET will see the original row.
    let mut qb = sqlx::QueryBuilder::<sqlx::Postgres>::new(
        "INSERT INTO phone_pickups \
         (id, user_id, sleep_record_id, session_id, picked_up_at, duration_seconds, app_category) ",
    );
    qb.push_values(input.events.iter(), |mut b, e| {
        b.push_bind(e.id.unwrap_or_else(Uuid::new_v4))
            .push_bind(user_id)
            .push_bind(input.sleep_record_id)
            .push_bind(input.session_id)
            .push_bind(e.picked_up_at)
            .push_bind(e.duration_seconds)
            .push_bind(&e.app_category);
    });
    qb.push(" ON CONFLICT (id) DO NOTHING RETURNING *");

    let rows = qb
        .build_query_as::<PhonePickup>()
        .fetch_all(&state.pool)
        .await?;

    Ok((StatusCode::CREATED, Json(rows)))
}

async fn list(
    State(state): State<AppState>,
    claims: Claims,
    Query(params): Query<PickupQuery>,
) -> Result<Json<Vec<PhonePickup>>, AppError> {
    let user_id = parse_user_id(&claims)?;

    let pickups = if let Some(sleep_id) = params.sleep_id {
        sqlx::query_as::<_, PhonePickup>(
            "SELECT * FROM phone_pickups
             WHERE user_id = $1 AND sleep_record_id = $2
             ORDER BY picked_up_at DESC",
        )
        .bind(user_id)
        .bind(sleep_id)
        .fetch_all(&state.pool)
        .await?
    } else if let Some(session_id) = params.session_id {
        sqlx::query_as::<_, PhonePickup>(
            "SELECT * FROM phone_pickups
             WHERE user_id = $1 AND session_id = $2
             ORDER BY picked_up_at DESC",
        )
        .bind(user_id)
        .bind(session_id)
        .fetch_all(&state.pool)
        .await?
    } else {
        sqlx::query_as::<_, PhonePickup>(
            "SELECT * FROM phone_pickups
             WHERE user_id = $1
             ORDER BY picked_up_at DESC",
        )
        .bind(user_id)
        .fetch_all(&state.pool)
        .await?
    };

    Ok(Json(pickups))
}
