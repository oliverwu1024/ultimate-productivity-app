// §9.4 — Weekly Insight + future AI feature handlers.
//
// All AI endpoints follow the same shape:
//   1. Parse user_id from JWT
//   2. Quota check (rejects with 429 if user hit daily cap)
//   3. Cache lookup in `ai_insights` (return cached if fresh enough)
//   4. Aggregate source data from existing tables
//   5. Build a Markdown "data card" (Claude reads tables well)
//   6. Bedrock Converse with a cache breakpoint on the static system prompt
//   7. Anti-hallucination validator on numerals in the response
//   8. Persist to ai_insights + record_usage on `ai_quota`
//   9. Return JSON to the client
//
// Security: AI features are Pro-tier only at launch. The cap in `AiClient`
// is the Pro limit. Free-tier blocking should happen here (route layer) once
// monetization unpauses; for now the cap is just defense-in-depth.

use std::collections::HashSet;

use aws_sdk_bedrockruntime::types::{
    CachePointBlock, CachePointType, ContentBlock, ConversationRole, InferenceConfiguration,
    Message, SystemContentBlock,
};
use axum::extract::{Path, State};
use axum::http::StatusCode;
use axum::routing::post;
use axum::{Json, Router};
use chrono::{DateTime, Duration, Utc};
use serde::{Deserialize, Serialize};
use serde_json::json;

use sqlx::PgPool;
use uuid::Uuid;

use crate::ai::{MODEL_HAIKU, MODEL_SONNET};
use crate::config::AppState;
use crate::error::AppError;
use crate::middleware::auth::Claims;
use crate::models::ai::AiInsight;

pub fn router() -> Router<AppState> {
    Router::new()
        .route("/ai/weekly-insight", post(weekly_insight))
        .route("/ai/session-debrief/:id", post(session_debrief))
}

// ── 24h cache window ──────────────────────────────────────────────────────

/// Re-use a stored weekly insight if it was generated in the last 24 hours.
/// Trims tail noise: if a user refreshes 100 times in a day they still cost
/// exactly one Bedrock call.
const INSIGHT_CACHE_HOURS: i64 = 24;

#[derive(Serialize)]
struct WeeklyInsightResponse {
    id: Uuid,
    content: String,
    model: String,
    generated_at: DateTime<Utc>,
    expires_at: DateTime<Utc>,
    /// True if served from the 24h cache, false if freshly generated.
    cached: bool,
}

fn parse_user_id(claims: &Claims) -> Result<Uuid, AppError> {
    claims
        .sub
        .parse::<Uuid>()
        .map_err(|_| AppError::new(StatusCode::UNAUTHORIZED, "Invalid token"))
}

// ── Main handler ──────────────────────────────────────────────────────────

async fn weekly_insight(
    State(state): State<AppState>,
    claims: Claims,
) -> Result<Json<WeeklyInsightResponse>, AppError> {
    let user_id = parse_user_id(&claims)?;

    // 1. Cached?
    if let Some(cached) = fetch_cached_insight(&state.pool, user_id).await? {
        return Ok(Json(WeeklyInsightResponse {
            expires_at: cached
                .expires_at
                .unwrap_or(cached.generated_at + Duration::hours(INSIGHT_CACHE_HOURS)),
            id: cached.id,
            content: cached.content,
            model: cached.model,
            generated_at: cached.generated_at,
            cached: true,
        }));
    }

    // 2. Quota — only after cache miss, so cached reads don't consume budget.
    state.ai.check_quota(&state.pool, user_id).await?;

    // 3. Aggregate the past 7 days.
    let summary = aggregate_week(&state.pool, user_id).await?;

    // 4. Build the Markdown data card the model will read.
    let data_card = render_data_card(&summary);

    // 5. Call Bedrock with a cache breakpoint on the static system prompt.
    let (insight_text, usage) = call_weekly_insight_model(&state.ai, &data_card).await?;

    // 6. Anti-hallucination — log if model invented numerals not in the card.
    let unverifiable = validate_numerals(&insight_text, &data_card);
    if !unverifiable.is_empty() {
        tracing::warn!(
            target: "ai.weekly_insight",
            user = %user_id,
            count = unverifiable.len(),
            "model returned numerals not present in the data card; first few = {:?}",
            unverifiable.iter().take(5).collect::<Vec<_>>(),
        );
    }

    // 7. Persist + record usage.
    let generated_at = Utc::now();
    let expires_at = generated_at + Duration::hours(INSIGHT_CACHE_HOURS);
    let row_id = sqlx::query_scalar::<_, Uuid>(
        "INSERT INTO ai_insights (user_id, kind, content, source_data, model, generated_at, expires_at)
         VALUES ($1, 'weekly', $2, $3, $4, $5, $6)
         RETURNING id",
    )
    .bind(user_id)
    .bind(&insight_text)
    .bind(json!({ "summary": summary, "data_card": data_card }))
    .bind(MODEL_SONNET)
    .bind(generated_at)
    .bind(expires_at)
    .fetch_one(&state.pool)
    .await?;

    state
        .ai
        .record_usage(
            &state.pool,
            user_id,
            usage.input,
            usage.output,
            usage.cache_read,
            usage.cache_write,
        )
        .await?;

    Ok(Json(WeeklyInsightResponse {
        id: row_id,
        content: insight_text,
        model: MODEL_SONNET.to_string(),
        generated_at,
        expires_at,
        cached: false,
    }))
}

