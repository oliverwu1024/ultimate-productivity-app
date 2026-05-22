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

    state.ai.check_quota(&state.pool, user_id).await?;

    let conversation_id = ensure_active_conversation(&state.pool, user_id).await?;
    let history = fetch_recent_messages(&state.pool, conversation_id).await?;
    let (assistant_text, usage) =
        call_chat_model(&state.ai, &history, content).await?;

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
    .bind(usage.input as i32)
    .bind(usage.output as i32)
    .fetch_one(&mut *tx)
    .await?;
    sqlx::query("UPDATE ai_conversations SET updated_at = NOW() WHERE id = $1")
        .bind(conversation_id)
        .execute(&mut *tx)
        .await?;
    tx.commit().await?;

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

    Ok(Json(ChatSendResponse {
        user_message: user_msg,
        assistant_message: assistant_msg,
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
