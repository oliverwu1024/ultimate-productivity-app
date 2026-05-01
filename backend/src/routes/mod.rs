pub mod auth;
pub mod calendar;
pub mod checklist;
pub mod phone_pickups;
pub mod sessions;
pub mod sleep;

use axum::{routing::get, Router};
use crate::config::AppState;

async fn health() -> &'static str {
    "ok"
}

pub fn all_routes() -> Router<AppState> {
    Router::new()
        .route("/health", get(health))
        .merge(auth::router())
        .merge(sleep::router())
        .merge(phone_pickups::router())
        .merge(sessions::router())
        .merge(calendar::router())
        .merge(checklist::router())
}
