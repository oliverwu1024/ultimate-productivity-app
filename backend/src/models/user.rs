use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use uuid::Uuid;

#[derive(Debug, sqlx::FromRow)]
pub struct User {
    pub id: Uuid,
    pub email: String,
    pub password_hash: String,
    pub created_at: DateTime<Utc>,
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

#[derive(Debug, Serialize)]
pub struct UserResponse {
    pub id: Uuid,
    pub email: String,
    pub created_at: DateTime<Utc>,
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
        }
    }
}
