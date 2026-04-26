use axum::extract::{Path, Query, State};
use axum::http::StatusCode;
use axum::routing::{get, post};
use axum::{Json, Router};
use chrono::{NaiveDate, Utc};
use uuid::Uuid;

use crate::config::AppState;
use crate::error::AppError;
use crate::middleware::auth::Claims;
use crate::models::checklist::{ChecklistItem, CreateChecklistItem, UpdateChecklistItem};

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

fn validate_input(title: &str, priority: i16) -> Result<(), AppError> {
    if title.trim().is_empty() {
        return Err(AppError::new(
            StatusCode::BAD_REQUEST,
            "title must not be empty",
        ));
    }
    if !(0..=2).contains(&priority) {
        return Err(AppError::new(
            StatusCode::BAD_REQUEST,
            "priority must be 0, 1, or 2",
        ));
    }
    Ok(())
}

async fn create(
    State(state): State<AppState>,
    claims: Claims,
    Json(input): Json<CreateChecklistItem>,
) -> Result<(StatusCode, Json<ChecklistItem>), AppError> {
    let user_id = parse_user_id(&claims)?;
    validate_input(&input.title, input.priority)?;

    let item = sqlx::query_as::<_, ChecklistItem>(
        "INSERT INTO checklist_items
            (user_id, title, description, due_date, estimated_minutes, priority)
         VALUES ($1, $2, $3, $4, $5, $6)
         RETURNING *",
    )
    .bind(user_id)
    .bind(input.title.trim())
    .bind(input.description.as_deref().map(str::trim))
    .bind(input.due_date)
    .bind(input.estimated_minutes)
    .bind(input.priority)
    .fetch_one(&state.pool)
    .await?;

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
        sqlx::query_as::<_, ChecklistItem>(
            "SELECT * FROM checklist_items
             WHERE user_id = $1 AND due_date BETWEEN $2 AND $3 AND completed = $4
             ORDER BY due_date ASC, priority DESC, created_at ASC",
        )
        .bind(user_id)
        .bind(start)
        .bind(end)
        .bind(completed)
        .fetch_all(&state.pool)
        .await?
    } else {
        sqlx::query_as::<_, ChecklistItem>(
            "SELECT * FROM checklist_items
             WHERE user_id = $1 AND due_date BETWEEN $2 AND $3
             ORDER BY due_date ASC, priority DESC, created_at ASC",
        )
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
    let today = Utc::now().date_naive();

    let items = sqlx::query_as::<_, ChecklistItem>(
        "SELECT * FROM checklist_items
         WHERE user_id = $1 AND due_date = $2
         ORDER BY completed ASC, priority DESC, created_at ASC",
    )
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

    let item = sqlx::query_as::<_, ChecklistItem>(
        "SELECT * FROM checklist_items WHERE id = $1 AND user_id = $2",
    )
    .bind(id)
    .bind(user_id)
    .fetch_optional(&state.pool)
    .await?
    .ok_or_else(|| AppError::new(StatusCode::NOT_FOUND, "Checklist item not found"))?;

    Ok(Json(item))
}

async fn update(
    State(state): State<AppState>,
    claims: Claims,
    Path(id): Path<Uuid>,
    Json(input): Json<UpdateChecklistItem>,
) -> Result<Json<ChecklistItem>, AppError> {
    let user_id = parse_user_id(&claims)?;

    let existing = sqlx::query_as::<_, ChecklistItem>(
        "SELECT * FROM checklist_items WHERE id = $1 AND user_id = $2",
    )
    .bind(id)
    .bind(user_id)
    .fetch_optional(&state.pool)
    .await?
    .ok_or_else(|| AppError::new(StatusCode::NOT_FOUND, "Checklist item not found"))?;

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
    let description = match input.description {
        Some(s) => Some(s),
        None => existing.description,
    };
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

    let item = sqlx::query_as::<_, ChecklistItem>(
        "UPDATE checklist_items
         SET title = $1, description = $2, due_date = $3, estimated_minutes = $4,
             priority = $5, completed = $6, completed_at = $7, updated_at = NOW()
         WHERE id = $8 AND user_id = $9
         RETURNING *",
    )
    .bind(&title)
    .bind(&description)
    .bind(due_date)
    .bind(estimated_minutes)
    .bind(priority)
    .bind(completed)
    .bind(completed_at)
    .bind(id)
    .bind(user_id)
    .fetch_one(&state.pool)
    .await?;

    Ok(Json(item))
}

async fn complete(
    State(state): State<AppState>,
    claims: Claims,
    Path(id): Path<Uuid>,
) -> Result<Json<ChecklistItem>, AppError> {
    let user_id = parse_user_id(&claims)?;

    let item = sqlx::query_as::<_, ChecklistItem>(
        "UPDATE checklist_items
         SET completed = TRUE, completed_at = NOW(), updated_at = NOW()
         WHERE id = $1 AND user_id = $2
         RETURNING *",
    )
    .bind(id)
    .bind(user_id)
    .fetch_optional(&state.pool)
    .await?
    .ok_or_else(|| AppError::new(StatusCode::NOT_FOUND, "Checklist item not found"))?;

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
        validate_input(&item.title, item.priority)?;
    }

    let mut tx = state.pool.begin().await?;
    let mut created = Vec::with_capacity(items.len());

    for input in items {
        let row = sqlx::query_as::<_, ChecklistItem>(
            "INSERT INTO checklist_items
                (user_id, title, description, due_date, estimated_minutes, priority)
             VALUES ($1, $2, $3, $4, $5, $6)
             RETURNING *",
        )
        .bind(user_id)
        .bind(input.title.trim())
        .bind(input.description.as_deref().map(str::trim))
        .bind(input.due_date)
        .bind(input.estimated_minutes)
        .bind(input.priority)
        .fetch_one(&mut *tx)
        .await?;
        created.push(row);
    }

    tx.commit().await?;
    Ok((StatusCode::CREATED, Json(created)))
}
