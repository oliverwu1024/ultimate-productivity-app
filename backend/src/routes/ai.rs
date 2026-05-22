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
    Message, SpecificToolChoice, SystemContentBlock, Tool, ToolChoice, ToolConfiguration,
    ToolInputSchema, ToolSpecification,
};
use aws_smithy_types::{Document, Number};
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
        .route("/ai/parse-event", post(parse_event))
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

// ──────────────────────────────────────────────────────────────────────────
// §9.5 — NL parse for calendar + checklist (Sonnet tool-calling)
// ──────────────────────────────────────────────────────────────────────────
//
// The user types free-form text on the Calendar or Checklist screen (e.g.
// "meeting with Sarah tomorrow at 3pm" or "buy groceries on friday"). The
// server hands it to Sonnet with two tools defined — `create_calendar_event`
// and `create_checklist_item` — and the model picks one and fills the args.
// The server unwraps the tool input back into a typed shape the client can
// pre-fill its existing create form with. No DB writes; the client confirms.
//
// Tool calling buys us schema enforcement for free: the model's output is
// validated against the JSON schema before it ever reaches our handler, so
// the parse layer can stay thin.

/// Cap on incoming text. ~500 chars is comfortably more than a one-sentence
/// "create X tomorrow" prompt and well under any tokeniser concern.
const PARSE_TEXT_MAX_CHARS: usize = 500;

const TOOL_CALENDAR: &str = "create_calendar_event";
const TOOL_CHECKLIST: &str = "create_checklist_item";

#[derive(Debug, Deserialize)]
struct ParseEventRequest {
    text: String,
    /// Hint about the surface the user is on. When set, the model is forced
    /// to call that tool (rather than picking). Values: "calendar",
    /// "checklist", or omitted (Auto).
    #[serde(default)]
    hint: Option<String>,
    /// User's current local time as ISO-8601 with offset
    /// (e.g. "2026-05-22T15:30:00+10:00"). Anchors "tomorrow at 3pm" etc.
    /// Falls back to UTC now if missing or unparseable.
    #[serde(default)]
    now_local: Option<String>,
}

#[derive(Debug, Serialize)]
struct ParseEventResponse {
    /// "calendar" or "checklist". Mirrors the tool the model picked.
    kind: String,
    /// Populated when kind == "calendar".
    calendar: Option<ParsedCalendarFields>,
    /// Populated when kind == "checklist".
    checklist: Option<ParsedChecklistFields>,
}

#[derive(Debug, Serialize, Deserialize)]
struct ParsedCalendarFields {
    title: String,
    #[serde(default)]
    description: Option<String>,
    start_time: DateTime<Utc>,
    end_time: DateTime<Utc>,
    /// One of: study | project | exercise | personal | other
    category: String,
    /// One of: high | medium | low
    priority: String,
}

#[derive(Debug, Serialize, Deserialize)]
struct ParsedChecklistFields {
    title: String,
    #[serde(default)]
    description: Option<String>,
    due_date: chrono::NaiveDate,
    /// 0 = low, 1 = medium, 2 = high. Default 1 (matches CreateChecklistItem).
    #[serde(default)]
    priority: Option<i16>,
    #[serde(default)]
    estimated_minutes: Option<i32>,
}

