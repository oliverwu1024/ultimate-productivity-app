use std::convert::Infallible;
use std::time::Duration;

use axum::extract::{Query, State};
use axum::http::StatusCode;
use axum::response::sse::{Event, KeepAlive, Sse};
use axum::routing::{get, post};
use axum::{Json, Router};
use futures::stream::{Stream, StreamExt};
use serde::{Deserialize, Serialize};
use tokio_stream::wrappers::BroadcastStream;
use uuid::Uuid;

use crate::config::AppState;
use crate::error::AppError;
use crate::middleware::auth::Claims;

pub fn router() -> Router<AppState> {
    Router::new()
        .route("/sync/events", get(sync_events))
        .route("/sync/ticket", post(issue_ticket))
}

#[derive(Debug, Deserialize)]
struct TicketQuery {
    ticket: Option<String>,
}

#[derive(Serialize)]
struct TicketResponse {
    ticket: String,
}

/// Trade a long-lived JWT for a single-use, ~30s SSE ticket. Browser EventSource
/// can't set custom headers, so the dashboard issues a ticket via this POST and
/// then opens the SSE stream with `?ticket=<x>`. Keeps the JWT out of URLs.
async fn issue_ticket(
    State(state): State<AppState>,
    claims: Claims,
) -> Result<Json<TicketResponse>, AppError> {
    let user_id = claims
        .sub
        .parse::<Uuid>()
        .map_err(|_| AppError::new(StatusCode::UNAUTHORIZED, "Invalid token subject"))?;
    let ticket = state.tickets.issue(user_id);
    Ok(Json(TicketResponse { ticket }))
}

/// SSE endpoint that streams sync events for the authenticated user.
/// Auth is taken from either:
///   - `Authorization: Bearer <jwt>` header (Android / fetch), or
///   - `?ticket=<x>` query string redeemed via the in-memory TicketStore
///     (browser EventSource, which cannot set custom headers).
async fn sync_events(
    State(state): State<AppState>,
    Query(q): Query<TicketQuery>,
    claims_header: Option<Claims>,
) -> Result<Sse<impl Stream<Item = Result<Event, Infallible>>>, AppError> {
    let user_id = if let Some(claims) = claims_header {
        claims
            .sub
            .parse::<Uuid>()
            .map_err(|_| AppError::new(StatusCode::UNAUTHORIZED, "Invalid token subject"))?
    } else {
        let ticket = q.ticket.ok_or_else(|| {
            AppError::new(
                StatusCode::UNAUTHORIZED,
                "Missing authorization header or ?ticket= query parameter",
            )
        })?;
        state
            .tickets
            .redeem(&ticket)
            .ok_or_else(|| AppError::new(StatusCode::UNAUTHORIZED, "Invalid or expired ticket"))?
    };

    let receiver = state.events.subscribe(user_id);
    let stream = BroadcastStream::new(receiver).filter_map(|r| async move {
        match r {
            Ok(event) => {
                let json = serde_json::to_string(&event).ok()?;
                Some(Ok::<_, Infallible>(Event::default().data(json)))
            }
            // Lagged: client fell behind. Send a typed marker so it can resync,
            // rather than silently dropping the tick.
            Err(tokio_stream::wrappers::errors::BroadcastStreamRecvError::Lagged(_)) => Some(Ok(
                Event::default().event("lagged").data("{\"resync\":true}"),
            )),
        }
    });

    Ok(Sse::new(stream).keep_alive(
        KeepAlive::new()
            .interval(Duration::from_secs(15))
            .text("keep-alive"),
    ))
}
