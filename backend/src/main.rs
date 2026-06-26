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
mod sleep_audio_clips;
mod ticket;
mod totp;
mod turnstile;
mod tz;

use std::sync::Arc;

use axum::extract::Request;
use axum::http::{header, HeaderName, HeaderValue, Method, StatusCode};
use axum::middleware::{from_fn, Next};
use axum::response::Response;
use config::{AppState, Config};
use email::EmailClient;
use event_bus::EventBus;
use ticket::TicketStore;
use tower_governor::governor::GovernorConfigBuilder;
use tower_governor::key_extractor::SmartIpKeyExtractor;
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

    // Same recovery for migration 023: its header comment was corrected after
    // it had already applied in prod (7-day → 30-day lifecycle, and the clip
    // janitor now exists), so the stored checksum no longer matches the file.
    // Drop the row so sqlx re-applies the migration — made idempotent via
    // DROP CONSTRAINT IF EXISTS — with a fresh checksum. Harmless on a fresh DB.
    let _ = sqlx::query("DELETE FROM _sqlx_migrations WHERE version = 23")
        .execute(&pool)
        .await;

    sqlx::migrate!("src/migrations").run(&pool).await.expect("Failed to run migrations");

    let email = EmailClient::new(config.resend_api_key.clone());
    let events = EventBus::new();
    let tickets = TicketStore::new();
    let ai = ai::AiClient::new().await;
    // §10.x — Sleep-audio clip store. None when SLEEP_AUDIO_S3_BUCKET env
    // is unset (dev / freshly-cut env); the clip routes 503 instead of
    // crashing, and the rest of the sleep-audio pipeline keeps working.
    let sleep_audio_clips = sleep_audio_clips::SleepAudioClipStore::try_new().await;
    match &sleep_audio_clips {
        Some(s) => tracing::info!(target: "sleep-audio", "S3 clip store initialised (bucket={})", s.bucket()),
        None => tracing::info!(target: "sleep-audio", "SLEEP_AUDIO_S3_BUCKET unset — Pro-tier clip routes will 503"),
    }
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
    //   - Global 500 rps / burst 8000: effectively no limit for an
    //     interactive user. The burst was widened from 2000 to 8000
    //     after PR #187 / 2026-06-06 because legitimate catch-up
    //     sync after the Android app comes back online from an
    //     extended offline period (Pro-tier night = batch events +
    //     per-event clip-bytes uploads + dashboard SSE-driven
    //     refetches) was repeatedly tripping the 2000 ceiling,
    //     leaving the bucket in deep deficit for minutes. The bucket
    //     state is in-memory per ECS task, so deficit accumulates
    //     across days until the task restarts.
    //   - Auth endpoints stay strict at 1 rps / burst 5 — that's where
    //     real abuse surfaces are (brute-force, account flood, draining
    //     Resend's email quota via `forgot-password`).
    //
    // ECS sits behind an ALB, so we MUST use SmartIpKeyExtractor to read
    // the real client IP from `X-Forwarded-For`. Without this every
    // request's "peer IP" is the ALB's internal IP — every user on the
    // planet shares one bucket per ALB-IP, and any traffic burst from
    // the dashboard / SSE reconnects / background workers exhausts the
    // bucket and 429s everyone else. Symptom we saw in v2.15.7 testing:
    // the sleep-audio worker's first request would 429 immediately,
    // banner stuck, retry no-op, all because the global bucket was
    // already empty from unrelated traffic.
    let global_governor = Arc::new(
        GovernorConfigBuilder::default()
            .per_second(500)
            .burst_size(8000)
            .key_extractor(SmartIpKeyExtractor)
            .use_headers()
            .finish()
            .expect("valid governor config"),
    );
    // §v2.15.10 — Widened from 1 rps / burst 5 after the previous
    // limit started 429-ing the web dashboard on routine page loads
    // (the dashboard calls /auth/me from auth gate + multiple
    // components + on SSE reconnect, which together exhausts burst 5
    // in <1 s on a tab switch). Brute-force concerns on /auth/login
    // and /auth/forgot-password are mitigated at the application
    // layer (password hashing cost + Resend daily quota), so a tighter
    // transport-level limit was strictly defense-in-depth that
    // tripped on the happy path. 5 rps / burst 30 still bounds the
    // bucket-per-IP usefully but stops false-positive-429-ing the UI.
    let auth_governor = Arc::new(
        GovernorConfigBuilder::default()
            .per_second(5)
            .burst_size(30)
            .key_extractor(SmartIpKeyExtractor)
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
            .key_extractor(SmartIpKeyExtractor)
            .use_headers()
            .finish()
            .expect("valid governor config"),
    );

    // `/admin/**` rate limit. 1 rps with burst 60 smooths to "60 req/min",
    // which is enough headroom for the dashboard's signup chart + user
    // list rendering back-to-back. Without this, a leaked admin JWT could
    // enumerate users / dispatch test pushes at the global 200 rps ceiling.
    let admin_governor = Arc::new(
        GovernorConfigBuilder::default()
            .per_second(1)
            .burst_size(60)
            .key_extractor(SmartIpKeyExtractor)
            .use_headers()
            .finish()
            .expect("valid governor config"),
    );

    let state = AppState { pool, config, email, events, tickets, ai, fcm, sleep_audio_clips };

    // §9.8 Phase C — daily anomaly scan. No-op unless
    // ANOMALY_SCHEDULER_ENABLED=true at task launch.
    scheduler::spawn(state.clone());

    // §10.x — daily clip-pointer janitor. NULLs clip_s3_key/clip_duration_ms
    // once the S3 object is past its 30-day lifecycle expiry so the DB never
    // points at a deleted object. On by default; CLIP_JANITOR_ENABLED=false off.
    scheduler::spawn_clip_janitor(state.clone());

    let auth_routes = routes::auth_routes()
        .layer(GovernorLayer { config: auth_governor });
    let other_routes = routes::other_routes()
        .layer(GovernorLayer { config: global_governor });
    // AI sits behind its own governor — strictly tighter than the global
    // bucket because each request burns Bedrock dollars and quota.
    let ai_routes = routes::ai_routes()
        .layer(GovernorLayer { config: ai_governor });
    // Admin routes sit behind their own governor — limits the blast
    // radius of a leaked admin JWT (enumeration, test-push spam, etc.).
    let admin_routes = routes::admin_routes()
        .layer(GovernorLayer { config: admin_governor });
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
        .merge(admin_routes)
        .merge(health_routes)
        // Log every 429 with route + X-Forwarded-For + retry-after so
        // we never again have to guess what drained the limiter. Lives
        // outside every governor layer so it sees the rejection
        // response. Lives inside cors so we don't double-handle the
        // preflight (which is rejected upstream anyway for OPTIONS).
        .layer(from_fn(log_rate_limit_429))
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