// ── Cache lookup ──────────────────────────────────────────────────────────

async fn fetch_cached_insight(pool: &PgPool, user_id: Uuid) -> Result<Option<AiInsight>, AppError> {
    let cutoff = Utc::now() - Duration::hours(INSIGHT_CACHE_HOURS);
    let row = sqlx::query_as::<_, AiInsight>(
        "SELECT * FROM ai_insights
          WHERE user_id = $1 AND kind = 'weekly' AND generated_at > $2
          ORDER BY generated_at DESC
          LIMIT 1",
    )
    .bind(user_id)
    .bind(cutoff)
    .fetch_optional(pool)
    .await?;
    Ok(row)
}

// ── Data aggregation ──────────────────────────────────────────────────────

#[derive(Debug, Serialize)]
struct WeekSummary {
    days_with_sleep_logged: i64,
    avg_sleep_minutes: i64,
    avg_sleep_quality: f64,
    nights_under_six_hours: i64,
    total_phone_pickups: i64,
    focus_sessions_completed: i64,
    focus_minutes_total: i64,
    avg_focus_session_minutes: i64,
    checklist_items_due: i64,
    checklist_items_completed: i64,
    checklist_completion_pct: i32,
    calendar_events_total: i64,
    calendar_hours_total: f64,
}

async fn aggregate_week(pool: &PgPool, user_id: Uuid) -> Result<WeekSummary, AppError> {
    let now = Utc::now();
    let seven_days_ago = now - Duration::days(7);

    // Sleep aggregate.
    let sleep: (Option<i64>, Option<f64>, Option<f64>, Option<i64>, Option<i64>) =
        sqlx::query_as(
            "SELECT
                COUNT(*)::BIGINT,
                AVG(EXTRACT(EPOCH FROM (actual_wake_time - actual_bedtime))/60.0)::DOUBLE PRECISION,
                AVG(quality_rating)::DOUBLE PRECISION,
                COUNT(*) FILTER (
                    WHERE EXTRACT(EPOCH FROM (actual_wake_time - actual_bedtime))/60.0 < 360
                )::BIGINT,
                COALESCE(SUM(phone_pickups), 0)::BIGINT
             FROM sleep_records
             WHERE user_id = $1 AND actual_bedtime >= $2",
        )
        .bind(user_id)
        .bind(seven_days_ago)
        .fetch_one(pool)
        .await?;

    // Focus sessions aggregate.
    let focus: (Option<i64>, Option<i64>, Option<f64>) = sqlx::query_as(
        "SELECT
            COUNT(*) FILTER (WHERE completed)::BIGINT,
            COALESCE(SUM(duration_minutes) FILTER (WHERE completed), 0)::BIGINT,
            AVG(duration_minutes) FILTER (WHERE completed)::DOUBLE PRECISION
         FROM productivity_sessions
         WHERE user_id = $1 AND started_at >= $2",
    )
    .bind(user_id)
    .bind(seven_days_ago)
    .fetch_one(pool)
    .await?;

    // Checklist aggregate.
    let checklist: (Option<i64>, Option<i64>) = sqlx::query_as(
        "SELECT
            COUNT(*)::BIGINT,
            COUNT(*) FILTER (WHERE completed)::BIGINT
         FROM checklist_items
         WHERE user_id = $1 AND due_date >= ($2::TIMESTAMPTZ)::DATE",
    )
    .bind(user_id)
    .bind(seven_days_ago)
    .fetch_one(pool)
    .await?;

    // Calendar aggregate.
    let calendar: (Option<i64>, Option<f64>) = sqlx::query_as(
        "SELECT
            COUNT(*)::BIGINT,
            COALESCE(SUM(EXTRACT(EPOCH FROM (end_time - start_time))/3600.0), 0)::DOUBLE PRECISION
         FROM calendar_events
         WHERE user_id = $1 AND start_time >= $2",
    )
    .bind(user_id)
    .bind(seven_days_ago)
    .fetch_one(pool)
    .await?;

    let cl_due = checklist.0.unwrap_or(0);
    let cl_done = checklist.1.unwrap_or(0);
    let completion_pct = if cl_due > 0 {
        ((cl_done as f64 / cl_due as f64) * 100.0).round() as i32
    } else {
        0
    };

    Ok(WeekSummary {
        days_with_sleep_logged: sleep.0.unwrap_or(0),
        avg_sleep_minutes: sleep.1.unwrap_or(0.0).round() as i64,
        avg_sleep_quality: round2(sleep.2.unwrap_or(0.0)),
        nights_under_six_hours: sleep.3.unwrap_or(0),
        total_phone_pickups: sleep.4.unwrap_or(0),
        focus_sessions_completed: focus.0.unwrap_or(0),
        focus_minutes_total: focus.1.unwrap_or(0),
        avg_focus_session_minutes: focus.2.unwrap_or(0.0).round() as i64,
        checklist_items_due: cl_due,
        checklist_items_completed: cl_done,
        checklist_completion_pct: completion_pct,
        calendar_events_total: calendar.0.unwrap_or(0),
        calendar_hours_total: round2(calendar.1.unwrap_or(0.0)),
    })
}

