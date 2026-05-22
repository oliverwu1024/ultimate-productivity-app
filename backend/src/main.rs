mod ai;
mod config;
mod db;
mod email;
mod error;
mod event_bus;
mod fcm;
mod middleware;
mod models;
mod routes;
mod scheduler;
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
    let ai = ai::AiClient::new().await;
    // §9.8 — FCM client. Returns None when GOOGLE_APPLICATION_CREDENTIALS
    // is unset or the file is unreadable (e.g. fresh dev / CI). The backend
    // continues to start; push-dependent routes 503 instead.
    let fcm = match fcm::FcmClient::try_new() {
        Ok(Some(client)) => {
            tracing::info!(target: "fcm", "FCM client initialised (project={})", client.project_id());
            Some(client)
        }
        Ok(None) => None,
        Err(e) => {
            tracing::error!(target: "fcm", "FCM init failed: {e}; push disabled");
            None
        }
    };

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
    //   - Global 200 rps / burst 500: effectively no limit for a single
    //     interactive user (rapid tab-hopping in the dashboard fans out
    //     into many parallel fetches per second). Still bounded enough
    //     that a runaway client or abuse-from-one-IP can't fully DoS the
    //     backend or burn through downstream quotas.
    //   - Auth endpoints stay strict at 1 rps / burst 5 — that's where
    //     real abuse surfaces are (brute-force, account flood, draining
    //     Resend's email quota via `forgot-password`).
    // ECS sits behind an ALB, so we read X-Forwarded-For for the real client IP.
    let global_governor = Arc::new(
        GovernorConfigBuilder::default()
            .per_second(200)
            .burst_size(500)
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

    // `/ai/**` rate limit. Tighter than the global bucket because every
    // request fans out into a Bedrock call (or several, for the chat
    // tool-loop) and downstream dollars. 1 rps with burst 10 smooths to
    // "10 requests per minute" — plenty for a real interactive user, but
    // catches retry-loop bugs and abuse before they reach the daily quota.
    let ai_governor = Arc::new(
        GovernorConfigBuilder::default()
            .per_second(1)
            .burst_size(10)
            .use_headers()
            .finish()
            .expect("valid governor config"),
    );

    let state = AppState { pool, config, email, events, tickets, ai, fcm };

    // §9.8 Phase C — daily anomaly scan. No-op unless
    // ANOMALY_SCHEDULER_ENABLED=true at task launch.
    scheduler::spawn(state.clone());

    let auth_routes = routes::auth_routes()
        .layer(GovernorLayer { config: auth_governor });
    let other_routes = routes::other_routes()
        .layer(GovernorLayer { config: global_governor });
    // AI sits behind its own governor — strictly tighter than the global
    // bucket because each request burns Bedrock dollars and quota.
    let ai_routes = routes::ai_routes()
        .layer(GovernorLayer { config: ai_governor });
    // `/health` is mounted outside both governors. ALB probes must never
    // be rate-limited — a burst from real traffic depleting the bucket
    // would otherwise 429 the next probe and make ECS kill the task.
    let health_routes = routes::health_routes();

    // Security headers applied to every response. The API only ever returns
    // JSON / SSE; CSP locks scripts/iframes/etc out entirely, HSTS pins HTTPS,
    // and Referrer-Policy keeps reset tokens off the wire if an old client
    // ever follows an external link from a token-bearing URL.
    let app = auth_routes
        .merge(other_routes)
        .merge(ai_routes)
        .merge(health_routes)
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
