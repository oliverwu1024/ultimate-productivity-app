// §9.8 Phase C — Daily anomaly-scan scheduler.
//
// Runs a single tokio task that sleeps until the next scheduled hour
// (default: 22:00 UTC ≈ 8am AEST) then iterates active users, calling
// `run_anomaly_check_for_user` for each. Per-user cache + quota gating
// live inside that function — this scheduler just decides WHEN.
//
// Single-instance assumption: the ECS service runs one task today. If we
// ever scale out, only the task with `ANOMALY_SCHEDULER_ENABLED=true` will
// run the scan — gate that env var at the task-definition level on one
// instance, or move to EventBridge → Lambda for a true single-firer.
//
// We don't use cron syntax for now; the simple "sleep until next HH:00 UTC"
// loop is enough for one job per day. Switch to a real cron crate (or
// EventBridge → Lambda) only if we add more scheduled jobs.

use std::time::Duration as StdDuration;

use chrono::{Duration, NaiveTime, TimeZone, Utc};
use sqlx::types::Uuid;
use tokio::time::sleep;

use crate::config::AppState;
use crate::routes::ai::run_anomaly_check_for_user;

/// Default scan hour (UTC). 22:00 UTC ≈ 08:00 AEST (Australia/Sydney), the
/// primary user timezone today. Overridable via `ANOMALY_SCAN_HOUR_UTC`.
const DEFAULT_SCAN_HOUR_UTC: u32 = 22;

/// Sleep between per-user runs to spread Bedrock load across a few minutes
/// rather than spike at scan-start. 200ms ✕ 100 users = 20s — well under
/// any noticeable user impact and well within Bedrock per-account TPM.
const PER_USER_DELAY_MS: u64 = 200;

/// Cap on how many users we scan per cycle. Defense against a single user
/// from a typo / loop bug eating Bedrock budget. Raise if active users
/// genuinely exceed this.
const MAX_USERS_PER_SCAN: usize = 5000;

/// Spawn the daily-scan task. Returns immediately; the actual loop runs
/// in the background for the lifetime of the process. Opt-in via env var
/// so local dev doesn't accidentally hit Bedrock.
pub fn spawn(state: AppState) {
    let enabled = std::env::var("ANOMALY_SCHEDULER_ENABLED")
        .map(|s| s == "true" || s == "1")
        .unwrap_or(false);
    if !enabled {
        tracing::info!(
            target: "scheduler",
            "ANOMALY_SCHEDULER_ENABLED unset/false — daily scan skipped",
        );
        return;
    }

    let scan_hour: u32 = std::env::var("ANOMALY_SCAN_HOUR_UTC")
        .ok()
        .and_then(|s| s.parse().ok())
        .filter(|h: &u32| *h < 24)
        .unwrap_or(DEFAULT_SCAN_HOUR_UTC);

    tracing::info!(
        target: "scheduler",
        scan_hour_utc = scan_hour,
        "daily anomaly scan scheduler armed",
    );

    tokio::spawn(async move {
        loop {
            let wait = StdDuration::from_secs(seconds_until_next_run(scan_hour) as u64);
            tracing::info!(
                target: "scheduler",
                seconds_until_next = wait.as_secs(),
                "sleeping until next anomaly scan",
            );
            sleep(wait).await;
            if let Err(e) = run_scan(&state).await {
                tracing::error!(target: "scheduler", "anomaly scan failed: {e:?}");
            }
        }
    });
}

/// Seconds from now until the next HH:00:00 UTC at `hour`. If `hour` is
/// today and hasn't passed yet → today; otherwise → tomorrow.
fn seconds_until_next_run(hour: u32) -> i64 {
    let now = Utc::now();
    let today_target = Utc
        .from_utc_datetime(
            &now.date_naive()
                .and_time(NaiveTime::from_hms_opt(hour, 0, 0).unwrap()),
        );
    let next = if now < today_target {
        today_target
    } else {
        today_target + Duration::days(1)
    };
    (next - now).num_seconds().max(60) // floor at 60s so we never tight-loop
}

/// One pass over active users. "Active" = at least one registered device
/// token (only pushable users matter — non-pushable users see no value
/// from being scanned today, since the in-app anomaly card lands later in
/// Phase D and pulls on-demand anyway).
async fn run_scan(state: &AppState) -> Result<(), sqlx::Error> {
    let user_ids: Vec<Uuid> = sqlx::query_scalar(
        "SELECT DISTINCT user_id FROM device_tokens
          ORDER BY user_id
          LIMIT $1",
    )
    .bind(MAX_USERS_PER_SCAN as i64)
    .fetch_all(&state.pool)
    .await?;

    tracing::info!(
        target: "scheduler",
        user_count = user_ids.len(),
        "anomaly scan starting",
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
        "anomaly scan complete",
    );
    Ok(())
}
