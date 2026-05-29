pub mod admin;
pub mod ai;
pub mod alarms;
pub mod auth;
pub mod calendar;
pub mod checklist;
pub mod devices;
pub mod phone_pickups;
pub mod sessions;
pub mod sleep;
pub mod sleep_audio;
pub mod sync;
pub mod validation;

use axum::{routing::get, Router};
use crate::config::AppState;

async fn health() -> &'static str {
    "ok"
}

/// Liveness probe. Mounted outside the rate limiter so ALB health checks
/// can never share a bucket with real traffic — a burst from one client
/// must not be able to make ECS think the task is unhealthy.
pub fn health_routes() -> Router<AppState> {
    Router::new().route("/health", get(health))
}

/// Routes that need a tighter per-IP rate limit than the global 200 rps —
/// login, register, forgot-password and reset-password are the abuse-prone
/// surfaces (email-quota drain, brute force, account flood).
pub fn auth_routes() -> Router<AppState> {
    auth::router()
}

/// Routes covered by the regular global rate limit.
pub fn other_routes() -> Router<AppState> {
    Router::new()
        .merge(sleep::router())
        .merge(sleep_audio::router())
        .merge(phone_pickups::router())
        .merge(sessions::router())
        .merge(calendar::router())
        .merge(checklist::router())
        .merge(alarms::router())
        .merge(sync::router())
        .merge(devices::router())
}

/// `/ai/**` endpoints sit behind a strict 10-req-per-minute governor in
/// `main.rs`. Every AI request consumes downstream Bedrock quota and dollars
/// — a runaway retry loop on a misbehaving client would otherwise be the
/// most expensive footgun in the codebase, so the per-IP cap is much
/// tighter than the global 200 rps that covers normal CRUD traffic.
pub fn ai_routes() -> Router<AppState> {
    ai::router()
}

/// `/admin/**` endpoints sit behind their own governor so a leaked admin
/// JWT can't enumerate users / dispatch test-pushes / revoke tokens at
/// the global 200 rps ceiling. Tighter than the global bucket but looser
/// than auth — admin tooling is interactive and bursty (signup chart
/// loads + user list refresh fire close together).
pub fn admin_routes() -> Router<AppState> {
    admin::router()
}
