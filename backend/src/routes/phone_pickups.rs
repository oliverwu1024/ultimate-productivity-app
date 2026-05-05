use axum::extract::{Query, State};
use axum::http::StatusCode;
use axum::routing::post;
use axum::{Json, Router};
use uuid::Uuid;

use crate::config::AppState;
use crate::error::AppError;
use crate::middleware::auth::Claims;
use crate::models::phone_pickup::{CreatePhonePickup, PhonePickup};

pub fn router() -> Router<AppState> {
    Router::new().route("/phone-pickups", post(create).get(list))
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