fn round2(x: f64) -> f64 {
    (x * 100.0).round() / 100.0
}

// ── Data card (sent to the model) ─────────────────────────────────────────

fn render_data_card(s: &WeekSummary) -> String {
    let avg_sleep_hours = s.avg_sleep_minutes as f64 / 60.0;
    format!(
        r#"### User's last 7 days

| Metric | Value |
|---|---|
| Days sleep logged | {} |
| Avg sleep duration | {} min ({:.2} h) |
| Avg sleep quality (1–5) | {:.2} |
| Nights under 6 hours | {} |
| Phone pickups (sleep window) | {} |
| Focus sessions completed | {} |
| Focus minutes total | {} |
| Avg focus session length | {} min |
| Checklist items due | {} |
| Checklist items completed | {} |
| Checklist completion | {}% |
| Calendar events | {} |
| Calendar hours scheduled | {:.2} |
"#,
        s.days_with_sleep_logged,
        s.avg_sleep_minutes,
        avg_sleep_hours,
        s.avg_sleep_quality,
        s.nights_under_six_hours,
        s.total_phone_pickups,
        s.focus_sessions_completed,
        s.focus_minutes_total,
        s.avg_focus_session_minutes,
        s.checklist_items_due,
        s.checklist_items_completed,
        s.checklist_completion_pct,
        s.calendar_events_total,
        s.calendar_hours_total,
    )
}

// ── Bedrock call (with prompt caching) ────────────────────────────────────

/// System prompt deliberately verbose to (a) lock the coach voice and
/// (b) clear Sonnet 4.6's 1,024-token cache minimum. Static across requests
/// so it goes behind a cache breakpoint and costs ~10% on subsequent reads.
const WEEKLY_INSIGHT_SYSTEM_PROMPT: &str = r#"You are Ultiq's productivity coach. The user is a university student who tracks sleep, focus sessions, phone pickups, checklist items, and calendar events through the Ultiq app.

You will receive a data card describing the user's last 7 days as a Markdown table. Your job is to write a tight, useful weekly review in EXACTLY three paragraphs, in this order:

PARAGRAPH 1 — What went well.
- Identify 1–2 specific positive patterns from the data card.
- Reference the actual numbers (e.g. "you completed 12 focus sessions averaging 38 minutes").
- Be encouraging but never sycophantic. If the week was poor, do not invent positives — instead acknowledge "this week was light, here's what to notice."

PARAGRAPH 2 — What to adjust.
- Identify 1–2 patterns that suggest a leverage point (e.g. 4 nights under 6 hours, low checklist completion, high pickup count vs. sleep window).
- Connect the dots if multiple metrics agree (e.g. low sleep + high pickups + low focus = late phone use story).
- Stay concrete. No vague platitudes like "you should focus more."

