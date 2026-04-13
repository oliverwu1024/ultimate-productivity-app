pub mod auth;

use axum::Router;
use crate::config::AppState;

pub fn all_routes() -> Router<AppState> {
    Router::new().merge(auth::router())
}