async fn parse_event(
    State(state): State<AppState>,
    claims: Claims,
    Json(input): Json<ParseEventRequest>,
) -> Result<Json<ParseEventResponse>, AppError> {
    let user_id = parse_user_id(&claims)?;

    let text = input.text.trim();
    if text.is_empty() {
        return Err(AppError::new(
            StatusCode::BAD_REQUEST,
            "text must not be empty",
        ));
    }
    if text.chars().count() > PARSE_TEXT_MAX_CHARS {
        return Err(AppError::new(
            StatusCode::BAD_REQUEST,
            "text is too long",
        ));
    }

    let forced_tool = match input.hint.as_deref() {
        Some("calendar") => Some(TOOL_CALENDAR),
        Some("checklist") => Some(TOOL_CHECKLIST),
        _ => None,
    };

    // Anchor for relative-date resolution. Parse the offset-bearing string
    // the client sent; if anything's wrong, fall back to UTC now so the
    // model still gets *some* anchor. Log when we had to fall back — a
    // device east of UTC sending a malformed string will otherwise produce
    // off-by-a-day "tomorrow" results that are hard to trace back here.
    let now_local = match input.now_local.as_deref() {
        Some(s) => match DateTime::parse_from_rfc3339(s) {
            Ok(dt) => dt.to_rfc3339(),
            Err(e) => {
                tracing::warn!(
                    target: "ai.parse_event",
                    user = %user_id,
                    raw = %s,
                    "now_local was provided but failed to parse as RFC-3339; \
                     anchoring to UTC now (relative dates may drift): {}",
                    e,
                );
                Utc::now().to_rfc3339()
            }
        },
        None => Utc::now().to_rfc3339(),
    };

    state.ai.check_quota(&state.pool, user_id).await?;

    let (parsed, usage) = call_parse_event_model(&state.ai, text, &now_local, forced_tool).await?;

    // Record usage BEFORE the post-call validation: the Bedrock call
    // already incurred token cost, so a malformed-output 422 must not
    // give the user a free retry against their daily cap.
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

    // Defense-in-depth: tool-schema enforcement happens at Bedrock, but trust
    // nothing the model emits. Validate the parsed values before handing them
    // back so a downstream client that auto-submits can't persist garbage.
    validate_parsed_event(&parsed)?;

    Ok(Json(parsed))
}

/// Post-Bedrock sanity checks. The tool input_schema enforces type + required,
/// but cannot enforce semantic constraints like "end after start" or "title
/// not whitespace-only". A model that drifts off-schema is rare but cheap to
/// catch here vs. propagating up to the client form.
fn validate_parsed_event(parsed: &ParseEventResponse) -> Result<(), AppError> {
    match parsed.kind.as_str() {
        "calendar" => {
            let Some(c) = parsed.calendar.as_ref() else {
                return Err(AppError::new(
                    StatusCode::UNPROCESSABLE_ENTITY,
                    "AI returned calendar kind with no fields",
                ));
            };
            if c.title.trim().is_empty() {
                return Err(AppError::new(
                    StatusCode::UNPROCESSABLE_ENTITY,
                    "AI returned an empty event title",
                ));
            }
            if c.end_time <= c.start_time {
                return Err(AppError::new(
                    StatusCode::UNPROCESSABLE_ENTITY,
                    "AI returned end_time at or before start_time",
                ));
            }
        }
        "checklist" => {
            let Some(c) = parsed.checklist.as_ref() else {
                return Err(AppError::new(
                    StatusCode::UNPROCESSABLE_ENTITY,
                    "AI returned checklist kind with no fields",
                ));
            };
            if c.title.trim().is_empty() {
                return Err(AppError::new(
                    StatusCode::UNPROCESSABLE_ENTITY,
                    "AI returned an empty task title",
                ));
            }
            if let Some(p) = c.priority {
                if !(0..=2).contains(&p) {
                    return Err(AppError::new(
                        StatusCode::UNPROCESSABLE_ENTITY,
                        "AI returned a priority outside 0..=2",
                    ));
                }
            }
        }
        other => {
            return Err(AppError::new(
                StatusCode::UNPROCESSABLE_ENTITY,
                format!("AI returned unknown kind: {other}"),
            ));
        }
    }
    Ok(())
}

