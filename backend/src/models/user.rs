use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use serde_json::Value as JsonValue;
use uuid::Uuid;

#[derive(Debug, sqlx::FromRow)]
pub struct User {
    pub id: Uuid,
    pub email: String,
    pub password_hash: String,
    pub created_at: DateTime<Utc>,
    pub sleep_target_minutes: i32,
    pub is_admin: bool,
    pub token_version: i32,
    pub preferences: JsonValue,
}

#[derive(Debug, Deserialize)]
pub struct UpdateProfile {
    pub sleep_target_minutes: Option<i32>,
    /// §sync-prefs: a partial preferences blob, merged into the existing
    /// JSONB column via `||`. Send only the keys you want to change; the
    /// rest stay as they were. Null clears the column to `{}`.
    pub preferences: Option<JsonValue>,
}

#[derive(Debug, Deserialize)]
pub struct CreateUser {
    pub email: String,
    pub password: String,
}

#[derive(Debug, Deserialize)]
pub struct LoginUser {
    pub email: String,
    pub password: String,
}

#[derive(Debug, Deserialize)]
pub struct ChangePassword {
    pub current_password: String,
    pub new_password: String,
}

#[derive(Debug, Deserialize)]
pub struct ForgotPassword {
    pub email: String,
}

#[derive(Debug, Deserialize)]
pub struct ResetPassword {
    pub token: String,
    pub new_password: String,
}

#[derive(Debug, Serialize)]
pub struct UserResponse {
    pub id: Uuid,
    pub email: String,
    pub created_at: DateTime<Utc>,
    pub sleep_target_minutes: i32,
    pub is_admin: bool,
    pub preferences: JsonValue,
}

#[derive(Debug, Serialize)]
pub struct AuthResponse {
    pub token: String,
    pub user: UserResponse,
}

impl From<User> for UserResponse {
    fn from(user: User) -> Self {
        Self {
            id: user.id,
            email: user.email,
            created_at: user.created_at,
            sleep_target_minutes: user.sleep_target_minutes,
            is_admin: user.is_admin,
            preferences: user.preferences,
        }
    }
}
