use std::convert::Infallible;
use std::time::Duration;

use axum::extract::{Query, State};
use axum::http::StatusCode;
use axum::response::sse::{Event, KeepAlive, Sse};
use axum::routing::get;
use axum::Router;
use futures::stream::{Stream, StreamExt};
use jsonwebtoken::{decode, DecodingKey, Validation};
use serde::Deserialize;
use tokio_stream::wrappers::BroadcastStream;
use uuid::Uuid;

use crate::config::AppState;
use crate::error::AppError;
use crate::middleware::auth::Claims;

pub fn router() -> Router<AppState> {
    Router::new().route("/sync/events", get(sync_events))
}

#[derive(Debug, Deserialize)]
struct TokenQuery {
    token: Option<String>,
}

/// SSE endpoint that streams sync events for the authenticated user.
/// Auth is taken from the `Authorization: Bearer <jwt>` header (Android / fetch)
/// OR the `?token=<jwt>` query string (browser EventSource which can't set headers).
async fn sync_events(
    State(state): State<AppState>,
    Query(token_q): Query<TokenQuery>,
    claims_header: Option<Claims>,
) -> Result<Sse<impl Stream<Item = Result<Event, Infallible>>>, AppError> {
    let claims = match claims_header {
        Some(c) => c,
        None => {
            let token = token_q.token.ok_or_else(|| {
                AppError::new(
                    StatusCode::UNAUTHORIZED,
                    "Missing authorization header or ?token= query parameter",
                )
            })?;
            decode::<Claims>(
                &token,
                &DecodingKey::from_secret(state.config.jwt_secret.as_bytes()),
                &Validation::default(),
            )
            .map(|d| d.claims)
            .map_err(|e| AppError::new(StatusCode::UNAUTHORIZED, format!("Invalid token: {e}")))?
        }
    };

    let user_id = claims
        .sub
        .parse::<Uuid>()
        .map_err(|_| AppError::new(StatusCode::UNAUTHORIZED, "Invalid token subject"))?;

    let receiver = state.events.subscribe(user_id);
    let stream = BroadcastStream::new(receiver).filter_map(|r| async move {
        match r {
            Ok(event) => {
                let json = serde_json::to_string(&event).ok()?;
                Some(Ok::<_, Infallible>(Event::default().data(json)))
            }
            Err(_) => None, // lagged or closed; drop this tick
        }
    });

    Ok(Sse::new(stream).keep_alive(
        KeepAlive::new()
            .interval(Duration::from_secs(15))
            .text("keep-alive"),
    ))
}
