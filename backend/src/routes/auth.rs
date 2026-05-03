use axum::extract::State;
use axum::http::StatusCode;
use axum::routing::{delete, get, patch, post};
use axum::{Json, Router};
use chrono::Utc;
use jsonwebtoken::{encode, EncodingKey, Header};
use uuid::Uuid;

use crate::config::AppState;
use crate::error::AppError;
use crate::middleware::auth::Claims;
use crate::models::user::{
    AuthResponse, ChangePassword, CreateUser, ForgotPassword, LoginUser, ResetPassword,
    UpdateProfile, User, UserResponse,
};

use argon2::{
    password_hash::{rand_core::OsRng, PasswordHash, PasswordHasher, PasswordVerifier, SaltString},
    Argon2,
};
use sha2::{Digest, Sha256};

pub fn router() -> Router<AppState> {
    Router::new()
        .route("/auth/register", post(register))
        .route("/auth/login", post(login))
        .route("/auth/me", get(me))
        .route("/auth/me", patch(update_profile))
        .route("/auth/me", delete(delete_account))
        .route("/auth/reset", post(reset_account))
        .route("/auth/password", post(change_password))
        .route("/auth/password/forgot", post(forgot_password))
        .route("/auth/password/reset", post(reset_password))
}

async fn register(
    State(state): State<AppState>,
    Json(input): Json<CreateUser>,
) -> Result<Json<AuthResponse>, AppError> {
    // Validate email
    if input.email.is_empty() || !input.email.contains('@') {
        return Err(AppError::new(StatusCode::BAD_REQUEST, "Invalid email"));
    }

    validate_password_strength(&input.password)?;

    // Hash password
    let salt = SaltString::generate(&mut OsRng);
    let password_hash = Argon2::default()
        .hash_password(input.password.as_bytes(), &salt)
        .map_err(|_| AppError::new(StatusCode::INTERNAL_SERVER_ERROR, "Failed to hash password"))?
        .to_string();

    // Insert user — unique constraint handles duplicate emails
    let user = sqlx::query_as::<_, User>(
        "INSERT INTO users (email, password_hash) VALUES ($1, $2) RETURNING *",
    )
    .bind(&input.email)
    .bind(&password_hash)
    .fetch_one(&state.pool)
    .await
    .map_err(|e| match &e {
        sqlx::Error::Database(db_err) if db_err.code().as_deref() == Some("23505") => {
            AppError::new(StatusCode::CONFLICT, "Email already registered")
        }
        _ => e.into(),
    })?;

    // Generate JWT
    let token = create_token(&user.id, &state.config.jwt_secret)?;

    Ok(Json(AuthResponse {
        token,
        user: user.into(),
    }))
}

async fn login(
    State(state): State<AppState>,
    Json(input): Json<LoginUser>,
) -> Result<Json<AuthResponse>, AppError> {
    // Find user by email
    let user = sqlx::query_as::<_, User>("SELECT * FROM users WHERE email = $1")
        .bind(&input.email)
        .fetch_optional(&state.pool)
        .await?
        .ok_or_else(|| AppError::new(StatusCode::UNAUTHORIZED, "Invalid email or password"))?;

    // Verify password
    let parsed_hash = PasswordHash::new(&user.password_hash)
        .map_err(|_| AppError::new(StatusCode::INTERNAL_SERVER_ERROR, "Internal server error"))?;

    Argon2::default()
        .verify_password(input.password.as_bytes(), &parsed_hash)
        .map_err(|_| AppError::new(StatusCode::UNAUTHORIZED, "Invalid email or password"))?;

    // Generate JWT
    let token = create_token(&user.id, &state.config.jwt_secret)?;

    Ok(Json(AuthResponse {
        token,
        user: user.into(),
    }))
}