PARAGRAPH 3 — A single small experiment for next week.
- One change, with a measurable success criterion.
- The change must be small enough to attempt in 7 days (no "rewrite your routine").
- Tie the success criterion to a metric the app already tracks.

HARD RULES (these are absolute, not preferences):
1. Every numeral that appears in your response MUST appear in the data card, with the exception of:
   - Percentages you computed from data-card values, when you also state the inputs
   - Counts of paragraphs ("three things") or list positions ("first")
2. Never invent a number. If you want to make a claim that needs a number not in the card, rephrase it qualitatively or omit it.
3. Do not address the user as "I" or "we" — second person only ("you", "your").
4. No emojis, no Markdown headers, no bullet lists. Plain prose paragraphs only.
5. Keep the whole response under 300 words.
6. Do not invent app features that weren't named in the data card. The app tracks: sleep records, focus sessions, phone pickups, checklist items, calendar events. Don't reference "meditation streaks" or "mood logs."
7. If a metric is 0 or missing, do not pretend it's data. Acknowledge the gap if it's relevant ("you logged no sleep this week, so quality patterns are hard to read").

TONE:
- Direct. Warm but not gushing. Imagine a smart older friend who happens to have your data and respects your time.
- No corporate wellness language. No "growth mindset." No "level up." No "embrace the journey."
- If the user had a rough week, be honest, not bleak. "This week was hard on sleep" beats either "you crushed it" or "your sleep was terrible."

You will be evaluated on (a) accuracy of numerals, (b) actionability of paragraph 3, and (c) whether a real person would actually want to read this on a Monday morning.
"#;

struct CallUsage {
    input: i64,
    output: i64,
    cache_read: i64,
    cache_write: i64,
}

async fn call_weekly_insight_model(
    ai: &crate::ai::AiClient,
    data_card: &str,
) -> Result<(String, CallUsage), AppError> {
    // System: static instructions + cache breakpoint so the next call within
    // the 5-minute TTL pays ~10% of the input cost. The CachePointBlock must
    // come AFTER at least one text block; both go in the system list.
    let system_text = SystemContentBlock::Text(WEEKLY_INSIGHT_SYSTEM_PROMPT.to_string());
    let cache_breakpoint = SystemContentBlock::CachePoint(
        CachePointBlock::builder()
            .r#type(CachePointType::Default)
            .build()
            .map_err(|e| {
                tracing::error!("cache point build failed: {}", e);
                AppError::new(StatusCode::INTERNAL_SERVER_ERROR, "AI request build failed")
            })?,
    );

    let user_message = Message::builder()
        .role(ConversationRole::User)
        .content(ContentBlock::Text(format!(
            "Here is the data card. Write the three-paragraph weekly review per the rules.\n\n{data_card}"
        )))
        .build()
        .map_err(|e| {
            tracing::error!("user message build failed: {}", e);
            AppError::new(StatusCode::INTERNAL_SERVER_ERROR, "AI request build failed")
        })?;

    // Claude 4.x rejects requests that specify both `temperature` and
    // `top_p` — pick one. Temperature is the more intuitive knob here.
    let inference = InferenceConfiguration::builder()
        .max_tokens(800)
        .temperature(0.6)
        .build();

    let response = ai
        .bedrock()
        .converse()
        .model_id(MODEL_SONNET)
        .system(system_text)
        .system(cache_breakpoint)
        .messages(user_message)
        .inference_config(inference)
        .send()
        .await
        .map_err(|e| {
            // Display strips the AWS service-error detail (you only get
            // "service error"). Dig in to log the actual variant + message
            // so CloudWatch shows AccessDenied / Validation / etc.
            let detail = e
                .as_service_error()
                .map(|svc| format!("{:?}", svc))
                .unwrap_or_else(|| format!("{:?}", e));
            tracing::error!(target: "ai.weekly_insight", "bedrock converse failed: {}", detail);
            AppError::new(StatusCode::BAD_GATEWAY, "AI service request failed")
        })?;

    let text = response
        .output()
        .and_then(|o| o.as_message().ok())
        .and_then(|m| m.content().first())
        .and_then(|c| c.as_text().ok())
        .cloned()
        .ok_or_else(|| {
            tracing::error!("bedrock response had no text content");
            AppError::new(StatusCode::BAD_GATEWAY, "AI service returned no content")
        })?;

    let usage = response.usage();
    let call_usage = CallUsage {
        input: usage.map(|u| u.input_tokens()).unwrap_or(0) as i64,
        output: usage.map(|u| u.output_tokens()).unwrap_or(0) as i64,
        cache_read: usage
            .and_then(|u| u.cache_read_input_tokens())
            .unwrap_or(0) as i64,
        cache_write: usage
            .and_then(|u| u.cache_write_input_tokens())
            .unwrap_or(0) as i64,
    };

    ai.sampled_log("weekly_insight.response", &text);

    Ok((text, call_usage))
}

