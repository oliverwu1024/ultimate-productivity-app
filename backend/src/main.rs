mod config;
mod db;
mod error;
mod middleware;
mod models;
mod routes;

use config::{AppState, Config};
use tower_http::cors::{Any, CorsLayer};
use tracing_subscriber::EnvFilter;

#[tokio::main]
async fn main() {
    dotenvy::dotenv().ok();

    tracing_subscriber::fmt()
        .with_env_filter(EnvFilter::try_from_default_env().unwrap_or_else(|_| "info".into()))
        .init();

    let config = Config::from_env();

    let pool = db::create_pool(&config.database_url).await;

    sqlx::migrate!("src/migrations").run(&pool).await.expect("Failed to run migrations");

    let state = AppState { pool, config };

    let cors = CorsLayer::new()
        .allow_origin(Any)
        .allow_methods(Any)
        .allow_headers(Any);

    let app = routes::all_routes()
        .layer(cors)
        .with_state(state);

    let listener = tokio::net::TcpListener::bind("0.0.0.0:8080")
        .await
        .expect("Failed to bind to port 8080");

    tracing::info!("Server running on http://0.0.0.0:8080");

    axum::serve(listener, app).await.expect("Server error");
}