async fn me(
    State(state): State<AppState>,
    claims: Claims,
) -> Result<Json<UserResponse>, AppError> {
    let user_id = parse_user_id(&claims)?;

    let user = sqlx::query_as::<_, User>("SELECT * FROM users WHERE id = $1")
        .bind(user_id)
        .fetch_optional(&state.pool)
        .await?
        .ok_or_else(|| AppError::new(StatusCode::UNAUTHORIZED, "User not found"))?;

    Ok(Json(user.into()))
}

async fn update_profile(
    State(state): State<AppState>,
    claims: Claims,
    Json(input): Json<UpdateProfile>,
) -> Result<Json<UserResponse>, AppError> {
    let user_id = parse_user_id(&claims)?;

    if let Some(target) = input.sleep_target_minutes {
        if !(180..=900).contains(&target) {
            return Err(AppError::new(
                StatusCode::BAD_REQUEST,
                "sleep_target_minutes must be between 180 and 900 (3h–15h)",
            ));
        }
    }

    let user = sqlx::query_as::<_, User>(
        "UPDATE users
         SET sleep_target_minutes = COALESCE($1, sleep_target_minutes)
         WHERE id = $2
         RETURNING *",
    )
    .bind(input.sleep_target_minutes)
    .bind(user_id)
    .fetch_optional(&state.pool)
    .await?
    .ok_or_else(|| AppError::new(StatusCode::UNAUTHORIZED, "User not found"))?;

    Ok(Json(user.into()))
}

async fn delete_account(
    State(state): State<AppState>,
    claims: Claims,
) -> Result<StatusCode, AppError> {
    let user_id = parse_user_id(&claims)?;
    // ON DELETE CASCADE on user_id columns wipes sleep records, sessions, calendar
    // events, checklist items, phone pickups, etc. in one shot.
    let result = sqlx::query("DELETE FROM users WHERE id = $1")
        .bind(user_id)
        .execute(&state.pool)
        .await?;
    if result.rows_affected() == 0 {
        return Err(AppError::new(StatusCode::NOT_FOUND, "User not found"));
    }
    Ok(StatusCode::NO_CONTENT)
}

async fn reset_account(
    State(state): State<AppState>,
    claims: Claims,
) -> Result<StatusCode, AppError> {
    let user_id = parse_user_id(&claims)?;
    // Wipe data tables but keep the user row + auth credentials.
    // Order matters only for tables without CASCADE on each other.
    let mut tx = state.pool.begin().await?;
    sqlx::query("DELETE FROM phone_pickups WHERE user_id = $1")
        .bind(user_id).execute(&mut *tx).await?;
    sqlx::query("DELETE FROM productivity_sessions WHERE user_id = $1")
        .bind(user_id).execute(&mut *tx).await?;
    sqlx::query("DELETE FROM sleep_records WHERE user_id = $1")
        .bind(user_id).execute(&mut *tx).await?;
    sqlx::query("DELETE FROM calendar_events WHERE user_id = $1")
        .bind(user_id).execute(&mut *tx).await?;
    sqlx::query("DELETE FROM checklist_items WHERE user_id = $1")
        .bind(user_id).execute(&mut *tx).await?;
    tx.commit().await?;
    Ok(StatusCode::NO_CONTENT)
}

async fn change_password(
    State(state): State<AppState>,
    claims: Claims,
    Json(input): Json<ChangePassword>,
) -> Result<StatusCode, AppError> {
    let user_id = parse_user_id(&claims)?;

    // Look up current hash
    let user = sqlx::query_as::<_, User>("SELECT * FROM users WHERE id = $1")
        .bind(user_id)
        .fetch_optional(&state.pool)
        .await?
        .ok_or_else(|| AppError::new(StatusCode::UNAUTHORIZED, "User not found"))?;

    // Verify current password
    let parsed_hash = PasswordHash::new(&user.password_hash)
        .map_err(|_| AppError::new(StatusCode::INTERNAL_SERVER_ERROR, "Internal server error"))?;
    Argon2::default()
        .verify_password(input.current_password.as_bytes(), &parsed_hash)
        .map_err(|_| AppError::new(StatusCode::UNAUTHORIZED, "Current password is incorrect"))?;

    // Validate new password
    validate_password_strength(&input.new_password)?;
    if input.new_password == input.current_password {
        return Err(AppError::new(
            StatusCode::BAD_REQUEST,
            "New password must differ from the current password",
        ));
    }

    // Hash and store
    let salt = SaltString::generate(&mut OsRng);
    let new_hash = Argon2::default()
        .hash_password(input.new_password.as_bytes(), &salt)
        .map_err(|_| AppError::new(StatusCode::INTERNAL_SERVER_ERROR, "Failed to hash password"))?
        .to_string();

    sqlx::query("UPDATE users SET password_hash = $1 WHERE id = $2")
        .bind(&new_hash)
        .bind(user_id)
        .execute(&state.pool)
        .await?;

    Ok(StatusCode::NO_CONTENT)
}

