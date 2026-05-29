use axum::extract::{Path, Query, State};
use axum::http::StatusCode;
use axum::routing::{get, post};
use axum::{Json, Router};
use chrono::{NaiveDate, Utc};
use sqlx::PgPool;
use uuid::Uuid;

use crate::config::AppState;
use crate::error::AppError;
use crate::event_bus::SyncEvent;
use crate::middleware::auth::Claims;
use crate::models::checklist::{ChecklistItem, CreateChecklistItem, UpdateChecklistItem};
use crate::routes::validation::{cap_chars, cap_chars_opt, MAX_DESCRIPTION_CHARS, MAX_TITLE_CHARS};

pub fn router() -> Router<AppState> {
    Router::new()
        .route("/checklist", post(create).get(list))
        .route("/checklist/today", get(today))
        .route("/checklist/bulk", post(bulk_create))
        .route(
            "/checklist/:id",
            get(get_one).put(update).delete(remove),
        )
        .route("/checklist/:id/complete", post(complete))
        .route("/checklist/:id/uncomplete", post(uncomplete))
        // §024 per-day completion log for recurring items. The legacy
        // /complete + /uncomplete routes only flip the boolean and don't
        // know about a specific day, so a recurring task ticked on Tue
        // would silently un-tick Mon's stamp. These routes operate on
        // checklist_completions, one row per (item, epoch_day).
        .route(
            "/checklist/:id/complete-on/:epoch_day",
            post(complete_on),
        )
        .route(
            "/checklist/:id/uncomplete-on/:epoch_day",
            post(uncomplete_on),
        )
}

#[derive(serde::Deserialize)]
struct ListQuery {
    start: Option<NaiveDate>,
    end: Option<NaiveDate>,
    completed: Option<bool>,
}

fn parse_user_id(claims: &Claims) -> Result<Uuid, AppError> {
    claims
        .sub
        .parse::<Uuid>()
        .map_err(|_| AppError::new(StatusCode::UNAUTHORIZED, "Invalid token"))
}

fn validate_input(title: &str, description: Option<&str>, priority: i16) -> Result<(), AppError> {
    if title.trim().is_empty() {
        return Err(AppError::new(
            StatusCode::BAD_REQUEST,
            "title must not be empty",
        ));
    }
    cap_chars(title, MAX_TITLE_CHARS, "title")?;
    if let Some(d) = description {
        cap_chars(d, MAX_DESCRIPTION_CHARS, "description")?;
    }
    if !(0..=2).contains(&priority) {
        return Err(AppError::new(
            StatusCode::BAD_REQUEST,
            "priority must be 0, 1, or 2",
        ));
    }
    Ok(())
}

/// Wraps a `SELECT ci.*` with a correlated `ARRAY` subquery so
/// `completed_epoch_days` lands on each row in a single round-trip.
/// ARRAY(...) is cheaper than GROUP BY here because we don't need to
/// aggregate anything else, and it keeps the existing column list
/// intact for sqlx::FromRow.
///
/// Implemented as a macro so each callsite gets a `&'static str` that
/// satisfies sqlx 0.9's `SqlSafeStr` bound on `query_as`.
macro_rules! select_with_completions {
    ($where_and_order:literal $(,)?) => {
        concat!(
            "SELECT ci.*, ",
            "COALESCE(ARRAY(SELECT epoch_day FROM checklist_completions ",
            "               WHERE item_id = ci.id ORDER BY epoch_day), ",
            "         '{}'::bigint[]) AS completed_epoch_days ",
            "FROM checklist_items ci ",
            $where_and_order,
        )
    };
}

/// Fetch a single item by id with its completion days attached. Returns
/// 404 if the row doesn't exist or belongs to a different user.
async fn fetch_one_for_user(
    pool: &PgPool,
    id: Uuid,
    user_id: Uuid,
) -> Result<ChecklistItem, AppError> {
    sqlx::query_as::<_, ChecklistItem>(select_with_completions!(
        "WHERE ci.id = $1 AND ci.user_id = $2"
    ))
        .bind(id)
        .bind(user_id)
        .fetch_optional(pool)
        .await?
        .ok_or_else(|| AppError::new(StatusCode::NOT_FOUND, "Checklist item not found"))
}

