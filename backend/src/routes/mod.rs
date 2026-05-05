pub mod admin;
pub mod auth;
pub mod calendar;
pub mod checklist;
pub mod phone_pickups;
pub mod sessions;
pub mod sleep;
pub mod sync;
pub mod validation;

use axum::{routing::get, Router};
use crate::config::AppState;

async fn health() -> &'static str {
    "ok"
}

/// Routes that need a tighter per-IP rate limit than the global 20 rps —
/// login, register, forgot-password and reset-password are the abuse-prone
/// surfaces (email-quota drain, brute force, account flood).
pub fn auth_routes() -> Router<AppState> {
    auth::router()
}

/// Routes covered by the regular global rate limit.
pub fn other_routes() -> Router<AppState> {
    Router::new()
        .route("/health", get(health))
        .merge(sleep::router())
        .merge(phone_pickups::router())
        .merge(sessions::router())
        .merge(calendar::router())
        .merge(checklist::router())
        .merge(admin::router())
        .merge(sync::router())
}