async fn forgot_password(
    State(state): State<AppState>,
    Json(input): Json<ForgotPassword>,
) -> Result<StatusCode, AppError> {
    // Always 200 — never reveal whether the email is registered (account-enumeration defence).
    let user = sqlx::query_as::<_, User>("SELECT * FROM users WHERE email = $1")
        .bind(&input.email)
        .fetch_optional(&state.pool)
        .await?;

    if let Some(user) = user {
        // Invalidate any prior unused tokens so only the freshest works.
        sqlx::query(
            "UPDATE password_reset_tokens SET used_at = now() \
             WHERE user_id = $1 AND used_at IS NULL",
        )
        .bind(user.id)
        .execute(&state.pool)
        .await?;

        // Generate a fresh token, store its sha256 (never the raw value).
        let raw = Uuid::new_v4().to_string();
        let hash = sha256_hex(&raw);
        let expires_at = Utc::now() + chrono::Duration::hours(1);

        sqlx::query(
            "INSERT INTO password_reset_tokens (user_id, token_hash, expires_at) \
             VALUES ($1, $2, $3)",
        )
        .bind(user.id)
        .bind(&hash)
        .bind(expires_at)
        .execute(&state.pool)
        .await?;

        if let Err(e) = send_reset_email(&state, &user.email, &raw).await {
            tracing::error!("Failed to send password reset email: {:?}", e);
            // Still 200 — user can retry. Don't leak SES errors to the client.
        }
    }

    Ok(StatusCode::OK)
}

async fn reset_password(
    State(state): State<AppState>,
    Json(input): Json<ResetPassword>,
) -> Result<StatusCode, AppError> {
    let token_hash = sha256_hex(&input.token);

    // Same generic error for every failure mode — don't help attackers distinguish.
    let invalid = || AppError::new(StatusCode::BAD_REQUEST, "Invalid or expired reset link");

    let row = sqlx::query_as::<_, ResetTokenRow>(
        "SELECT id, user_id, expires_at, used_at \
         FROM password_reset_tokens WHERE token_hash = $1",
    )
    .bind(&token_hash)
    .fetch_optional(&state.pool)
    .await?
    .ok_or_else(invalid)?;

    if row.used_at.is_some() || row.expires_at < Utc::now() {
        return Err(invalid());
    }

    validate_password_strength(&input.new_password)?;

    let salt = SaltString::generate(&mut OsRng);
    let new_hash = Argon2::default()
        .hash_password(input.new_password.as_bytes(), &salt)
        .map_err(|_| {
            AppError::new(StatusCode::INTERNAL_SERVER_ERROR, "Failed to hash password")
        })?
        .to_string();

    let mut tx = state.pool.begin().await?;
    sqlx::query("UPDATE users SET password_hash = $1 WHERE id = $2")
        .bind(&new_hash)
        .bind(row.user_id)
        .execute(&mut *tx)
        .await?;
    sqlx::query("UPDATE password_reset_tokens SET used_at = now() WHERE id = $1")
        .bind(row.id)
        .execute(&mut *tx)
        .await?;
    tx.commit().await?;

    Ok(StatusCode::OK)
}

