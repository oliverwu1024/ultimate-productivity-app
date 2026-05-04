use serde::{Deserialize, Serialize};

use crate::api::client::{get, post, ApiError};
use crate::auth::{AuthContext, User};

#[derive(Debug, Serialize)]
pub struct LoginRequest<'a> {
    pub email: &'a str,
    pub password: &'a str,
}

#[derive(Debug, Deserialize)]
pub struct AuthResponse {
    pub token: String,
    pub user: User,
}

pub async fn login(email: &str, password: &str) -> Result<AuthResponse, ApiError> {
    let body = LoginRequest { email, password };
    let resp: AuthResponse = post("/auth/login", &body).await?;
    AuthContext::set_token(&resp.token);
    Ok(resp)
}

pub async fn fetch_me() -> Result<User, ApiError> {
    get("/auth/me").await
}

pub fn logout() {
    AuthContext::clear_token();
}