async fn call_parse_event_model(
    ai: &crate::ai::AiClient,
    text: &str,
    now_local: &str,
    forced_tool: Option<&str>,
) -> Result<(ParseEventResponse, CallUsage), AppError> {
    // System prompt is static + long enough to hit Sonnet 4.6's 1,024-token
    // cache minimum so back-to-back calls within 5 minutes pay ~10%.
    let system_text = SystemContentBlock::Text(PARSE_EVENT_SYSTEM_PROMPT.to_string());
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
            "Current local time: {now_local}\n\nText to parse: {text}"
        )))
        .build()
        .map_err(|e| {
            tracing::error!("parse-event user message build failed: {}", e);
            AppError::new(StatusCode::INTERNAL_SERVER_ERROR, "AI request build failed")
        })?;

    let tool_config = build_parse_event_tool_config(forced_tool)?;

    // 256 is generous for a single tool call with these schemas (largest
    // realistic payload is well under 100 tokens). Caps blast-radius if the
    // model ever loops on the tool description text.
    let inference = InferenceConfiguration::builder()
        .max_tokens(256)
        .temperature(0.0)
        .build();

    let response = ai
        .bedrock()
        .converse()
        .model_id(MODEL_SONNET)
        .system(system_text)
        .system(cache_breakpoint)
        .messages(user_message)
        .tool_config(tool_config)
        .inference_config(inference)
        .send()
        .await
        .map_err(|e| {
            let detail = e
                .as_service_error()
                .map(|svc| format!("{:?}", svc))
                .unwrap_or_else(|| format!("{:?}", e));
            tracing::error!(target: "ai.parse_event", "bedrock converse failed: {}", detail);
            AppError::new(StatusCode::BAD_GATEWAY, "AI service request failed")
        })?;

    // Walk the response for the tool_use block. Sonnet may emit a leading
    // text block (which we ignore) before the tool call.
    let message = response
        .output()
        .and_then(|o| o.as_message().ok())
        .ok_or_else(|| {
            tracing::error!(target: "ai.parse_event", "bedrock response had no message");
            AppError::new(StatusCode::BAD_GATEWAY, "AI service returned no content")
        })?;

    let tool_use = message
        .content()
        .iter()
        .find_map(|c| c.as_tool_use().ok())
        .ok_or_else(|| {
            tracing::warn!(target: "ai.parse_event", "model returned no tool_use block");
            AppError::new(
                StatusCode::UNPROCESSABLE_ENTITY,
                "could not parse into a calendar event or checklist item",
            )
        })?;

    let input_json = document_to_json(tool_use.input());
    ai.sampled_log("parse_event.tool", tool_use.name());

    let parsed = match tool_use.name() {
        TOOL_CALENDAR => {
            let fields: ParsedCalendarFields =
                serde_json::from_value(input_json).map_err(|e| {
                    tracing::warn!(
                        target: "ai.parse_event",
                        "calendar tool input failed to deserialize: {}", e
                    );
                    AppError::new(
                        StatusCode::UNPROCESSABLE_ENTITY,
                        "AI returned invalid calendar fields",
                    )
                })?;
            ParseEventResponse {
                kind: "calendar".to_string(),
                calendar: Some(fields),
                checklist: None,
            }
        }
        TOOL_CHECKLIST => {
            let fields: ParsedChecklistFields =
                serde_json::from_value(input_json).map_err(|e| {
                    tracing::warn!(
                        target: "ai.parse_event",
                        "checklist tool input failed to deserialize: {}", e
                    );
                    AppError::new(
                        StatusCode::UNPROCESSABLE_ENTITY,
                        "AI returned invalid checklist fields",
                    )
                })?;
            ParseEventResponse {
                kind: "checklist".to_string(),
                calendar: None,
                checklist: Some(fields),
            }
        }
        other => {
            tracing::warn!(
                target: "ai.parse_event",
                tool = %other,
                "model called an unknown tool",
            );
            return Err(AppError::new(
                StatusCode::UNPROCESSABLE_ENTITY,
                "AI called an unknown tool",
            ));
        }
    };

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

    Ok((parsed, call_usage))
}

