use axum::extract::{Path, Query, State};
use axum::http::StatusCode;
use axum::routing::{delete, get, post};
use axum::{Json, Router};
use uuid::Uuid;

use crate::config::AppState;
use crate::error::AppError;
use crate::middleware::auth::Claims;
use crate::models::sleep_audio::{
    AttachClipRequest, BatchCreateSleepAudioEvents, ClipPlaybackUrlResponse,
    ClipUploadUrlResponse, CreateSleepAudioEvent, SleepAudioEvent, SleepAudioEventResponse,
};
use crate::sleep_audio_clips::{CLIP_CONTENT_TYPE, MAX_CLIP_BYTES};

// One sleep session usually produces 0-200 debounced events (snore-heavy
// users push the upper end). 2000 is the abuse cap — a malicious client
// shouldn't be able to flood the table from a single sync.
const MAX_BATCH: usize = 2000;

/// §10.x — Independent clip recording is Pro-tier only. We don't yet have a
/// billing-backed `is_pro` column (Phase 11 — Launch will introduce it via
/// Play Billing webhooks), so the stand-in is `is_admin`: only the dev
/// account passes, which matches our closed-testing rollout. Swap this
/// implementation when monetization lands; every route is the same call.
async fn require_pro_tier(pool: &sqlx::PgPool, user_id: Uuid) -> Result<(), AppError> {
    let is_pro: bool = sqlx::query_scalar("SELECT is_admin FROM users WHERE id = $1")
        .bind(user_id)
        .fetch_optional(pool)
        .await?
        .unwrap_or(false);
    if !is_pro {
        return Err(AppError::new(
            StatusCode::PAYMENT_REQUIRED,
            "Sleep-audio clip recording is a Pro-tier feature",
        ));
    }
    Ok(())
}

pub fn router() -> Router<AppState> {
    Router::new()
        .route("/sleep-audio-events/batch", post(batch_create))
        .route("/sleep-audio-events", get(list))
        .route("/sleep-audio-events/clip-upload-url", post(clip_upload_url))
        .route("/sleep-audio-events/:id/clip", post(attach_clip))
        .route("/sleep-audio-events/:id/clip", delete(delete_clip))
        .route("/sleep-audio-events/:id/clip-url", get(clip_playback_url))
}

#[derive(serde::Deserialize)]
struct ListQuery {
    sleep_record_id: Uuid,
}

#[derive(serde::Deserialize)]
struct ClipUploadUrlRequest {
    event_id: Uuid,
}

fn parse_user_id(claims: &Claims) -> Result<Uuid, AppError> {
    claims
        .sub
        .parse::<Uuid>()
        .map_err(|_| AppError::new(StatusCode::UNAUTHORIZED, "Invalid token"))
}

fn validate_event(e: &CreateSleepAudioEvent) -> Result<(), AppError> {
    // §10.x — event_type now includes 'sleep_talk' alongside the original
    // 'snore' + 'cough'. Anything else is a malformed client; reject early
    // rather than letting the CHECK constraint fail the whole batch.
    if !matches!(e.event_type.as_str(), "snore" | "cough" | "sleep_talk") {
        return Err(AppError::new(
            StatusCode::BAD_REQUEST,
            "event_type must be 'snore', 'cough', or 'sleep_talk'",
        ));
    }
    if e.ended_at < e.started_at {
        return Err(AppError::new(
            StatusCode::BAD_REQUEST,
            "ended_at must be >= started_at",
        ));
    }
    if !(0.0..=1.0).contains(&e.peak_confidence) {
        return Err(AppError::new(
            StatusCode::BAD_REQUEST,
            "peak_confidence must be in [0.0, 1.0]",
        ));
    }
    Ok(())
}

async fn owns_sleep_record(
    pool: &sqlx::PgPool,
    user_id: Uuid,
    sleep_record_id: Uuid,
) -> Result<(), AppError> {
    let owns: Option<(Uuid,)> = sqlx::query_as(
        "SELECT id FROM sleep_records WHERE id = $1 AND user_id = $2",
    )
    .bind(sleep_record_id)
    .bind(user_id)
    .fetch_optional(pool)
    .await?;
    if owns.is_none() {
        return Err(AppError::new(
            StatusCode::FORBIDDEN,
            "Invalid sleep_record_id",
        ));
    }
    Ok(())
}

/// Fetch an event by id, scoped to the caller. Returns 404 (not 403) when
/// the event exists but belongs to someone else — same surface either way
/// so cross-user enumeration is indistinguishable from a typoed id.
async fn fetch_owned_event(
    pool: &sqlx::PgPool,
    user_id: Uuid,
    event_id: Uuid,
) -> Result<SleepAudioEvent, AppError> {
    let row: Option<SleepAudioEvent> = sqlx::query_as::<_, SleepAudioEvent>(
        "SELECT * FROM sleep_audio_events WHERE id = $1 AND user_id = $2",
    )
    .bind(event_id)
    .bind(user_id)
    .fetch_optional(pool)
    .await?;
    row.ok_or_else(|| AppError::new(StatusCode::NOT_FOUND, "Sleep audio event not found"))
}

