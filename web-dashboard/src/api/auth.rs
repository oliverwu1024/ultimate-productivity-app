use gloo_net::http::Request;
use serde::{Deserialize, Serialize};

use crate::api::client::{api_base_url, get, post, ApiError};
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

#[derive(Debug, Serialize)]
struct VerifyEmailRequest<'a> {
    token: &'a str,
}

/// Public — accepts the raw token from the email link. Backend hashes,
/// looks up, and flips `email_verified` on the matching row.
pub async fn verify_email(token: &str) -> Result<(), ApiError> {
    let body = VerifyEmailRequest { token };
    post_empty("/auth/verify-email", Some(&body), false).await
}

/// Auth-required — generates a fresh token and re-sends the email.
/// Idempotent for already-verified users (backend returns 200 without sending).
pub async fn resend_verification_email() -> Result<(), ApiError> {
    post_empty::<()>("/auth/verify-email/resend", None, true).await
}

/// Shared helper for POST endpoints that return 200 with an empty body —
/// the standard `post()` helper requires a JSON response, which would
/// fail here. Mirrors the bare-Request pattern used in reset_password.rs.
async fn post_empty<B: Serialize>(
    path: &str,
    body: Option<&B>,
    include_auth: bool,
) -> Result<(), ApiError> {
    let url = format!("{}{}", api_base_url(), path);
    let mut req = Request::post(&url).header("Content-Type", "application/json");
    if include_auth {
        if let Some(token) = AuthContext::token() {
            req = req.header("Authorization", &format!("Bearer {}", token));
        }
    }
    let resp = match body {
        Some(b) => req
            .json(b)
            .map_err(|e| ApiError { status: 0, message: e.to_string() })?
            .send()
            .await
            .map_err(|e| ApiError { status: 0, message: e.to_string() })?,
        None => req
            .send()
            .await
            .map_err(|e| ApiError { status: 0, message: e.to_string() })?,
    };
    let status = resp.status();
    if !(200..300).contains(&status) {
        let msg = resp
            .text()
            .await
            .ok()
            .and_then(|body| {
                serde_json::from_str::<serde_json::Value>(&body)
                    .ok()
                    .and_then(|v| v.get("error").and_then(|e| e.as_str()).map(|s| s.to_string()))
            })
            .unwrap_or_else(|| format!("HTTP {}", status));
        return Err(ApiError { status, message: msg });
    }
    Ok(())
}
