// §i18n (v2.13.9) — Per-user timezone helpers.
//
// Routes previously called `Utc::now().date_naive()` for "today" buckets —
// correct for storage (timestamps are UTC) but wrong for aggregation. A
// user in New York closing a 9pm focus session was logged under UTC's
// "tomorrow" (02:00 UTC), so their "today" totals didn't include it.
// These helpers replace those callsites: pull the user's IANA timezone
// from the DB once per request, then compute "today" in that zone.
//
// Invalid timezone strings (corrupt DB, deprecated zone) fall back to UTC
// silently — no panic, no error to the user. Worst case is the same
// behaviour we had before the column existed.

use chrono::{DateTime, NaiveDate, Utc};
use chrono_tz::Tz;
use sqlx::{types::Uuid, PgPool};

use crate::error::AppError;

/// Look up a user's stored IANA timezone. Returns the literal string;
/// validation happens at `parse_tz` time so we can keep a single
/// invalid-string fallback path. Errors only on DB failure / unknown
/// user_id — both should be impossible from an authenticated route, so
/// surface as 500.
pub async fn fetch_user_tz(pool: &PgPool, user_id: Uuid) -> Result<String, AppError> {
    let tz: String = sqlx::query_scalar("SELECT timezone FROM users WHERE id = $1")
        .bind(user_id)
        .fetch_one(pool)
        .await?;
    Ok(tz)
}

/// Parse a stored IANA timezone string. Unknown strings fall back to UTC
/// so a corrupt / deprecated value never crashes a request. Returns the
/// concrete `Tz` so callers can do further math (`Utc::now().with_timezone(&tz)`).
pub fn parse_tz(tz_str: &str) -> Tz {
    tz_str.parse::<Tz>().unwrap_or(Tz::UTC)
}

/// "Today" in the user's local timezone. The half-day around UTC midnight
/// is where this differs from `Utc::now().date_naive()` — e.g. a user in
/// Sydney at 09:00 local (23:00 UTC yesterday) gets the correct local
/// date here.
pub fn user_today(tz_str: &str) -> NaiveDate {
    let tz = parse_tz(tz_str);
    Utc::now().with_timezone(&tz).date_naive()
}

/// "Now" in the user's local timezone, full date+time. Useful when a
/// caller needs both `today` AND the time-of-day (e.g. weekly aggregation
/// that wants "this week so far"). Returns a `DateTime<Tz>` rather than
/// stripping back to a `NaiveDate` so the time portion survives.
pub fn user_now(tz_str: &str) -> DateTime<Tz> {
    let tz = parse_tz(tz_str);
    Utc::now().with_timezone(&tz)
}

/// Convert a user-local calendar date into the [start, end] UTC instants
/// covering that day in the user's timezone. Used by SQL filters that
/// compare `started_at` / `actual_bedtime` (which are TIMESTAMPTZ stored as
/// UTC) against "this user-local day". DST edge cases — a spring-forward
/// 00:00 that doesn't exist, or a fall-back ambiguity — fall back to the
/// `earliest()` resolution; worst case we lose ~1h of session data at
/// the DST boundary which is much better than a panic.
pub fn user_local_day_utc_range(
    tz_str: &str,
    date: chrono::NaiveDate,
) -> (DateTime<Utc>, DateTime<Utc>) {
    use chrono::TimeZone;
    let tz = parse_tz(tz_str);
    let start_local = tz
        .from_local_datetime(&date.and_hms_opt(0, 0, 0).unwrap())
        .earliest()
        .unwrap_or_else(|| {
            // Truly impossible local time → fall back to UTC midnight.
            Utc.from_utc_datetime(&date.and_hms_opt(0, 0, 0).unwrap())
                .with_timezone(&tz)
        });
    let end_local = tz
        .from_local_datetime(&date.and_hms_opt(23, 59, 59).unwrap())
        .latest()
        .unwrap_or_else(|| {
            Utc.from_utc_datetime(&date.and_hms_opt(23, 59, 59).unwrap())
                .with_timezone(&tz)
        });
    (start_local.with_timezone(&Utc), end_local.with_timezone(&Utc))
}