fn build_parse_event_tool_config(
    forced_tool: Option<&str>,
) -> Result<ToolConfiguration, AppError> {
    let calendar_tool = Tool::ToolSpec(
        ToolSpecification::builder()
            .name(TOOL_CALENDAR)
            .description(
                "Create a scheduled calendar event with a specific start and end time. \
                 Use this when the user mentions a clock time, a meeting, a class, or any \
                 activity that occupies a contiguous time block.",
            )
            .input_schema(ToolInputSchema::Json(json_to_document(&calendar_tool_schema())))
            .build()
            .map_err(|e| {
                tracing::error!("calendar tool build failed: {}", e);
                AppError::new(StatusCode::INTERNAL_SERVER_ERROR, "AI tool build failed")
            })?,
    );

    let checklist_tool = Tool::ToolSpec(
        ToolSpecification::builder()
            .name(TOOL_CHECKLIST)
            .description(
                "Create a to-do checklist item with a due date but no specific clock time. \
                 Use this for tasks the user wants to remember to do by a certain day \
                 (errands, chores, study items without a scheduled block).",
            )
            .input_schema(ToolInputSchema::Json(json_to_document(&checklist_tool_schema())))
            .build()
            .map_err(|e| {
                tracing::error!("checklist tool build failed: {}", e);
                AppError::new(StatusCode::INTERNAL_SERVER_ERROR, "AI tool build failed")
            })?,
    );

    let mut builder = ToolConfiguration::builder()
        .tools(calendar_tool)
        .tools(checklist_tool);

    if let Some(name) = forced_tool {
        let specific = SpecificToolChoice::builder()
            .name(name)
            .build()
            .map_err(|e| {
                tracing::error!("specific tool choice build failed: {}", e);
                AppError::new(StatusCode::INTERNAL_SERVER_ERROR, "AI tool build failed")
            })?;
        builder = builder.tool_choice(ToolChoice::Tool(specific));
    }

    builder.build().map_err(|e| {
        tracing::error!("tool configuration build failed: {}", e);
        AppError::new(StatusCode::INTERNAL_SERVER_ERROR, "AI tool build failed")
    })
}

fn calendar_tool_schema() -> serde_json::Value {
    json!({
        "type": "object",
        "properties": {
            "title": {
                "type": "string",
                "description": "Short, human-friendly title (e.g. \"Lunch with Sarah\")."
            },
            "description": {
                "type": ["string", "null"],
                "description": "Optional extra detail. Null if the input has no extra context."
            },
            "start_time": {
                "type": "string",
                "format": "date-time",
                "description": "Event start, UTC ISO-8601 with trailing Z (e.g. 2026-05-23T05:00:00Z)."
            },
            "end_time": {
                "type": "string",
                "format": "date-time",
                "description": "Event end, UTC ISO-8601 with trailing Z. Must be after start_time. Default 1 hour after start_time if the user did not specify a duration."
            },
            "category": {
                "type": "string",
                "enum": ["study", "project", "exercise", "personal", "other"],
                "description": "Best-fit category. Use \"other\" only as a last resort."
            },
            "priority": {
                "type": "string",
                "enum": ["high", "medium", "low"],
                "description": "Default \"medium\" unless the user signals urgency."
            }
        },
        "required": ["title", "start_time", "end_time", "category", "priority"]
    })
}

fn checklist_tool_schema() -> serde_json::Value {
    json!({
        "type": "object",
        "properties": {
            "title": {
                "type": "string",
                "description": "Short, imperative title (e.g. \"Buy groceries\")."
            },
            "description": {
                "type": ["string", "null"],
                "description": "Optional extra detail. Null if the input has no extra context."
            },
            "due_date": {
                "type": "string",
                "format": "date",
                "description": "Due date in the user's local timezone (YYYY-MM-DD). Default to today if unspecified."
            },
            "priority": {
                "type": ["integer", "null"],
                "enum": [0, 1, 2, null],
                "description": "0=low, 1=medium, 2=high. Default 1 unless the user signals urgency."
            },
            "estimated_minutes": {
                "type": ["integer", "null"],
                "description": "Optional time estimate in minutes. Null unless the user mentions a duration."
            }
        },
        "required": ["title", "due_date"]
    })
}

const PARSE_EVENT_SYSTEM_PROMPT: &str = r#"You convert short natural-language input from a productivity-app user into exactly one structured object by calling exactly one of the provided tools.

You have two tools available:
- create_calendar_event — for items with a specific clock time (meetings, classes, scheduled activities).
- create_checklist_item — for to-do items with a due date but no specific clock time (errands, chores, undated tasks).