/// Logs every 429 leaving the app with enough context to identify the
/// source. tower_governor itself emits no log on rejection, which left
/// the 2026-06-06 rate-limit drain debugging blind for hours. The
/// `target = "rate-limit"` tag is so CloudWatch Logs Insights can pull
/// just these entries with `filter @logStream =~ /backend/ and @message
/// =~ /rate-limit/`.
async fn log_rate_limit_429(req: Request, next: Next) -> Response {
    let method = req.method().clone();
    let path = req.uri().path().to_owned();
    let xff = req
        .headers()
        .get("x-forwarded-for")
        .and_then(|v| v.to_str().ok())
        .map(str::to_owned);
    let response = next.run(req).await;
    if response.status() == StatusCode::TOO_MANY_REQUESTS {
        let after = response
            .headers()
            .get("x-ratelimit-after")
            .and_then(|v| v.to_str().ok())
            .unwrap_or("?");
        let remaining = response
            .headers()
            .get("x-ratelimit-remaining")
            .and_then(|v| v.to_str().ok())
            .unwrap_or("?");
        tracing::warn!(
            target: "rate-limit",
            method = %method,
            path = %path,
            xff = xff.as_deref().unwrap_or("?"),
            retry_after_s = after,
            remaining = remaining,
            "request rate-limited (429)"
        );
    }
    response
}
