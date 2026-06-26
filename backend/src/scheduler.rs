// §9.8 Phase C — Per-user anomaly-scan scheduler.
//
// v2.13.9 reworked this from "single 22:00 UTC scan" to "hourly tick that
// scans whichever users have their local clock at the configured scan
// hour right now." This is what unlocks shipping internationally: a
// New York user gets their anomaly check at 08:00 EST instead of at
// 17:00 EST (= 22:00 UTC, the old AU-centric default).
//
// Mechanics:
//   1. Wait until the next HH:00:00 UTC (start of the next clock hour).
//   2. SELECT users where EXTRACT(HOUR FROM (now() AT TIME ZONE timezone))
//      = ANOMALY_SCAN_HOUR_LOCAL (default 8). The SQL filter does the
//      per-user timezone math — no fan-out in Rust.
//   3. Run the anomaly check for each matched user, throttled.
//   4. Sleep until the next HH:00:00 UTC.
//
// Single-instance assumption is unchanged: the ECS service runs one task
// today; the env-var gate `ANOMALY_SCHEDULER_ENABLED` is still the
// single-firer guard. If scaling out later, only one task should set
// the env var, or migrate to EventBridge → Lambda.

use std::time::Duration as StdDuration;

use chrono::{Duration, NaiveTime, TimeZone, Timelike, Utc};
use sqlx::types::Uuid;
use tokio::time::sleep;

use crate::config::AppState;
use crate::routes::ai::run_anomaly_check_for_user;

/// Default scan hour in each user's local time (24-hour clock). 08:00 is
/// a reasonable "morning check-in" slot — late enough that the user has
/// likely woken, early enough to deliver an alert before their day fills
/// up. Overridable globally via `ANOMALY_SCAN_HOUR_LOCAL`; eventually
/// per-user via a `preferences.anomaly_scan_hour_local` knob (not yet
/// wired — uniform default for v2.13.9).
const DEFAULT_SCAN_HOUR_LOCAL: u32 = 8;

/// Sleep between per-user runs inside a single hourly tick. Spreads
/// Bedrock load over a few seconds rather than spiking — fine well
/// inside per-account TPM at our user count.
const PER_USER_DELAY_MS: u64 = 200;

/// Cap on how many users get matched per hourly tick. Defense against a
/// runaway query / a single user with a corrupt timezone matching every
/// hour. Raise once active users exceed this.
const MAX_USERS_PER_TICK: usize = 5000;

/// Spawn the hourly scheduler task. Returns immediately; the actual loop
/// runs in the background for the lifetime of the process. Opt-in via env
/// var so local dev doesn't accidentally hit Bedrock.
pub fn spawn(state: AppState) {
    let enabled = std::env::var("ANOMALY_SCHEDULER_ENABLED")
        .map(|s| s == "true" || s == "1")
        .unwrap_or(false);
    if !enabled {
        tracing::info!(
            target: "scheduler",
            "ANOMALY_SCHEDULER_ENABLED unset/false — anomaly tick skipped",
        );
        return;
    }

    let scan_hour_local: u32 = std::env::var("ANOMALY_SCAN_HOUR_LOCAL")
        .ok()
        .and_then(|s| s.parse().ok())
        .filter(|h: &u32| *h < 24)
        .unwrap_or(DEFAULT_SCAN_HOUR_LOCAL);

    tracing::info!(
        target: "scheduler",
        scan_hour_local = scan_hour_local,
        "per-user anomaly scheduler armed (hourly tick)",
    );

    tokio::spawn(async move {
        loop {
            let wait = StdDuration::from_secs(seconds_until_next_hour() as u64);
            tracing::debug!(
                target: "scheduler",
                seconds_until_next_tick = wait.as_secs(),
                "sleeping until next hourly tick",
            );
            sleep(wait).await;
            if let Err(e) = run_tick(&state, scan_hour_local).await {
                tracing::error!(target: "scheduler", "anomaly tick failed: {e:?}");
            }
        }
    });
}

/// Seconds until the next HH:00:00 UTC. Tick boundary is UTC-aligned
/// because that's what `EXTRACT(HOUR FROM (now() AT TIME ZONE tz))`
/// queries against — the hour we'd match for any user.
fn seconds_until_next_hour() -> i64 {
    let now = Utc::now();
    // Add 1 hour, then round down to the start of that hour.
    let next_hour = (now + Duration::hours(1))
        .date_naive()
        .and_time(NaiveTime::from_hms_opt((now + Duration::hours(1)).time().hour(), 0, 0).unwrap());
    let next = Utc.from_utc_datetime(&next_hour);
    (next - now).num_seconds().max(60)
}

