pub mod auth;
pub mod calendar;
pub mod phone_pickups;
pub mod sessions;
pub mod sleep;

use axum::Router;
use crate::config::AppState;

pub fn all_routes() -> Router<AppState> {
    Router::new()
        .merge(auth::router())
        .merge(sleep::router())
        .merge(phone_pickups::router())
        .merge(sessions::router())
        .merge(calendar::router())
}
