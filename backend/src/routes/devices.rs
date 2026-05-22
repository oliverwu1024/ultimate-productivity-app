// §9.8 — Device-token registration for FCM push delivery.
//
// Mobile calls `POST /devices/register` after login + every time Firebase
// rotates the token (via `onNewToken`). The upsert keeps one row per token
// globally, transferring ownership when a phone is logged out and back in
// as a different user (so the prior owner doesn't keep receiving pushes).
//
// Logout is handled via `DELETE /devices/register` — mobile sends the token
// it just used so we can scrub it; the user is then push-silent until they
// re-register on next login.

use axum::extract::State;
use axum::http::StatusCode;
use axum::routing::post;
use axum::{Json, Router};
use uuid::Uuid;

use crate::config::AppState;
use crate::error::AppError;
use crate::middleware::auth::Claims;
use crate::models::device_token::{DeviceToken, RegisterDeviceTokenRequest};

const ALLOWED_PLATFORMS: [&str; 3] = ["android", "ios", "web"];

/// FCM tokens top out at ~163 chars in current docs; APNs is smaller still.
/// 1 KiB gives us comfortable headroom without letting a misbehaving client
/// spam multi-KB strings into the column.
const MAX_TOKEN_CHARS: usize = 1024;

pub fn router() -> Router<AppState> {
    Router::new()
        .route("/devices/register", post(register).delete(unregister))
}

fn parse_user_id(claims: &Claims) -> Result<Uuid, AppError> {
    claims
        .sub
        .parse::<Uuid>()
        .map_err(|_| AppError::new(StatusCode::UNAUTHORIZED, "Invalid token"))
}

async fn register(
    State(state): State<AppState>,
    claims: Claims,
    Json(input): Json<RegisterDeviceTokenRequest>,
) -> Result<Json<DeviceToken>, AppError> {
    let user_id = parse_user_id(&claims)?;

    let token = input.token.trim();
    if token.is_empty() {
        return Err(AppError::new(
            StatusCode::BAD_REQUEST,
            "token must not be empty",
        ));
    }
    if token.chars().count() > MAX_TOKEN_CHARS {
        return Err(AppError::new(StatusCode::BAD_REQUEST, "token too long"));
    }
    if !ALLOWED_PLATFORMS.contains(&input.platform.as_str()) {
        return Err(AppError::new(
            StatusCode::BAD_REQUEST,
            "platform must be android, ios, or web",
        ));
    }

    // Upsert by token (UNIQUE INDEX): same install rotating tokens updates
    // last_seen; same install switching users transfers ownership.
    let row = sqlx::query_as::<_, DeviceToken>(
        "INSERT INTO device_tokens (user_id, token, platform, last_seen_at)
         VALUES ($1, $2, $3, NOW())
         ON CONFLICT (token) DO UPDATE
            SET user_id      = EXCLUDED.user_id,
                platform     = EXCLUDED.platform,
                last_seen_at = NOW()
         RETURNING *",
    )
    .bind(user_id)
    .bind(token)
    .bind(&input.platform)
    .fetch_one(&state.pool)
    .await?;

    Ok(Json(row))
}

async fn unregister(
    State(state): State<AppState>,
    claims: Claims,
    Json(input): Json<RegisterDeviceTokenRequest>,
) -> Result<StatusCode, AppError> {
    let user_id = parse_user_id(&claims)?;
    let token = input.token.trim();
    if token.is_empty() {
        return Err(AppError::new(
            StatusCode::BAD_REQUEST,
            "token must not be empty",
        ));
    }
    // Scoped delete: only nukes the row if it belongs to the calling user.
    // Prevents a malicious caller from invalidating someone else's token
    // even if they somehow obtained the string.
    sqlx::query("DELETE FROM device_tokens WHERE token = $1 AND user_id = $2")
        .bind(token)
        .bind(user_id)
        .execute(&state.pool)
        .await?;
    Ok(StatusCode::NO_CONTENT)
}