#[derive(sqlx::FromRow)]
struct ResetTokenRow {
    id: Uuid,
    user_id: Uuid,
    expires_at: chrono::DateTime<Utc>,
    used_at: Option<chrono::DateTime<Utc>>,
}

fn sha256_hex(s: &str) -> String {
    let digest = Sha256::digest(s.as_bytes());
    digest.iter().map(|b| format!("{:02x}", b)).collect()
}

async fn send_reset_email(state: &AppState, to: &str, token: &str) -> Result<(), AppError> {
    let link = format!("https://ultiqapp.com/reset?token={}", token);
    let body_text = format!(
        "Hi,\n\nWe received a request to reset your Ultiq password. Tap the link below to choose a new password:\n\n{}\n\nThis link expires in 1 hour. If you didn't request a reset, you can safely ignore this email.\n\n— Ultiq",
        link
    );
    let body_html = format!(
        "<div style=\"font-family:system-ui,-apple-system,Segoe UI,Roboto,sans-serif;color:#2A1B6E;line-height:1.55;\">\
         <p>Hi,</p>\
         <p>We received a request to reset your Ultiq password. Tap the button below to choose a new password:</p>\
         <p style=\"margin:24px 0\"><a href=\"{link}\" style=\"display:inline-block;padding:12px 28px;background:#2A1B6E;color:#FFF4E6;border-radius:24px;text-decoration:none;font-weight:600;\">Reset password</a></p>\
         <p style=\"font-size:14px;color:#2A1B6Eaa\">If the button doesn't open the app, copy this link into Ultiq:<br><code style=\"background:#FFF4E6;padding:4px 8px;border-radius:4px;\">{link}</code></p>\
         <p style=\"font-size:14px;color:#2A1B6Eaa\">This link expires in 1 hour. If you didn't request a reset, you can safely ignore this email.</p>\
         <p style=\"margin-top:32px;color:#2A1B6E\">— Ultiq</p></div>",
        link = link
    );

    state
        .email
        .send(
            &state.config.from_address,
            &state.config.reply_to,
            to,
            "Reset your Ultiq password",
            &body_text,
            &body_html,
        )
        .await
}

fn validate_password_strength(password: &str) -> Result<(), AppError> {
    if password.len() < 8 {
        return Err(AppError::new(
            StatusCode::BAD_REQUEST,
            "Password must be at least 8 characters",
        ));
    }
    let has_upper = password.chars().any(|c| c.is_ascii_uppercase());
    let has_lower = password.chars().any(|c| c.is_ascii_lowercase());
    let has_digit = password.chars().any(|c| c.is_ascii_digit());
    let has_special = password.chars().any(|c| !c.is_ascii_alphanumeric() && !c.is_whitespace());
    let mut missing: Vec<&str> = Vec::new();
    if !has_upper { missing.push("uppercase letter"); }
    if !has_lower { missing.push("lowercase letter"); }
    if !has_digit { missing.push("digit"); }
    if !has_special { missing.push("special character"); }
    if !missing.is_empty() {
        return Err(AppError::new(
            StatusCode::BAD_REQUEST,
            format!("Password must include: {}", missing.join(", ")),
        ));
    }
    Ok(())
}

fn parse_user_id(claims: &Claims) -> Result<Uuid, AppError> {
    claims
        .sub
        .parse::<Uuid>()
        .map_err(|_| AppError::new(StatusCode::UNAUTHORIZED, "Invalid token"))
}

fn create_token(user_id: &Uuid, jwt_secret: &str) -> Result<String, AppError> {
    let expiry = Utc::now()
        .checked_add_signed(chrono::Duration::days(365))
        .expect("valid timestamp")
        .timestamp() as usize;

    let claims = Claims {
        sub: user_id.to_string(),
        exp: expiry,
    };

    encode(
        &Header::default(),
        &claims,
        &EncodingKey::from_secret(jwt_secret.as_bytes()),
    )
    .map_err(|_| AppError::new(StatusCode::INTERNAL_SERVER_ERROR, "Failed to create token"))
}
