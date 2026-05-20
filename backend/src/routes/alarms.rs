use axum::extract::{Path, State};
use axum::http::StatusCode;
use axum::routing::{get, post};
use axum::{Json, Router};
use serde_json::{json, Value as JsonValue};
use uuid::Uuid;

use crate::config::AppState;
use crate::error::AppError;
use crate::event_bus::SyncEvent;
use crate::middleware::auth::Claims;
use crate::models::alarm::{Alarm, AlarmEvent, CreateAlarm, CreateAlarmEvent};

pub fn router() -> Router<AppState> {
    Router::new()
        .route("/alarms", post(create).get(list))
        .route("/alarms/:id", get(get_one).put(update).delete(remove))
        .route("/alarms/:id/events", post(log_event))
}

fn parse_user_id(claims: &Claims) -> Result<Uuid, AppError> {
    claims
        .sub
        .parse::<Uuid>()
        .map_err(|_| AppError::new(StatusCode::UNAUTHORIZED, "Invalid token"))
}

const VALID_MISSION_KINDS: &[&str] = &["none", "math", "shake", "photo"];
const VALID_DISMISS_METHODS: &[&str] = &["mission", "snooze", "force", "abandoned"];
const VALID_MATH_DIFFICULTIES: &[&str] = &["easy", "medium", "hard"];
const VALID_SHAKE_INTENSITIES: &[&str] = &["low", "medium", "high"];

fn bad(msg: impl Into<String>) -> AppError {
    AppError::new(StatusCode::BAD_REQUEST, msg.into())
}

/// §L9: validate the per-kind shape of `mission_config`. The client parsers
/// degrade malformed configs to defaults silently, but the server should
/// reject obviously-broken payloads so we don't store junk that will confuse
/// future analytics.
fn validate_mission_config(kind: &str, config: &JsonValue) -> Result<(), AppError> {
    let obj = config.as_object().ok_or_else(|| bad("mission_config must be a JSON object"))?;
    match kind {
        "none" => Ok(()),
        "math" => {
            if let Some(d) = obj.get("difficulty") {
                let s = d.as_str().ok_or_else(|| bad("math.difficulty must be a string"))?;
                if !VALID_MATH_DIFFICULTIES.contains(&s) {
                    return Err(bad("math.difficulty must be easy|medium|hard"));
                }
            }
            if let Some(c) = obj.get("count") {
                let n = c.as_i64().ok_or_else(|| bad("math.count must be an integer"))?;
                if !(1..=10).contains(&n) {
                    return Err(bad("math.count must be 1..10"));
                }
            }
            Ok(())
        }
        "shake" => {
            if let Some(i) = obj.get("intensity") {
                let s = i.as_str().ok_or_else(|| bad("shake.intensity must be a string"))?;
                if !VALID_SHAKE_INTENSITIES.contains(&s) {
                    return Err(bad("shake.intensity must be low|medium|high"));
                }
            }
            if let Some(n) = obj.get("shakes_required") {
                let v = n.as_i64().ok_or_else(|| bad("shake.shakes_required must be an integer"))?;
                if !(10..=100).contains(&v) {
                    return Err(bad("shake.shakes_required must be 10..100"));
                }
            }
            Ok(())
        }
        "photo" => {
            // reference_uri is opaque (device-local URI); only validate type.
            if let Some(u) = obj.get("reference_uri") {
                if !u.is_string() {
                    return Err(bad("photo.reference_uri must be a string"));
                }
            }
            if let Some(h) = obj.get("phash_hex") {
                let s = h.as_str().ok_or_else(|| bad("photo.phash_hex must be a string"))?;
                if s.len() > 16 || !s.chars().all(|c| c.is_ascii_hexdigit()) {
                    return Err(bad("photo.phash_hex must be ≤16 hex chars"));
                }
            }
            if let Some(t) = obj.get("tolerance") {
                let n = t.as_i64().ok_or_else(|| bad("photo.tolerance must be an integer"))?;
                if !(4..=24).contains(&n) {
                    return Err(bad("photo.tolerance must be 4..24"));
                }
            }
            Ok(())
        }
        _ => unreachable!("mission_kind is gated by validate_payload"),
    }
}

