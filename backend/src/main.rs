mod config;
mod db;
mod email;
mod error;
mod event_bus;
mod middleware;
mod models;
mod routes;
mod ticket;

use std::sync::Arc;

use axum::http::{header, HeaderName, HeaderValue, Method};
use config::{AppState, Config};
use email::EmailClient;
use event_bus::EventBus;
use ticket::TicketStore;
use tower_governor::governor::GovernorConfigBuilder;
use tower_governor::GovernorLayer;
use tower_http::cors::{AllowOrigin, CorsLayer};
use tower_http::limit::RequestBodyLimitLayer;
use tower_http::set_header::SetResponseHeaderLayer;
use tracing_subscriber::EnvFilter;

#[tokio::main]
async fn main() {
    dotenvy::dotenv().ok();

    tracing_subscriber::fmt()
        .with_env_filter(EnvFilter::try_from_default_env().unwrap_or_else(|_| "info".into()))
        .init();

    let config = Config::from_env();

    let pool = db::create_pool(&config.database_url).await;

    // One-shot recovery: migration 009 was rewritten from a literal UPDATE to
    // `SELECT 1;` during the pre-public security scrub, so prod's stored
    // checksum no longer matches the file. Drop the row so sqlx re-applies
    // the (now no-op) migration with a fresh checksum. Idempotent: harmless
    // on a fresh DB or once the row already matches the new content.
    let _ = sqlx::query("DELETE FROM _sqlx_migrations WHERE version = 9")
        .execute(&pool)
        .await;

    sqlx::migrate!("src/migrations").run(&pool).await.expect("Failed to run migrations");

    let email = EmailClient::new(config.resend_api_key.clone());
    let events = EventBus::new();
    let tickets = TicketStore::new();

    // CORS — explicit allowlist (loaded from ALLOWED_ORIGINS env, defaults to
    // app.ultiqapp.com + ultiqapp.com). Android calls don't trigger preflight
    // so the allowlist only affects browser origins.
    let allowed: Vec<HeaderValue> = config
        .allowed_origins
        .iter()
        .filter_map(|o| HeaderValue::from_str(o).ok())
        .collect();
    let cors = CorsLayer::new()
        .allow_origin(AllowOrigin::list(allowed))
        .allow_methods([
            Method::GET,
            Method::POST,
            Method::PUT,
            Method::PATCH,
            Method::DELETE,
            Method::OPTIONS,
        ])
        .allow_headers([header::AUTHORIZATION, header::CONTENT_TYPE, header::ACCEPT]);

    // Per-IP rate limit. Two tiers:
    //   - Global 40 rps / burst 80 covers normal app traffic. A single
    //     dashboard load fans out into ~8-10 parallel data fetches plus
    //     an SSE ticket exchange, and any reconnect can briefly add more
    //     — burst 80 absorbs that without 429s.
    //   - Auth endpoints get a stricter 1 rps / burst 5 to slow brute force,
    //     drain attempts on Resend's email quota via `forgot-password`, and
    //     account-creation floods.
    // ECS sits behind an ALB, so we read X-Forwarded-For for the real client IP.
    let global_governor = Arc::new(
        GovernorConfigBuilder::default()
            .per_second(40)
            .burst_size(80)
            .use_headers()
            .finish()
            .expect("valid governor config"),
    );
    let auth_governor = Arc::new(
        GovernorConfigBuilder::default()
            .per_second(1)
            .burst_size(5)
            .use_headers()
            .finish()
            .expect("valid governor config"),
    );

    let state = AppState { pool, config, email, events, tickets };

    let auth_routes = routes::auth_routes()
        .layer(GovernorLayer { config: auth_governor });
    let other_routes = routes::other_routes()
        .layer(GovernorLayer { config: global_governor });

    // Security headers applied to every response. The API only ever returns
    // JSON / SSE; CSP locks scripts/iframes/etc out entirely, HSTS pins HTTPS,
    // and Referrer-Policy keeps reset tokens off the wire if an old client
    // ever follows an external link from a token-bearing URL.
    let app = auth_routes
        .merge(other_routes)
        // 1 MiB hard cap on request bodies — well above the largest legitimate
        // bulk_create payload but cheap to enforce.
        .layer(RequestBodyLimitLayer::new(1024 * 1024))
        .layer(cors)
        .layer(SetResponseHeaderLayer::if_not_present(
            HeaderName::from_static("strict-transport-security"),
            HeaderValue::from_static("max-age=31536000; includeSubDomains; preload"),
        ))
        .layer(SetResponseHeaderLayer::if_not_present(
            HeaderName::from_static("x-content-type-options"),
            HeaderValue::from_static("nosniff"),
        ))
        .layer(SetResponseHeaderLayer::if_not_present(
            HeaderName::from_static("x-frame-options"),
            HeaderValue::from_static("DENY"),
        ))
        .layer(SetResponseHeaderLayer::if_not_present(
            HeaderName::from_static("referrer-policy"),
            HeaderValue::from_static("no-referrer"),
        ))
        .layer(SetResponseHeaderLayer::if_not_present(
            HeaderName::from_static("content-security-policy"),
            HeaderValue::from_static("default-src 'none'; frame-ancestors 'none'"),
        ))
        .with_state(state);

    let listener = tokio::net::TcpListener::bind("0.0.0.0:8080")
        .await
        .expect("Failed to bind to port 8080");

    tracing::info!("Server listening on 0.0.0.0:8080 (TLS terminated upstream)");

    axum::serve(
        listener,
        app.into_make_service_with_connect_info::<std::net::SocketAddr>(),
    )
    .await
    .expect("Server error");
}
