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
    ToolInputSchema, ToolResultBlock, ToolResultContentBlock, ToolResultStatus, ToolSpecification,
    ToolUseBlock,
};
use aws_smithy_types::{Document, Number};
use axum::extract::{Path, State};
use axum::http::StatusCode;
use axum::routing::{get, post};
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
        .route("/ai/anomaly-check", post(anomaly_check))
        // §9.8 Phase D — read-only fetch of the latest anomaly alert. Cheap
        // DB read, never touches Bedrock. Mobile Dashboard polls this on
        // load so it can render a card if there's an active alert.
        .route("/ai/anomaly", get(get_latest_anomaly))
        // §9.6 — Coach chat. One active ai_conversation per user, multi-turn.
        // List + send + reset. Non-streaming first ship; SSE can layer on
        // later without changing the client contract (just a new mime type).
        .route("/ai/chat/messages", get(chat_list_messages).post(chat_send))
        .route("/ai/chat/reset", post(chat_reset))
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

#[derive(Debug, Clone, Serialize, Deserialize)]
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

/// Schema for the `log_sleep_record` Coach tool. Captures only what the
/// user typically mentions in chat — bed/wake times, quality, pickups,
/// and an optional notes field. Target bed/wake times come from the
/// user's prefs server-side, so the model doesn't have to invent them.
fn sleep_tool_schema() -> serde_json::Value {
    json!({
        "type": "object",
        "properties": {
            "actual_bedtime": {
                "type": "string",
                "format": "date-time",
                "description": "When the user actually fell asleep, UTC ISO-8601 with trailing Z. Convert from the user's local time using the snapshot's offset footer."
            },
            "actual_wake_time": {
                "type": "string",
                "format": "date-time",
                "description": "When the user actually woke, UTC ISO-8601 with trailing Z. Must be after actual_bedtime."
            },
            "quality_rating": {
                "type": "integer",
                "minimum": 1,
                "maximum": 5,
                "description": "Self-rated sleep quality, 1=poor through 5=great. Default 3 if the user didn't characterise it."
            },
            "phone_pickups": {
                "type": ["integer", "null"],
                "minimum": 0,
                "description": "Number of phone pickups during the sleep window. Default 0 if not mentioned."
            },
            "notes": {
                "type": ["string", "null"],
                "description": "Optional one-line note ('woke up at 3am for water', 'felt anxious'). Null if the user didn't add context."
            }
        },
        "required": ["actual_bedtime", "actual_wake_time", "quality_rating"]
    })
}