/// One hourly tick: find users whose local hour matches `scan_hour_local`
/// right now and run the anomaly check for each. The SQL does the
/// timezone math via `EXTRACT(HOUR FROM (now() AT TIME ZONE timezone))`
/// — Postgres knows IANA zones natively, so no need to fan out into Rust.
/// We additionally restrict to users with at least one device_token (only
/// pushable users get value from a fresh anomaly check today; the
/// in-app card pulls on-demand too).
async fn run_tick(state: &AppState, scan_hour_local: u32) -> Result<(), sqlx::Error> {
    let user_ids: Vec<Uuid> = sqlx::query_scalar(
        "SELECT DISTINCT u.id
           FROM users u
           JOIN device_tokens d ON d.user_id = u.id
          WHERE EXTRACT(HOUR FROM (now() AT TIME ZONE u.timezone)) = $1
          ORDER BY u.id
          LIMIT $2",
    )
    .bind(scan_hour_local as i32)
    .bind(MAX_USERS_PER_TICK as i64)
    .fetch_all(&state.pool)
    .await?;

    if user_ids.is_empty() {
        tracing::debug!(
            target: "scheduler",
            scan_hour_local = scan_hour_local,
            "tick: no users at scan hour right now",
        );
        return Ok(());
    }

    tracing::info!(
        target: "scheduler",
        scan_hour_local = scan_hour_local,
        user_count = user_ids.len(),
        "anomaly tick starting",
    );

    let mut alerts = 0usize;
    let mut errors = 0usize;
    for user_id in &user_ids {
        match run_anomaly_check_for_user(state, *user_id).await {
            Ok(resp) => {
                if resp.alert {
                    alerts += 1;
                    tracing::info!(
                        target: "scheduler",
                        user = %user_id,
                        cached = resp.cached,
                        pushed = resp.pushed,
                        "anomaly: ALERT",
                    );
                } else if !resp.cached {
                    tracing::debug!(
                        target: "scheduler",
                        user = %user_id,
                        "anomaly: no alert (fresh)",
                    );
                }
            }
            Err(e) => {
                errors += 1;
                tracing::warn!(
                    target: "scheduler",
                    user = %user_id,
                    "anomaly check failed: {e:?}",
                );
            }
        }
        sleep(StdDuration::from_millis(PER_USER_DELAY_MS)).await;
    }

    tracing::info!(
        target: "scheduler",
        scanned = user_ids.len(),
        alerts = alerts,
        errors = errors,
        "anomaly tick complete",
    );
    Ok(())
}

// §10.x — Clip-pointer janitor.
//
// Pro-tier audio clips live in S3 and auto-expire after 30 days via the
// bucket's `expire-after-30-days` lifecycle rule (see sleep_audio_clips.rs).
// The sleep_audio_events row, however, keeps `clip_s3_key` set — so once the
// object is gone, the playback route presigns a key that 404s and the client
// shows "clip expired" forever. This task NULLs the two clip columns once a
// row is past the retention window so the source of truth matches S3 and the
// UI simply hides the ▶.
//
// It only clears the dangling DB pointer — the bytes are already deleted by
// the S3 lifecycle rule, so there's no S3 call (and no S3 credentials needed)
// here. The UPDATE is idempotent and safe to run from more than one task, so
// unlike the Bedrock-spending anomaly scan it isn't behind a single-firer
// guard; set `CLIP_JANITOR_ENABLED=false` to disable it if ever needed.

/// Age past which a clip pointer is cleaned: the 30-day S3 lifecycle window
/// plus a 1-day buffer so we never NULL a clip whose object is still alive.
/// Keep this >= the bucket's `expire-after-30-days` rule.
const CLIP_JANITOR_AGE_DAYS: i32 = 31;

/// How often the janitor sweeps. Daily is plenty — a pointer lingers at most
/// ~24h past day 31 before it's cleaned, versus forever without this task.
const CLIP_JANITOR_INTERVAL_SECS: u64 = 24 * 60 * 60;

/// Spawn the clip-pointer janitor. Returns immediately; the loop runs in the
/// background for the process lifetime. On by default (pure, cheap DB
/// maintenance); opt out with `CLIP_JANITOR_ENABLED=false`.
pub fn spawn_clip_janitor(state: AppState) {
    let enabled = std::env::var("CLIP_JANITOR_ENABLED")
        .map(|s| s != "false" && s != "0")
        .unwrap_or(true);
    if !enabled {
        tracing::info!(
            target: "clip-janitor",
            "CLIP_JANITOR_ENABLED=false — clip janitor skipped",
        );
        return;
    }

    tracing::info!(
        target: "clip-janitor",
        age_days = CLIP_JANITOR_AGE_DAYS,
        "clip-pointer janitor armed (daily)",
    );

    tokio::spawn(async move {
        loop {
            // Sweep on startup first so a fresh deploy clears any backlog
            // promptly, then once a day thereafter.
            match run_clip_janitor(&state).await {
                Ok(n) if n > 0 => tracing::info!(
                    target: "clip-janitor",
                    cleaned = n,
                    "nulled clip pointers for expired objects",
                ),
                Ok(_) => tracing::debug!(
                    target: "clip-janitor",
                    "no expired clip pointers to clean",
                ),
                Err(e) => tracing::error!(
                    target: "clip-janitor",
                    "clip janitor pass failed: {e:?}",
                ),
            }
            sleep(StdDuration::from_secs(CLIP_JANITOR_INTERVAL_SECS)).await;
        }
    });
}

/// One janitor pass: NULL the clip columns on every row whose clip is past the
/// retention window. Returns the number of rows cleaned. `created_at` is the
/// row-insert time, which is within minutes of the S3 upload, so a row older
/// than `CLIP_JANITOR_AGE_DAYS` is guaranteed past the 30-day S3 expiry.
async fn run_clip_janitor(state: &AppState) -> Result<u64, sqlx::Error> {
    let result = sqlx::query(
        "UPDATE sleep_audio_events
            SET clip_s3_key = NULL, clip_duration_ms = NULL
          WHERE clip_s3_key IS NOT NULL
            AND created_at < now() - make_interval(days => $1)",
    )
    .bind(CLIP_JANITOR_AGE_DAYS)
    .execute(&state.pool)
    .await?;
    Ok(result.rows_affected())
}
