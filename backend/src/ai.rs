// `dead_code` is silenced for the whole module while §9.3 ships ahead of
// the feature handlers (§9.4–§9.8) that will consume these items. Drop this
// attribute as soon as the first AI route (weekly insight) lands.
#![allow(dead_code)]

// §9.3 — AI service foundation. Phase-9 features (weekly insight, NL parse,
// coach chat, debrief tagging, anomaly detection) all go through this
// module. Bedrock is reached with the ECS task role; there is no API key.
//
// Security notes:
//   - Never log raw prompts or full model output; use `sampled_log` which
//     truncates and only fires for 1-in-10 calls.
//   - Quota is checked before the Bedrock call and recorded after — never
//     trust the client to self-limit.
//   - Model IDs are public AWS inference-profile identifiers; safe to ship
//     in source.
//   - All AI features are Pro-tier only at launch; the cap below is the
//     "Pro" limit. The free tier blocks at the route layer (not here).

use std::env;
use std::sync::atomic::{AtomicU32, Ordering};

use aws_sdk_bedrockruntime as bedrock;
use axum::http::StatusCode;
use chrono::Utc;
use sqlx::PgPool;
use uuid::Uuid;

use crate::error::AppError;

/// Cross-region inference profile that fans out across ap-southeast-2 and
/// ap-southeast-4. Used for narrative + tool-calling features.
pub const MODEL_SONNET: &str = "au.anthropic.claude-sonnet-4-6";

/// Cheap classification model — anomaly detection and session-debrief tagging.
pub const MODEL_HAIKU: &str = "au.anthropic.claude-haiku-4-5-20251001-v1:0";

/// Default per-user-per-day request cap. AI is Pro-tier only at launch, so
/// this is the Pro limit. Override with `AI_DAILY_REQUEST_CAP` env var.
pub const DEFAULT_DAILY_REQUEST_CAP: i32 = 30;

/// Max characters logged per prompt/response field when sampling.
const LOG_TRUNCATE_CHARS: usize = 200;

/// 1-in-N sampling for prompt/response logging.
const LOG_SAMPLE_EVERY: u32 = 10;

#[derive(Clone)]
pub struct AiClient {
    bedrock: bedrock::Client,
    daily_request_cap: i32,
    log_counter: std::sync::Arc<AtomicU32>,
}

impl AiClient {
    /// Build a Bedrock client from ambient AWS credentials. On ECS this
    /// picks up the task-role automatically; locally it walks the standard
    /// credential chain (env, profile, IMDS).
    pub async fn new() -> Self {
        let config = aws_config::load_from_env().await;
        let bedrock = bedrock::Client::new(&config);
        let daily_request_cap = env::var("AI_DAILY_REQUEST_CAP")
            .ok()
            .and_then(|s| s.parse().ok())
            .filter(|n| *n > 0)
            .unwrap_or(DEFAULT_DAILY_REQUEST_CAP);
        Self {
            bedrock,
            daily_request_cap,
            log_counter: std::sync::Arc::new(AtomicU32::new(0)),
        }
    }

    /// Borrow the underlying Bedrock client. Phase-9 features build their
    /// Converse requests directly with the SDK builder; this avoids
    /// re-implementing the SDK's typed surface.
    pub fn bedrock(&self) -> &bedrock::Client {
        &self.bedrock
    }

    pub fn daily_request_cap(&self) -> i32 {
        self.daily_request_cap
    }

    /// Read today's request count and reject if the user has hit the cap.
    /// Call this *before* invoking Bedrock; pair with `record_usage` after a
    /// successful call.
    pub async fn check_quota(&self, pool: &PgPool, user_id: Uuid) -> Result<(), AppError> {
        let today = Utc::now().date_naive();
        let used: i32 = sqlx::query_scalar(
            "SELECT COALESCE(requests_used, 0)
               FROM ai_quota
              WHERE user_id = $1 AND day = $2",
        )
        .bind(user_id)
        .bind(today)
        .fetch_optional(pool)
        .await?
        .unwrap_or(0);

        if used >= self.daily_request_cap {
            return Err(AppError::new(
                StatusCode::TOO_MANY_REQUESTS,
                "Daily AI request limit reached",
            ));
        }
        Ok(())
    }

    /// Upsert today's row, incrementing the per-call counters. Idempotent
    /// for the first call of the day (creates the row); subsequent calls
    /// add to the existing row.
    pub async fn record_usage(
        &self,
        pool: &PgPool,
        user_id: Uuid,
        input_tokens: i64,
        output_tokens: i64,
        cache_read_tokens: i64,
        cache_write_tokens: i64,
    ) -> Result<(), AppError> {
        let today = Utc::now().date_naive();
        sqlx::query(
            "INSERT INTO ai_quota
                 (user_id, day, requests_used,
                  input_tokens_used, output_tokens_used,
                  cache_read_tokens, cache_write_tokens, updated_at)
             VALUES ($1, $2, 1, $3, $4, $5, $6, NOW())
             ON CONFLICT (user_id, day) DO UPDATE
                SET requests_used      = ai_quota.requests_used + 1,
                    input_tokens_used  = ai_quota.input_tokens_used + EXCLUDED.input_tokens_used,
                    output_tokens_used = ai_quota.output_tokens_used + EXCLUDED.output_tokens_used,
                    cache_read_tokens  = ai_quota.cache_read_tokens + EXCLUDED.cache_read_tokens,
                    cache_write_tokens = ai_quota.cache_write_tokens + EXCLUDED.cache_write_tokens,
                    updated_at         = NOW()",
        )
        .bind(user_id)
        .bind(today)
        .bind(input_tokens)
        .bind(output_tokens)
        .bind(cache_read_tokens)
        .bind(cache_write_tokens)
        .execute(pool)
        .await?;
        Ok(())
    }

    /// 1-in-10 sampled log helper. Truncates inputs so a leaked log line
    /// can never contain a full message body. Use this for debug breadcrumbs;
    /// never call `tracing::info!` directly with prompt content.
    pub fn sampled_log(&self, label: &str, snippet: &str) {
        let n = self.log_counter.fetch_add(1, Ordering::Relaxed);
        if n % LOG_SAMPLE_EVERY != 0 {
            return;
        }
        let truncated: String = snippet.chars().take(LOG_TRUNCATE_CHARS).collect();
        tracing::info!(target: "ai", "{label}: {truncated}");
    }
}