// ── Anti-hallucination numeric validator ──────────────────────────────────

/// Extract every numeral from `text` and return those not present in the
/// data card (the "legal" set). Empty result = response is clean.
///
/// This is intentionally lenient for first ship — small percentages and
/// derived counts are allowed even if they're not literally in the card.
/// We log warnings rather than reject, so a borderline match doesn't break
/// the UX. Tighten as we collect ground-truth examples.
fn validate_numerals(response: &str, data_card: &str) -> Vec<String> {
    let legal = collect_numerals(data_card);
    // Allowlist: tiny integers and 100/1000 are always considered valid (the
    // model uses them for ordering, percentages, etc.).
    let allowed_constants: HashSet<&str> = ["0", "1", "2", "3", "4", "5", "6", "7", "100", "1000"]
        .iter()
        .copied()
        .collect();

    let mut bad = Vec::new();
    for numeral in collect_numerals(response) {
        if legal.contains(&numeral) || allowed_constants.contains(numeral.as_str()) {
            continue;
        }
        // Accept things like "12%" if "12" is in the card.
        let stripped = numeral.trim_end_matches('%');
        if legal.contains(stripped) {
            continue;
        }
        bad.push(numeral);
    }
    bad
}

fn collect_numerals(s: &str) -> HashSet<String> {
    let mut out = HashSet::new();
    let bytes = s.as_bytes();
    let mut i = 0;
    while i < bytes.len() {
        if bytes[i].is_ascii_digit() {
            let start = i;
            while i < bytes.len() && (bytes[i].is_ascii_digit() || bytes[i] == b'.') {
                i += 1;
            }
            // Optional trailing % to capture "12%" as one token.
            if i < bytes.len() && bytes[i] == b'%' {
                i += 1;
            }
            if let Ok(s) = std::str::from_utf8(&bytes[start..i]) {
                // Trim a trailing '.' that came from sentence punctuation.
                let cleaned = s.trim_end_matches('.');
                if !cleaned.is_empty() {
                    out.insert(cleaned.to_string());
                }
            }
        } else {
            i += 1;
        }
    }
    out
}

// ──────────────────────────────────────────────────────────────────────────
// §9.7 — Session debriefs (Haiku auto-tagging)
// ──────────────────────────────────────────────────────────────────────────

/// Max characters allowed in a single debrief line. A 1-line "what did
/// you work on" should never need more.
const DEBRIEF_MAX_CHARS: usize = 240;

#[derive(Debug, Deserialize)]
struct SessionDebriefRequest {
    text: String,
}

#[derive(Debug, Serialize)]
struct SessionDebriefResponse {
    id: Uuid,
    debrief: String,
    debrief_tag: String,
}

/// Allowed tag values — keep in sync with the CHECK constraint in
/// migration 017 and with the Haiku classifier prompt.
const DEBRIEF_TAGS: [&str; 4] = ["deep_work", "meetings", "admin", "other"];

async fn session_debrief(
    State(state): State<AppState>,
    claims: Claims,
    Path(session_id): Path<Uuid>,
    Json(input): Json<SessionDebriefRequest>,
) -> Result<Json<SessionDebriefResponse>, AppError> {
    let user_id = parse_user_id(&claims)?;

    // Validate text.
    let text = input.text.trim();
    if text.is_empty() {
        return Err(AppError::new(
            StatusCode::BAD_REQUEST,
            "debrief text must not be empty",
        ));
    }
    if text.chars().count() > DEBRIEF_MAX_CHARS {
        return Err(AppError::new(
            StatusCode::BAD_REQUEST,
            "debrief text is too long",
        ));
    }

    // Verify the session belongs to this user. We don't require it to be
    // completed — a user can label a session at any point.
    let owns: Option<Uuid> = sqlx::query_scalar(
        "SELECT id FROM productivity_sessions WHERE id = $1 AND user_id = $2",
    )
    .bind(session_id)
    .bind(user_id)
    .fetch_optional(&state.pool)
    .await?;
    if owns.is_none() {
        return Err(AppError::new(StatusCode::NOT_FOUND, "session not found"));
    }

    // Quota — before the Bedrock call.
    state.ai.check_quota(&state.pool, user_id).await?;

    // Classify via Haiku.
    let (tag, usage) = classify_debrief(&state.ai, text).await?;

    // Persist debrief + tag.
    sqlx::query(
        "UPDATE productivity_sessions
            SET debrief = $1, debrief_tag = $2, updated_at = NOW()
          WHERE id = $3 AND user_id = $4",
    )
    .bind(text)
    .bind(&tag)
    .bind(session_id)
    .bind(user_id)
    .execute(&state.pool)
    .await?;

    state
        .ai
        .record_usage(
            &state.pool,
            user_id,
            usage.input,
            usage.output,
            usage.cache_read,
            usage.cache_write,
        )
        .await?;

    Ok(Json(SessionDebriefResponse {
        id: session_id,
        debrief: text.to_string(),
        debrief_tag: tag,
    }))
}