fn validate_payload(input: &CreateAlarm) -> Result<(), AppError> {
    if !VALID_MISSION_KINDS.contains(&input.mission_kind.as_str()) {
        return Err(bad("mission_kind must be one of: none, math, shake, photo"));
    }
    if !(0..=127).contains(&input.days_of_week) {
        return Err(bad("days_of_week bitmask must be 0..127"));
    }
    if let Some(v) = input.volume_pct {
        if !(0..=100).contains(&v) {
            return Err(bad("volume_pct must be 0..100"));
        }
    }
    if let Some(m) = input.snooze_minutes {
        if !(1..=60).contains(&m) {
            return Err(bad("snooze_minutes must be 1..60"));
        }
    }
    if let Some(max) = input.snooze_max {
        if !(0..=10).contains(&max) {
            return Err(bad("snooze_max must be 0..10"));
        }
    }
    crate::routes::validation::cap_chars_opt(
        &input.label,
        crate::routes::validation::MAX_TITLE_CHARS,
        "label",
    )?;
    if let Some(cfg) = &input.mission_config {
        validate_mission_config(&input.mission_kind, cfg)?;
    }
    Ok(())
}

/// §H1/M4: upsert. When the client provides an `id` (e.g. an offline-created
/// alarm syncing for the first time, OR a retry after a network blip), we
/// insert with that id; on conflict we update the row, scoped to the same
/// user via the WHERE clause on the conflict action. If a different user
/// owns the conflicting id (UUID collision — astronomically unlikely), the
/// UPDATE no-ops and RETURNING returns nothing → we fail with NOT_FOUND
/// rather than leaking the other user's row.
async fn create(
    State(state): State<AppState>,
    claims: Claims,
    Json(input): Json<CreateAlarm>,
) -> Result<(StatusCode, Json<Alarm>), AppError> {
    let user_id = parse_user_id(&claims)?;
    validate_payload(&input)?;

    let provided_id = input.id;
    let record = sqlx::query_as::<_, Alarm>(
        "INSERT INTO alarms
            (id, user_id, label, trigger_time_local, days_of_week, enabled,
             sound_uri, volume_pct, volume_escalates, vibration,
             snooze_minutes, snooze_max, mission_kind, mission_config)
         VALUES (COALESCE($1, uuid_generate_v4()), $2, $3, $4, $5,
                 COALESCE($6, TRUE), $7,
                 COALESCE($8, 80), COALESCE($9, TRUE), COALESCE($10, TRUE),
                 COALESCE($11, 9), COALESCE($12, 3), $13, COALESCE($14, '{}'::jsonb))
         ON CONFLICT (id) DO UPDATE
            SET label = EXCLUDED.label,
                trigger_time_local = EXCLUDED.trigger_time_local,
                days_of_week = EXCLUDED.days_of_week,
                enabled = EXCLUDED.enabled,
                sound_uri = EXCLUDED.sound_uri,
                volume_pct = EXCLUDED.volume_pct,
                volume_escalates = EXCLUDED.volume_escalates,
                vibration = EXCLUDED.vibration,
                snooze_minutes = EXCLUDED.snooze_minutes,
                snooze_max = EXCLUDED.snooze_max,
                mission_kind = EXCLUDED.mission_kind,
                mission_config = EXCLUDED.mission_config,
                updated_at = NOW()
            WHERE alarms.user_id = EXCLUDED.user_id
         RETURNING *",
    )
    .bind(provided_id)
    .bind(user_id)
    .bind(&input.label)
    .bind(input.trigger_time_local)
    .bind(input.days_of_week)
    .bind(input.enabled)
    .bind(&input.sound_uri)
    .bind(input.volume_pct)
    .bind(input.volume_escalates)
    .bind(input.vibration)
    .bind(input.snooze_minutes)
    .bind(input.snooze_max)
    .bind(&input.mission_kind)
    .bind(input.mission_config.clone().unwrap_or_else(|| json!({})))
    .fetch_optional(&state.pool)
    .await?
    .ok_or_else(|| AppError::new(
        StatusCode::CONFLICT,
        "Alarm id collides with another user's alarm",
    ))?;

    state
        .events
        .publish(user_id, SyncEvent::AlarmCreated(record.clone()));

    Ok((StatusCode::CREATED, Json(record)))
}

async fn list(
    State(state): State<AppState>,
    claims: Claims,
) -> Result<Json<Vec<Alarm>>, AppError> {
    let user_id = parse_user_id(&claims)?;

    let records = sqlx::query_as::<_, Alarm>(
        "SELECT * FROM alarms
         WHERE user_id = $1
         ORDER BY trigger_time_local ASC, created_at ASC",
    )
    .bind(user_id)
    .fetch_all(&state.pool)
    .await?;

    Ok(Json(records))
}

async fn get_one(
    State(state): State<AppState>,
    claims: Claims,
    Path(id): Path<Uuid>,
) -> Result<Json<Alarm>, AppError> {
    let user_id = parse_user_id(&claims)?;

    let record = sqlx::query_as::<_, Alarm>(
        "SELECT * FROM alarms WHERE id = $1 AND user_id = $2",
    )
    .bind(id)
    .bind(user_id)
    .fetch_optional(&state.pool)
    .await?
    .ok_or_else(|| AppError::new(StatusCode::NOT_FOUND, "Alarm not found"))?;

    Ok(Json(record))
}

