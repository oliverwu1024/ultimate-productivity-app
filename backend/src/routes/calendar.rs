use axum::extract::{Path, Query, State};
use axum::http::StatusCode;
use axum::routing::{get, post};
use axum::{Json, Router};
use chrono::{DateTime, Datelike, NaiveDate, Utc, Weekday};
use std::collections::HashSet;
use uuid::Uuid;

use crate::config::AppState;
use crate::error::AppError;
use crate::event_bus::SyncEvent;
use crate::middleware::auth::Claims;
use crate::models::calendar::{CalendarEvent, CreateCalendarEvent, EventCategory};

pub fn router() -> Router<AppState> {
    Router::new()
        .route("/calendar", post(create).get(list))
        .route("/calendar/:id", get(get_one).put(update).delete(remove))
}

#[derive(serde::Deserialize)]
struct CalendarQuery {
    start: Option<NaiveDate>,
    end: Option<NaiveDate>,
    category: Option<String>,
    priority: Option<String>,
    /// §v2.17.2-sync-collision — When false, the list endpoint returns the
    /// raw master rows instead of expanding recurring events into one row
    /// per occurrence. Android passes `expand=false` from
    /// `CalendarRepository.sync()` because Room keys on the master `id`
    /// and `OnConflictStrategy.REPLACE` collapsed the ~395 daily-expansion
    /// rows down to whichever one was inserted last (the furthest-future
    /// instance), making DAILY events invisible in every local-DB read
    /// path. Web keeps the default (expansion on) so its rendering stays
    /// unchanged.
    expand: Option<bool>,
}

/// §029 — When the URL carries `?occurrence_date=YYYY-MM-DD`, the PUT /
/// DELETE handlers route to the per-occurrence path:
///
/// * DELETE: appends the date to `excluded_dates` on the master row
///   instead of dropping the row.
/// * PUT: only the `is_done` field of the body is honoured; the date is
///   toggled in / out of `done_dates`. Other fields are ignored so older
///   "mark-done" UIs that send a full PUT don't accidentally edit the
///   whole series.
#[derive(serde::Deserialize)]
struct OccurrenceQuery {
    occurrence_date: Option<NaiveDate>,
}

fn parse_user_id(claims: &Claims) -> Result<Uuid, AppError> {
    claims
        .sub
        .parse::<Uuid>()
        .map_err(|_| AppError::new(StatusCode::UNAUTHORIZED, "Invalid token"))
}

fn default_color(category: &EventCategory) -> &str {
    match category {
        EventCategory::Study => "#4A90D9",
        EventCategory::Project => "#E67E22",
        EventCategory::Exercise => "#2ECC71",
        EventCategory::Personal => "#9B59B6",
        EventCategory::Other => "#95A5A6",
    }
}

// ── CRUD handlers ──────────────────────────────────────────────────────

async fn create(
    State(state): State<AppState>,
    claims: Claims,
    Json(input): Json<CreateCalendarEvent>,
) -> Result<(StatusCode, Json<CalendarEvent>), AppError> {
    let user_id = parse_user_id(&claims)?;

    if input.title.trim().is_empty() {
        return Err(AppError::new(
            StatusCode::BAD_REQUEST,
            "title must not be empty",
        ));
    }
    if input.start_time >= input.end_time {
        return Err(AppError::new(
            StatusCode::BAD_REQUEST,
            "start_time must be before end_time",
        ));
    }
    if input.is_recurring && input.recurrence_rule.is_none() {
        return Err(AppError::new(
            StatusCode::BAD_REQUEST,
            "recurrence_rule is required when is_recurring is true",
        ));
    }

    let color = input
        .color
        .as_deref()
        .unwrap_or_else(|| default_color(&input.category));

    // §tz/calendar — stamp the event's timezone (creator's IANA zone). Validate
    // the client value; fall back to the user's stored tz when missing/invalid.
    let event_tz = match input.event_tz.as_deref() {
        Some(t) if t.parse::<chrono_tz::Tz>().is_ok() => t.to_string(),
        _ => crate::tz::fetch_user_tz(&state.pool, user_id).await?,
    };

    let event = sqlx::query_as::<_, CalendarEvent>(
        "INSERT INTO calendar_events
            (user_id, title, description, start_time, end_time, category, priority,
             is_recurring, recurrence_rule, color, is_done, reminder_minutes,
             event_tz, done_dates, excluded_dates)
         VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13, NULL, NULL)
         RETURNING *",
    )
    .bind(user_id)
    .bind(input.title.trim())
    .bind(&input.description)
    .bind(input.start_time)
    .bind(input.end_time)
    .bind(&input.category)
    .bind(&input.priority)
    .bind(input.is_recurring)
    .bind(&input.recurrence_rule)
    .bind(color)
    .bind(input.is_done.unwrap_or(false))
    .bind(input.reminder_minutes.as_deref())
    .bind(&event_tz)
    .fetch_one(&state.pool)
    .await?;

    state
        .events
        .publish(user_id, SyncEvent::CalendarCreated(event.clone()));

    Ok((StatusCode::CREATED, Json(event)))
}