async fn batch_create(
    State(state): State<AppState>,
    claims: Claims,
    Json(input): Json<BatchCreateSleepAudioEvents>,
) -> Result<(StatusCode, Json<Vec<SleepAudioEventResponse>>), AppError> {
    let user_id = parse_user_id(&claims)?;

    if input.events.is_empty() {
        return Ok((StatusCode::CREATED, Json(Vec::new())));
    }
    if input.events.len() > MAX_BATCH {
        return Err(AppError::new(
            StatusCode::BAD_REQUEST,
            format!("batch too large (max {} events)", MAX_BATCH),
        ));
    }

    // Without this check a malicious client could attach events to another
    // user's sleep_record via the FK. Same pattern as phone_pickups.
    owns_sleep_record(&state.pool, user_id, input.sleep_record_id).await?;

    for e in &input.events {
        validate_event(e)?;
    }

    // Single multi-row INSERT — one round-trip to RDS regardless of batch
    // size. QueryBuilder is the SQLx-idiomatic way to do this; the per-row
    // alternative would be N+2 round-trips inside an explicit transaction.
    //
    // §10.x-fix — Honour client-supplied id so the same UUID identifies the
    // row on phone, in Room, and in subsequent clip-attach / delete /
    // playback calls. Falls back to a fresh server UUID when the client
    // omits it (pre-v2.14.1 callers).
    let mut qb = sqlx::QueryBuilder::<sqlx::Postgres>::new(
        "INSERT INTO sleep_audio_events \
         (id, user_id, sleep_record_id, event_type, started_at, ended_at, peak_confidence) ",
    );
    qb.push_values(input.events.iter(), |mut b, e| {
        b.push_bind(e.id.unwrap_or_else(Uuid::new_v4))
            .push_bind(user_id)
            .push_bind(input.sleep_record_id)
            .push_bind(&e.event_type)
            .push_bind(e.started_at)
            .push_bind(e.ended_at)
            .push_bind(e.peak_confidence);
    });
    qb.push(" RETURNING *");

    let rows = qb
        .build_query_as::<SleepAudioEvent>()
        .fetch_all(&state.pool)
        .await?;

    let responses: Vec<SleepAudioEventResponse> = rows.into_iter().map(Into::into).collect();
    Ok((StatusCode::CREATED, Json(responses)))
}

async fn list(
    State(state): State<AppState>,
    claims: Claims,
    Query(params): Query<ListQuery>,
) -> Result<Json<Vec<SleepAudioEventResponse>>, AppError> {
    let user_id = parse_user_id(&claims)?;

    owns_sleep_record(&state.pool, user_id, params.sleep_record_id).await?;

    let rows = sqlx::query_as::<_, SleepAudioEvent>(
        "SELECT * FROM sleep_audio_events
         WHERE user_id = $1 AND sleep_record_id = $2
         ORDER BY started_at ASC",
    )
    .bind(user_id)
    .bind(params.sleep_record_id)
    .fetch_all(&state.pool)
    .await?;

    let responses: Vec<SleepAudioEventResponse> = rows.into_iter().map(Into::into).collect();
    Ok(Json(responses))
}

/// Issue a presigned PUT URL for a clip upload. The backend generates the
/// S3 key (so the client can't write outside its own prefix) and returns
/// it; the client uploads, then calls `attach_clip` with the same key.
///
/// Idempotency: re-requesting against an already-clipped event 409s rather
/// than silently leaking a second key — the per-clip delete is the only
/// way to free the slot, which keeps storage accounting honest.
async fn clip_upload_url(
    State(state): State<AppState>,
    claims: Claims,
    Json(input): Json<ClipUploadUrlRequest>,
) -> Result<Json<ClipUploadUrlResponse>, AppError> {
    let user_id = parse_user_id(&claims)?;
    require_pro_tier(&state.pool, user_id).await?;

    let store = state
        .sleep_audio_clips
        .as_ref()
        .ok_or_else(|| AppError::new(StatusCode::SERVICE_UNAVAILABLE, "Clip storage not configured"))?;

    let event = fetch_owned_event(&state.pool, user_id, input.event_id).await?;
    if event.clip_s3_key.is_some() {
        return Err(AppError::new(
            StatusCode::CONFLICT,
            "Event already has a clip attached",
        ));
    }

    let key = store.new_clip_key(user_id);
    let put_url = store
        .presign_put(&key)
        .await
        .map_err(|_| AppError::new(StatusCode::BAD_GATEWAY, "Failed to presign upload URL"))?;

    Ok(Json(ClipUploadUrlResponse {
        put_url,
        s3_key: key,
        content_type: CLIP_CONTENT_TYPE,
        max_bytes: MAX_CLIP_BYTES,
        expires_in_secs: 5 * 60,
    }))
}