async fn create(
    State(state): State<AppState>,
    claims: Claims,
    Json(input): Json<CreateChecklistItem>,
) -> Result<(StatusCode, Json<ChecklistItem>), AppError> {
    let user_id = parse_user_id(&claims)?;
    validate_input(&input.title, input.description.as_deref(), input.priority)?;

    let row = sqlx::query_as::<_, (Uuid,)>(
        "INSERT INTO checklist_items
            (user_id, title, description, due_date, estimated_minutes, priority,
             recurrence_days_mask, show_until_due)
         VALUES ($1, $2, $3, $4, $5, $6, $7, $8)
         RETURNING id",
    )
    .bind(user_id)
    .bind(input.title.trim())
    .bind(input.description.as_deref().map(str::trim))
    .bind(input.due_date)
    .bind(input.estimated_minutes)
    .bind(input.priority)
    .bind(input.recurrence_days_mask)
    .bind(input.show_until_due)
    .fetch_one(&state.pool)
    .await?;

    let item = fetch_one_for_user(&state.pool, row.0, user_id).await?;

    state
        .events
        .publish(user_id, SyncEvent::ChecklistCreated(item.clone()));

    Ok((StatusCode::CREATED, Json(item)))
}

async fn list(
    State(state): State<AppState>,
    claims: Claims,
    Query(params): Query<ListQuery>,
) -> Result<Json<Vec<ChecklistItem>>, AppError> {
    let user_id = parse_user_id(&claims)?;

    let start = params
        .start
        .unwrap_or_else(|| Utc::now().date_naive() - chrono::Duration::days(30));
    let end = params
        .end
        .unwrap_or_else(|| Utc::now().date_naive() + chrono::Duration::days(30));

    let items = if let Some(completed) = params.completed {
        sqlx::query_as::<_, ChecklistItem>(select_with_completions!(
            "WHERE ci.user_id = $1 AND ci.due_date BETWEEN $2 AND $3 AND ci.completed = $4 \
             ORDER BY ci.due_date ASC, ci.priority DESC, ci.created_at ASC"
        ))
            .bind(user_id)
            .bind(start)
            .bind(end)
            .bind(completed)
            .fetch_all(&state.pool)
            .await?
    } else {
        sqlx::query_as::<_, ChecklistItem>(select_with_completions!(
            "WHERE ci.user_id = $1 AND ci.due_date BETWEEN $2 AND $3 \
             ORDER BY ci.due_date ASC, ci.priority DESC, ci.created_at ASC"
        ))
            .bind(user_id)
            .bind(start)
            .bind(end)
            .fetch_all(&state.pool)
            .await?
    };

    Ok(Json(items))
}

async fn today(
    State(state): State<AppState>,
    claims: Claims,
) -> Result<Json<Vec<ChecklistItem>>, AppError> {
    let user_id = parse_user_id(&claims)?;
    // §i18n (v2.13.9) — "Today" is the user's local calendar day, not UTC's.
    let tz = crate::tz::fetch_user_tz(&state.pool, user_id).await?;
    let today = crate::tz::user_today(&tz);

    let items = sqlx::query_as::<_, ChecklistItem>(select_with_completions!(
        "WHERE ci.user_id = $1 AND ci.due_date = $2 \
         ORDER BY ci.completed ASC, ci.priority DESC, ci.created_at ASC"
    ))
        .bind(user_id)
        .bind(today)
        .fetch_all(&state.pool)
        .await?;

    Ok(Json(items))
}

async fn get_one(
    State(state): State<AppState>,
    claims: Claims,
    Path(id): Path<Uuid>,
) -> Result<Json<ChecklistItem>, AppError> {
    let user_id = parse_user_id(&claims)?;
    let item = fetch_one_for_user(&state.pool, id, user_id).await?;
    Ok(Json(item))
}

