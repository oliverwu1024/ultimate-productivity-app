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
        // §10 — Bedrock Haiku rates a single sleep session (1-5 + 1-line
        // reasoning). Used by the End Sleep dialog to offer an AI-suggested
        // rating alongside the self-rate stars.
        .route("/ai/sleep-rating", post(sleep_rating))
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
    // 2026-06-06 — Pickup behaviour during FOCUS sessions (distinct from
    // sleep-window pickups above). Surfaces "you completed 6 hours of
    // focus but picked up the phone 18 times for 24 min" patterns Sonnet
    // can coach on.
    focus_phone_pickups_total: i64,
    focus_phone_pickup_minutes: i64,
    checklist_items_due: i64,
    checklist_items_completed: i64,
    checklist_completion_pct: i32,
    calendar_events_total: i64,
    calendar_hours_total: f64,
    // §10.8 — On-device-detected sleep audio. Per-night counts roll up so
    // Sonnet can comment on snoring / coughing / sleep-talk trends in the
    // weekly summary.
    snore_events_total: i64,
    nights_with_snoring: i64,
    cough_events_total: i64,
    nights_with_coughing: i64,
    sleep_talk_events_total: i64,
    nights_with_sleep_talk: i64,
    // §9.7 — Focus debrief tag breakdown across the week's completed
    // sessions ("deep_work", "admin", "learning", etc. — set by Haiku
    // post-session). Vec of (tag, count); empty when no debriefs were
    // submitted. Rendered as percentages so Sonnet can comment on how
    // the user actually spent their focus time.
    focus_debrief_breakdown: Vec<(String, i64)>,
}