async fn list(
    State(state): State<AppState>,
    claims: Claims,
    Query(params): Query<CalendarQuery>,
) -> Result<Json<Vec<CalendarEvent>>, AppError> {
    let user_id = parse_user_id(&claims)?;

    let end = params
        .end
        .unwrap_or_else(|| Utc::now().date_naive())
        .and_hms_opt(23, 59, 59)
        .unwrap()
        .and_utc();

    let start = params
        .start
        .unwrap_or_else(|| (Utc::now() - chrono::Duration::days(30)).date_naive())
        .and_hms_opt(0, 0, 0)
        .unwrap()
        .and_utc();

    // One-time events in range + recurring events that could have instances in range
    let events = sqlx::query_as::<_, CalendarEvent>(
        "SELECT * FROM calendar_events
         WHERE user_id = $1
           AND (
             (is_recurring = false AND start_time >= $2 AND start_time <= $3)
             OR
             (is_recurring = true AND start_time <= $3)
           )
         ORDER BY start_time ASC",
    )
    .bind(user_id)
    .bind(start)
    .bind(end)
    .fetch_all(&state.pool)
    .await?;

    // Apply optional category / priority filters
    let filtered: Vec<&CalendarEvent> = events
        .iter()
        .filter(|e| {
            params
                .category
                .as_ref()
                .map_or(true, |c| e.category.as_str() == c)
                && params
                    .priority
                    .as_ref()
                    .map_or(true, |p| e.priority.as_str() == p)
        })
        .collect();

    // Expand recurring events into virtual instances; keep one-time events as-is.
    // §tz/calendar (Phase B) — each event now expands in its OWN event_tz (the
    // creator's zone), so recurrence + per-occurrence override (done/excluded)
    // local-date matching are both DST-stable; no per-request user tz needed.
    // §v2.17.2-sync-collision — Android opts out via `?expand=false` so its
    // local sync receives one row per master `id` instead of a collision
    // storm against Room's REPLACE conflict strategy. Default stays on so
    // the web dashboard (which renders the expanded list directly) is
    // untouched.
    let expand = params.expand.unwrap_or(true);
    let mut result = Vec::new();
    for event in filtered {
        if event.is_recurring && expand {
            result.extend(expand_recurrence(event, start, end, &event.event_tz));
        } else {
            result.push(event.clone());
        }
    }

    result.sort_by_key(|e| e.start_time);

    Ok(Json(result))
}

async fn get_one(
    State(state): State<AppState>,
    claims: Claims,
    Path(id): Path<Uuid>,
) -> Result<Json<CalendarEvent>, AppError> {
    let user_id = parse_user_id(&claims)?;

    let event = sqlx::query_as::<_, CalendarEvent>(
        "SELECT * FROM calendar_events WHERE id = $1 AND user_id = $2",
    )
    .bind(id)
    .bind(user_id)
    .fetch_optional(&state.pool)
    .await?
    .ok_or_else(|| AppError::new(StatusCode::NOT_FOUND, "Calendar event not found"))?;

    Ok(Json(event))
}