async fn update(
    State(state): State<AppState>,
    claims: Claims,
    Path(id): Path<Uuid>,
    Json(input): Json<UpdateChecklistItem>,
) -> Result<Json<ChecklistItem>, AppError> {
    let user_id = parse_user_id(&claims)?;

    let existing = fetch_one_for_user(&state.pool, id, user_id).await?;

    if let Some(p) = input.priority {
        if !(0..=2).contains(&p) {
            return Err(AppError::new(
                StatusCode::BAD_REQUEST,
                "priority must be 0, 1, or 2",
            ));
        }
    }

    let title = input
        .title
        .map(|t| t.trim().to_string())
        .unwrap_or(existing.title);
    if title.is_empty() {
        return Err(AppError::new(
            StatusCode::BAD_REQUEST,
            "title must not be empty",
        ));
    }
    cap_chars(&title, MAX_TITLE_CHARS, "title")?;
    let description = match input.description {
        Some(s) => Some(s),
        None => existing.description,
    };
    cap_chars_opt(&description, MAX_DESCRIPTION_CHARS, "description")?;
    let due_date = input.due_date.unwrap_or(existing.due_date);
    let estimated_minutes = match input.estimated_minutes {
        Some(m) => Some(m),
        None => existing.estimated_minutes,
    };
    let priority = input.priority.unwrap_or(existing.priority);
    let completed = input.completed.unwrap_or(existing.completed);
    let completed_at = if completed && !existing.completed {
        Some(Utc::now())
    } else if !completed {
        None
    } else {
        existing.completed_at
    };
    let recurrence_days_mask = input
        .recurrence_days_mask
        .unwrap_or(existing.recurrence_days_mask);
    let show_until_due = input.show_until_due.unwrap_or(existing.show_until_due);
    // last_completed_epoch_day is legacy: pre-024 clients still send it,
    // but server-side filtering ignores it. We write through whatever the
    // client sent so we don't fight the field; the source of truth for
    // recurring "done today" is checklist_completions.
    let last_completed_epoch_day = match input.last_completed_epoch_day {
        Some(v) => Some(v),
        None => existing.last_completed_epoch_day,
    };

    sqlx::query(
        "UPDATE checklist_items
         SET title = $1, description = $2, due_date = $3, estimated_minutes = $4,
             priority = $5, completed = $6, completed_at = $7,
             recurrence_days_mask = $8, show_until_due = $9,
             last_completed_epoch_day = $10, updated_at = NOW()
         WHERE id = $11 AND user_id = $12",
    )
    .bind(&title)
    .bind(&description)
    .bind(due_date)
    .bind(estimated_minutes)
    .bind(priority)
    .bind(completed)
    .bind(completed_at)
    .bind(recurrence_days_mask)
    .bind(show_until_due)
    .bind(last_completed_epoch_day)
    .bind(id)
    .bind(user_id)
    .execute(&state.pool)
    .await?;

    let item = fetch_one_for_user(&state.pool, id, user_id).await?;

    state
        .events
        .publish(user_id, SyncEvent::ChecklistUpdated(item.clone()));

    Ok(Json(item))
}

async fn complete(
    State(state): State<AppState>,
    claims: Claims,
    Path(id): Path<Uuid>,
) -> Result<Json<ChecklistItem>, AppError> {
    let user_id = parse_user_id(&claims)?;

    sqlx::query(
        "UPDATE checklist_items
         SET completed = TRUE, completed_at = NOW(), updated_at = NOW()
         WHERE id = $1 AND user_id = $2",
    )
    .bind(id)
    .bind(user_id)
    .execute(&state.pool)
    .await?;

    let item = fetch_one_for_user(&state.pool, id, user_id).await?;

    state
        .events
        .publish(user_id, SyncEvent::ChecklistUpdated(item.clone()));

    Ok(Json(item))
}

/// Symmetric inverse of `complete` for the non-recurring path. Also
/// clears the legacy `last_completed_epoch_day` so pre-024 clients see
/// a clean state. The per-day completion log for recurring items is
/// untouched — use `uncomplete_on` for that.
async fn uncomplete(
    State(state): State<AppState>,
    claims: Claims,
    Path(id): Path<Uuid>,
) -> Result<Json<ChecklistItem>, AppError> {
    let user_id = parse_user_id(&claims)?;

    sqlx::query(
        "UPDATE checklist_items
         SET completed = FALSE,
             completed_at = NULL,
             last_completed_epoch_day = NULL,
             updated_at = NOW()
         WHERE id = $1 AND user_id = $2",
    )
    .bind(id)
    .bind(user_id)
    .execute(&state.pool)
    .await?;

    let item = fetch_one_for_user(&state.pool, id, user_id).await?;

    state
        .events
        .publish(user_id, SyncEvent::ChecklistUpdated(item.clone()));

    Ok(Json(item))
}

/// §024 — Record that a recurring item was ticked on a specific epoch
/// day. Idempotent: re-posting for the same day is a no-op (ON CONFLICT
/// DO NOTHING). Non-recurring items should use the plain /complete
/// route instead; this endpoint refuses them so a client can't
/// accidentally accumulate per-day rows for a row that never repeats.
async fn complete_on(
    State(state): State<AppState>,
    claims: Claims,
    Path((id, epoch_day)): Path<(Uuid, i64)>,
) -> Result<Json<ChecklistItem>, AppError> {
    let user_id = parse_user_id(&claims)?;

    // Existence + ownership + recurrence check in one round trip.
    let row = sqlx::query_as::<_, (i16,)>(
        "SELECT recurrence_days_mask FROM checklist_items
         WHERE id = $1 AND user_id = $2",
    )
    .bind(id)
    .bind(user_id)
    .fetch_optional(&state.pool)
    .await?
    .ok_or_else(|| AppError::new(StatusCode::NOT_FOUND, "Checklist item not found"))?;

    if row.0 == 0 {
        return Err(AppError::new(
            StatusCode::BAD_REQUEST,
            "Item is not recurring — use /complete instead",
        ));
    }

    sqlx::query(
        "INSERT INTO checklist_completions (item_id, epoch_day)
         VALUES ($1, $2)
         ON CONFLICT (item_id, epoch_day) DO NOTHING",
    )
    .bind(id)
    .bind(epoch_day)
    .execute(&state.pool)
    .await?;

    // Bump updated_at so sync-pulls notice the change.
    sqlx::query(
        "UPDATE checklist_items SET updated_at = NOW() WHERE id = $1 AND user_id = $2",
    )
    .bind(id)
    .bind(user_id)
    .execute(&state.pool)
    .await?;

    let item = fetch_one_for_user(&state.pool, id, user_id).await?;

    state
        .events
        .publish(user_id, SyncEvent::ChecklistUpdated(item.clone()));

    Ok(Json(item))
}