/// Bind a successfully-uploaded clip to its event row. The phone has just
/// PUT the AAC bytes to `s3_key`; we trust the key only if it matches the
/// caller's prefix (defense against replay with another user's leaked key).
async fn attach_clip(
    State(state): State<AppState>,
    claims: Claims,
    Path(event_id): Path<Uuid>,
    Json(input): Json<AttachClipRequest>,
) -> Result<Json<SleepAudioEventResponse>, AppError> {
    let user_id = parse_user_id(&claims)?;
    require_pro_tier(&state.pool, user_id).await?;

    // Reject keys that don't live under the caller's prefix — the prefix is
    // server-generated in `clip_upload_url`, so a mismatch means the client
    // is trying to attach someone else's key (or a forged one).
    let expected_prefix = format!("u/{}/", user_id);
    if !input.s3_key.starts_with(&expected_prefix) || input.s3_key.contains("..") {
        return Err(AppError::new(
            StatusCode::BAD_REQUEST,
            "Invalid s3_key for this user",
        ));
    }
    if input.duration_ms <= 0 || input.duration_ms > 60_000 {
        return Err(AppError::new(
            StatusCode::BAD_REQUEST,
            "duration_ms out of range (1..=60000)",
        ));
    }

    let event = fetch_owned_event(&state.pool, user_id, event_id).await?;
    if event.clip_s3_key.is_some() {
        return Err(AppError::new(
            StatusCode::CONFLICT,
            "Event already has a clip attached",
        ));
    }

    let updated: SleepAudioEvent = sqlx::query_as::<_, SleepAudioEvent>(
        "UPDATE sleep_audio_events
            SET clip_s3_key = $1, clip_duration_ms = $2
          WHERE id = $3 AND user_id = $4
          RETURNING *",
    )
    .bind(&input.s3_key)
    .bind(input.duration_ms)
    .bind(event_id)
    .bind(user_id)
    .fetch_one(&state.pool)
    .await?;

    Ok(Json(updated.into()))
}

/// Issue a short-lived presigned GET so the client can stream playback.
/// Generated fresh on every play tap; the URL is intentionally short-lived
/// because caching it client-side would extend the window where a leaked
/// URL is replayable.
async fn clip_playback_url(
    State(state): State<AppState>,
    claims: Claims,
    Path(event_id): Path<Uuid>,
) -> Result<Json<ClipPlaybackUrlResponse>, AppError> {
    let user_id = parse_user_id(&claims)?;
    require_pro_tier(&state.pool, user_id).await?;

    let store = state
        .sleep_audio_clips
        .as_ref()
        .ok_or_else(|| AppError::new(StatusCode::SERVICE_UNAVAILABLE, "Clip storage not configured"))?;

    let event = fetch_owned_event(&state.pool, user_id, event_id).await?;
    let key = event
        .clip_s3_key
        .as_ref()
        .ok_or_else(|| AppError::new(StatusCode::NOT_FOUND, "Event has no clip"))?;

    let get_url = store
        .presign_get(key)
        .await
        .map_err(|_| AppError::new(StatusCode::BAD_GATEWAY, "Failed to presign playback URL"))?;

    Ok(Json(ClipPlaybackUrlResponse {
        get_url,
        expires_in_secs: 60,
    }))
}

/// Delete a single clip (keeps the detection event row). S3 deletion is
/// best-effort: if it fails we still NULL the columns so the row no longer
/// points at an unreachable object, and the lifecycle rule will clean up
/// the orphan within 7 days. Idempotent — a second call on an already-NULL
/// row 204s rather than 404s.
async fn delete_clip(
    State(state): State<AppState>,
    claims: Claims,
    Path(event_id): Path<Uuid>,
) -> Result<StatusCode, AppError> {
    let user_id = parse_user_id(&claims)?;
    require_pro_tier(&state.pool, user_id).await?;

    let store = state
        .sleep_audio_clips
        .as_ref()
        .ok_or_else(|| AppError::new(StatusCode::SERVICE_UNAVAILABLE, "Clip storage not configured"))?;

    let event = fetch_owned_event(&state.pool, user_id, event_id).await?;
    let Some(key) = event.clip_s3_key.as_ref() else {
        return Ok(StatusCode::NO_CONTENT);
    };

    if let Err(e) = store.delete(key).await {
        tracing::warn!(target: "sleep-audio", "S3 delete failed for {key}: {e:?} — clearing row anyway");
    }

    sqlx::query(
        "UPDATE sleep_audio_events
            SET clip_s3_key = NULL, clip_duration_ms = NULL
          WHERE id = $1 AND user_id = $2",
    )
    .bind(event_id)
    .bind(user_id)
    .execute(&state.pool)
    .await?;

    Ok(StatusCode::NO_CONTENT)
}