/// One-shot Haiku classification. Returns (tag, usage). Tag is always
/// one of `DEBRIEF_TAGS`; anything weird falls back to "other".
async fn classify_debrief(
    ai: &crate::ai::AiClient,
    text: &str,
) -> Result<(String, CallUsage), AppError> {
    let system = SystemContentBlock::Text(
        "You classify a 1-line description of a productivity work session \
into exactly one of these four buckets:\n\
\n\
- deep_work — coding, writing, problem-solving, study, focused single-task work\n\
- meetings — calls, syncs, 1:1s, group discussions, interviews\n\
- admin — email, planning, scheduling, organizing, low-cognitive busywork\n\
- other — anything that clearly does not fit the three above\n\
\n\
Reply with ONLY the bucket name (lowercase, snake_case, no punctuation, \
no quotes, no explanation). If the input is ambiguous, choose the closest \
fit rather than guessing 'other'."
            .to_string(),
    );

    let user_msg = Message::builder()
        .role(ConversationRole::User)
        .content(ContentBlock::Text(text.to_string()))
        .build()
        .map_err(|e| {
            tracing::error!("debrief user message build failed: {}", e);
            AppError::new(StatusCode::INTERNAL_SERVER_ERROR, "AI request build failed")
        })?;

    let inference = InferenceConfiguration::builder()
        .max_tokens(8)
        .temperature(0.0)
        .build();

    let response = ai
        .bedrock()
        .converse()
        .model_id(MODEL_HAIKU)
        .system(system)
        .messages(user_msg)
        .inference_config(inference)
        .send()
        .await
        .map_err(|e| {
            let detail = e
                .as_service_error()
                .map(|svc| format!("{:?}", svc))
                .unwrap_or_else(|| format!("{:?}", e));
            tracing::error!(target: "ai.session_debrief", "bedrock haiku call failed: {}", detail);
            AppError::new(StatusCode::BAD_GATEWAY, "AI service request failed")
        })?;

    let raw = response
        .output()
        .and_then(|o| o.as_message().ok())
        .and_then(|m| m.content().first())
        .and_then(|c| c.as_text().ok())
        .cloned()
        .ok_or_else(|| {
            tracing::error!("haiku returned no text");
            AppError::new(StatusCode::BAD_GATEWAY, "AI service returned no content")
        })?;

    // Sanitize: lowercase, strip whitespace + punctuation, then match against
    // the allowed set. Anything we don't recognise becomes 'other' — never
    // trust a model to stay in-distribution.
    let cleaned: String = raw
        .trim()
        .to_lowercase()
        .chars()
        .filter(|c| c.is_alphanumeric() || *c == '_')
        .collect();
    let tag = DEBRIEF_TAGS
        .iter()
        .find(|t| **t == cleaned)
        .copied()
        .unwrap_or("other")
        .to_string();

    if tag == "other" && cleaned != "other" {
        tracing::warn!(
            target: "ai.session_debrief",
            raw = %raw,
            cleaned = %cleaned,
            "haiku returned out-of-distribution tag; falling back to 'other'",
        );
    }

    let usage = response.usage();
    let call_usage = CallUsage {
        input: usage.map(|u| u.input_tokens()).unwrap_or(0) as i64,
        output: usage.map(|u| u.output_tokens()).unwrap_or(0) as i64,
        cache_read: usage
            .and_then(|u| u.cache_read_input_tokens())
            .unwrap_or(0) as i64,
        cache_write: usage
            .and_then(|u| u.cache_write_input_tokens())
            .unwrap_or(0) as i64,
    };

    Ok((tag, call_usage))
}