/// Schema for the `create_alarm` Coach tool. The user confirms before the
/// alarm is actually written, so this is a proposal schema — keep it
/// loose. The client maps `days_of_week` ([sun..sat] strings) to the
/// bitmask the existing /alarms endpoint expects, and uses the user's
/// existing mission-config defaults from the Alarm preferences.
fn alarm_tool_schema() -> serde_json::Value {
    json!({
        "type": "object",
        "properties": {
            "trigger_time_local": {
                "type": "string",
                "pattern": "^([01][0-9]|2[0-3]):[0-5][0-9]$",
                "description": "24-hour local time the alarm fires, \"HH:MM\". Always two-digit (07:30, not 7:30)."
            },
            "label": {
                "type": ["string", "null"],
                "description": "Optional short label like \"Lab meeting wake\" or \"Gym\". Null if the user didn't name it."
            },
            "days_of_week": {
                "type": "array",
                "items": {
                    "type": "string",
                    "enum": ["sun", "mon", "tue", "wed", "thu", "fri", "sat"]
                },
                "uniqueItems": true,
                "description": "Days the alarm repeats. Empty array = one-shot at the next occurrence of trigger_time_local. \"weekdays\" → mon..fri. \"daily\" → all seven."
            },
            "mission_kind": {
                "type": "string",
                "enum": ["none", "math", "shake", "photo"],
                "description": "Dismiss mission. \"math\" is the default if the user didn't specify."
            }
        },
        "required": ["trigger_time_local", "days_of_week", "mission_kind"]
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

// ──────────────────────────────────────────────────────────────────────────
// §9.8 — Anomaly detection (Haiku, scheduled daily — invoked manually here)
// ──────────────────────────────────────────────────────────────────────────
//
// One Haiku call per user per day. Pulls 14 days of daily sleep + focus +
// pickup metrics, asks Haiku "is anything off enough to interrupt the user
// over?", and persists an `ai_insights` row when the answer is yes. The
// daily scheduler (Phase C) iterates active users; this endpoint is also
// reachable by hand so devs can probe a specific account.
//
// The push notification fan-out is wired in `anomaly_check` after the Haiku
// call returns alert=true: pulls all device_tokens for the user and fires
// each via the FCM client. Per-device failures are logged; expired tokens
// are pruned by `FcmClient::send_to_user` so they don't keep failing.

/// We keep one alert row per user per day. Re-running the check within the
/// same day returns the cached row instead of re-billing Haiku.
const ANOMALY_CACHE_HOURS: i64 = 24;

/// 14 days is the roadmap-specified window. Long enough to spot streaks,
/// short enough to keep the data card tight.
const ANOMALY_LOOKBACK_DAYS: i64 = 14;

#[derive(Debug, Serialize)]
pub(crate) struct AnomalyCheckResponse {
    /// True when Haiku flagged a pattern worth interrupting the user about.
    pub alert: bool,
    /// Short, second-person, names the specific pattern. Empty when no alert.
    pub reason: String,
    /// Row id when persisted; None when alert=false (we don't store
    /// "nothing wrong" rows).
    pub insight_id: Option<Uuid>,
    /// True when served from today's cache.
    pub cached: bool,
    /// True when an actual push was delivered to at least one device.
    pub pushed: bool,
}

#[derive(Debug, Serialize, Deserialize)]
struct AnomalyVerdict {
    alert: bool,
    reason: String,
}

async fn anomaly_check(
    State(state): State<AppState>,
    claims: Claims,
) -> Result<Json<AnomalyCheckResponse>, AppError> {
    let user_id = parse_user_id(&claims)?;
    let resp = run_anomaly_check_for_user(&state, user_id).await?;
    Ok(Json(resp))
}

/// Core anomaly-check pipeline, callable from both the HTTP route and the
/// daily scheduler. Honors the same 24h cache so back-to-back calls within
/// the day don't double-bill Bedrock or double-push.
pub(crate) async fn run_anomaly_check_for_user(
    state: &AppState,
    user_id: Uuid,
) -> Result<AnomalyCheckResponse, AppError> {
    // Today's cache — re-running shouldn't double-bill or double-push.
    if let Some(cached) = fetch_cached_anomaly(&state.pool, user_id).await? {
        // source_data holds the AnomalyVerdict JSON we stored. Fall back to
        // (alert=true, reason=content) if the row predates structured storage
        // or got corrupted somehow.
        let verdict: AnomalyVerdict =
            serde_json::from_value(cached.source_data.clone()).unwrap_or(AnomalyVerdict {
                alert: true,
                reason: cached.content.clone(),
            });
        return Ok(AnomalyCheckResponse {
            alert: verdict.alert,
            reason: verdict.reason,
            insight_id: Some(cached.id),
            cached: true,
            pushed: false,
        });
    }

    state.ai.check_quota(&state.pool, user_id).await?;

    let daily = aggregate_daily(&state.pool, user_id).await?;
    let data_card = render_anomaly_card(&daily);
    let (verdict, usage) = call_anomaly_model(&state.ai, &data_card).await?;

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

    if !verdict.alert {
        // Don't pollute ai_insights with daily "all good" rows; just return.
        return Ok(AnomalyCheckResponse {
            alert: false,
            reason: String::new(),
            insight_id: None,
            cached: false,
            pushed: false,
        });
    }

    // Persist before pushing so a push failure doesn't lose the verdict.
    let generated_at = Utc::now();
    let expires_at = generated_at + Duration::hours(ANOMALY_CACHE_HOURS);
    let row_id = sqlx::query_scalar::<_, Uuid>(
        "INSERT INTO ai_insights (user_id, kind, content, source_data, model, generated_at, expires_at)
         VALUES ($1, 'anomaly', $2, $3, $4, $5, $6)
         RETURNING id",
    )
    .bind(user_id)
    .bind(&verdict.reason)
    .bind(serde_json::to_value(&verdict).unwrap_or(serde_json::Value::Null))
    .bind(MODEL_HAIKU)
    .bind(generated_at)
    .bind(expires_at)
    .fetch_one(&state.pool)
    .await?;

    // Push fan-out — best-effort. FCM unavailable (no creds) is just a
    // logged warn; the in-app card will still surface the alert next time
    // the user opens the Dashboard.
    let pushed = match state.fcm.as_ref() {
        Some(fcm) => {
            match fcm
                .send_to_user(
                    &state.pool,
                    user_id,
                    "Heads up — Ultiq spotted a pattern",
                    &verdict.reason,
                    Some(json!({
                        "kind": "anomaly",
                        "insight_id": row_id.to_string(),
                    })),
                )
                .await
            {
                Ok(n) => {
                    tracing::info!(
                        target: "ai.anomaly",
                        user = %user_id,
                        delivered = n,
                        "anomaly push fan-out complete",
                    );
                    n > 0
                }
                Err(e) => {
                    tracing::warn!(
                        target: "ai.anomaly",
                        user = %user_id,
                        "anomaly push fan-out failed: {e:?}",
                    );
                    false
                }
            }
        }
        None => {
            tracing::warn!(
                target: "ai.anomaly",
                user = %user_id,
                "FCM unavailable — alert stored but no push delivered",
            );
            false
        }
    };

    Ok(AnomalyCheckResponse {
        alert: true,
        reason: verdict.reason,
        insight_id: Some(row_id),
        cached: false,
        pushed,
    })
}

async fn fetch_cached_anomaly(
    pool: &PgPool,
    user_id: Uuid,
) -> Result<Option<AiInsight>, AppError> {
    let cutoff = Utc::now() - Duration::hours(ANOMALY_CACHE_HOURS);
    let row = sqlx::query_as::<_, AiInsight>(
        "SELECT * FROM ai_insights
          WHERE user_id = $1 AND kind = 'anomaly' AND generated_at > $2
          ORDER BY generated_at DESC
          LIMIT 1",
    )
    .bind(user_id)
    .bind(cutoff)
    .fetch_optional(pool)
    .await?;
    Ok(row)
}

#[derive(Debug, Serialize)]
struct LatestAnomalyResponse {
    /// True when an active alert exists within the cache window.
    alert: bool,
    /// Reason text from the most recent alert. Empty when alert=false.
    reason: String,
    /// Insight id; useful if the client wants to mark it dismissed locally.
    insight_id: Option<Uuid>,
    /// When the alert was generated (UTC ISO-8601). None when alert=false.
    generated_at: Option<DateTime<Utc>>,
}

/// Read-only fetch of the latest anomaly alert (last 24h). Returns
/// `alert: false` when the daily scan hasn't produced an alert today, so
/// clients don't need to special-case the empty state.
async fn get_latest_anomaly(
    State(state): State<AppState>,
    claims: Claims,
) -> Result<Json<LatestAnomalyResponse>, AppError> {
    let user_id = parse_user_id(&claims)?;
    let cached = fetch_cached_anomaly(&state.pool, user_id).await?;
    let resp = match cached {
        Some(row) => {
            // The verdict was persisted with alert=true (we never store
            // alert=false rows), so the row's presence already implies
            // active alert. Still parse `source_data` defensively so a
            // missing/corrupt blob falls back to `row.content`.
            let verdict: AnomalyVerdict =
                serde_json::from_value(row.source_data.clone()).unwrap_or(AnomalyVerdict {
                    alert: true,
                    reason: row.content.clone(),
                });
            LatestAnomalyResponse {
                alert: verdict.alert,
                reason: verdict.reason,
                insight_id: Some(row.id),
                generated_at: Some(row.generated_at),
            }
        }
        None => LatestAnomalyResponse {
            alert: false,
            reason: String::new(),
            insight_id: None,
            generated_at: None,
        },
    };
    Ok(Json(resp))
}

// ── Per-day aggregation ──────────────────────────────────────────────────

#[derive(Debug, Serialize)]
struct DailyMetric {
    date: chrono::NaiveDate,
    sleep_minutes: Option<i64>,
    quality: Option<f64>,
    focus_minutes: i64,
    pickups: Option<i64>,
}

async fn aggregate_daily(pool: &PgPool, user_id: Uuid) -> Result<Vec<DailyMetric>, AppError> {
    let today = Utc::now().date_naive();
    let start = today - Duration::days(ANOMALY_LOOKBACK_DAYS - 1);

    // Per-day sleep (grouped by actual_bedtime's date).
    let sleep: Vec<(chrono::NaiveDate, Option<f64>, Option<f64>, Option<i64>)> =
        sqlx::query_as(
            "SELECT
                DATE(actual_bedtime)                                                       AS day,
                AVG(EXTRACT(EPOCH FROM (actual_wake_time - actual_bedtime))/60.0)::DOUBLE PRECISION,
                AVG(quality_rating)::DOUBLE PRECISION,
                COALESCE(SUM(phone_pickups), 0)::BIGINT
             FROM sleep_records
             WHERE user_id = $1 AND actual_bedtime >= $2::DATE
             GROUP BY day",
        )
        .bind(user_id)
        .bind(start)
        .fetch_all(pool)
        .await?;

    // Per-day focus minutes (grouped by started_at's date).
    let focus: Vec<(chrono::NaiveDate, Option<i64>)> = sqlx::query_as(
        "SELECT
            DATE(started_at) AS day,
            COALESCE(SUM(duration_minutes) FILTER (WHERE completed), 0)::BIGINT
         FROM productivity_sessions
         WHERE user_id = $1 AND started_at >= $2::DATE
         GROUP BY day",
    )
    .bind(user_id)
    .bind(start)
    .fetch_all(pool)
    .await?;

    let sleep_map: std::collections::HashMap<_, _> = sleep
        .into_iter()
        .map(|(d, m, q, p)| (d, (m.map(|x| x.round() as i64), q, p)))
        .collect();
    let focus_map: std::collections::HashMap<_, _> =
        focus.into_iter().map(|(d, m)| (d, m.unwrap_or(0))).collect();

    let mut out = Vec::with_capacity(ANOMALY_LOOKBACK_DAYS as usize);
    for offset in 0..ANOMALY_LOOKBACK_DAYS {
        let day = start + Duration::days(offset);
        let (sleep_minutes, quality, pickups) = sleep_map
            .get(&day)
            .cloned()
            .unwrap_or((None, None, None));
        out.push(DailyMetric {
            date: day,
            sleep_minutes,
            quality,
            focus_minutes: focus_map.get(&day).copied().unwrap_or(0),
            pickups,
        });
    }
    Ok(out)
}

fn render_anomaly_card(daily: &[DailyMetric]) -> String {
    let mut s = String::from("### Last 14 days (oldest → newest)\n\n");
    s.push_str("| Date | Sleep (h) | Quality (1-5) | Focus min | Pickups |\n");
    s.push_str("|------|-----------|---------------|-----------|--------|\n");
    for d in daily {
        let sleep = d
            .sleep_minutes
            .map(|m| format!("{:.2}", m as f64 / 60.0))
            .unwrap_or_else(|| "—".to_string());
        let quality = d
            .quality
            .map(|q| format!("{:.1}", q))
            .unwrap_or_else(|| "—".to_string());
        let pickups = d
            .pickups
            .map(|p| p.to_string())
            .unwrap_or_else(|| "—".to_string());
        s.push_str(&format!(
            "| {} | {} | {} | {} | {} |\n",
            d.date, sleep, quality, d.focus_minutes, pickups
        ));
    }
    s
}

// ── Bedrock call (Haiku, JSON output) ────────────────────────────────────

/// Long static prompt clears Haiku 4.5's 4,096-token cache minimum on
/// subsequent calls within the 5-min TTL. Below the threshold the cache
/// silently doesn't engage — fine, the per-call cost is already small.
const ANOMALY_SYSTEM_PROMPT: &str = r#"You are a passive health/productivity watchdog for an Ultiq user. You review the user's last 14 days of daily metrics (sleep duration, sleep quality, focus minutes, phone pickups during sleep window) and decide ONE thing: should we interrupt the user with a notification right now?

You return ONLY a JSON object with this exact shape (no prose, no markdown, no code fences):

{ "alert": <bool>, "reason": "<string>" }

DECISION RULES — alert=true when the data shows one of these patterns clearly:

1. SLEEP DEPRIVATION STREAK
   - 5 or more nights of <6 hours sleep in the last 7 days, OR
   - 3 nights in a row of <5 hours

2. FOCUS COLLAPSE
   - Average focus minutes in the last 7 days is <50% of the prior 7 days, AND
   - The recent week's total is below 60 minutes total (not just a quiet week — a collapse)

3. NIGHT PHONE USE
   - 30 or more pickups during a single sleep window in the last 7 days

4. QUALITY DROP
   - Average sleep quality dropped by ≥1 point (1-5 scale) week over week AND recent week's average is below 3.0

If NONE of those patterns fit, return { "alert": false, "reason": "" }. Silent is correct most days — you should be triggering at most once or twice a week per user. Crying wolf burns the user's trust in the feature.

WHEN ALERT=TRUE — `reason` rules:
- Under 35 words.
- Second person ("you", "your"). Never "I" / "we" / "the user".
- Name the specific pattern with at least one concrete number from the card.
- Warm but direct. No emojis. No exclamation marks. No "great job" / "you crushed it".
- End with a soft nudge to look, not a demand. "Want to take a look?" / "Worth a glance." / "Worth noticing." — pick one, vary if you call this often.

HARD RULES (absolute):
- Output ONLY the JSON. No prose before or after. No ```json fences. No commentary.
- Every numeral in `reason` MUST come from the data card. Don't invent stats.
- Missing data (— in a row) is not evidence — don't claim "you didn't sleep on day X" when the user simply didn't log it. Treat missing values as unknown, not bad.
- Never invent app features. The app tracks: sleep sessions, focus sessions, phone pickups during sleep. Don't reference HRV, heart rate, meditation, mood, etc.
- If the entire card is empty or near-empty, return alert=false (we can't judge from no data).
"#;

async fn call_anomaly_model(
    ai: &crate::ai::AiClient,
    data_card: &str,
) -> Result<(AnomalyVerdict, CallUsage), AppError> {
    let system_text = SystemContentBlock::Text(ANOMALY_SYSTEM_PROMPT.to_string());
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
            "{data_card}\n\nReturn the JSON verdict now.",
        )))
        .build()
        .map_err(|e| {
            tracing::error!("anomaly user message build failed: {}", e);
            AppError::new(StatusCode::INTERNAL_SERVER_ERROR, "AI request build failed")
        })?;

    // 200 tokens is plenty for `{"alert": false, "reason": ""}` or the longest
    // valid alert string (~35 words ≈ 90 tokens) plus JSON overhead. Caps
    // blast-radius if the model ignores the "ONLY JSON" rule and rambles.
    let inference = InferenceConfiguration::builder()
        .max_tokens(200)
        .temperature(0.0)
        .build();

    let response = ai
        .bedrock()
        .converse()
        .model_id(MODEL_HAIKU)
        .system(system_text)
        .system(cache_breakpoint)
        .messages(user_message)
        .inference_config(inference)
        .send()
        .await
        .map_err(|e| {
            let detail = e
                .as_service_error()
                .map(|svc| format!("{:?}", svc))
                .unwrap_or_else(|| format!("{:?}", e));
            tracing::error!(target: "ai.anomaly", "bedrock haiku call failed: {}", detail);
            AppError::new(StatusCode::BAD_GATEWAY, "AI service request failed")
        })?;

    let raw = response
        .output()
        .and_then(|o| o.as_message().ok())
        .and_then(|m| m.content().first())
        .and_then(|c| c.as_text().ok())
        .cloned()
        .ok_or_else(|| {
            tracing::error!("haiku anomaly returned no text");
            AppError::new(StatusCode::BAD_GATEWAY, "AI service returned no content")
        })?;

    let verdict = parse_anomaly_json(&raw).map_err(|e| {
        tracing::warn!(
            target: "ai.anomaly",
            raw = %raw,
            "could not parse Haiku JSON verdict ({e}); treating as no-alert",
        );
        // Treat parse failure as "no alert" rather than 500-ing — the user
        // shouldn't get an error toast because Haiku formatted oddly.
        AppError::new(StatusCode::BAD_GATEWAY, "AI returned malformed verdict")
    })?;

    ai.sampled_log("anomaly.verdict", &raw);

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

    Ok((verdict, call_usage))
}

/// Models occasionally wrap JSON in ```json fences despite the prompt
/// forbidding it. Strip the most common wrappings before parsing.
fn parse_anomaly_json(raw: &str) -> Result<AnomalyVerdict, serde_json::Error> {
    let trimmed = raw.trim();
    let stripped = trimmed
        .strip_prefix("```json")
        .or_else(|| trimmed.strip_prefix("```"))
        .unwrap_or(trimmed)
        .trim_end_matches("```")
        .trim();
    let mut verdict: AnomalyVerdict = serde_json::from_str(stripped)?;
    verdict.reason = verdict.reason.trim().to_string();
    // Belt-and-braces: a "true alert with empty reason" is meaningless; treat
    // it as no-alert so we don't fire an empty push.
    if verdict.alert && verdict.reason.is_empty() {
        verdict.alert = false;
    }
    Ok(verdict)
}

// ──────────────────────────────────────────────────────────────────────────
// §9.6 — Coach Chat (Sonnet, multi-turn)
// ──────────────────────────────────────────────────────────────────────────
//
// Multi-turn conversation with Sonnet 4.6. One active `ai_conversations`
// row per user; messages stored in `ai_messages` in order. On each send:
//   1. Validate + quota check
//   2. Pull / create the active conversation (latest by updated_at)
//   3. Pull the last N messages as history
//   4. Build `Vec<Message>` for Bedrock Converse (alternating user/assistant)
//   5. Call Sonnet (non-streaming — full text on completion)
//   6. Persist user_msg + assistant_msg + bump conversation.updated_at
//   7. Record usage, return both messages
//
// Non-streaming for first ship: simpler client contract, no SSE plumbing,
// and a 100-word reply takes <2s anyway. SSE can layer on later without
// changing the JSON response shape.

/// Hard cap on a single user message. Most chat lines are <500 chars; 2,000
/// gives headroom for occasional long prompts without letting a misbehaving
/// client dump arbitrary content into Bedrock's context window.
const CHAT_USER_MESSAGE_MAX_CHARS: usize = 2000;

/// Pull this many recent messages into the prompt. 20 messages ≈ 10 turns of
/// back-and-forth, which is plenty of context without bloating the cache
/// breakpoint or exceeding Sonnet's tier-1 token budget.
const CHAT_HISTORY_LIMIT: i64 = 20;

/// Output cap. ~600 tokens ≈ 150 words; well over the system-prompt-stated
/// 150-word limit but caps blast-radius if Sonnet decides to monologue.
const CHAT_MAX_OUTPUT_TOKENS: i32 = 700;

#[derive(Debug, Deserialize)]
struct ChatSendRequest {
    content: String,
    /// User's current local time as RFC-3339 with offset. Anchors relative
    /// dates ("tomorrow", "next monday") when the chat tool-loop is enabled
    /// and the model needs to commit a calendar event or checklist item.
    /// Falls back to UTC now if missing — older clients pre-dating the
    /// tool-loop omit it entirely.
    #[serde(default)]
    now_local: Option<String>,
}

#[derive(Debug, Serialize, sqlx::FromRow)]
struct ChatMessageDto {
    id: Uuid,
    /// One of "user" / "assistant". Backend never lets a client send a
    /// "system" message — those are server-side only.
    role: String,
    content: String,
    created_at: DateTime<Utc>,
}

#[derive(Debug, Serialize)]
struct ChatSendResponse {
    user_message: ChatMessageDto,
    assistant_message: ChatMessageDto,
    /// Tool calls that ran inside the Coach loop. Empty when tools are
    /// disabled (`AI_CHAT_TOOLS_ENABLED` false) or when the model answered
    /// without needing any. Clients render these inline: read-tool status
    /// pills, write-tool commit confirmations with an undo affordance, and
    /// inline confirm cards for proposed calendar events.
    #[serde(default)]
    tool_invocations: Vec<ToolInvocationSurface>,
}

/// What the chat handler bubbles up to the client for each tool the model
/// called. Distinct from the internal Bedrock ToolUseBlock — that one is
/// re-fed to the model; this one is the UI's view of the same event.
#[derive(Debug, Clone, Serialize)]
struct ToolInvocationSurface {
    /// Bedrock-generated tool_use_id. Stable per invocation; clients use it
    /// as a list-diff key.
    id: String,
    /// Tool name from the schema (e.g. "create_checklist_item").
    name: String,
    /// "ok" | "error" | "proposed". `proposed` is exclusive to
    /// `create_calendar_event` — the server did NOT write a row and the
    /// client must show a Create/Cancel confirm card.
    status: String,
    /// One-line human summary for the UI pill ("Looking at your sleep…",
    /// "Added: Buy groceries"). Always populated.
    summary: String,
    /// True iff the server actually wrote a row for this invocation.
    /// Always false for read tools and for proposed calendar events.
    committed: bool,
    /// When `committed = true`, identifies the resource so the client can
    /// hook up an Undo affordance.
    #[serde(skip_serializing_if = "Option::is_none")]
    committed_resource: Option<CommittedResource>,
    /// When `status = "proposed"` and `name = "create_calendar_event"`, the
    /// parsed fields the user will confirm or cancel via the proposal card.
    #[serde(skip_serializing_if = "Option::is_none")]
    proposed_event: Option<ParsedCalendarFields>,
    /// When `status = "proposed"` and `name = "create_alarm"`, the parsed
    /// alarm fields. The client renders a Create/Cancel card with these
    /// values and POSTs to `/alarms` on confirm.
    #[serde(skip_serializing_if = "Option::is_none")]
    proposed_alarm: Option<ProposedAlarmFields>,
}

/// Alarm fields a Coach call proposes for the user to confirm. Mirrors the
/// shape the Android `AlarmEditScreen` needs to prefill its form, then
/// posts to `/alarms`. Kept loose (label optional, days a bitmask the way
/// the mobile model stores them) so the model doesn't have to invent
/// device-specific defaults like `volume_pct`.
#[derive(Debug, Clone, Serialize, Deserialize)]
struct ProposedAlarmFields {
    /// 24-hour local time, "HH:MM".
    trigger_time_local: String,
    #[serde(default)]
    label: Option<String>,
    /// 7-bit bitmask of weekdays the alarm fires on. Bit 0 = Sunday … bit
    /// 6 = Saturday. 0 = one-shot (next matching trigger time only).
    days_of_week: i16,
    /// One of: "none" | "math" | "shake" | "photo". The mission_config
    /// payload is left empty on propose — the client fills sensible
    /// defaults from the user's existing prefs.
    mission_kind: String,
}

#[derive(Debug, Clone, Serialize)]
struct CommittedResource {
    /// "checklist" (created) | "checklist_complete" (marked done) |
    /// "sleep_record" (logged a night). Tells the client which undo
    /// endpoint to hit (DELETE /checklist/:id, POST
    /// /checklist/:id/uncomplete, or DELETE /sleep/:id).
    kind: String,
    id: Uuid,
    #[serde(skip_serializing_if = "Option::is_none")]
    title: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    due_date: Option<chrono::NaiveDate>,
}

#[derive(Debug, Serialize)]
struct ChatResetResponse {
    conversation_id: Uuid,
}

#[derive(Debug, Deserialize)]
struct ChatListQuery {
    /// Optional cap; default + max is CHAT_HISTORY_LIMIT * 5 to support
    /// "show me the whole conversation" without pagination plumbing.
    #[serde(default)]
    limit: Option<i64>,
}

async fn chat_send(
    State(state): State<AppState>,
    claims: Claims,
    Json(input): Json<ChatSendRequest>,
) -> Result<Json<ChatSendResponse>, AppError> {
    let user_id = parse_user_id(&claims)?;

    let content = input.content.trim();
    if content.is_empty() {
        return Err(AppError::new(
            StatusCode::BAD_REQUEST,
            "message must not be empty",
        ));
    }
    if content.chars().count() > CHAT_USER_MESSAGE_MAX_CHARS {
        return Err(AppError::new(
            StatusCode::BAD_REQUEST,
            "message is too long",
        ));
    }

    // Quota check once at the top — the tool loop may consume several
    // tickets per send (one per Bedrock call), but we never want to
    // reject mid-loop and leave the model in a half-state. The N tickets
    // get recorded one-by-one inside the loop.
    state.ai.check_quota(&state.pool, user_id).await?;

    let conversation_id = ensure_active_conversation(&state.pool, user_id).await?;
    let history = fetch_recent_messages(&state.pool, conversation_id).await?;

    // Anchor for relative-date resolution inside tool calls. We swallow
    // parse errors deliberately — falling back to UTC now is safer than
    // 500-ing the user's chat over a client clock glitch.
    let now_local = match input.now_local.as_deref() {
        Some(s) => match DateTime::parse_from_rfc3339(s) {
            Ok(dt) => dt.to_rfc3339(),
            Err(_) => Utc::now().to_rfc3339(),
        },
        None => Utc::now().to_rfc3339(),
    };

    // Feature flag: keep the vanilla chat path live until both clients
    // ship the tool-aware UI. Flipping the env var promotes the new path
    // without a redeploy. Older clients send no `now_local` and ignore
    // the `tool_invocations` field, so they remain functional under
    // either branch.
    let tools_enabled = std::env::var("AI_CHAT_TOOLS_ENABLED")
        .ok()
        .map(|v| v == "true" || v == "1")
        .unwrap_or(false);

    let assistant_text: String;
    let tool_invocations: Vec<ToolInvocationSurface>;
    let total_input: i64;
    let total_output: i64;
    if tools_enabled {
        let outcome =
            call_chat_model_with_tools(&state, user_id, &history, content, &now_local).await?;
        assistant_text = outcome.text;
        tool_invocations = outcome.invocations;
        total_input = outcome.total_input_tokens;
        total_output = outcome.total_output_tokens;
        // `record_usage` was already called per-iteration inside the loop;
        // nothing left to do quota-side here.
    } else {
        let (text, usage) = call_chat_model(&state.ai, &history, content).await?;
        assistant_text = text;
        tool_invocations = Vec::new();
        total_input = usage.input;
        total_output = usage.output;
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
    }

    // Persist user + assistant turns atomically so a Bedrock-after-DB
    // inconsistency can't store the user message twice on retry.
    let mut tx = state.pool.begin().await?;
    let user_msg: ChatMessageDto = sqlx::query_as(
        "INSERT INTO ai_messages (conversation_id, role, content)
         VALUES ($1, 'user', $2)
         RETURNING id, role, content, created_at",
    )
    .bind(conversation_id)
    .bind(content)
    .fetch_one(&mut *tx)
    .await?;
    let assistant_msg: ChatMessageDto = sqlx::query_as(
        "INSERT INTO ai_messages (conversation_id, role, content, input_tokens, output_tokens)
         VALUES ($1, 'assistant', $2, $3, $4)
         RETURNING id, role, content, created_at",
    )
    .bind(conversation_id)
    .bind(&assistant_text)
    .bind(total_input as i32)
    .bind(total_output as i32)
    .fetch_one(&mut *tx)
    .await?;
    sqlx::query("UPDATE ai_conversations SET updated_at = NOW() WHERE id = $1")
        .bind(conversation_id)
        .execute(&mut *tx)
        .await?;
    tx.commit().await?;

    Ok(Json(ChatSendResponse {
        user_message: user_msg,
        assistant_message: assistant_msg,
        tool_invocations,
    }))
}

async fn chat_list_messages(
    State(state): State<AppState>,
    claims: Claims,
    axum::extract::Query(query): axum::extract::Query<ChatListQuery>,
) -> Result<Json<Vec<ChatMessageDto>>, AppError> {
    let user_id = parse_user_id(&claims)?;
    let limit = query
        .limit
        .filter(|n| *n > 0)
        .unwrap_or(CHAT_HISTORY_LIMIT * 5)
        .min(CHAT_HISTORY_LIMIT * 5);

    // Pull the active conversation, but DON'T create one — empty history
    // for a fresh user is a valid state ("no conversation yet").
    let active: Option<Uuid> = sqlx::query_scalar(
        "SELECT id FROM ai_conversations
          WHERE user_id = $1
          ORDER BY updated_at DESC
          LIMIT 1",
    )
    .bind(user_id)
    .fetch_optional(&state.pool)
    .await?;
    let Some(conversation_id) = active else {
        return Ok(Json(Vec::new()));
    };

    let rows: Vec<ChatMessageDto> = sqlx::query_as(
        "SELECT id, role, content, created_at
           FROM ai_messages
          WHERE conversation_id = $1
          ORDER BY created_at ASC
          LIMIT $2",
    )
    .bind(conversation_id)
    .bind(limit)
    .fetch_all(&state.pool)
    .await?;
    Ok(Json(rows))
}

async fn chat_reset(
    State(state): State<AppState>,
    claims: Claims,
) -> Result<Json<ChatResetResponse>, AppError> {
    let user_id = parse_user_id(&claims)?;
    // New empty conversation row — the next /chat/messages POST will use it
    // because it's the latest by updated_at. Prior conversations stay in
    // the DB for audit / future "history" UX, just inactive.
    let id: Uuid = sqlx::query_scalar(
        "INSERT INTO ai_conversations (user_id) VALUES ($1) RETURNING id",
    )
    .bind(user_id)
    .fetch_one(&state.pool)
    .await?;
    Ok(Json(ChatResetResponse { conversation_id: id }))
}

async fn ensure_active_conversation(
    pool: &PgPool,
    user_id: Uuid,
) -> Result<Uuid, AppError> {
    if let Some(id) = sqlx::query_scalar::<_, Uuid>(
        "SELECT id FROM ai_conversations
          WHERE user_id = $1
          ORDER BY updated_at DESC
          LIMIT 1",
    )
    .bind(user_id)
    .fetch_optional(pool)
    .await?
    {
        return Ok(id);
    }
    let id: Uuid = sqlx::query_scalar(
        "INSERT INTO ai_conversations (user_id) VALUES ($1) RETURNING id",
    )
    .bind(user_id)
    .fetch_one(pool)
    .await?;
    Ok(id)
}

async fn fetch_recent_messages(
    pool: &PgPool,
    conversation_id: Uuid,
) -> Result<Vec<ChatMessageDto>, AppError> {
    // Pull oldest→newest to preserve turn order. The LIMIT applies after
    // the ORDER BY in PG, so we use a subquery to take the latest N then
    // re-sort ascending — otherwise we'd drop the oldest messages.
    let rows: Vec<ChatMessageDto> = sqlx::query_as(
        "SELECT id, role, content, created_at
           FROM (
             SELECT id, role, content, created_at
               FROM ai_messages
              WHERE conversation_id = $1
              ORDER BY created_at DESC
              LIMIT $2
           ) recent
          ORDER BY created_at ASC",
    )
    .bind(conversation_id)
    .bind(CHAT_HISTORY_LIMIT)
    .fetch_all(pool)
    .await?;
    Ok(rows)
}

/// Static prompt sized to clear Sonnet 4.6's 1,024-token cache minimum so
/// subsequent calls within the 5-min TTL pay ~10% for input. Lock the
/// coach voice + the hard rules; the per-message data card (none for chat)
/// stays out of this so the cache hit lasts across the user's turn-taking.
const CHAT_SYSTEM_PROMPT: &str = r#"You are Ultiq's productivity coach, talking to a university student who uses the Ultiq app to track sleep, focus sessions, phone pickups during sleep, checklist items, and calendar events. You help them think about sleep habits, focus blocks, weekly planning, schedule design, and study workflows.

PERSONA:
- Warm, direct, never gushing. Picture a smart older friend who actually knows what they're doing — confident, encouraging, but allergic to bullshit.
- Use second person ("you", "your"). Don't refer to yourself as "I am an AI" unless directly asked — it's a chat, the user knows.
- Plain English. No corporate wellness language. No "level up". No "growth mindset". No "embrace the journey". No "you crushed it".

ANSWER SHAPE:
- Default to under 150 words. Detailed plans can be longer if the user asks for them explicitly ("walk me through a full schedule…").
- Plain prose for normal questions. Use a short numbered list ONLY when the answer is genuinely a sequence of steps.
- If the user asks for advice and you'd want concrete data to answer well (current sleep, current focus minutes, etc.) and they haven't shared it: ask ONE specific follow-up rather than guessing.
- If asked something outside productivity / sleep / focus / study habits (e.g. "write me a poem", "what's the weather", "do my homework"), give a one-line redirect: you're here for productivity coaching, not those things.

HARD RULES (absolute):
- You do not have access to the user's data tables in this chat. Don't invent stats about them ("you slept 5.5h on average last week" — unless they tell you, you don't know).
- Don't invent app features. Ultiq has: sleep sessions, alarm + missions, focus sessions, phone-pickup tracking, checklist, calendar, weekly insight, session debrief, anomaly alerts. No heart-rate, no mood log, no meditation streak.
- Never recommend specific medical advice or medication. If sleep problems sound serious (chronic insomnia, suspected sleep disorder), suggest they talk to a clinician.
- Refuse and redirect on disallowed content (graphic, illegal, etc.) without lecturing — one line is enough.
- No emojis.

TONE EXAMPLES (style, not content):
- "That's a reasonable target. The harder question is whether you'll actually defend the time — Tuesday afternoons are usually the first thing that slips. What's currently on the Tuesday calendar?"
- "Honestly: 4h is fine for one night and bad for five. Which one is this?"
- "I'd pick the morning block. Decision fatigue accumulates — your 3pm self is worse at the same task than your 9am self."
"#;

async fn call_chat_model(
    ai: &crate::ai::AiClient,
    history: &[ChatMessageDto],
    new_user_message: &str,
) -> Result<(String, CallUsage), AppError> {
    let system_text = SystemContentBlock::Text(CHAT_SYSTEM_PROMPT.to_string());
    let cache_breakpoint = SystemContentBlock::CachePoint(
        CachePointBlock::builder()
            .r#type(CachePointType::Default)
            .build()
            .map_err(|e| {
                tracing::error!("chat cache point build failed: {}", e);
                AppError::new(StatusCode::INTERNAL_SERVER_ERROR, "AI request build failed")
            })?,
    );

    // Build the Bedrock message list. History first (alternating user/asst
    // exactly as we stored them), then the new user message. Skip any
    // "system" messages from the DB — they shouldn't exist here, but if a
    // future migration adds them we don't want to crash the chat.
    let mut messages: Vec<Message> = Vec::with_capacity(history.len() + 1);
    for m in history {
        let role = match m.role.as_str() {
            "assistant" => ConversationRole::Assistant,
            "user" => ConversationRole::User,
            _ => continue,
        };
        let msg = Message::builder()
            .role(role)
            .content(ContentBlock::Text(m.content.clone()))
            .build()
            .map_err(|e| {
                tracing::error!("chat history message build failed: {}", e);
                AppError::new(
                    StatusCode::INTERNAL_SERVER_ERROR,
                    "AI request build failed",
                )
            })?;
        messages.push(msg);
    }
    let new_user_msg = Message::builder()
        .role(ConversationRole::User)
        .content(ContentBlock::Text(new_user_message.to_string()))
        .build()
        .map_err(|e| {
            tracing::error!("chat user message build failed: {}", e);
            AppError::new(StatusCode::INTERNAL_SERVER_ERROR, "AI request build failed")
        })?;
    messages.push(new_user_msg);

    let inference = InferenceConfiguration::builder()
        .max_tokens(CHAT_MAX_OUTPUT_TOKENS)
        .temperature(0.7)
        .build();

    let mut converse = ai
        .bedrock()
        .converse()
        .model_id(MODEL_SONNET)
        .system(system_text)
        .system(cache_breakpoint)
        .inference_config(inference);
    for m in messages {
        converse = converse.messages(m);
    }
    let response = converse.send().await.map_err(|e| {
        let detail = e
            .as_service_error()
            .map(|svc| format!("{:?}", svc))
            .unwrap_or_else(|| format!("{:?}", e));
        tracing::error!(target: "ai.chat", "bedrock converse failed: {}", detail);
        AppError::new(StatusCode::BAD_GATEWAY, "AI service request failed")
    })?;

    let text = response
        .output()
        .and_then(|o| o.as_message().ok())
        .and_then(|m| m.content().first())
        .and_then(|c| c.as_text().ok())
        .cloned()
        .ok_or_else(|| {
            tracing::error!("chat bedrock response had no text");
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

    ai.sampled_log("chat.response", &text);

    Ok((text.trim().to_string(), call_usage))
}

// ──────────────────────────────────────────────────────────────────────────
// §9.x — Coach tool loop (chat-with-tools, hybrid context, auto-commit + propose)
// ──────────────────────────────────────────────────────────────────────────
//
// Gated by AI_CHAT_TOOLS_ENABLED. The model gets eight tools:
//   Read:  get_today_summary, get_sleep_history, get_focus_history,
//          get_calendar_events, get_checklist
//   Write: create_checklist_item   (auto-commit + SSE)
//          complete_checklist_item (auto-commit + SSE)
//          create_calendar_event   (PROPOSED only — never writes here;
//                                   the client confirms via Create/Cancel)
//
// Each turn pre-loads a "today + last 3 days" context card into the user
// message so the model has grounded numbers without spending a read-tool
// round-trip on common questions. The card is rendered fresh per call and
// intentionally NOT cached: stale snapshots are worse UX than the ~300
// input tokens. The system prompt + tool definitions ARE cached (system
// block cache point), so loop iterations 2+ hit the cache.
//
// Tool-use blocks are NOT persisted to ai_messages — only the user-typed
// text and the final assistant text. Across turns the model loses the
// tool-call detail; that's fine, the next turn re-pulls the snapshot and
// can re-call any tool it needs.

/// Tripwire for runaway loops. Realistic ceiling is 2-3 iterations.
const MAX_TOOL_ITERATIONS: usize = 6;

/// Output cap per loop iteration. Slightly higher than the no-tools path
/// because tool-using replies often include a brief reference to a result.
const CHAT_TOOLS_MAX_OUTPUT_TOKENS: i32 = 900;

/// Soft cap on `get_calendar_events` range to defend against the model
/// asking for "the whole year". 90 days is well over any reasonable
/// scheduling question.
const CALENDAR_RANGE_MAX_DAYS: i64 = 90;

const TOOL_GET_TODAY_SUMMARY: &str = "get_today_summary";
const TOOL_GET_SLEEP_HISTORY: &str = "get_sleep_history";
const TOOL_GET_FOCUS_HISTORY: &str = "get_focus_history";
const TOOL_GET_CALENDAR_EVENTS: &str = "get_calendar_events";
const TOOL_GET_CHECKLIST: &str = "get_checklist";
const TOOL_COMPLETE_CHECKLIST_ITEM: &str = "complete_checklist_item";
const TOOL_LOG_SLEEP_RECORD: &str = "log_sleep_record";
const TOOL_CREATE_ALARM: &str = "create_alarm";
// TOOL_CALENDAR and TOOL_CHECKLIST are defined for parse_event above and
// reused here verbatim — the schema is identical; the only difference is
// the handler (propose vs. auto-commit).

struct ChatToolLoopOutcome {
    text: String,
    invocations: Vec<ToolInvocationSurface>,
    total_input_tokens: i64,
    total_output_tokens: i64,
}

struct ToolRunOutcome {
    /// JSON-serialised payload the model sees in its tool_result block.
    payload_for_model: String,
    /// Bedrock-side status. Success keeps the loop natural; Error is fed
    /// back to the model so it can acknowledge or retry.
    bedrock_status: ToolResultStatus,
    /// Client-facing record of what the tool did.
    surface: ToolInvocationSurface,
}

/// System prompt for the tools-enabled chat path. Sized > 1,024 tokens so
/// the cache point catches it. Replaces the un-grounded `CHAT_SYSTEM_PROMPT`
/// rule "you do not have access to the user's data tables" with the
/// opposite: you DO have tools, USE them.
const CHAT_SYSTEM_PROMPT_TOOLS: &str = r#"You are Ultiq's productivity coach, talking to a university student who uses the Ultiq app to track sleep, focus sessions, phone pickups, checklist items, and calendar events. You help them think about sleep habits, focus blocks, weekly planning, schedule design, and study workflows.

PERSONA:
- Warm, direct, never gushing. Picture a smart older friend who actually knows what they're doing — confident, encouraging, but allergic to bullshit.
- Use second person ("you", "your"). Don't introduce yourself as an AI unless directly asked — it's a chat, the user knows.
- Plain English. No corporate wellness language. No "level up". No "growth mindset". No "embrace the journey". No "you crushed it". No emojis.

YOU HAVE TOOLS. USE THEM.

Read tools (look at the user's actual data):
- get_today_summary — refreshes the snapshot at the top of this turn. Use only when you've just completed a write and want to confirm the new state. Otherwise, the snapshot at the top of the user message is already fresh.
- get_sleep_history(days) — last N nights with bedtime, wake, duration, quality (1..5), and phone pickups during the sleep window.
- get_focus_history(days) — per-day focus minutes and completed-session counts.
- get_calendar_events(start_date, end_date) — events in a date range. Range capped at 90 days.
- get_checklist(date) — checklist items due on a specific day. Each item includes its id so you can complete it.

Write tools (act on the user's behalf):
- create_checklist_item — adds a checklist item. Commits immediately. Speak as though it's done ("Added 'Buy bananas' to today's list.").
- complete_checklist_item(item_id) — marks an existing item done. Commits immediately. The id comes from get_checklist or get_today_summary.
- create_calendar_event — PROPOSES an event for the user to confirm. You did NOT add it — the user will see a Create/Cancel card and decide. Phrase your reply as a draft ("I've drafted a 4pm study block — tap Create if that works.").

TOOL-USE RULES:
- The context snapshot at the start of the user message is server-generated truth. Trust it. Don't call a read tool just to re-fetch what's already there.
- If the user asks something needing data beyond the snapshot ("how did I sleep two weeks ago", "what's on next friday"), call the matching read tool first.
- Never fabricate stats. If you don't have it and a tool would give it to you, call the tool.
- Don't narrate tool calls. Don't say "I'll check your sleep tool now." Just call it and answer.
- Don't call the same read tool twice in a turn with the same arguments.

CHECKLIST vs CALENDAR (mirror the parse-event rule):
- Specific clock time mentioned → calendar event (proposed).
- Day-only, "remind me to…", "add a task to…", or vague "next friday" without a time → checklist item.
- For relative dates, anchor on the local-time field in the snapshot footer.

ANSWER SHAPE:
- Default under 150 words. Detailed plans can be longer if the user explicitly asks ("walk me through a full schedule…").
- Plain prose for normal questions. Use a short numbered list ONLY when the answer is genuinely a sequence of steps.
- For off-topic asks (poetry, weather, "do my homework"), one-line redirect — you're here for productivity coaching, not those things.

FORMATTING (the chat UI renders only inline Markdown — anything else shows up as raw text):
- NEVER use Markdown tables. The pipe characters render as literal text.
- NEVER use Markdown headers (no `#`, `##`).
- NEVER use horizontal rules or HTML.
- Inline `**bold**` and `*italic*` are okay sparingly for emphasis on a key number or word. Use `\`code\`` only when literally referring to code or a specific id.
- Numbered lists are fine. Bullet lists ("- item") are fine but use sparingly.
- Prefer flowing prose with one or two **bold** words over any structured layout.

HARD RULES (absolute):
- Don't invent app features. Ultiq has: sleep sessions, alarm + missions, focus sessions, phone-pickup tracking, checklist, calendar, weekly insight, session debrief, anomaly alerts, Coach (you). No heart-rate, no mood log, no meditation streak.
- Never recommend specific medical advice or medication. If sleep problems sound serious (chronic insomnia, suspected sleep disorder), suggest they talk to a clinician.
- Refuse and redirect on disallowed content (graphic, illegal, etc.) without lecturing — one line is enough.

TONE EXAMPLES (style, not content):
- "That's a reasonable target. The harder question is whether you'll actually defend the time — Tuesday afternoons are usually the first thing that slips. What's currently on the Tuesday calendar?"
- "Honestly: 4h is fine for one night and bad for five. Which one is this?"
- "I'd pick the morning block. Decision fatigue accumulates — your 3pm self is worse at the same task than your 9am self."
- "Added 'Lab report — section 3' to today. Anything else?"
- "I've drafted a 9-10am focus block for Wednesday — tap Create if that works, or I can shift it."
"#;

async fn call_chat_model_with_tools(
    state: &AppState,
    user_id: Uuid,
    history: &[ChatMessageDto],
    new_user_message: &str,
    now_local: &str,
) -> Result<ChatToolLoopOutcome, AppError> {
    let system_text = SystemContentBlock::Text(CHAT_SYSTEM_PROMPT_TOOLS.to_string());
    let cache_breakpoint = SystemContentBlock::CachePoint(
        CachePointBlock::builder()
            .r#type(CachePointType::Default)
            .build()
            .map_err(|e| {
                tracing::error!("chat tools cache point build failed: {}", e);
                AppError::new(StatusCode::INTERNAL_SERVER_ERROR, "AI request build failed")
            })?,
    );

    let tool_config = build_chat_tool_config()?;
    let context_card = build_context_card(&state.pool, user_id, now_local).await?;

    // Compose messages. The context card is the leading user-role block —
    // outside the cached prefix because it changes daily.
    let mut messages: Vec<Message> = Vec::with_capacity(history.len() + 2);
    let context_msg = Message::builder()
        .role(ConversationRole::User)
        .content(ContentBlock::Text(context_card))
        .build()
        .map_err(|e| {
            tracing::error!("chat context message build failed: {}", e);
            AppError::new(StatusCode::INTERNAL_SERVER_ERROR, "AI request build failed")
        })?;
    messages.push(context_msg);
    for m in history {
        let role = match m.role.as_str() {
            "assistant" => ConversationRole::Assistant,
            "user" => ConversationRole::User,
            _ => continue,
        };
        let msg = Message::builder()
            .role(role)
            .content(ContentBlock::Text(m.content.clone()))
            .build()
            .map_err(|e| {
                tracing::error!("chat history message build failed: {}", e);
                AppError::new(StatusCode::INTERNAL_SERVER_ERROR, "AI request build failed")
            })?;
        messages.push(msg);
    }
    let new_user_msg = Message::builder()
        .role(ConversationRole::User)
        .content(ContentBlock::Text(new_user_message.to_string()))
        .build()
        .map_err(|e| {
            tracing::error!("chat new user message build failed: {}", e);
            AppError::new(StatusCode::INTERNAL_SERVER_ERROR, "AI request build failed")
        })?;
    messages.push(new_user_msg);

    let inference = InferenceConfiguration::builder()
        .max_tokens(CHAT_TOOLS_MAX_OUTPUT_TOKENS)
        .temperature(0.7)
        .build();

    let mut invocations: Vec<ToolInvocationSurface> = Vec::new();
    let mut total_input: i64 = 0;
    let mut total_output: i64 = 0;
    let mut final_text: Option<String> = None;

    for iteration in 0..MAX_TOOL_ITERATIONS {
        let mut converse = state
            .ai
            .bedrock()
            .converse()
            .model_id(MODEL_SONNET)
            .system(system_text.clone())
            .system(cache_breakpoint.clone())
            .tool_config(tool_config.clone())
            .inference_config(inference.clone());
        for m in &messages {
            converse = converse.messages(m.clone());
        }
        let response = converse.send().await.map_err(|e| {
            let detail = e
                .as_service_error()
                .map(|svc| format!("{:?}", svc))
                .unwrap_or_else(|| format!("{:?}", e));
            tracing::error!(
                target: "ai.chat",
                iteration,
                "bedrock converse failed: {}",
                detail
            );
            AppError::new(StatusCode::BAD_GATEWAY, "AI service request failed")
        })?;

        let usage = response.usage();
        let in_t = usage.map(|u| u.input_tokens()).unwrap_or(0) as i64;
        let out_t = usage.map(|u| u.output_tokens()).unwrap_or(0) as i64;
        let cache_read = usage
            .and_then(|u| u.cache_read_input_tokens())
            .unwrap_or(0) as i64;
        let cache_write = usage
            .and_then(|u| u.cache_write_input_tokens())
            .unwrap_or(0) as i64;
        total_input += in_t;
        total_output += out_t;
        tracing::info!(
            target: "ai.chat",
            iter = iteration,
            input = in_t,
            output = out_t,
            cache_read,
            cache_write,
            "chat tool loop iter"
        );

        // Record this Bedrock call as one quota ticket before continuing.
        // The cap check at the top reserved capacity; this is just the
        // bookkeeping side.
        state
            .ai
            .record_usage(&state.pool, user_id, in_t, out_t, cache_read, cache_write)
            .await?;

        let message = response
            .output()
            .and_then(|o| o.as_message().ok())
            .ok_or_else(|| {
                tracing::error!("chat bedrock response had no message");
                AppError::new(StatusCode::BAD_GATEWAY, "AI service returned no content")
            })?
            .clone();

        // Collect any tool_use blocks the model emitted in this turn.
        let tool_uses: Vec<ToolUseBlock> = message
            .content()
            .iter()
            .filter_map(|c| c.as_tool_use().ok().cloned())
            .collect();

        if tool_uses.is_empty() {
            // No tools — take the first text block as the final reply.
            let text = message
                .content()
                .iter()
                .find_map(|c| c.as_text().ok())
                .cloned()
                .unwrap_or_default();
            final_text = Some(text.trim().to_string());
            break;
        }

        // Append the model's tool-using turn verbatim — Bedrock requires
        // it for tool_result chaining on the next call.
        messages.push(message);

        // Run each tool. Build a single user-role message containing all
        // tool_result blocks (the SDK / API expects them batched in one
        // user turn before the next assistant call).
        let mut result_msg_builder = Message::builder().role(ConversationRole::User);
        for tu in &tool_uses {
            let outcome = run_chat_tool(state, user_id, tu, now_local).await;
            invocations.push(outcome.surface.clone());
            let trb = ToolResultBlock::builder()
                .tool_use_id(tu.tool_use_id().to_string())
                .content(ToolResultContentBlock::Text(outcome.payload_for_model))
                .status(outcome.bedrock_status)
                .build()
                .map_err(|e| {
                    tracing::error!("chat tool_result build failed: {}", e);
                    AppError::new(
                        StatusCode::INTERNAL_SERVER_ERROR,
                        "AI tool result build failed",
                    )
                })?;
            result_msg_builder = result_msg_builder.content(ContentBlock::ToolResult(trb));
        }
        let result_msg = result_msg_builder.build().map_err(|e| {
            tracing::error!("chat tool result message build failed: {}", e);
            AppError::new(
                StatusCode::INTERNAL_SERVER_ERROR,
                "AI request build failed",
            )
        })?;
        messages.push(result_msg);
        // Loop again so the model can use the tool results.
    }

    let text = final_text.unwrap_or_else(|| {
        tracing::warn!(
            target: "ai.chat",
            iters = MAX_TOOL_ITERATIONS,
            "chat tool loop exhausted iterations without final text"
        );
        "Sorry — I got tangled up looking at your data. Could you ask me again, maybe a bit more specifically?".to_string()
    });

    state.ai.sampled_log("chat.tools.response", &text);

    Ok(ChatToolLoopOutcome {
        text,
        invocations,
        total_input_tokens: total_input,
        total_output_tokens: total_output,
    })
}

fn build_chat_tool_config() -> Result<ToolConfiguration, AppError> {
    fn build_one(
        name: &str,
        description: &str,
        schema: serde_json::Value,
    ) -> Result<Tool, AppError> {
        Ok(Tool::ToolSpec(
            ToolSpecification::builder()
                .name(name)
                .description(description)
                .input_schema(ToolInputSchema::Json(json_to_document(&schema)))
                .build()
                .map_err(|e| {
                    tracing::error!("chat tool {} build failed: {}", name, e);
                    AppError::new(StatusCode::INTERNAL_SERVER_ERROR, "AI tool build failed")
                })?,
        ))
    }

    let tools = vec![
        build_one(
            TOOL_GET_TODAY_SUMMARY,
            "Fetch a fresh snapshot of the user's state (sleep last 3 nights, focus week-to-date, today's checklist with ids, next 3 calendar events). The same snapshot is already in the user message at the start of this turn — only call this after a write, to confirm the updated state.",
            json!({ "type": "object", "properties": {}, "required": [] }),
        )?,
        build_one(
            TOOL_GET_SLEEP_HISTORY,
            "Fetch the user's sleep records for the last N nights (1..=30). Each row has bedtime, wake time, duration in minutes, quality rating (1..5), and phone pickups during the sleep window.",
            json!({
                "type": "object",
                "properties": {
                    "days": {
                        "type": "integer",
                        "minimum": 1,
                        "maximum": 30,
                        "description": "How many days of sleep history to fetch."
                    }
                },
                "required": ["days"]
            }),
        )?,
        build_one(
            TOOL_GET_FOCUS_HISTORY,
            "Fetch the user's focus-session activity for the last N days (1..=30). Returns per-day completed-session count and total focused minutes.",
            json!({
                "type": "object",
                "properties": {
                    "days": {
                        "type": "integer",
                        "minimum": 1,
                        "maximum": 30,
                        "description": "How many days of focus history to fetch."
                    }
                },
                "required": ["days"]
            }),
        )?,
        build_one(
            TOOL_GET_CALENDAR_EVENTS,
            "Fetch calendar events in a date range. Each event has id, title, start, end (UTC ISO-8601), category, and priority. Range cannot exceed 90 days.",
            json!({
                "type": "object",
                "properties": {
                    "start_date": {
                        "type": "string",
                        "format": "date",
                        "description": "Inclusive start date in the user's local timezone (YYYY-MM-DD)."
                    },
                    "end_date": {
                        "type": "string",
                        "format": "date",
                        "description": "Inclusive end date in the user's local timezone (YYYY-MM-DD). Must be on or after start_date. Range capped at 90 days."
                    }
                },
                "required": ["start_date", "end_date"]
            }),
        )?,
        build_one(
            TOOL_GET_CHECKLIST,
            "Fetch checklist items due on a specific date. Each item has id, title, description, priority (0=low / 1=medium / 2=high), completed flag, and estimated minutes.",
            json!({
                "type": "object",
                "properties": {
                    "date": {
                        "type": "string",
                        "format": "date",
                        "description": "Local date (YYYY-MM-DD) in the user's timezone."
                    }
                },
                "required": ["date"]
            }),
        )?,
        build_one(
            TOOL_CHECKLIST,
            "Add a checklist item for the user. Commits immediately. Use for to-do items with a due date but no specific clock time. Speak as though it's done.",
            checklist_tool_schema(),
        )?,
        build_one(
            TOOL_COMPLETE_CHECKLIST_ITEM,
            "Mark an existing checklist item as done. The item_id comes from get_checklist or get_today_summary. Commits immediately.",
            json!({
                "type": "object",
                "properties": {
                    "item_id": {
                        "type": "string",
                        "description": "UUID of the checklist item to mark complete."
                    }
                },
                "required": ["item_id"]
            }),
        )?,
        build_one(
            TOOL_CALENDAR,
            "Propose a calendar event for the user to confirm. This does NOT write the event — the user will see a Create/Cancel card and decide. Use when the user mentions a specific clock time. Speak as a suggestion, not a fait accompli.",
            calendar_tool_schema(),
        )?,
        build_one(
            TOOL_LOG_SLEEP_RECORD,
            "Log a past night of sleep on the user's behalf. Commits immediately — speak as though it's done (\"Logged last night: 11:30 → 7:05, quality 3.\"). Use when the user wants to backfill a missing night (\"I forgot to log last night, I slept 10:30 to 6:45 quality 4\") or note that they slept badly without going into the Sleep tab. Defaults the target_bedtime / target_wake_time from the user's saved preferences server-side.",
            sleep_tool_schema(),
        )?,
        build_one(
            TOOL_CREATE_ALARM,
            "Propose a wake-up alarm for the user to confirm. This does NOT create the alarm — the user will see a Create/Cancel card and decide. Use when the user wants to set an alarm (\"set a 6:30am alarm tomorrow with math\", \"wake me at 7 weekdays\"). Speak as a draft pending user confirmation. Mission kinds: \"none\" | \"math\" | \"shake\" | \"photo\" — pick the user's preferred one if they mention it, default to \"math\" otherwise.",
            alarm_tool_schema(),
        )?,
    ];

    let mut builder = ToolConfiguration::builder();
    for t in tools {
        builder = builder.tools(t);
    }
    builder.build().map_err(|e| {
        tracing::error!("chat tool configuration build failed: {}", e);
        AppError::new(StatusCode::INTERNAL_SERVER_ERROR, "AI tool build failed")
    })
}

/// Builds the "today + last 3 days" snapshot card. Rendered fresh per
/// chat turn and never cached. Target size ~300 tokens.
async fn build_context_card(
    pool: &PgPool,
    user_id: Uuid,
    now_local: &str,
) -> Result<String, AppError> {
    let (today_local, offset_str) = match DateTime::parse_from_rfc3339(now_local) {
        Ok(dt) => (dt.date_naive(), dt.offset().to_string()),
        Err(_) => (Utc::now().date_naive(), "+00:00".to_string()),
    };
    let week_start_utc = Utc::now() - Duration::days(7);

    // Sleep — last 3 nights.
    let sleep_rows: Vec<(DateTime<Utc>, DateTime<Utc>, i16, i32)> = sqlx::query_as(
        "SELECT actual_bedtime, actual_wake_time, quality_rating, phone_pickups
           FROM sleep_records
          WHERE user_id = $1
          ORDER BY actual_bedtime DESC
          LIMIT 3",
    )
    .bind(user_id)
    .fetch_all(pool)
    .await?;

    // Focus — last 7 days aggregate.
    let focus: (Option<i64>, Option<i64>, Option<i64>) = sqlx::query_as(
        "SELECT
            COUNT(*)::BIGINT,
            COUNT(*) FILTER (WHERE completed)::BIGINT,
            COALESCE(SUM(duration_minutes) FILTER (WHERE completed), 0)::BIGINT
         FROM productivity_sessions
         WHERE user_id = $1 AND started_at >= $2",
    )
    .bind(user_id)
    .bind(week_start_utc)
    .fetch_one(pool)
    .await?;

    // Today's checklist.
    let checklist_today: Vec<(Uuid, String, i16, bool, Option<i32>)> = sqlx::query_as(
        "SELECT id, title, priority, completed, estimated_minutes
           FROM checklist_items
          WHERE user_id = $1 AND due_date = $2
          ORDER BY completed ASC, priority DESC, created_at ASC
          LIMIT 10",
    )
    .bind(user_id)
    .bind(today_local)
    .fetch_all(pool)
    .await?;

    // Next 3 upcoming calendar events from today's local-midnight onward.
    let day_start_utc = today_local.and_hms_opt(0, 0, 0).unwrap().and_utc();
    let calendar_next: Vec<(String, DateTime<Utc>, DateTime<Utc>, String, String)> =
        sqlx::query_as(
            "SELECT title, start_time, end_time, category::TEXT, priority::TEXT
               FROM calendar_events
              WHERE user_id = $1 AND start_time >= $2
              ORDER BY start_time ASC
              LIMIT 3",
        )
        .bind(user_id)
        .bind(day_start_utc)
        .fetch_all(pool)
        .await?;

    // Render.
    let mut out = String::with_capacity(800);
    out.push_str("### Context snapshot — ");
    out.push_str(&today_local.to_string());
    out.push_str(" (user offset ");
    out.push_str(&offset_str);
    out.push_str(")\n\n");

    out.push_str("**Sleep — last 3 nights (newest first)**\n");
    if sleep_rows.is_empty() {
        out.push_str("(no sleep records yet)\n");
    } else {
        for (bedtime, wake, quality, pickups) in &sleep_rows {
            let minutes = wake.signed_duration_since(*bedtime).num_minutes().max(0);
            let h = minutes / 60;
            let m = minutes % 60;
            out.push_str(&format!(
                "- {} → {} ({}h{:02}, quality {}, {} pickups)\n",
                bedtime.format("%Y-%m-%d %H:%M"),
                wake.format("%H:%M"),
                h,
                m,
                quality,
                pickups
            ));
        }
    }
    out.push('\n');

    out.push_str(&format!(
        "**Focus, last 7 days:** {} sessions ({} completed), {} minutes focused.\n\n",
        focus.0.unwrap_or(0),
        focus.1.unwrap_or(0),
        focus.2.unwrap_or(0),
    ));

    out.push_str("**Today's checklist**\n");
    if checklist_today.is_empty() {
        out.push_str("(no items due today)\n");
    } else {
        for (id, title, priority, completed, est) in &checklist_today {
            let mark = if *completed { "✓" } else { "·" };
            let est_str = est.map(|m| format!(", ~{}m", m)).unwrap_or_default();
            out.push_str(&format!(
                "- {} {} (id={}, priority={}{})\n",
                mark, title, id, priority, est_str
            ));
        }
    }
    out.push('\n');

    out.push_str("**Next 3 calendar events**\n");
    if calendar_next.is_empty() {
        out.push_str("(none scheduled)\n");
    } else {
        for (title, start, end, category, priority) in &calendar_next {
            out.push_str(&format!(
                "- {} → {} — {} ({}, {})\n",
                start.format("%Y-%m-%d %H:%M"),
                end.format("%H:%M"),
                title,
                category,
                priority
            ));
        }
    }
    out.push('\n');

    out.push_str(&format!("User local time: {}\n", now_local));

    Ok(out)
}

async fn run_chat_tool(
    state: &AppState,
    user_id: Uuid,
    tu: &ToolUseBlock,
    now_local: &str,
) -> ToolRunOutcome {
    let tool_name = tu.name().to_string();
    let tool_use_id = tu.tool_use_id().to_string();
    let input = document_to_json(tu.input());

    let dispatched: Result<ToolRunOutcome, String> = match tool_name.as_str() {
        TOOL_GET_TODAY_SUMMARY => {
            handle_get_today_summary(state, user_id, &tool_use_id, &tool_name, now_local).await
        }
        TOOL_GET_SLEEP_HISTORY => {
            handle_get_sleep_history(state, user_id, &tool_use_id, &tool_name, &input).await
        }
        TOOL_GET_FOCUS_HISTORY => {
            handle_get_focus_history(state, user_id, &tool_use_id, &tool_name, &input).await
        }
        TOOL_GET_CALENDAR_EVENTS => {
            handle_get_calendar_events(state, user_id, &tool_use_id, &tool_name, &input).await
        }
        TOOL_GET_CHECKLIST => {
            handle_get_checklist(state, user_id, &tool_use_id, &tool_name, &input).await
        }
        TOOL_CHECKLIST => {
            handle_create_checklist_item(state, user_id, &tool_use_id, &tool_name, &input).await
        }
        TOOL_COMPLETE_CHECKLIST_ITEM => {
            handle_complete_checklist_item(state, user_id, &tool_use_id, &tool_name, &input).await
        }
        TOOL_CALENDAR => {
            handle_propose_calendar_event(&tool_use_id, &tool_name, &input).await
        }
        TOOL_LOG_SLEEP_RECORD => {
            handle_log_sleep_record(state, user_id, &tool_use_id, &tool_name, &input).await
        }
        TOOL_CREATE_ALARM => {
            handle_propose_alarm(&tool_use_id, &tool_name, &input).await
        }
        other => Err(format!("Unknown tool: {}", other)),
    };

    match dispatched {
        Ok(o) => o,
        Err(msg) => ToolRunOutcome {
            payload_for_model: json!({ "error": msg }).to_string(),
            bedrock_status: ToolResultStatus::Error,
            surface: ToolInvocationSurface {
                id: tool_use_id,
                name: tool_name,
                status: "error".to_string(),
                summary: msg,
                committed: false,
                committed_resource: None,
                proposed_event: None,
                proposed_alarm: None,
            },
        },
    }
}

async fn handle_get_today_summary(
    state: &AppState,
    user_id: Uuid,
    tool_use_id: &str,
    tool_name: &str,
    now_local: &str,
) -> Result<ToolRunOutcome, String> {
    let card = build_context_card(&state.pool, user_id, now_local)
        .await
        .map_err(|e| format!("snapshot failed: {}", e.message))?;
    Ok(ToolRunOutcome {
        payload_for_model: card,
        bedrock_status: ToolResultStatus::Success,
        surface: ToolInvocationSurface {
            id: tool_use_id.to_string(),
            name: tool_name.to_string(),
            status: "ok".to_string(),
            summary: "Refreshed today's snapshot".to_string(),
            committed: false,
            committed_resource: None,
            proposed_event: None,
            proposed_alarm: None,
        },
    })
}

async fn handle_get_sleep_history(
    state: &AppState,
    user_id: Uuid,
    tool_use_id: &str,
    tool_name: &str,
    input: &serde_json::Value,
) -> Result<ToolRunOutcome, String> {
    let days = input
        .get("days")
        .and_then(|v| v.as_i64())
        .ok_or_else(|| "missing days".to_string())?;
    let days = days.clamp(1, 30);
    let since = Utc::now() - Duration::days(days);
    let rows: Vec<(Uuid, DateTime<Utc>, DateTime<Utc>, i16, i32)> = sqlx::query_as(
        "SELECT id, actual_bedtime, actual_wake_time, quality_rating, phone_pickups
           FROM sleep_records
          WHERE user_id = $1 AND actual_bedtime >= $2
          ORDER BY actual_bedtime DESC",
    )
    .bind(user_id)
    .bind(since)
    .fetch_all(&state.pool)
    .await
    .map_err(|e| format!("db error: {}", e))?;
    let serializable: Vec<_> = rows
        .iter()
        .map(|(id, bed, wake, q, p)| {
            let mins = wake.signed_duration_since(*bed).num_minutes().max(0);
            json!({
                "id": id,
                "bedtime": bed.to_rfc3339(),
                "wake_time": wake.to_rfc3339(),
                "duration_minutes": mins,
                "quality": q,
                "phone_pickups": p,
            })
        })
        .collect();
    let payload = json!({ "days": days, "records": serializable }).to_string();
    let summary = if rows.is_empty() {
        format!("No sleep records in the last {} days", days)
    } else {
        format!("Pulled {} nights", rows.len())
    };
    Ok(ToolRunOutcome {
        payload_for_model: payload,
        bedrock_status: ToolResultStatus::Success,
        surface: ToolInvocationSurface {
            id: tool_use_id.to_string(),
            name: tool_name.to_string(),
            status: "ok".to_string(),
            summary,
            committed: false,
            committed_resource: None,
            proposed_event: None,
            proposed_alarm: None,
        },
    })
}

async fn handle_get_focus_history(
    state: &AppState,
    user_id: Uuid,
    tool_use_id: &str,
    tool_name: &str,
    input: &serde_json::Value,
) -> Result<ToolRunOutcome, String> {
    let days = input
        .get("days")
        .and_then(|v| v.as_i64())
        .ok_or_else(|| "missing days".to_string())?;
    let days = days.clamp(1, 30);
    let since = Utc::now() - Duration::days(days);
    let rows: Vec<(chrono::NaiveDate, i64, i64)> = sqlx::query_as(
        "SELECT (started_at AT TIME ZONE 'UTC')::DATE AS day,
                COUNT(*) FILTER (WHERE completed)::BIGINT AS completed_count,
                COALESCE(SUM(duration_minutes) FILTER (WHERE completed), 0)::BIGINT AS total_minutes
           FROM productivity_sessions
          WHERE user_id = $1 AND started_at >= $2
          GROUP BY day
          ORDER BY day DESC",
    )
    .bind(user_id)
    .bind(since)
    .fetch_all(&state.pool)
    .await
    .map_err(|e| format!("db error: {}", e))?;
    let serializable: Vec<_> = rows
        .iter()
        .map(|(day, count, minutes)| {
            json!({
                "date": day.to_string(),
                "completed_sessions": count,
                "focus_minutes": minutes,
            })
        })
        .collect();
    let summary = if rows.is_empty() {
        format!("No focus sessions in the last {} days", days)
    } else {
        format!("Pulled focus for {} days", rows.len())
    };
    Ok(ToolRunOutcome {
        payload_for_model: json!({ "days": days, "by_day": serializable }).to_string(),
        bedrock_status: ToolResultStatus::Success,
        surface: ToolInvocationSurface {
            id: tool_use_id.to_string(),
            name: tool_name.to_string(),
            status: "ok".to_string(),
            summary,
            committed: false,
            committed_resource: None,
            proposed_event: None,
            proposed_alarm: None,
        },
    })
}

async fn handle_get_calendar_events(
    state: &AppState,
    user_id: Uuid,
    tool_use_id: &str,
    tool_name: &str,
    input: &serde_json::Value,
) -> Result<ToolRunOutcome, String> {
    let start_s = input
        .get("start_date")
        .and_then(|v| v.as_str())
        .ok_or_else(|| "missing start_date".to_string())?;
    let end_s = input
        .get("end_date")
        .and_then(|v| v.as_str())
        .ok_or_else(|| "missing end_date".to_string())?;
    let start_d: chrono::NaiveDate = start_s
        .parse()
        .map_err(|_| format!("bad start_date: {}", start_s))?;
    let end_d: chrono::NaiveDate = end_s
        .parse()
        .map_err(|_| format!("bad end_date: {}", end_s))?;
    if end_d < start_d {
        return Err("end_date is before start_date".to_string());
    }
    if (end_d - start_d).num_days() > CALENDAR_RANGE_MAX_DAYS {
        return Err(format!(
            "range exceeds {} days; pick a smaller window",
            CALENDAR_RANGE_MAX_DAYS
        ));
    }
    let start_utc = start_d.and_hms_opt(0, 0, 0).unwrap().and_utc();
    let end_utc = end_d.and_hms_opt(23, 59, 59).unwrap().and_utc();
    let rows: Vec<(Uuid, String, DateTime<Utc>, DateTime<Utc>, String, String)> = sqlx::query_as(
        "SELECT id, title, start_time, end_time, category::TEXT, priority::TEXT
           FROM calendar_events
          WHERE user_id = $1
            AND start_time >= $2
            AND start_time <= $3
          ORDER BY start_time ASC",
    )
    .bind(user_id)
    .bind(start_utc)
    .bind(end_utc)
    .fetch_all(&state.pool)
    .await
    .map_err(|e| format!("db error: {}", e))?;
    let serializable: Vec<_> = rows
        .iter()
        .map(|(id, title, start, end, category, priority)| {
            json!({
                "id": id,
                "title": title,
                "start": start.to_rfc3339(),
                "end": end.to_rfc3339(),
                "category": category,
                "priority": priority,
            })
        })
        .collect();
    let summary = if rows.is_empty() {
        format!("No events from {} to {}", start_d, end_d)
    } else {
        format!("Found {} events", rows.len())
    };
    Ok(ToolRunOutcome {
        payload_for_model: json!({
            "start_date": start_s,
            "end_date": end_s,
            "events": serializable,
        })
        .to_string(),
        bedrock_status: ToolResultStatus::Success,
        surface: ToolInvocationSurface {
            id: tool_use_id.to_string(),
            name: tool_name.to_string(),
            status: "ok".to_string(),
            summary,
            committed: false,
            committed_resource: None,
            proposed_event: None,
            proposed_alarm: None,
        },
    })
}

async fn handle_get_checklist(
    state: &AppState,
    user_id: Uuid,
    tool_use_id: &str,
    tool_name: &str,
    input: &serde_json::Value,
) -> Result<ToolRunOutcome, String> {
    let date_s = input
        .get("date")
        .and_then(|v| v.as_str())
        .ok_or_else(|| "missing date".to_string())?;
    let date: chrono::NaiveDate = date_s
        .parse()
        .map_err(|_| format!("bad date: {}", date_s))?;
    let rows: Vec<(
        Uuid,
        String,
        Option<String>,
        i16,
        bool,
        Option<i32>,
    )> = sqlx::query_as(
        "SELECT id, title, description, priority, completed, estimated_minutes
           FROM checklist_items
          WHERE user_id = $1 AND due_date = $2
          ORDER BY completed ASC, priority DESC, created_at ASC",
    )
    .bind(user_id)
    .bind(date)
    .fetch_all(&state.pool)
    .await
    .map_err(|e| format!("db error: {}", e))?;
    let serializable: Vec<_> = rows
        .iter()
        .map(|(id, title, desc, priority, completed, est)| {
            json!({
                "id": id,
                "title": title,
                "description": desc,
                "priority": priority,
                "completed": completed,
                "estimated_minutes": est,
            })
        })
        .collect();
    let summary = if rows.is_empty() {
        format!("No checklist items on {}", date_s)
    } else {
        format!("{} items on {}", rows.len(), date_s)
    };
    Ok(ToolRunOutcome {
        payload_for_model: json!({ "date": date_s, "items": serializable }).to_string(),
        bedrock_status: ToolResultStatus::Success,
        surface: ToolInvocationSurface {
            id: tool_use_id.to_string(),
            name: tool_name.to_string(),
            status: "ok".to_string(),
            summary,
            committed: false,
            committed_resource: None,
            proposed_event: None,
            proposed_alarm: None,
        },
    })
}

async fn handle_create_checklist_item(
    state: &AppState,
    user_id: Uuid,
    tool_use_id: &str,
    tool_name: &str,
    input: &serde_json::Value,
) -> Result<ToolRunOutcome, String> {
    // Re-use the same parsed shape as parse_event so validation stays
    // in lockstep.
    let fields: ParsedChecklistFields =
        serde_json::from_value(input.clone()).map_err(|e| format!("bad input: {}", e))?;
    let title = fields.title.trim().to_string();
    if title.is_empty() {
        return Err("title is empty".to_string());
    }
    let priority = fields.priority.unwrap_or(1);
    if !(0..=2).contains(&priority) {
        return Err(format!("priority {} out of 0..=2", priority));
    }
    let item: crate::models::checklist::ChecklistItem = sqlx::query_as(
        "INSERT INTO checklist_items
            (user_id, title, description, due_date, estimated_minutes, priority,
             recurrence_days_mask, show_until_due)
         VALUES ($1, $2, $3, $4, $5, $6, 0, false)
         RETURNING *",
    )
    .bind(user_id)
    .bind(&title)
    .bind(fields.description.as_deref().map(str::trim))
    .bind(fields.due_date)
    .bind(fields.estimated_minutes)
    .bind(priority)
    .fetch_one(&state.pool)
    .await
    .map_err(|e| format!("db error: {}", e))?;

    state
        .events
        .publish(user_id, crate::event_bus::SyncEvent::ChecklistCreated(item.clone()));

    let payload = json!({
        "created_id": item.id,
        "title": item.title,
        "due_date": item.due_date,
    })
    .to_string();
    Ok(ToolRunOutcome {
        payload_for_model: payload,
        bedrock_status: ToolResultStatus::Success,
        surface: ToolInvocationSurface {
            id: tool_use_id.to_string(),
            name: tool_name.to_string(),
            status: "ok".to_string(),
            summary: format!("Added: {}", item.title),
            committed: true,
            committed_resource: Some(CommittedResource {
                kind: "checklist".to_string(),
                id: item.id,
                title: Some(item.title.clone()),
                due_date: Some(item.due_date),
            }),
            proposed_event: None,
            proposed_alarm: None,
        },
    })
}

async fn handle_complete_checklist_item(
    state: &AppState,
    user_id: Uuid,
    tool_use_id: &str,
    tool_name: &str,
    input: &serde_json::Value,
) -> Result<ToolRunOutcome, String> {
    let id_s = input
        .get("item_id")
        .and_then(|v| v.as_str())
        .ok_or_else(|| "missing item_id".to_string())?;
    let item_id: Uuid = id_s
        .parse()
        .map_err(|_| format!("bad item_id: {}", id_s))?;
    let item: Option<crate::models::checklist::ChecklistItem> = sqlx::query_as(
        "UPDATE checklist_items
            SET completed = TRUE, completed_at = NOW(), updated_at = NOW()
          WHERE id = $1 AND user_id = $2
        RETURNING *",
    )
    .bind(item_id)
    .bind(user_id)
    .fetch_optional(&state.pool)
    .await
    .map_err(|e| format!("db error: {}", e))?;
    let item = item.ok_or_else(|| format!("no checklist item with id {}", id_s))?;
    state
        .events
        .publish(user_id, crate::event_bus::SyncEvent::ChecklistUpdated(item.clone()));
    let payload = json!({
        "completed_id": item.id,
        "title": item.title,
    })
    .to_string();
    Ok(ToolRunOutcome {
        payload_for_model: payload,
        bedrock_status: ToolResultStatus::Success,
        surface: ToolInvocationSurface {
            id: tool_use_id.to_string(),
            name: tool_name.to_string(),
            status: "ok".to_string(),
            summary: format!("Marked done: {}", item.title),
            committed: true,
            committed_resource: Some(CommittedResource {
                kind: "checklist_complete".to_string(),
                id: item.id,
                title: Some(item.title.clone()),
                due_date: None,
            }),
            proposed_event: None,
            proposed_alarm: None,
        },
    })
}

async fn handle_propose_calendar_event(
    tool_use_id: &str,
    tool_name: &str,
    input: &serde_json::Value,
) -> Result<ToolRunOutcome, String> {
    let fields: ParsedCalendarFields =
        serde_json::from_value(input.clone()).map_err(|e| format!("bad input: {}", e))?;
    if fields.title.trim().is_empty() {
        return Err("title is empty".to_string());
    }
    if fields.end_time <= fields.start_time {
        return Err("end_time must be after start_time".to_string());
    }
    // Server NEVER writes here. The model is told this in the prompt, and
    // we reinforce it in the tool result so the model phrases the reply as
    // a draft pending user confirmation.
    let summary = format!(
        "Proposed: {} {} → {}",
        fields.title,
        fields.start_time.format("%Y-%m-%d %H:%M"),
        fields.end_time.format("%H:%M"),
    );
    let payload = json!({
        "status": "proposed",
        "note": "The user will see a Create/Cancel card. You did NOT add this event — phrase your reply as a draft pending user confirmation.",
        "proposed": &fields,
    })
    .to_string();
    Ok(ToolRunOutcome {
        payload_for_model: payload,
        bedrock_status: ToolResultStatus::Success,
        surface: ToolInvocationSurface {
            id: tool_use_id.to_string(),
            name: tool_name.to_string(),
            status: "proposed".to_string(),
            summary,
            committed: false,
            committed_resource: None,
            proposed_event: Some(fields),
            proposed_alarm: None,
        },
    })
}

/// Auto-commit sleep record. Used by the `log_sleep_record` Coach tool.
/// The user's saved bed/wake-target preferences are pulled from the JSONB
/// `users.preferences` blob and used to default the schema's required
/// fields, with a 23:00 → 07:00 floor if the user hasn't set them yet.
async fn handle_log_sleep_record(
    state: &AppState,
    user_id: Uuid,
    tool_use_id: &str,
    tool_name: &str,
    input: &serde_json::Value,
) -> Result<ToolRunOutcome, String> {
    let actual_bedtime: chrono::DateTime<Utc> = input
        .get("actual_bedtime")
        .and_then(|v| v.as_str())
        .ok_or_else(|| "missing actual_bedtime".to_string())
        .and_then(|s| chrono::DateTime::parse_from_rfc3339(s).map_err(|e| format!("bad actual_bedtime: {}", e)))?
        .with_timezone(&Utc);
    let actual_wake_time: chrono::DateTime<Utc> = input
        .get("actual_wake_time")
        .and_then(|v| v.as_str())
        .ok_or_else(|| "missing actual_wake_time".to_string())
        .and_then(|s| chrono::DateTime::parse_from_rfc3339(s).map_err(|e| format!("bad actual_wake_time: {}", e)))?
        .with_timezone(&Utc);
    if actual_wake_time <= actual_bedtime {
        return Err("actual_wake_time must be after actual_bedtime".to_string());
    }
    let quality_rating: i16 = input
        .get("quality_rating")
        .and_then(|v| v.as_i64())
        .map(|n| n as i16)
        .ok_or_else(|| "missing quality_rating".to_string())?;
    if !(1..=5).contains(&quality_rating) {
        return Err(format!("quality_rating {} out of 1..=5", quality_rating));
    }
    let phone_pickups: i32 = input
        .get("phone_pickups")
        .and_then(|v| v.as_i64())
        .map(|n| n.max(0) as i32)
        .unwrap_or(0);
    let notes: Option<String> = input
        .get("notes")
        .and_then(|v| v.as_str())
        .map(|s| s.trim().to_string())
        .filter(|s| !s.is_empty());

    // Default target_bedtime / target_wake_time from the user's prefs.
    // The `preferences` JSONB blob carries the keys when the user has set
    // them via the Sleep tab's preferences screen; fall back to 23:00 /
    // 07:00 when the user hasn't customised them yet.
    let prefs: Option<serde_json::Value> = sqlx::query_scalar(
        "SELECT preferences FROM users WHERE id = $1",
    )
    .bind(user_id)
    .fetch_optional(&state.pool)
    .await
    .map_err(|e| format!("db error reading prefs: {}", e))?;
    let (target_bedtime, target_wake_time) = extract_sleep_targets(prefs.as_ref());

    let record: crate::models::sleep::SleepRecord = sqlx::query_as(
        "INSERT INTO sleep_records
            (user_id, target_bedtime, target_wake_time,
             actual_bedtime, actual_wake_time, quality_rating,
             phone_pickups, total_phone_minutes, notes)
         VALUES ($1, $2, $3, $4, $5, $6, $7, NULL, $8)
         RETURNING *",
    )
    .bind(user_id)
    .bind(target_bedtime)
    .bind(target_wake_time)
    .bind(actual_bedtime)
    .bind(actual_wake_time)
    .bind(quality_rating)
    .bind(phone_pickups)
    .bind(notes.as_deref())
    .fetch_one(&state.pool)
    .await
    .map_err(|e| format!("db error: {}", e))?;

    state
        .events
        .publish(user_id, crate::event_bus::SyncEvent::SleepCreated(record.clone()));

    let minutes = actual_wake_time.signed_duration_since(actual_bedtime).num_minutes().max(0);
    let h = minutes / 60;
    let m = minutes % 60;
    let payload = json!({
        "logged_id": record.id,
        "duration_minutes": minutes,
        "quality": quality_rating,
        "phone_pickups": phone_pickups,
    })
    .to_string();
    Ok(ToolRunOutcome {
        payload_for_model: payload,
        bedrock_status: ToolResultStatus::Success,
        surface: ToolInvocationSurface {
            id: tool_use_id.to_string(),
            name: tool_name.to_string(),
            status: "ok".to_string(),
            summary: format!("Logged sleep: {}h{:02}, quality {}", h, m, quality_rating),
            committed: true,
            committed_resource: Some(CommittedResource {
                kind: "sleep_record".to_string(),
                id: record.id,
                title: Some(format!(
                    "{} → {}",
                    actual_bedtime.format("%H:%M"),
                    actual_wake_time.format("%H:%M")
                )),
                due_date: None,
            }),
            proposed_event: None,
            proposed_alarm: None,
        },
    })
}

/// Pulls `target_bedtime` / `target_wake_time` out of the user's
/// preferences JSONB. Accepts the same `"HH:MM"` shape the mobile prefs
/// screen writes. Falls back to 23:00 / 07:00 if either is missing or
/// unparseable — the user can re-set their proper targets on the Sleep
/// tab, and this default keeps the row valid in the meantime.
fn extract_sleep_targets(prefs: Option<&serde_json::Value>) -> (chrono::NaiveTime, chrono::NaiveTime) {
    let default_bed = chrono::NaiveTime::from_hms_opt(23, 0, 0).unwrap();
    let default_wake = chrono::NaiveTime::from_hms_opt(7, 0, 0).unwrap();
    let Some(obj) = prefs.and_then(|v| v.as_object()) else {
        return (default_bed, default_wake);
    };
    let parse = |key: &str, fallback: chrono::NaiveTime| -> chrono::NaiveTime {
        obj.get(key)
            .and_then(|v| v.as_str())
            .and_then(|s| chrono::NaiveTime::parse_from_str(s, "%H:%M").ok())
            .unwrap_or(fallback)
    };
    (parse("target_bedtime", default_bed), parse("target_wake_time", default_wake))
}

/// Propose an alarm — server NEVER writes here. Validates the shape, then
/// returns the parsed `ProposedAlarmFields` for the client to render in a
/// Create/Cancel card.
async fn handle_propose_alarm(
    tool_use_id: &str,
    tool_name: &str,
    input: &serde_json::Value,
) -> Result<ToolRunOutcome, String> {
    let trigger_time_local = input
        .get("trigger_time_local")
        .and_then(|v| v.as_str())
        .ok_or_else(|| "missing trigger_time_local".to_string())?
        .to_string();
    // Validate HH:MM.
    chrono::NaiveTime::parse_from_str(&trigger_time_local, "%H:%M")
        .map_err(|_| format!("bad trigger_time_local: {}", trigger_time_local))?;
    let label = input
        .get("label")
        .and_then(|v| v.as_str())
        .map(|s| s.trim().to_string())
        .filter(|s| !s.is_empty());
    let mission_kind = input
        .get("mission_kind")
        .and_then(|v| v.as_str())
        .unwrap_or("math")
        .to_string();
    if !["none", "math", "shake", "photo"].contains(&mission_kind.as_str()) {
        return Err(format!("unknown mission_kind: {}", mission_kind));
    }
    let days_arr = input
        .get("days_of_week")
        .and_then(|v| v.as_array())
        .cloned()
        .unwrap_or_default();
    let days_of_week: i16 = days_arr
        .iter()
        .filter_map(|v| v.as_str())
        .fold(0i16, |acc, day| {
            let bit = match day.to_ascii_lowercase().as_str() {
                "sun" => 1,
                "mon" => 2,
                "tue" => 4,
                "wed" => 8,
                "thu" => 16,
                "fri" => 32,
                "sat" => 64,
                _ => 0,
            };
            acc | bit
        });

    let fields = ProposedAlarmFields {
        trigger_time_local: trigger_time_local.clone(),
        label,
        days_of_week,
        mission_kind: mission_kind.clone(),
    };

    let summary = if days_of_week == 0 {
        format!("Proposed: {} alarm one-shot ({})", trigger_time_local, mission_kind)
    } else {
        format!(
            "Proposed: {} alarm ({} repeats, {})",
            trigger_time_local,
            days_arr.iter().filter_map(|v| v.as_str()).collect::<Vec<_>>().join("/"),
            mission_kind,
        )
    };
    let payload = json!({
        "status": "proposed",
        "note": "The user will see a Create/Cancel card. You did NOT add this alarm — phrase your reply as a draft pending user confirmation.",
        "proposed": &fields,
    })
    .to_string();
    Ok(ToolRunOutcome {
        payload_for_model: payload,
        bedrock_status: ToolResultStatus::Success,
        surface: ToolInvocationSurface {
            id: tool_use_id.to_string(),
            name: tool_name.to_string(),
            status: "proposed".to_string(),
            summary,
            committed: false,
            committed_resource: None,
            proposed_event: None,
            proposed_alarm: Some(fields),
        },
    })
}