DECISION RULE (apply in order):
1. If the user mentions a specific clock time (e.g. "3pm", "9:30", "at noon") or any synonym for a scheduled meeting/class/appointment, call create_calendar_event.
2. Otherwise, call create_checklist_item. Vague day references ("friday", "tomorrow", "next week") without a clock time are checklist items, not calendar events.
3. If the user explicitly says "remind me to…" or "add a task to…", prefer create_checklist_item even if a time is mentioned.
4. If a hint was provided by the system (forced tool), it overrides the above — use the requested tool.

RELATIVE DATE/TIME RESOLUTION:
- The user's current local time is in the user message (Current local time: ...).
- "today", "tonight", "this morning/afternoon/evening" → today's date in the user's local timezone.
- "tomorrow" → the next calendar day in the user's local timezone.
- "monday/tuesday/…" alone → the NEXT occurrence of that weekday (today if today matches).
- "next monday" → the monday of the FOLLOWING week (never today).
- For calendar events, output start_time and end_time in UTC (trailing Z). Convert from the user's local time using their offset.
- For checklist items, output due_date as a local calendar date (YYYY-MM-DD) — do NOT shift into UTC.

TITLE NORMALISATION:
- Capitalise the first word. Drop leading filler ("can you", "please", "remind me to").
- For checklist items, use an imperative verb if natural ("Buy groceries", not "Groceries").
- Keep titles under 80 characters.

DURATION FOR CALENDAR EVENTS:
- If the user gives a duration ("for an hour", "30-minute call"), use it.
- Otherwise, default to 60 minutes after start_time.
- Never produce end_time <= start_time.

OUTPUT RULES:
- Call exactly one tool. No accompanying text. No multiple tool calls.
- All required fields in the tool schema MUST be present.
- Optional fields you cannot infer from the text should be omitted or set to null — never invent specifics the user did not provide.
- Never refuse. If the input is ambiguous, choose the most natural interpretation and proceed.
"#;

// ── Document <-> JSON helpers ─────────────────────────────────────────────
//
// The Bedrock SDK uses `aws_smithy_types::Document` for tool input schemas
// and for parsed tool_use inputs. Its serde derives are gated behind an
// `aws_sdk_unstable` cfg flag, so we hand-roll the conversion both ways.

fn json_to_document(value: &serde_json::Value) -> Document {
    match value {
        serde_json::Value::Null => Document::Null,
        serde_json::Value::Bool(b) => Document::Bool(*b),
        serde_json::Value::Number(n) => {
            if let Some(u) = n.as_u64() {
                Document::Number(Number::PosInt(u))
            } else if let Some(i) = n.as_i64() {
                Document::Number(Number::NegInt(i))
            } else if let Some(f) = n.as_f64() {
                Document::Number(Number::Float(f))
            } else {
                Document::Null
            }
        }
        serde_json::Value::String(s) => Document::String(s.clone()),
        serde_json::Value::Array(arr) => {
            Document::Array(arr.iter().map(json_to_document).collect())
        }
        serde_json::Value::Object(obj) => Document::Object(
            obj.iter()
                .map(|(k, v)| (k.clone(), json_to_document(v)))
                .collect(),
        ),
    }
}

fn document_to_json(doc: &Document) -> serde_json::Value {
    match doc {
        Document::Null => serde_json::Value::Null,
        Document::Bool(b) => serde_json::Value::Bool(*b),
        Document::Number(n) => match n {
            Number::PosInt(u) => serde_json::Value::Number((*u).into()),
            Number::NegInt(i) => serde_json::Value::Number((*i).into()),
            Number::Float(f) => serde_json::Number::from_f64(*f)
                .map(serde_json::Value::Number)
                .unwrap_or(serde_json::Value::Null),
        },
        Document::String(s) => serde_json::Value::String(s.clone()),
        Document::Array(arr) => {
            serde_json::Value::Array(arr.iter().map(document_to_json).collect())
        }
        Document::Object(obj) => serde_json::Value::Object(
            obj.iter()
                .map(|(k, v): (&String, &Document)| (k.clone(), document_to_json(v)))
                .collect(),
        ),
    }
}