async fn update(
    State(state): State<AppState>,
    claims: Claims,
    Path(id): Path<Uuid>,
    Json(input): Json<CreateAlarm>,
) -> Result<Json<Alarm>, AppError> {
    let user_id = parse_user_id(&claims)?;
    validate_payload(&input)?;

    let record = sqlx::query_as::<_, Alarm>(
        "UPDATE alarms
         SET label = $1,
             trigger_time_local = $2,
             days_of_week = $3,
             enabled = COALESCE($4, enabled),
             sound_uri = $5,
             volume_pct = COALESCE($6, volume_pct),
             volume_escalates = COALESCE($7, volume_escalates),
             vibration = COALESCE($8, vibration),
             snooze_minutes = COALESCE($9, snooze_minutes),
             snooze_max = COALESCE($10, snooze_max),
             mission_kind = $11,
             mission_config = COALESCE($12, mission_config),
             updated_at = NOW()
         WHERE id = $13 AND user_id = $14
         RETURNING *",
    )
    .bind(&input.label)
    .bind(input.trigger_time_local)
    .bind(input.days_of_week)
    .bind(input.enabled)
    .bind(&input.sound_uri)
    .bind(input.volume_pct)
    .bind(input.volume_escalates)
    .bind(input.vibration)
    .bind(input.snooze_minutes)
    .bind(input.snooze_max)
    .bind(&input.mission_kind)
    .bind(input.mission_config.clone())
    .bind(id)
    .bind(user_id)
    .fetch_optional(&state.pool)
    .await?
    .ok_or_else(|| AppError::new(StatusCode::NOT_FOUND, "Alarm not found"))?;

    state
        .events
        .publish(user_id, SyncEvent::AlarmUpdated(record.clone()));

    Ok(Json(record))
}

async fn remove(
    State(state): State<AppState>,
    claims: Claims,
    Path(id): Path<Uuid>,
) -> Result<StatusCode, AppError> {
    let user_id = parse_user_id(&claims)?;

    let result = sqlx::query("DELETE FROM alarms WHERE id = $1 AND user_id = $2")
        .bind(id)
        .bind(user_id)
        .execute(&state.pool)
        .await?;

    if result.rows_affected() == 0 {
        return Err(AppError::new(StatusCode::NOT_FOUND, "Alarm not found"));
    }

    state
        .events
        .publish(user_id, SyncEvent::AlarmDeleted { id });

    Ok(StatusCode::NO_CONTENT)
}

async fn log_event(
    State(state): State<AppState>,
    claims: Claims,
    Path(alarm_id): Path<Uuid>,
    Json(input): Json<CreateAlarmEvent>,
) -> Result<(StatusCode, Json<AlarmEvent>), AppError> {
    let user_id = parse_user_id(&claims)?;

    if let Some(method) = &input.dismiss_method {
        if !VALID_DISMISS_METHODS.contains(&method.as_str()) {
            return Err(bad(
                "dismiss_method must be one of: mission, snooze, force, abandoned",
            ));
        }
    }

    // §L10: 404 on unknown alarm_id (the alarm has never existed under this
    // user). Previously this silently inserted a row with alarm_id = NULL
    // owned by the caller, which is fine for privacy (no cross-user leak)
    // but lets clients inject arbitrary events. Now we reject unless the
    // alarm row currently exists.
    let exists = sqlx::query_scalar::<_, Uuid>(
        "SELECT id FROM alarms WHERE id = $1 AND user_id = $2",
    )
    .bind(alarm_id)
    .bind(user_id)
    .fetch_optional(&state.pool)
    .await?;

    if exists.is_none() {
        return Err(AppError::new(StatusCode::NOT_FOUND, "Alarm not found"));
    }

    let event = sqlx::query_as::<_, AlarmEvent>(
        "INSERT INTO alarm_events
            (user_id, alarm_id, fired_at, dismissed_at, dismiss_method,
             snooze_count, mission_kind, mission_attempts, mission_duration_ms)
         VALUES ($1, $2, $3, $4, $5,
                 COALESCE($6, 0), $7, COALESCE($8, 0), $9)
         RETURNING *",
    )
    .bind(user_id)
    .bind(alarm_id)
    .bind(input.fired_at)
    .bind(input.dismissed_at)
    .bind(&input.dismiss_method)
    .bind(input.snooze_count)
    .bind(&input.mission_kind)
    .bind(input.mission_attempts)
    .bind(input.mission_duration_ms)
    .fetch_one(&state.pool)
    .await?;

    state
        .events
        .publish(user_id, SyncEvent::AlarmEventLogged(event.clone()));

    Ok((StatusCode::CREATED, Json(event)))
}