/// §024 — Remove a recurring item's completion stamp for a specific
/// epoch day. Idempotent: deleting a non-existent (item, day) row
/// returns the current state without error.
async fn uncomplete_on(
    State(state): State<AppState>,
    claims: Claims,
    Path((id, epoch_day)): Path<(Uuid, i64)>,
) -> Result<Json<ChecklistItem>, AppError> {
    let user_id = parse_user_id(&claims)?;

    // Ownership check before mutating anything.
    let exists = sqlx::query_as::<_, (Uuid,)>(
        "SELECT id FROM checklist_items WHERE id = $1 AND user_id = $2",
    )
    .bind(id)
    .bind(user_id)
    .fetch_optional(&state.pool)
    .await?;
    if exists.is_none() {
        return Err(AppError::new(
            StatusCode::NOT_FOUND,
            "Checklist item not found",
        ));
    }

    sqlx::query(
        "DELETE FROM checklist_completions WHERE item_id = $1 AND epoch_day = $2",
    )
    .bind(id)
    .bind(epoch_day)
    .execute(&state.pool)
    .await?;

    sqlx::query(
        "UPDATE checklist_items SET updated_at = NOW() WHERE id = $1 AND user_id = $2",
    )
    .bind(id)
    .bind(user_id)
    .execute(&state.pool)
    .await?;

    let item = fetch_one_for_user(&state.pool, id, user_id).await?;

    state
        .events
        .publish(user_id, SyncEvent::ChecklistUpdated(item.clone()));

    Ok(Json(item))
}

async fn remove(
    State(state): State<AppState>,
    claims: Claims,
    Path(id): Path<Uuid>,
) -> Result<StatusCode, AppError> {
    let user_id = parse_user_id(&claims)?;

    let result = sqlx::query("DELETE FROM checklist_items WHERE id = $1 AND user_id = $2")
        .bind(id)
        .bind(user_id)
        .execute(&state.pool)
        .await?;

    if result.rows_affected() == 0 {
        return Err(AppError::new(StatusCode::NOT_FOUND, "Checklist item not found"));
    }

    state
        .events
        .publish(user_id, SyncEvent::ChecklistDeleted { id });

    Ok(StatusCode::NO_CONTENT)
}

async fn bulk_create(
    State(state): State<AppState>,
    claims: Claims,
    Json(items): Json<Vec<CreateChecklistItem>>,
) -> Result<(StatusCode, Json<Vec<ChecklistItem>>), AppError> {
    let user_id = parse_user_id(&claims)?;

    if items.is_empty() {
        return Ok((StatusCode::CREATED, Json(Vec::new())));
    }
    if items.len() > 200 {
        return Err(AppError::new(
            StatusCode::BAD_REQUEST,
            "bulk creates limited to 200 items",
        ));
    }

    for item in &items {
        validate_input(&item.title, item.description.as_deref(), item.priority)?;
    }

    let mut tx = state.pool.begin().await?;
    let mut created_ids = Vec::with_capacity(items.len());

    for input in items {
        let row = sqlx::query_as::<_, (Uuid,)>(
            "INSERT INTO checklist_items
                (user_id, title, description, due_date, estimated_minutes, priority,
                 recurrence_days_mask, show_until_due)
             VALUES ($1, $2, $3, $4, $5, $6, $7, $8)
             RETURNING id",
        )
        .bind(user_id)
        .bind(input.title.trim())
        .bind(input.description.as_deref().map(str::trim))
        .bind(input.due_date)
        .bind(input.estimated_minutes)
        .bind(input.priority)
        .bind(input.recurrence_days_mask)
        .bind(input.show_until_due)
        .fetch_one(&mut *tx)
        .await?;
        created_ids.push(row.0);
    }

    tx.commit().await?;

    // Newly-created rows have no completions, but we route through the
    // helper for consistency (single source of read-after-write logic).
    let mut created = Vec::with_capacity(created_ids.len());
    for id in created_ids {
        let item = fetch_one_for_user(&state.pool, id, user_id).await?;
        created.push(item);
    }

    for item in &created {
        state
            .events
            .publish(user_id, SyncEvent::ChecklistCreated(item.clone()));
    }

    Ok((StatusCode::CREATED, Json(created)))
}