async fn aggregate_week(pool: &PgPool, user_id: Uuid) -> Result<WeekSummary, AppError> {
    let now = Utc::now();
    let seven_days_ago = now - Duration::days(7);
    let tz_str = crate::tz::fetch_user_tz(pool, user_id).await?;

    // §sleep-day (v2.13.17) — `days_with_sleep_logged` previously counted
    // rows, which double-counted users who logged two sleeps in one
    // calendar day (e.g. a Tue 02:00 bedtime + a Tue 22:00 bedtime both
    // labeled "Tuesday"). Now counts DISTINCT sleep-days using the 6h
    // shift, so the Tue 02:00 bedtime correctly buckets to Monday and the
    // label means what it says.
    let sleep: (Option<i64>, Option<f64>, Option<f64>, Option<i64>, Option<i64>) =
        sqlx::query_as(
            "SELECT
                COUNT(DISTINCT DATE((actual_bedtime - INTERVAL '6 hours') AT TIME ZONE COALESCE(recorded_tz, $3)))::BIGINT,
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
        .bind(&tz_str)
        .fetch_one(pool)
        .await?;

    // Focus sessions aggregate. phone_pickups is the per-session denormalized
    // counter (productivity_sessions column); pickup minutes come from
    // joining phone_pickups so we get real durations instead of just counts.
    let focus: (Option<i64>, Option<i64>, Option<f64>, Option<i64>) = sqlx::query_as(
        "SELECT
            COUNT(*) FILTER (WHERE completed)::BIGINT,
            COALESCE(SUM(duration_minutes) FILTER (WHERE completed), 0)::BIGINT,
            AVG(duration_minutes) FILTER (WHERE completed)::DOUBLE PRECISION,
            COALESCE(SUM(phone_pickups) FILTER (WHERE completed), 0)::BIGINT
         FROM productivity_sessions
         WHERE user_id = $1 AND started_at >= $2",
    )
    .bind(user_id)
    .bind(seven_days_ago)
    .fetch_one(pool)
    .await?;

    // Focus pickup duration — sum across all pickup rows whose parent
    // session falls inside this user's week. Joined via session_id; only
    // counts sessions that completed so the user's "I just ended a
    // session" jitter doesn't double-count.
    let focus_pickup_seconds: (Option<i64>,) = sqlx::query_as(
        "SELECT COALESCE(SUM(pp.duration_seconds), 0)::BIGINT
           FROM phone_pickups pp
           JOIN productivity_sessions ps ON ps.id = pp.session_id
          WHERE pp.user_id = $1
            AND ps.completed = TRUE
            AND ps.started_at >= $2",
    )
    .bind(user_id)
    .bind(seven_days_ago)
    .fetch_one(pool)
    .await?;

    // §9.7 — Debrief tag breakdown across the week's completed sessions.
    // NULL debrief_tag rows (user skipped the prompt) get bucketed under
    // "untagged" so percentages stay grounded in the total session count.
    let debrief_rows: Vec<(String, i64)> = sqlx::query_as(
        "SELECT COALESCE(debrief_tag, 'untagged')::TEXT, COUNT(*)::BIGINT
           FROM productivity_sessions
          WHERE user_id = $1 AND completed = TRUE AND started_at >= $2
          GROUP BY 1
          ORDER BY 2 DESC, 1 ASC",
    )
    .bind(user_id)
    .bind(seven_days_ago)
    .fetch_all(pool)
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

    // §10.8 — Sleep audio aggregate (snore + cough + sleep_talk episodes
    // detected on-device by YAMNet during the past 7 nights). Both
    // per-event totals AND the distinct-nights-affected count, so Sonnet
    // can say "snored on 5 of 7 nights" instead of just "47 snoring
    // episodes". `sleep_record_id` is a stable per-night id from the
    // on-device session, so DISTINCT already counts sleep_days correctly
    // without needing the shift here. Sleep-talk added 2026-06-06 to
    // close a gap where the data was captured but never reached the AI.
    let audio: (
        Option<i64>, Option<i64>,
        Option<i64>, Option<i64>,
        Option<i64>, Option<i64>,
    ) = sqlx::query_as(
        "SELECT
            COUNT(*) FILTER (WHERE event_type = 'snore')::BIGINT,
            COUNT(DISTINCT sleep_record_id) FILTER (WHERE event_type = 'snore')::BIGINT,
            COUNT(*) FILTER (WHERE event_type = 'cough')::BIGINT,
            COUNT(DISTINCT sleep_record_id) FILTER (WHERE event_type = 'cough')::BIGINT,
            COUNT(*) FILTER (WHERE event_type = 'sleep_talk')::BIGINT,
            COUNT(DISTINCT sleep_record_id) FILTER (WHERE event_type = 'sleep_talk')::BIGINT
         FROM sleep_audio_events
         WHERE user_id = $1 AND started_at >= $2",
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

    let focus_pickup_total_seconds = focus_pickup_seconds.0.unwrap_or(0);
    Ok(WeekSummary {
        days_with_sleep_logged: sleep.0.unwrap_or(0),
        avg_sleep_minutes: sleep.1.unwrap_or(0.0).round() as i64,
        avg_sleep_quality: round2(sleep.2.unwrap_or(0.0)),
        nights_under_six_hours: sleep.3.unwrap_or(0),
        total_phone_pickups: sleep.4.unwrap_or(0),
        focus_sessions_completed: focus.0.unwrap_or(0),
        focus_minutes_total: focus.1.unwrap_or(0),
        avg_focus_session_minutes: focus.2.unwrap_or(0.0).round() as i64,
        focus_phone_pickups_total: focus.3.unwrap_or(0),
        focus_phone_pickup_minutes: (focus_pickup_total_seconds + 30) / 60,
        checklist_items_due: cl_due,
        checklist_items_completed: cl_done,
        checklist_completion_pct: completion_pct,
        calendar_events_total: calendar.0.unwrap_or(0),
        calendar_hours_total: round2(calendar.1.unwrap_or(0.0)),
        snore_events_total: audio.0.unwrap_or(0),
        nights_with_snoring: audio.1.unwrap_or(0),
        cough_events_total: audio.2.unwrap_or(0),
        nights_with_coughing: audio.3.unwrap_or(0),
        sleep_talk_events_total: audio.4.unwrap_or(0),
        nights_with_sleep_talk: audio.5.unwrap_or(0),
        focus_debrief_breakdown: debrief_rows,
    })
}

fn round2(x: f64) -> f64 {
    (x * 100.0).round() / 100.0
}

// ── Data card (sent to the model) ─────────────────────────────────────────

fn render_data_card(s: &WeekSummary) -> String {
    let avg_sleep_hours = s.avg_sleep_minutes as f64 / 60.0;
    // §10.8 — Only emit the audio rows when at least one event was captured.
    // Users with audio tracking off have all-zero values, and a zero row
    // gives Sonnet nothing to comment on (and risks invented commentary).
    // Same rule for sleep_talk — Pro-tier opt-in, often zero.
    let audio_section = {
        let mut parts: Vec<String> = Vec::new();
        if s.snore_events_total > 0 {
            parts.push(format!(
                "| Snoring episodes (week total) | {} |\n| Nights with snoring | {} of {} logged |",
                s.snore_events_total, s.nights_with_snoring, s.days_with_sleep_logged,
            ));
        }
        if s.cough_events_total > 0 {
            parts.push(format!(
                "| Coughing episodes (week total) | {} |\n| Nights with coughing | {} of {} logged |",
                s.cough_events_total, s.nights_with_coughing, s.days_with_sleep_logged,
            ));
        }
        if s.sleep_talk_events_total > 0 {
            parts.push(format!(
                "| Sleep-talk episodes (week total) | {} |\n| Nights with sleep-talk | {} of {} logged |",
                s.sleep_talk_events_total, s.nights_with_sleep_talk, s.days_with_sleep_logged,
            ));
        }
        if parts.is_empty() {
            String::new()
        } else {
            format!("{}\n", parts.join("\n"))
        }
    };
    // Focus pickup rows — only render when there were completed sessions
    // AND at least one pickup; otherwise we'd say "0 pickups across 0
    // sessions" which Sonnet sometimes turns into ungrounded coaching.
    let focus_pickup_section = if s.focus_sessions_completed > 0 && s.focus_phone_pickups_total > 0 {
        format!(
            "| Phone pickups during focus | {} times totalling {} min |\n",
            s.focus_phone_pickups_total, s.focus_phone_pickup_minutes,
        )
    } else {
        String::new()
    };
    // §9.7 — Debrief tag breakdown. Skip "untagged" if it's the only
    // bucket (means the user never filled a debrief — nothing to say).
    let debrief_section = {
        let total: i64 = s.focus_debrief_breakdown.iter().map(|(_, c)| *c).sum();
        let tagged_count: i64 = s
            .focus_debrief_breakdown
            .iter()
            .filter(|(t, _)| t != "untagged")
            .map(|(_, c)| *c)
            .sum();
        if total == 0 || tagged_count == 0 {
            String::new()
        } else {
            let lines: Vec<String> = s
                .focus_debrief_breakdown
                .iter()
                .map(|(tag, c)| {
                    let pct = ((*c as f64 / total as f64) * 100.0).round() as i64;
                    format!("| Focus tag — {} | {} of {} ({}%) |", tag, c, total, pct)
                })
                .collect();
            format!("{}\n", lines.join("\n"))
        }
    };
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
{}{}| Checklist items due | {} |
| Checklist items completed | {} |
| Checklist completion | {}% |
| Calendar events | {} |
| Calendar hours scheduled | {:.2} |
{}"#,
        s.days_with_sleep_logged,
        s.avg_sleep_minutes,
        avg_sleep_hours,
        s.avg_sleep_quality,
        s.nights_under_six_hours,
        s.total_phone_pickups,
        s.focus_sessions_completed,
        s.focus_minutes_total,
        s.avg_focus_session_minutes,
        focus_pickup_section,
        debrief_section,
        s.checklist_items_due,
        s.checklist_items_completed,
        s.checklist_completion_pct,
        s.calendar_events_total,
        s.calendar_hours_total,
        audio_section,
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
8. "Focus tag — untagged" means the user completed those focus sessions but skipped the optional debrief. They are still real, completed focus time — credit them fully. Treat the tagged percentages (e.g. deep_work) as a floor, not a ceiling: the user may simply not have labelled every deep session. Never imply untagged sessions were unfocused, wasted, or less valuable.

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
// §10 — AI sleep rating (Haiku one-shot)
// ──────────────────────────────────────────────────────────────────────────
//
// User just ended a sleep session. The End Sleep dialog passes the session
// stats inline (no DB lookup — events aren't persisted yet) and asks Haiku
// for a 1-5 quality rating + a one-line reason. The user can accept the
// AI suggestion or override with self-rate stars. No DB writes here; the
// returned rating is just a suggestion until the user hits Save.

const SLEEP_RATING_REASONING_MAX_CHARS: usize = 120;

#[derive(Debug, Deserialize)]
struct SleepRatingRequest {
    actual_minutes: i64,
    target_minutes: i64,
    pickup_count: i32,
    pickup_minutes: i32,
    snore_count: i32,
    cough_count: i32,
    // Pro-tier sleep-talk detection (YAMNet Speech class). Optional in
    // the request so older clients that don't yet send the field still
    // get a valid rating; missing → treated as 0. Added 2026-06-06 to
    // close the gap where this was captured client-side but never
    // reached the model.
    #[serde(default)]
    sleep_talk_count: i32,
}

#[derive(Debug, Serialize)]
struct SleepRatingResponse {
    rating: i32,
    reasoning: String,
}

async fn sleep_rating(
    State(state): State<AppState>,
    claims: Claims,
    Json(input): Json<SleepRatingRequest>,
) -> Result<Json<SleepRatingResponse>, AppError> {
    let user_id = parse_user_id(&claims)?;

    if input.actual_minutes < 0 || input.target_minutes <= 0 {
        return Err(AppError::new(
            StatusCode::BAD_REQUEST,
            "actual_minutes / target_minutes invalid",
        ));
    }
    if input.pickup_count < 0
        || input.pickup_minutes < 0
        || input.snore_count < 0
        || input.cough_count < 0
        || input.sleep_talk_count < 0
    {
        return Err(AppError::new(
            StatusCode::BAD_REQUEST,
            "counts must be non-negative",
        ));
    }

    state.ai.check_quota(&state.pool, user_id).await?;

    let (rating, reasoning, usage) = call_sleep_rating_haiku(&state.ai, &input).await?;

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

    Ok(Json(SleepRatingResponse { rating, reasoning }))
}

async fn call_sleep_rating_haiku(
    ai: &crate::ai::AiClient,
    input: &SleepRatingRequest,
) -> Result<(i32, String, CallUsage), AppError> {
    let actual_h = input.actual_minutes / 60;
    let actual_m = input.actual_minutes % 60;
    let target_h = input.target_minutes / 60;
    let target_m = input.target_minutes % 60;

    let system = SystemContentBlock::Text(
        "You rate a single night's sleep on a 1-5 integer scale based on the \
provided stats: total duration vs target, phone pickups during the session, \
and on-device-detected snoring + coughing + sleep-talk episode counts.\n\
\n\
Rating heuristic (informative, not strict):\n\
- 5 = sleep met or exceeded target, ≤ 1 brief pickup, minimal audio events\n\
- 4 = within 30 min of target, ≤ 3 pickups, some snoring / coughing / sleep-talk acceptable\n\
- 3 = noticeable shortfall OR several pickups OR clear snoring / talking\n\
- 2 = significant shortfall (~1-2 h short) OR frequent pickups OR lots of audio events\n\
- 1 = severe shortfall AND/OR many pickups AND/OR extensive audio activity\n\
\n\
Sleep-talk note: occasional episodes are normal; only flag in the reasoning \
if combined with other indicators of disturbed sleep.\n\
\n\
Reply with EXACTLY ONE LINE in this format:\n\
RATING|REASONING\n\
\n\
Where:\n\
- RATING is a single integer from 1 to 5 (no decimals, no text)\n\
- REASONING is one short sentence ≤ 100 chars explaining the rating\n\
- The pipe character `|` is the only separator\n\
\n\
Examples:\n\
4|Solid 7h sleep with minimal phone use; a few snoring episodes kept it from a 5.\n\
2|Only 4h of sleep with several phone pickups during the session.\n\
\n\
Output ONLY that line. No greeting, no JSON, no quotes, no extra explanation."
            .to_string(),
    );

    let prompt = format!(
        "Slept: {actual_h}h {actual_m}m (target {target_h}h {target_m}m)\n\
Phone pickups: {} times totalling {} minutes\n\
Snoring episodes: {}\n\
Coughing episodes: {}\n\
Sleep-talk episodes: {}",
        input.pickup_count,
        input.pickup_minutes,
        input.snore_count,
        input.cough_count,
        input.sleep_talk_count,
    );

    let user_msg = Message::builder()
        .role(ConversationRole::User)
        .content(ContentBlock::Text(prompt))
        .build()
        .map_err(|e| {
            tracing::error!("sleep_rating user message build failed: {}", e);
            AppError::new(StatusCode::INTERNAL_SERVER_ERROR, "AI request build failed")
        })?;

    let inference = InferenceConfiguration::builder()
        .max_tokens(80)
        .temperature(0.2)
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
            tracing::error!(target: "ai.sleep_rating", "bedrock haiku call failed: {}", detail);
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

    let (rating, reasoning) = parse_sleep_rating_line(&raw);

    let usage_obj = response.usage();
    let call_usage = CallUsage {
        input: usage_obj.map(|u| u.input_tokens()).unwrap_or(0) as i64,
        output: usage_obj.map(|u| u.output_tokens()).unwrap_or(0) as i64,
        cache_read: usage_obj
            .and_then(|u| u.cache_read_input_tokens())
            .unwrap_or(0) as i64,
        cache_write: usage_obj
            .and_then(|u| u.cache_write_input_tokens())
            .unwrap_or(0) as i64,
    };

    ai.sampled_log("sleep_rating.response", &raw);

    Ok((rating, reasoning, call_usage))
}

/// Parse "N|reasoning" from a single line. Lenient — if Haiku drifts off
/// format we clamp to 3 + a generic reason rather than 502 the user.
fn parse_sleep_rating_line(raw: &str) -> (i32, String) {
    let line = raw.trim().lines().next().unwrap_or("").trim();
    let (rating_part, reasoning_part) = match line.split_once('|') {
        Some((r, why)) => (r.trim(), why.trim()),
        None => return (3, "AI rating unavailable — based on your session stats.".to_string()),
    };
    let rating = rating_part
        .chars()
        .filter(|c| c.is_ascii_digit())
        .collect::<String>()
        .parse::<i32>()
        .ok()
        .filter(|r| (1..=5).contains(r))
        .unwrap_or(3);
    let mut reasoning: String = reasoning_part.trim_matches('"').to_string();
    if reasoning.chars().count() > SLEEP_RATING_REASONING_MAX_CHARS {
        reasoning = reasoning
            .chars()
            .take(SLEEP_RATING_REASONING_MAX_CHARS)
            .collect::<String>()
            + "…";
    }
    if reasoning.is_empty() {
        reasoning = "Based on your session stats.".to_string();
    }
    (rating, reasoning)
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
    /// v2.13.4 — Coach-parsed reminder offsets. Forwarded to the client
    /// as part of the proposed_event card; on confirm, the client builds
    /// a CreateCalendarEvent with this same value. Null = use client
    /// default; empty array = explicit no-reminder.
    #[serde(default)]
    reminder_minutes: Option<Vec<i32>>,
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
            },
            "reminder_minutes": {
                "type": ["array", "null"],
                "items": { "type": "integer" },
                "description": "Optional list of minutes-before-start_time to fire reminder notifications, e.g. [60, 5] for 1 hour and 5 minutes before. Set when the user explicitly asks (\"remind me 1 day before\", \"30 min reminder\"). Omit (null) to use the client's default (single 15-min reminder). Use [] for explicit no-reminder when the user says \"no reminder\". Common values: 5, 15, 30, 60, 120, 240, 1440 (1 day), 2880 (2 days), 10080 (1 week)."
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
    // §i18n (v2.13.9) — Per-day buckets use the user's local date, not UTC.
    // §sleep-day (v2.13.17) — Sleep rows now bucket by sleep_day (bedtime
    // − 6h), so a Tue 02:00 bedtime correctly counts as Monday's night.
    // The anomaly model looks for "3 nights in a row of <5h" — sleep_day
    // bucketing is what makes that streak detection accurate. Focus
    // sessions keep wall-clock-date bucketing because a focus session
    // belongs to the day it started, not the night before.
    let tz_str = crate::tz::fetch_user_tz(pool, user_id).await?;
    let today = crate::tz::user_today(&tz_str);
    let start = today - Duration::days(ANOMALY_LOOKBACK_DAYS - 1);

    // Bedtime UTC window that covers the requested sleep-day range. Wider
    // than `start::DATE` because a sleep with sleep_day = start can have
    // a bedtime up to 6h before start's local midnight in UTC terms.
    let (sleep_window_start, sleep_window_end) =
        crate::tz::sleep_day_window_utc(&tz_str, start, today);

    // Per-day sleep (grouped by sleep_day = (bedtime − 6h) in user tz).
    let sleep: Vec<(chrono::NaiveDate, Option<f64>, Option<f64>, Option<i64>)> =
        sqlx::query_as(
            "SELECT
                DATE((actual_bedtime - INTERVAL '6 hours') AT TIME ZONE COALESCE(recorded_tz, $4)) AS day,
                AVG(EXTRACT(EPOCH FROM (actual_wake_time - actual_bedtime))/60.0)::DOUBLE PRECISION,
                AVG(quality_rating)::DOUBLE PRECISION,
                COALESCE(SUM(phone_pickups), 0)::BIGINT
             FROM sleep_records
             WHERE user_id = $1 AND actual_bedtime >= $2 AND actual_bedtime < $3
             GROUP BY day",
        )
        .bind(user_id)
        .bind(sleep_window_start)
        .bind(sleep_window_end)
        .bind(&tz_str)
        .fetch_all(pool)
        .await?;

    // Per-day focus minutes (grouped by user-local started_at date).
    let focus: Vec<(chrono::NaiveDate, Option<i64>)> = sqlx::query_as(
        "SELECT
            DATE(started_at AT TIME ZONE COALESCE(recorded_tz, $3)) AS day,
            COALESCE(SUM(duration_minutes) FILTER (WHERE completed), 0)::BIGINT
         FROM productivity_sessions
         WHERE user_id = $1 AND started_at >= $2::DATE
         GROUP BY day",
    )
    .bind(user_id)
    .bind(start)
    .bind(&tz_str)
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
    /// §clarify — when the model asked the user to disambiguate (e.g. "past
    /// month"), these are the option labels for tappable chips. None on normal
    /// turns. Tapping a chip sends that label as the next user message.
    #[serde(skip_serializing_if = "Option::is_none")]
    clarification_options: Option<Vec<String>>,
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
    let clarification_options: Option<Vec<String>>;
    let total_input: i64;
    let total_output: i64;
    if tools_enabled {
        let outcome =
            call_chat_model_with_tools(&state, user_id, &history, content, &now_local).await?;
        assistant_text = outcome.text;
        tool_invocations = outcome.invocations;
        clarification_options = outcome.clarification_options;
        total_input = outcome.total_input_tokens;
        total_output = outcome.total_output_tokens;
        // `record_usage` was already called per-iteration inside the loop;
        // nothing left to do quota-side here.
    } else {
        let (text, usage) = call_chat_model(&state.ai, &history, content).await?;
        assistant_text = text;
        tool_invocations = Vec::new();
        clarification_options = None;
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
        clarification_options,
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
/// §audit-2 — calendar-period reads. `get_sleep_history(days=7)` returns a
/// rolling 7-day window which is NOT "last week" — when the user asks
/// "how did I sleep last week", the model should use these tools instead.
const TOOL_GET_SLEEP_PERIOD: &str = "get_sleep_period";
const TOOL_GET_FOCUS_PERIOD: &str = "get_focus_period";
// §audio-times — per-event snore/cough/sleep-talk timestamps for ONE night.
// The history/period tools carry the COUNTS ("how much"); this carries the
// TIMES ("when did I cough"), keyed by the sleep_record_id those tools return.
const TOOL_GET_SLEEP_AUDIO_EVENTS: &str = "get_sleep_audio_events";
// §9.7 / 2026-06-06 — Per-session detail tool so the model can see what
// the user actually wrote in the debrief ("polished slides", "ran lab
// stats") and the per-session pickup count without us bloating the
// inline context card with it. Use it when the user asks "what did I
// focus on last week" / "show me my recent sessions" / "how distracted
// was I yesterday".
const TOOL_GET_FOCUS_SESSION_DETAIL: &str = "get_focus_session_detail";
/// §clarify — terminal tool: when the user's request is genuinely ambiguous
/// (e.g. "past month"), the model writes its question AND calls this with the
/// option labels. The loop surfaces them as tappable chips and ends the turn,
/// so the user's tap drives the next message — the model must not guess.
const TOOL_ASK_CLARIFICATION: &str = "ask_clarification";
// TOOL_CALENDAR and TOOL_CHECKLIST are defined for parse_event above and
// reused here verbatim — the schema is identical; the only difference is
// the handler (propose vs. auto-commit).

struct ChatToolLoopOutcome {
    text: String,
    invocations: Vec<ToolInvocationSurface>,
    clarification_options: Option<Vec<String>>,
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
- get_sleep_history(days) — last N nights with bedtime, wake, duration, quality (1..5), phone pickups, AND the on-device snore/cough/sleep-talk episode counts per night. This is a ROLLING N-day window — NOT the same as "last week" or "this month". For named calendar periods, use get_sleep_period.
- get_sleep_period(period) — calendar-period sleep stats + records, including total snore/cough/sleep-talk episodes for the period. Period ∈ {this_week, last_week, this_month, last_month}. ALWAYS use this when the user names a calendar period ("how did I sleep last week", "this month's sleep", etc.). Returns count + averages + the actual nights; if zero records, returns a friendly note (not an error).
- get_sleep_audio_events(sleep_record_id, event_type?) — per-event TIMES for one night's snore/cough/sleep-talk (started_at/ended_at + confidence). Use for "what times did I cough last night" / "when did I snore". Pass the sleep_record_id (the `id` from get_sleep_history, get_sleep_period, or the snapshot's sleep rows), optionally an event_type to filter. Counts ("how MUCH did I snore over a week/month") come from get_sleep_history / get_sleep_period — use this one for WHEN within a single night.
- get_focus_history(days) — rolling N-day focus history. Per-day rollup only — counts + total minutes.
- get_focus_period(period) — calendar-period focus stats. Same rule as get_sleep_period: USE THIS when the user names a calendar period.
- get_focus_session_detail(limit) — per-session detail for the N most recent completed sessions: duration, phone pickups during the session, what the user said they worked on (debrief), and the auto-classified debrief tag. USE THIS when the user asks "what did I work on", "how distracted was that session", or wants commentary on specific sessions — get_focus_history only gives per-day totals. A null/"untagged" debrief_tag just means the user completed that session but skipped the optional debrief — it's still genuine, completed focus time. Count it fully; never dismiss or under-credit untagged sessions, and don't treat a low deep_work share as proof the rest wasn't focused work.
- get_calendar_events(start_date, end_date) — events in a date range. Range capped at 90 days.
- get_checklist(date) — checklist items due on a specific day. Each item includes its id so you can complete it.

PERIOD-QUESTION RULE (this is the most common Coach failure mode — do this right):
- "last week" / "this week" / "this month" / "last month" / "in May" → call get_sleep_period or get_focus_period, NOT get_sleep_history(days=7). A 7-day rolling window is NOT the same as the previous Mon..Sun.
- "the last 10 days" / "this past week" / "the past 30 days" → that IS a rolling window; use get_sleep_history(days=N).
- ASK, don't guess — but ONLY for genuinely vague spans: "past month" / "the past month" / "this past month" / "lately" / "recently" / "how have I been". For one of those, write ONE short question (e.g. "Quick check — which do you mean?") AND call ask_clarification(["This month so far", "All of last month", "Last 30 days"]); their tap becomes the next message. NEVER call ask_clarification for an exact calendar period: "last month", "this month", "last week", "this week", "in May" are unambiguous — answer them directly with get_sleep_period / get_focus_period. ("how is my last month" → just answer last_month; do not ask.)

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

SLEEP LOG vs ALARM (these two tools get confused — be precise):
- The user describing a PAST night ("I slept 11pm to 7am quality 4", "forgot to log last night, 10:30 to 6:45") → log_sleep_record ONLY. The clock times in the user's message are bedtime/wake, not an alarm time.
- The user setting a FUTURE wake-up ("set a 6:30am alarm tomorrow", "wake me at 7 weekdays") → create_alarm ONLY.
- NEVER call both log_sleep_record AND create_alarm in the same turn. If the user mentions sleeping AND wanting to set a wake-up, log the sleep first, then ask a follow-up about the alarm — don't pre-emptively propose one.

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
    let mut clarification_options: Option<Vec<String>> = None;

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

        // §clarify — `ask_clarification` is terminal. The model has asked the
        // user to pick between concrete options and must NOT proceed to guess.
        // Capture its question text + option labels, then end the turn; the
        // user's tap arrives as the next message and drives the real answer.
        if let Some(tu) = tool_uses
            .iter()
            .find(|tu| tu.name() == TOOL_ASK_CLARIFICATION)
        {
            let opts: Vec<String> = document_to_json(tu.input())
                .get("options")
                .and_then(|v| v.as_array())
                .map(|a| {
                    a.iter()
                        .filter_map(|x| x.as_str().map(|s| s.trim().to_string()))
                        .filter(|s| !s.is_empty())
                        .collect()
                })
                .unwrap_or_default();
            let text = message
                .content()
                .iter()
                .find_map(|c| c.as_text().ok())
                .cloned()
                .unwrap_or_default();
            final_text = Some(if text.trim().is_empty() {
                "Which one do you mean?".to_string()
            } else {
                text.trim().to_string()
            });
            if opts.len() >= 2 {
                clarification_options = Some(opts.into_iter().take(4).collect());
            }
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
        clarification_options,
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
            "Fetch the user's sleep records for the last N nights (1..=90 — use 90 for a roughly-three-month view). Each row has bedtime, wake time, duration in minutes, quality rating (1..5), phone pickups, AND the on-device snore / cough / sleep-talk episode COUNTS for that night.",
            json!({
                "type": "object",
                "properties": {
                    "days": {
                        "type": "integer",
                        "minimum": 1,
                        "maximum": 90,
                        "description": "How many days of sleep history to fetch. Cap is 90 — use a higher value for trend questions, smaller for recent recall."
                    }
                },
                "required": ["days"]
            }),
        )?,
        build_one(
            TOOL_GET_FOCUS_HISTORY,
            "Fetch the user's focus-session activity for the last N days (1..=90). Returns per-day completed-session count and total focused minutes.",
            json!({
                "type": "object",
                "properties": {
                    "days": {
                        "type": "integer",
                        "minimum": 1,
                        "maximum": 90,
                        "description": "How many days of focus history to fetch. Cap is 90."
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
            TOOL_GET_SLEEP_PERIOD,
            "Fetch sleep records for a NAMED calendar period (this_week / last_week / this_month / last_month). Use this when the user asks \"how did I sleep last week\" or \"this month\" — `get_sleep_history(days)` returns a rolling N-day window and is NOT the same as \"last week\" (which means previous Mon..Sun). Returns the records in that period plus aggregate stats, including total snore/cough/sleep-talk episodes for the period; if the period had zero records, returns counts of 0 and a friendly note (NOT an error).",
            json!({
                "type": "object",
                "properties": {
                    "period": {
                        "type": "string",
                        "enum": ["this_week", "last_week", "this_month", "last_month"]
                    }
                },
                "required": ["period"]
            }),
        )?,
        build_one(
            TOOL_GET_FOCUS_PERIOD,
            "Fetch focus-session activity for a NAMED calendar period (this_week / last_week / this_month / last_month). Same rules as get_sleep_period — pick this over `get_focus_history(days)` when the user names a calendar period.",
            json!({
                "type": "object",
                "properties": {
                    "period": {
                        "type": "string",
                        "enum": ["this_week", "last_week", "this_month", "last_month"]
                    }
                },
                "required": ["period"]
            }),
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
        build_one(
            TOOL_GET_FOCUS_SESSION_DETAIL,
            "Fetch detailed per-session info for the user's most-recent completed focus sessions: started_at, duration_minutes, phone_pickups (count of times they picked up the phone DURING the session), debrief text (what they said they worked on, may be null if they skipped), and debrief_tag (auto-classified: deep_work / admin / learning / creative / communication / untagged). Use this when the user asks what they worked on, how distracted they were, or wants commentary on a specific session pattern. Cheaper than calling get_focus_history for the same info — that one only returns per-day rollups.",
            json!({
                "type": "object",
                "properties": {
                    "limit": {
                        "type": "integer",
                        "minimum": 1,
                        "maximum": 20,
                        "description": "How many recent completed sessions to return. Cap is 20."
                    }
                },
                "required": ["limit"]
            }),
        )?,
        build_one(
            TOOL_GET_SLEEP_AUDIO_EVENTS,
            "Fetch the per-event TIMES of on-device snore / cough / sleep-talk detections for ONE night. Use for \"what times did I cough last night\", \"when did I snore\". Pass the sleep_record_id (the `id` field returned by get_sleep_history, get_sleep_period, or the snapshot's sleep rows) and optionally an event_type to filter. Returns exact per-type totals plus the events (started_at / ended_at / peak_confidence, oldest→newest), capped at 60 — on a very noisy night the totals stay exact while the event list is truncated. For \"how MUCH did I snore\" over a week/month, use get_sleep_history or get_sleep_period instead (those carry the counts); use THIS tool for WHEN within a single night.",
            json!({
                "type": "object",
                "properties": {
                    "sleep_record_id": {
                        "type": "string",
                        "description": "UUID of the night, from the `id` field of get_sleep_history / get_sleep_period / the snapshot's sleep rows."
                    },
                    "event_type": {
                        "type": "string",
                        "enum": ["snore", "cough", "sleep_talk"],
                        "description": "Optional. Filter to one event type. Omit to return all three."
                    }
                },
                "required": ["sleep_record_id"]
            }),
        )?,
        build_one(
            TOOL_ASK_CLARIFICATION,
            "Ask the user to choose between 2–4 concrete options when their request is genuinely ambiguous — e.g. \"past month\" could mean this month so far, all of last month, or the last 30 days. Write your one-line question as your normal text reply AND call this tool with the option labels; the user gets tappable buttons and their tap becomes the next message. Use ONLY when you truly cannot tell which they mean — never for clear requests. Do NOT call any other (data) tool in the same turn; wait for their pick.",
            json!({
                "type": "object",
                "properties": {
                    "options": {
                        "type": "array",
                        "items": { "type": "string" },
                        "minItems": 2,
                        "maxItems": 4,
                        "description": "Short tappable labels, e.g. [\"This month so far\", \"All of last month\", \"Last 30 days\"]. Each becomes a button; tapping sends that exact text as the user's next message."
                    }
                },
                "required": ["options"]
            }),
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
/// Coach timestamps must be the user's LOCAL wall clock. The model reads tool
/// payloads + the context card verbatim and does NOT convert from UTC — given
/// raw UTC "13:56" it told the user they sleep at "1:56pm" and invented a
/// reversed-schedule narrative. Convert every instant the coach sees with the
/// user's stored IANA timezone (DST-correct across historical rows) first.
#[inline]
fn coach_local(dt: &DateTime<Utc>, tz: &chrono_tz::Tz) -> DateTime<chrono_tz::Tz> {
    dt.with_timezone(tz)
}

/// §tz-anchor — the tz a sleep/session row should render in: its own
/// `recorded_tz` (the zone it was logged in) if present, else the request-level
/// fallback (the user's current tz). Keeps a past record's wall-clock stable
/// after the user moves zones.
#[inline]
fn anchor_tz(recorded: &Option<String>, fallback: chrono_tz::Tz) -> chrono_tz::Tz {
    recorded
        .as_deref()
        .map(crate::tz::parse_tz)
        .unwrap_or(fallback)
}

async fn build_context_card(
    pool: &PgPool,
    user_id: Uuid,
    now_local: &str,
) -> Result<String, AppError> {
    let (today_local, offset_str) = match DateTime::parse_from_rfc3339(now_local) {
        Ok(dt) => (dt.date_naive(), dt.offset().to_string()),
        Err(_) => (Utc::now().date_naive(), "+00:00".to_string()),
    };
    // §tz-fix — the clock times rendered below MUST be the user's local wall
    // clock; the model reads them verbatim and won't convert from UTC.
    let tz = crate::tz::parse_tz(&crate::tz::fetch_user_tz(pool, user_id).await?);
    // §wave2 — context card uses the SAME Monday-of-current-week boundary
    // as sleep/sessions stats. Keeps the snapshot in lockstep with what
    // the user sees on the Dashboard / Sleep tab; otherwise Coach can
    // quote a different "this week" number than the cards.
    use chrono::Datelike;
    let today_local = today_local; // already computed above
    let days_since_monday = today_local.weekday().num_days_from_monday() as i64;
    let this_monday = today_local - chrono::Duration::days(days_since_monday);
    let week_start_utc = this_monday.and_hms_opt(0, 0, 0).unwrap().and_utc();

    // Sleep — last 3 nights with on-device audio event counts joined in.
    // LEFT JOIN keeps nights with audio tracking off (or no events) showing
    // up as zero counts rather than vanishing. Aggregated per sleep_record
    // so the resulting row count stays at 3.
    let sleep_rows: Vec<(DateTime<Utc>, DateTime<Utc>, i16, i32, i64, i64, i64, Option<String>)> = sqlx::query_as(
        "SELECT
             sr.actual_bedtime,
             sr.actual_wake_time,
             sr.quality_rating,
             sr.phone_pickups,
             COALESCE(SUM(CASE WHEN sae.event_type = 'snore' THEN 1 ELSE 0 END), 0)::BIGINT,
             COALESCE(SUM(CASE WHEN sae.event_type = 'cough' THEN 1 ELSE 0 END), 0)::BIGINT,
             COALESCE(SUM(CASE WHEN sae.event_type = 'sleep_talk' THEN 1 ELSE 0 END), 0)::BIGINT,
             sr.recorded_tz
           FROM sleep_records sr
           LEFT JOIN sleep_audio_events sae
             ON sae.sleep_record_id = sr.id AND sae.user_id = sr.user_id
          WHERE sr.user_id = $1
          GROUP BY sr.id, sr.actual_bedtime, sr.actual_wake_time, sr.quality_rating, sr.phone_pickups, sr.recorded_tz
          ORDER BY sr.actual_bedtime DESC
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
        for (bedtime, wake, quality, pickups, snore, cough, sleep_talk, rtz) in &sleep_rows {
            let z = anchor_tz(rtz, tz);
            let minutes = wake.signed_duration_since(*bedtime).num_minutes().max(0);
            let h = minutes / 60;
            let m = minutes % 60;
            // Build audio suffix only when at least one event type fired —
            // a flat ", 0/0/0 audio events" line on a non-tracking night
            // is just noise.
            let audio_suffix = if *snore + *cough + *sleep_talk > 0 {
                format!(
                    ", audio snore/cough/sleep-talk {}/{}/{}",
                    snore, cough, sleep_talk
                )
            } else {
                String::new()
            };
            out.push_str(&format!(
                "- {} → {} ({}h{:02}, quality {}, {} pickups{})\n",
                coach_local(bedtime, &z).format("%Y-%m-%d %H:%M"),
                coach_local(wake, &z).format("%H:%M"),
                h,
                m,
                quality,
                pickups,
                audio_suffix,
            ));
        }
    }
    out.push('\n');

    out.push_str(&format!(
        "**Focus, this week (Mon–today):** {} sessions ({} completed), {} minutes focused.\n\n",
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
                coach_local(start, &tz).format("%Y-%m-%d %H:%M"),
                coach_local(end, &tz).format("%H:%M"),
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
            handle_propose_calendar_event(state, user_id, &tool_use_id, &tool_name, &input).await
        }
        TOOL_GET_SLEEP_PERIOD => {
            handle_get_sleep_period(state, user_id, &tool_use_id, &tool_name, &input, now_local).await
        }
        TOOL_GET_FOCUS_PERIOD => {
            handle_get_focus_period(state, user_id, &tool_use_id, &tool_name, &input, now_local).await
        }
        TOOL_GET_FOCUS_SESSION_DETAIL => {
            handle_get_focus_session_detail(state, user_id, &tool_use_id, &tool_name, &input).await
        }
        TOOL_LOG_SLEEP_RECORD => {
            handle_log_sleep_record(state, user_id, &tool_use_id, &tool_name, &input).await
        }
        TOOL_CREATE_ALARM => {
            handle_propose_alarm(&tool_use_id, &tool_name, &input).await
        }
        TOOL_GET_SLEEP_AUDIO_EVENTS => {
            handle_get_sleep_audio_events(state, user_id, &tool_use_id, &tool_name, &input).await
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
    let days = days.clamp(1, 90);
    let since = Utc::now() - Duration::days(days);
    let tz = crate::tz::parse_tz(
        &crate::tz::fetch_user_tz(&state.pool, user_id)
            .await
            .map_err(|e| format!("db error: {}", e.message))?,
    );
    let rows: Vec<(Uuid, DateTime<Utc>, DateTime<Utc>, i16, i32, i64, i64, i64, Option<String>)> = sqlx::query_as(
        "SELECT
             sr.id, sr.actual_bedtime, sr.actual_wake_time, sr.quality_rating, sr.phone_pickups,
             COALESCE(SUM(CASE WHEN sae.event_type = 'snore'      THEN 1 ELSE 0 END), 0)::BIGINT,
             COALESCE(SUM(CASE WHEN sae.event_type = 'cough'      THEN 1 ELSE 0 END), 0)::BIGINT,
             COALESCE(SUM(CASE WHEN sae.event_type = 'sleep_talk' THEN 1 ELSE 0 END), 0)::BIGINT,
             sr.recorded_tz
           FROM sleep_records sr
           LEFT JOIN sleep_audio_events sae
             ON sae.sleep_record_id = sr.id AND sae.user_id = sr.user_id
          WHERE sr.user_id = $1 AND sr.actual_bedtime >= $2
          GROUP BY sr.id, sr.actual_bedtime, sr.actual_wake_time, sr.quality_rating, sr.phone_pickups, sr.recorded_tz
          ORDER BY sr.actual_bedtime DESC",
    )
    .bind(user_id)
    .bind(since)
    .fetch_all(&state.pool)
    .await
    .map_err(|e| format!("db error: {}", e))?;
    let serializable: Vec<_> = rows
        .iter()
        .map(|(id, bed, wake, q, p, snore, cough, sleep_talk, rtz)| {
            let z = anchor_tz(rtz, tz);
            let mins = wake.signed_duration_since(*bed).num_minutes().max(0);
            json!({
                "id": id,
                "bedtime": coach_local(bed, &z).to_rfc3339(),
                "wake_time": coach_local(wake, &z).to_rfc3339(),
                "duration_minutes": mins,
                "quality": q,
                "phone_pickups": p,
                "snore_events": snore,
                "cough_events": cough,
                "sleep_talk_events": sleep_talk,
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

/// §audio-times — Per-event snore/cough/sleep-talk timestamps for ONE night,
/// keyed by sleep_record_id (the `id` from get_sleep_history / get_sleep_period
/// / the context card). Optional event_type filter. Returns exact per-type
/// totals plus the events ordered oldest→newest, capped so a snore-heavy night
/// can't blow the token budget — the totals stay accurate even when the event
/// list is truncated. Ownership is enforced by the `user_id = $2` filter, so a
/// foreign sleep_record_id simply returns nothing rather than leaking data.
async fn handle_get_sleep_audio_events(
    state: &AppState,
    user_id: Uuid,
    tool_use_id: &str,
    tool_name: &str,
    input: &serde_json::Value,
) -> Result<ToolRunOutcome, String> {
    const AUDIO_EVENT_CAP: i64 = 60;
    let record_id_s = input
        .get("sleep_record_id")
        .and_then(|v| v.as_str())
        .ok_or_else(|| "missing sleep_record_id".to_string())?;
    let record_id: Uuid = record_id_s
        .parse()
        .map_err(|_| format!("bad sleep_record_id: {}", record_id_s))?;
    let event_type = input.get("event_type").and_then(|v| v.as_str());
    if let Some(et) = event_type {
        if !matches!(et, "snore" | "cough" | "sleep_talk") {
            return Err(format!("invalid event_type: {}", et));
        }
    }

    // Exact per-type totals (stay correct even when the event list is capped).
    let total_rows: Vec<(String, i64)> = sqlx::query_as(
        "SELECT event_type, COUNT(*)::BIGINT
           FROM sleep_audio_events
          WHERE sleep_record_id = $1 AND user_id = $2
            AND ($3::TEXT IS NULL OR event_type = $3)
          GROUP BY event_type",
    )
    .bind(record_id)
    .bind(user_id)
    .bind(event_type)
    .fetch_all(&state.pool)
    .await
    .map_err(|e| format!("db error: {}", e))?;

    let mut snore_total = 0i64;
    let mut cough_total = 0i64;
    let mut sleep_talk_total = 0i64;
    for (t, c) in &total_rows {
        match t.as_str() {
            "snore" => snore_total = *c,
            "cough" => cough_total = *c,
            "sleep_talk" => sleep_talk_total = *c,
            _ => {}
        }
    }
    let total_for_filter: i64 = total_rows.iter().map(|(_, c)| *c).sum();

    // §tz-anchor — render event times in the NIGHT's recording tz (the parent
    // sleep_record's recorded_tz), falling back to the user's current tz.
    let night_tz: Option<String> = sqlx::query_scalar::<_, Option<String>>(
        "SELECT recorded_tz FROM sleep_records WHERE id = $1 AND user_id = $2",
    )
    .bind(record_id)
    .bind(user_id)
    .fetch_optional(&state.pool)
    .await
    .map_err(|e| format!("db error: {}", e))?
    .flatten();
    let tz_str = match night_tz {
        Some(t) => t,
        None => crate::tz::fetch_user_tz(&state.pool, user_id)
            .await
            .map_err(|e| format!("db error: {}", e.message))?,
    };
    let tz = crate::tz::parse_tz(&tz_str);
    let event_rows: Vec<(String, DateTime<Utc>, DateTime<Utc>, f32)> = sqlx::query_as(
        "SELECT event_type, started_at, ended_at, peak_confidence
           FROM sleep_audio_events
          WHERE sleep_record_id = $1 AND user_id = $2
            AND ($3::TEXT IS NULL OR event_type = $3)
          ORDER BY started_at ASC
          LIMIT $4",
    )
    .bind(record_id)
    .bind(user_id)
    .bind(event_type)
    .bind(AUDIO_EVENT_CAP)
    .fetch_all(&state.pool)
    .await
    .map_err(|e| format!("db error: {}", e))?;

    let events: Vec<_> = event_rows
        .iter()
        .map(|(t, started, ended, conf)| {
            json!({
                "type": t,
                "started_at": coach_local(started, &tz).to_rfc3339(),
                "ended_at": coach_local(ended, &tz).to_rfc3339(),
                "peak_confidence": (*conf as f64 * 100.0).round() / 100.0,
            })
        })
        .collect();

    let returned = event_rows.len() as i64;
    let truncated = total_for_filter > returned;
    let note = if total_for_filter == 0 {
        "No audio events recorded for that night (recording may have been off).".to_string()
    } else if truncated {
        format!(
            "Showing the first {} of {} events (oldest→newest, UTC); the totals are exact.",
            returned, total_for_filter,
        )
    } else {
        format!("{} events (oldest→newest, UTC).", returned)
    };

    let payload = json!({
        "sleep_record_id": record_id,
        "event_type_filter": event_type,
        "totals": {
            "snore": snore_total,
            "cough": cough_total,
            "sleep_talk": sleep_talk_total,
        },
        "returned": returned,
        "truncated": truncated,
        "events": events,
        "note": note,
    })
    .to_string();

    let summary = if total_for_filter == 0 {
        "No audio events for that night".to_string()
    } else {
        format!("Pulled {} audio event(s)", returned)
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
    let days = days.clamp(1, 90);
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

/// §9.7 / 2026-06-06 — Returns per-session detail (debrief, debrief_tag,
/// pickup count) for the user's N most recent completed focus sessions.
/// Sibling to `get_focus_history` which only rolls up per-day; this one
/// is for "what did I work on" / "how distracted was that session"
/// follow-ups.
async fn handle_get_focus_session_detail(
    state: &AppState,
    user_id: Uuid,
    tool_use_id: &str,
    tool_name: &str,
    input: &serde_json::Value,
) -> Result<ToolRunOutcome, String> {
    let limit = input
        .get("limit")
        .and_then(|v| v.as_i64())
        .ok_or_else(|| "missing limit".to_string())?;
    let limit = limit.clamp(1, 20);
    let tz = crate::tz::parse_tz(
        &crate::tz::fetch_user_tz(&state.pool, user_id)
            .await
            .map_err(|e| format!("db error: {}", e.message))?,
    );
    let rows: Vec<(
        Uuid,
        DateTime<Utc>,
        i32,
        i32,
        Option<String>,
        Option<String>,
        Option<String>,
    )> = sqlx::query_as(
        "SELECT id, started_at, duration_minutes, phone_pickups, debrief, debrief_tag, recorded_tz
           FROM productivity_sessions
          WHERE user_id = $1 AND completed = TRUE
          ORDER BY started_at DESC
          LIMIT $2",
    )
    .bind(user_id)
    .bind(limit)
    .fetch_all(&state.pool)
    .await
    .map_err(|e| format!("db error: {}", e))?;
    let serializable: Vec<_> = rows
        .iter()
        .map(|(id, started_at, duration, pickups, debrief, tag, rtz)| {
            let z = anchor_tz(rtz, tz);
            json!({
                "id": id,
                "started_at": coach_local(started_at, &z).to_rfc3339(),
                "duration_minutes": duration,
                "phone_pickups": pickups,
                "debrief": debrief,
                "debrief_tag": tag,
            })
        })
        .collect();
    let summary = if rows.is_empty() {
        "No completed focus sessions yet".to_string()
    } else {
        format!("Pulled {} recent focus session(s)", rows.len())
    };
    Ok(ToolRunOutcome {
        payload_for_model: json!({ "limit": limit, "sessions": serializable }).to_string(),
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
    let tz = crate::tz::parse_tz(
        &crate::tz::fetch_user_tz(&state.pool, user_id)
            .await
            .map_err(|e| format!("db error: {}", e.message))?,
    );
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
                "start": coach_local(start, &tz).to_rfc3339(),
                "end": coach_local(end, &tz).to_rfc3339(),
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
    state: &AppState,
    user_id: Uuid,
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
    let tz = crate::tz::parse_tz(
        &crate::tz::fetch_user_tz(&state.pool, user_id)
            .await
            .map_err(|e| format!("db error: {}", e.message))?,
    );
    let summary = format!(
        "Proposed: {} {} → {}",
        fields.title,
        coach_local(&fields.start_time, &tz).format("%Y-%m-%d %H:%M"),
        coach_local(&fields.end_time, &tz).format("%H:%M"),
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

    // §tz-anchor — stamp the recording tz (current tz at log time) so the
    // logged night keeps its wall-clock after a move; reused for the title below.
    let recorded_tz = crate::tz::fetch_user_tz(&state.pool, user_id)
        .await
        .map_err(|e| format!("db error: {}", e.message))?;
    let record: crate::models::sleep::SleepRecord = sqlx::query_as(
        "INSERT INTO sleep_records
            (user_id, target_bedtime, target_wake_time,
             actual_bedtime, actual_wake_time, quality_rating,
             phone_pickups, total_phone_minutes, notes, recorded_tz)
         VALUES ($1, $2, $3, $4, $5, $6, $7, NULL, $8, $9)
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
    .bind(&recorded_tz)
    .fetch_one(&state.pool)
    .await
    .map_err(|e| format!("db error: {}", e))?;

    state
        .events
        .publish(user_id, crate::event_bus::SyncEvent::SleepCreated(record.clone()));

    let tz = crate::tz::parse_tz(&recorded_tz);
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
                    coach_local(&actual_bedtime, &tz).format("%H:%M"),
                    coach_local(&actual_wake_time, &tz).format("%H:%M")
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

/// Resolve a named calendar period into [start_utc, end_utc] bounds.
/// `now_local` carries the user's local-date so a Sunday-night question
/// about "this week" still bucket-by-Monday in their timezone.
fn calendar_period_bounds(
    period: &str,
    now_local: &str,
) -> Result<(chrono::DateTime<Utc>, chrono::DateTime<Utc>, String), String> {
    use chrono::Datelike;
    let today = match chrono::DateTime::parse_from_rfc3339(now_local) {
        Ok(dt) => dt.date_naive(),
        Err(_) => Utc::now().date_naive(),
    };
    let now_utc = Utc::now();
    let days_since_monday = today.weekday().num_days_from_monday() as i64;
    let this_monday = today - chrono::Duration::days(days_since_monday);
    let last_monday = this_monday - chrono::Duration::days(7);
    let last_sunday = this_monday - chrono::Duration::days(1);
    let first_of_month = today.with_day(1).ok_or("invalid first-of-month")?;
    let last_month_end = first_of_month - chrono::Duration::days(1);
    let first_of_last_month = last_month_end
        .with_day(1)
        .ok_or("invalid first-of-last-month")?;

    let (start_date, end_date, label) = match period {
        "this_week" => (this_monday, today, format!("this week ({}..{})", this_monday, today)),
        "last_week" => (last_monday, last_sunday, format!("last week ({}..{})", last_monday, last_sunday)),
        "this_month" => (first_of_month, today, format!("this month ({}..{})", first_of_month, today)),
        "last_month" => (first_of_last_month, last_month_end, format!("last month ({}..{})", first_of_last_month, last_month_end)),
        other => return Err(format!("unknown period: {}", other)),
    };
    let start = start_date.and_hms_opt(0, 0, 0).unwrap().and_utc();
    // For periods that include "today", cap end at the current instant so
    // we don't pull a record from tonight that hasn't happened yet.
    let end = if end_date >= today {
        now_utc
    } else {
        end_date.and_hms_opt(23, 59, 59).unwrap().and_utc()
    };
    Ok((start, end, label))
}

async fn handle_get_sleep_period(
    state: &AppState,
    user_id: Uuid,
    tool_use_id: &str,
    tool_name: &str,
    input: &serde_json::Value,
    now_local: &str,
) -> Result<ToolRunOutcome, String> {
    let period = input
        .get("period")
        .and_then(|v| v.as_str())
        .ok_or_else(|| "missing period".to_string())?;
    // §audit-2 / tz-fix — bucket calendar periods in the user's timezone by
    // using the request's `now_local` (client local time with offset, threaded
    // from chat_send) instead of UTC. Matches the context card + per-user
    // timezone rule; chat_send already falls back to UTC if the client omits it.
    let (start, end, label) = calendar_period_bounds(period, now_local)?;
    let tz = crate::tz::parse_tz(
        &crate::tz::fetch_user_tz(&state.pool, user_id)
            .await
            .map_err(|e| format!("db error: {}", e.message))?,
    );
    let rows: Vec<(Uuid, chrono::DateTime<Utc>, chrono::DateTime<Utc>, i16, i32, i64, i64, i64, Option<String>)> = sqlx::query_as(
        "SELECT
             sr.id, sr.actual_bedtime, sr.actual_wake_time, sr.quality_rating, sr.phone_pickups,
             COALESCE(SUM(CASE WHEN sae.event_type = 'snore'      THEN 1 ELSE 0 END), 0)::BIGINT,
             COALESCE(SUM(CASE WHEN sae.event_type = 'cough'      THEN 1 ELSE 0 END), 0)::BIGINT,
             COALESCE(SUM(CASE WHEN sae.event_type = 'sleep_talk' THEN 1 ELSE 0 END), 0)::BIGINT,
             sr.recorded_tz
           FROM sleep_records sr
           LEFT JOIN sleep_audio_events sae
             ON sae.sleep_record_id = sr.id AND sae.user_id = sr.user_id
          WHERE sr.user_id = $1 AND sr.actual_bedtime >= $2 AND sr.actual_bedtime <= $3
          GROUP BY sr.id, sr.actual_bedtime, sr.actual_wake_time, sr.quality_rating, sr.phone_pickups, sr.recorded_tz
          ORDER BY sr.actual_bedtime DESC",
    )
    .bind(user_id)
    .bind(start)
    .bind(end)
    .fetch_all(&state.pool)
    .await
    .map_err(|e| format!("db error: {}", e))?;
    let count = rows.len();
    let total_minutes: i64 = rows
        .iter()
        .map(|(_, b, w, _, _, _, _, _, _)| w.signed_duration_since(*b).num_minutes().max(0))
        .sum();
    let avg_minutes: i64 = if count > 0 { total_minutes / count as i64 } else { 0 };
    let avg_quality: f64 = if count > 0 {
        rows.iter().map(|(_, _, _, q, _, _, _, _, _)| *q as f64).sum::<f64>() / count as f64
    } else {
        0.0
    };
    // Period-level audio totals so the model can answer "how much did I snore
    // last month" without paging the per-night rows.
    let snore_total: i64 = rows.iter().map(|(_, _, _, _, _, s, _, _, _)| *s).sum();
    let cough_total: i64 = rows.iter().map(|(_, _, _, _, _, _, c, _, _)| *c).sum();
    let sleep_talk_total: i64 = rows.iter().map(|(_, _, _, _, _, _, _, t, _)| *t).sum();
    let serializable: Vec<_> = rows
        .iter()
        .map(|(id, bed, wake, q, p, snore, cough, sleep_talk, rtz)| {
            let z = anchor_tz(rtz, tz);
            let mins = wake.signed_duration_since(*bed).num_minutes().max(0);
            json!({
                "id": id,
                "bedtime": coach_local(bed, &z).to_rfc3339(),
                "wake_time": coach_local(wake, &z).to_rfc3339(),
                "duration_minutes": mins,
                "quality": q,
                "phone_pickups": p,
                "snore_events": snore,
                "cough_events": cough,
                "sleep_talk_events": sleep_talk,
            })
        })
        .collect();
    // Friendly empty payload — model can quote "no records in last month"
    // instead of falling back to a different period silently.
    let note = if count == 0 {
        format!("No sleep records logged in {}.", label)
    } else {
        format!(
            "{} nights logged in {}. avg duration {}h{:02}, avg quality {:.1}/5.",
            count,
            label,
            avg_minutes / 60,
            avg_minutes % 60,
            avg_quality,
        )
    };
    let payload = json!({
        "period": period,
        "label": label,
        "count": count,
        "avg_duration_minutes": avg_minutes,
        "avg_quality": avg_quality,
        "snore_events_total": snore_total,
        "cough_events_total": cough_total,
        "sleep_talk_events_total": sleep_talk_total,
        "records": serializable,
        "note": note,
    })
    .to_string();
    Ok(ToolRunOutcome {
        payload_for_model: payload,
        bedrock_status: ToolResultStatus::Success,
        surface: ToolInvocationSurface {
            id: tool_use_id.to_string(),
            name: tool_name.to_string(),
            status: "ok".to_string(),
            summary: if count == 0 {
                format!("No sleep records for {}", period.replace('_', " "))
            } else {
                format!("Pulled {} nights for {}", count, period.replace('_', " "))
            },
            committed: false,
            committed_resource: None,
            proposed_event: None,
            proposed_alarm: None,
        },
    })
}

async fn handle_get_focus_period(
    state: &AppState,
    user_id: Uuid,
    tool_use_id: &str,
    tool_name: &str,
    input: &serde_json::Value,
    now_local: &str,
) -> Result<ToolRunOutcome, String> {
    let period = input
        .get("period")
        .and_then(|v| v.as_str())
        .ok_or_else(|| "missing period".to_string())?;
    let (start, end, label) = calendar_period_bounds(period, now_local)?;
    let rows: Vec<(chrono::NaiveDate, i64, i64)> = sqlx::query_as(
        "SELECT (started_at AT TIME ZONE 'UTC')::DATE AS day,
                COUNT(*) FILTER (WHERE completed)::BIGINT AS completed_count,
                COALESCE(SUM(duration_minutes) FILTER (WHERE completed), 0)::BIGINT AS total_minutes
           FROM productivity_sessions
          WHERE user_id = $1 AND started_at >= $2 AND started_at <= $3
          GROUP BY day
          ORDER BY day DESC",
    )
    .bind(user_id)
    .bind(start)
    .bind(end)
    .fetch_all(&state.pool)
    .await
    .map_err(|e| format!("db error: {}", e))?;
    let total_minutes: i64 = rows.iter().map(|(_, _, m)| *m).sum();
    let total_sessions: i64 = rows.iter().map(|(_, c, _)| *c).sum();
    let day_count = rows.len();
    let by_day: Vec<_> = rows
        .iter()
        .map(|(d, c, m)| {
            json!({
                "date": d.to_string(),
                "completed_sessions": c,
                "focus_minutes": m,
            })
        })
        .collect();
    let note = if total_sessions == 0 {
        format!("No focus sessions in {}.", label)
    } else {
        format!(
            "{} sessions in {}, totalling {} minutes across {} day(s).",
            total_sessions, label, total_minutes, day_count
        )
    };
    Ok(ToolRunOutcome {
        payload_for_model: json!({
            "period": period,
            "label": label,
            "total_sessions": total_sessions,
            "total_minutes": total_minutes,
            "active_days": day_count,
            "by_day": by_day,
            "note": note,
        })
        .to_string(),
        bedrock_status: ToolResultStatus::Success,
        surface: ToolInvocationSurface {
            id: tool_use_id.to_string(),
            name: tool_name.to_string(),
            status: "ok".to_string(),
            summary: if total_sessions == 0 {
                format!("No focus sessions in {}", period.replace('_', " "))
            } else {
                format!("Pulled {} sessions for {}", total_sessions, period.replace('_', " "))
            },
            committed: false,
            committed_resource: None,
            proposed_event: None,
            proposed_alarm: None,
        },
    })
}