async fn update(
    State(state): State<AppState>,
    claims: Claims,
    Path(id): Path<Uuid>,
    Query(occ): Query<OccurrenceQuery>,
    Json(input): Json<CreateCalendarEvent>,
) -> Result<Json<CalendarEvent>, AppError> {
    let user_id = parse_user_id(&claims)?;

    // §029 — Per-occurrence toggle path. The client sends
    // `?occurrence_date=2026-06-02` with a body whose `is_done` field
    // carries the desired state for that single occurrence; every other
    // body field is ignored so a "mark done" tap can never accidentally
    // overwrite the master row's title / time / recurrence rule.
    if let Some(occ_date) = occ.occurrence_date {
        let desired_done = input.is_done.unwrap_or(true);
        return update_occurrence_done(&state, user_id, id, occ_date, desired_done).await;
    }

    if input.title.trim().is_empty() {
        return Err(AppError::new(
            StatusCode::BAD_REQUEST,
            "title must not be empty",
        ));
    }
    crate::routes::validation::cap_chars(
        &input.title,
        crate::routes::validation::MAX_TITLE_CHARS,
        "title",
    )?;
    crate::routes::validation::cap_chars_opt(
        &input.description,
        crate::routes::validation::MAX_DESCRIPTION_CHARS,
        "description",
    )?;
    if input.start_time >= input.end_time {
        return Err(AppError::new(
            StatusCode::BAD_REQUEST,
            "start_time must be before end_time",
        ));
    }
    if input.is_recurring && input.recurrence_rule.is_none() {
        return Err(AppError::new(
            StatusCode::BAD_REQUEST,
            "recurrence_rule is required when is_recurring is true",
        ));
    }

    let color = input
        .color
        .as_deref()
        .unwrap_or_else(|| default_color(&input.category));

    // §tz/calendar — preserve event_tz on update unless the client sends a valid
    // new one (COALESCE), same contract as is_done / reminder_minutes below.
    let event_tz_in = input
        .event_tz
        .as_deref()
        .filter(|t| t.parse::<chrono_tz::Tz>().is_ok());

    // is_done and reminder_minutes are COALESCEd so older clients (which omit
    // the field) preserve the stored value rather than clobbering it back to
    // the default on every update.
    let event = sqlx::query_as::<_, CalendarEvent>(
        "UPDATE calendar_events
         SET title = $1, description = $2, start_time = $3, end_time = $4,
             category = $5, priority = $6, is_recurring = $7, recurrence_rule = $8,
             color = $9, is_done = COALESCE($10, is_done),
             reminder_minutes = COALESCE($11, reminder_minutes),
             event_tz = COALESCE($12, event_tz),
             updated_at = NOW()
         WHERE id = $13 AND user_id = $14
         RETURNING *",
    )
    .bind(input.title.trim())
    .bind(&input.description)
    .bind(input.start_time)
    .bind(input.end_time)
    .bind(&input.category)
    .bind(&input.priority)
    .bind(input.is_recurring)
    .bind(&input.recurrence_rule)
    .bind(color)
    .bind(input.is_done)
    .bind(input.reminder_minutes.as_deref())
    .bind(event_tz_in)
    .bind(id)
    .bind(user_id)
    .fetch_optional(&state.pool)
    .await?
    .ok_or_else(|| AppError::new(StatusCode::NOT_FOUND, "Calendar event not found"))?;

    state
        .events
        .publish(user_id, SyncEvent::CalendarUpdated(event.clone()));

    Ok(Json(event))
}

async fn remove(
    State(state): State<AppState>,
    claims: Claims,
    Path(id): Path<Uuid>,
    Query(occ): Query<OccurrenceQuery>,
) -> Result<StatusCode, AppError> {
    let user_id = parse_user_id(&claims)?;

    // §029 — Per-occurrence delete. Appends the date to `excluded_dates`
    // on the master row so the next expansion skips that single instance;
    // every other occurrence in the series stays. The master row itself
    // is preserved.
    if let Some(occ_date) = occ.occurrence_date {
        return delete_occurrence(&state, user_id, id, occ_date).await;
    }

    let result = sqlx::query("DELETE FROM calendar_events WHERE id = $1 AND user_id = $2")
        .bind(id)
        .bind(user_id)
        .execute(&state.pool)
        .await?;

    if result.rows_affected() == 0 {
        return Err(AppError::new(
            StatusCode::NOT_FOUND,
            "Calendar event not found",
        ));
    }

    state
        .events
        .publish(user_id, SyncEvent::CalendarDeleted { id });

    Ok(StatusCode::NO_CONTENT)
}

// ── §029 Per-occurrence helpers ────────────────────────────────────────

/// Toggle the given date in the master row's `done_dates` set.
///   desired_done = true  → ensure the date is present.
///   desired_done = false → ensure the date is absent.
/// The master row's own `is_done` column is left untouched (it represents
/// the default for every occurrence not in `done_dates`; we never want a
/// single tap on Tuesday to flip the default for every other day).
async fn update_occurrence_done(
    state: &AppState,
    user_id: Uuid,
    id: Uuid,
    occ_date: NaiveDate,
    desired_done: bool,
) -> Result<Json<CalendarEvent>, AppError> {
    let existing = sqlx::query_as::<_, CalendarEvent>(
        "SELECT * FROM calendar_events WHERE id = $1 AND user_id = $2",
    )
    .bind(id)
    .bind(user_id)
    .fetch_optional(&state.pool)
    .await?
    .ok_or_else(|| AppError::new(StatusCode::NOT_FOUND, "Calendar event not found"))?;

    let mut dates = parse_date_set(existing.done_dates.as_deref());
    if desired_done {
        dates.insert(occ_date);
    } else {
        dates.remove(&occ_date);
    }
    let new_value = serialise_date_set(&dates);

    let event = sqlx::query_as::<_, CalendarEvent>(
        "UPDATE calendar_events
            SET done_dates = $1, updated_at = NOW()
          WHERE id = $2 AND user_id = $3
          RETURNING *",
    )
    .bind(new_value.as_deref())
    .bind(id)
    .bind(user_id)
    .fetch_one(&state.pool)
    .await?;

    state
        .events
        .publish(user_id, SyncEvent::CalendarUpdated(event.clone()));

    Ok(Json(event))
}

