//! §sleep-day (v2.13.17 backend / Android, completed in v2.13.x web) —
//! A sleep "belongs to" the calendar day it started on after shifting
//! clocks back by [`SLEEP_DAY_SHIFT_HOURS`] in the user's local timezone.
//! Mirrors `backend/src/tz.rs::sleep_day_for` and the Kotlin
//! `util/SleepDay.kt`. Any change to the shift must update all three.
//!
//!   - Bedtime Tue 02:00 local  → sleep_day = Monday (Monday's night)
//!   - Bedtime Tue 22:00 local  → sleep_day = Tuesday
//!   - Tue 2 pm nap             → sleep_day = Tuesday
//!
//! Tuned to 6 h so post-midnight nights bucket to the previous day
//! without breaking daytime naps. The browser's local timezone is the
//! reference (we don't have IANA tz available client-side — `Local`
//! reads from the OS).

use chrono::{DateTime, Duration, Local, NaiveDate, Utc};

pub const SLEEP_DAY_SHIFT_HOURS: i64 = 6;

/// Sleep-day for a UTC bedtime in the browser's local timezone.
pub fn sleep_day_for(bedtime: DateTime<Utc>) -> NaiveDate {
    (bedtime - Duration::hours(SLEEP_DAY_SHIFT_HOURS))
        .with_timezone(&Local)
        .date_naive()
}