/// Append the given date to the master row's `excluded_dates` set, then
/// publish a CalendarUpdated event so any open dashboard re-renders
/// without that occurrence. The master row itself stays so the series
/// keeps generating future + past instances either side of the gap.
async fn delete_occurrence(
    state: &AppState,
    user_id: Uuid,
    id: Uuid,
    occ_date: NaiveDate,
) -> Result<StatusCode, AppError> {
    let existing = sqlx::query_as::<_, CalendarEvent>(
        "SELECT * FROM calendar_events WHERE id = $1 AND user_id = $2",
    )
    .bind(id)
    .bind(user_id)
    .fetch_optional(&state.pool)
    .await?
    .ok_or_else(|| AppError::new(StatusCode::NOT_FOUND, "Calendar event not found"))?;

    let mut dates = parse_date_set(existing.excluded_dates.as_deref());
    dates.insert(occ_date);
    let new_value = serialise_date_set(&dates);

    let event = sqlx::query_as::<_, CalendarEvent>(
        "UPDATE calendar_events
            SET excluded_dates = $1, updated_at = NOW()
          WHERE id = $2 AND user_id = $3
          RETURNING *",
    )
    .bind(new_value.as_deref())
    .bind(id)
    .bind(user_id)
    .fetch_one(&state.pool)
    .await?;

    state
        .events
        .publish(user_id, SyncEvent::CalendarUpdated(event));

    Ok(StatusCode::NO_CONTENT)
}

fn parse_date_set(raw: Option<&str>) -> HashSet<NaiveDate> {
    match raw {
        None => HashSet::new(),
        Some(s) => s
            .split(',')
            .filter_map(|d| NaiveDate::parse_from_str(d.trim(), "%Y-%m-%d").ok())
            .collect(),
    }
}

fn serialise_date_set(dates: &HashSet<NaiveDate>) -> Option<String> {
    if dates.is_empty() {
        return None;
    }
    let mut sorted: Vec<NaiveDate> = dates.iter().copied().collect();
    sorted.sort();
    Some(
        sorted
            .into_iter()
            .map(|d| d.format("%Y-%m-%d").to_string())
            .collect::<Vec<_>>()
            .join(","),
    )
}

// ── Recurrence expansion ───────────────────────────────────────────────

/// §029 — A parsed recurrence rule. The optional `until` suffix
/// (`:UNTIL=YYYY-MM-DD`) caps the generated series so "This and following"
/// deletes / edits can chop the original series off at a date.
struct ParsedRule<'a> {
    base: &'a str,
    until: Option<NaiveDate>,
}

fn parse_rule(rule: &str) -> ParsedRule<'_> {
    // Rules look like one of:
    //   "DAILY"                        | "DAILY:UNTIL=2026-06-15"
    //   "WEEKLY:MON,WED"               | "WEEKLY:MON,WED:UNTIL=2026-06-15"
    //   "MONTHLY:15"                   | "MONTHLY:15:UNTIL=2026-06-15"
    if let Some(idx) = rule.find(":UNTIL=") {
        let (base, suffix) = rule.split_at(idx);
        let until_str = &suffix[":UNTIL=".len()..];
        let until = NaiveDate::parse_from_str(until_str.trim(), "%Y-%m-%d").ok();
        ParsedRule { base, until }
    } else {
        ParsedRule {
            base: rule,
            until: None,
        }
    }
}

fn expand_recurrence(
    event: &CalendarEvent,
    range_start: DateTime<Utc>,
    range_end: DateTime<Utc>,
    event_tz: &str,
) -> Vec<CalendarEvent> {
    let rule_raw = match &event.recurrence_rule {
        Some(r) => r.as_str(),
        None => return vec![],
    };
    let parsed = parse_rule(rule_raw);

    use chrono::TimeZone;
    let tz = crate::tz::parse_tz(event_tz);
    let duration = event.end_time - event.start_time;
    // §tz/DST (Phase B) — anchor recurrence to the event's LOCAL wall-clock in
    // its own timezone (event_tz), so "9am daily" stays 9am across a DST change
    // instead of drifting ±1h with the fixed UTC instant. Time-of-day + dates
    // are taken in event_tz; each occurrence is rebuilt local→UTC per date.
    let local_start = event.start_time.with_timezone(&tz);
    let event_time = local_start.time();
    let event_start_date = local_start.date_naive();
    let range_start_date = range_start.with_timezone(&tz).date_naive();
    // §029 — Cap the expansion window by the parsed UNTIL suffix. UNTIL
    // is inclusive (last valid occurrence date) — matches Google Calendar
    // behaviour where "remove this and following" leaves the picked
    // instance gone but everything strictly before remains.
    let effective_end_date = match parsed.until {
        Some(u) => range_end.with_timezone(&tz).date_naive().min(u),
        None => range_end.with_timezone(&tz).date_naive(),
    };
    if effective_end_date < range_start_date {
        return vec![];
    }

    // §029 — Per-occurrence overrides. excluded_dates drops instances
    // entirely; done_dates flips is_done on the surviving copy. Both keys
    // are LOCAL dates in event_tz — we convert each generated instance's UTC
    // start back to local for the lookup.
    let excluded = parse_date_set(event.excluded_dates.as_deref());
    let done = parse_date_set(event.done_dates.as_deref());
    let local_date = |start: DateTime<Utc>| -> NaiveDate { start.with_timezone(&tz).date_naive() };
    // Rebuild an occurrence's UTC instant from its local date + the event's
    // local time-of-day in event_tz (DST-correct). A nonexistent local time
    // (spring-forward gap) falls back to interpreting the naive time as UTC.
    let to_utc = |date: NaiveDate| -> DateTime<Utc> {
        let naive = date.and_time(event_time);
        tz.from_local_datetime(&naive)
            .earliest()
            .map(|dt| dt.with_timezone(&Utc))
            .unwrap_or_else(|| Utc.from_utc_datetime(&naive))
    };

    let push_instance =
        |instances: &mut Vec<CalendarEvent>, start: DateTime<Utc>, duration: chrono::Duration| {
            let local = local_date(start);
            if excluded.contains(&local) {
                return;
            }
            let mut inst = event.clone();
            inst.start_time = start;
            inst.end_time = start + duration;
            if done.contains(&local) {
                inst.is_done = true;
            }
            instances.push(inst);
        };

    let mut instances = Vec::new();

    if parsed.base == "DAILY" {
        let first = event_start_date.max(range_start_date);
        let mut cur = first;
        while cur <= effective_end_date {
            let start = to_utc(cur);
            push_instance(&mut instances, start, duration);
            cur += chrono::Duration::days(1);
        }
    } else if let Some(days_str) = parsed.base.strip_prefix("WEEKLY:") {
        let target_days: Vec<Weekday> = days_str
            .split(',')
            .filter_map(|d| parse_weekday(d.trim()))
            .collect();
        if target_days.is_empty() {
            return vec![];
        }
        let first = event_start_date.max(range_start_date);
        let mut cur = first;
        while cur <= effective_end_date {
            if target_days.contains(&cur.weekday()) {
                let start = to_utc(cur);
                push_instance(&mut instances, start, duration);
            }
            cur += chrono::Duration::days(1);
        }
    } else if let Some(day_str) = parsed.base.strip_prefix("MONTHLY:") {
        if let Ok(target_day) = day_str.trim().parse::<u32>() {
            let first = event_start_date.max(range_start_date);
            let mut year = first.year();
            let mut month = first.month();
            loop {
                if let Some(date) = NaiveDate::from_ymd_opt(year, month, target_day) {
                    if date > effective_end_date {
                        break;
                    }
                    if date >= event_start_date && date >= range_start_date {
                        let start = to_utc(date);
                        push_instance(&mut instances, start, duration);
                    }
                }
                month += 1;
                if month > 12 {
                    month = 1;
                    year += 1;
                }
                if year > effective_end_date.year() + 1 {
                    break;
                }
            }
        }
    }

    instances
}

fn parse_weekday(s: &str) -> Option<Weekday> {
    match s.to_uppercase().as_str() {
        "MON" => Some(Weekday::Mon),
        "TUE" => Some(Weekday::Tue),
        "WED" => Some(Weekday::Wed),
        "THU" => Some(Weekday::Thu),
        "FRI" => Some(Weekday::Fri),
        "SAT" => Some(Weekday::Sat),
        "SUN" => Some(Weekday::Sun),
        _ => None,
    }
}
