use axum::async_trait;
use axum::extract::FromRequestParts;
use axum::http::request::Parts;
use axum::http::StatusCode;
use jsonwebtoken::{decode, Algorithm, DecodingKey, Validation};
use serde::{Deserialize, Serialize};
use uuid::Uuid;

use crate::config::{AppState, JWT_AUDIENCE, JWT_ISSUER};

#[derive(Debug, Serialize, Deserialize)]
pub struct Claims {
    pub sub: String,
    pub exp: usize,
    pub iat: usize,
    pub iss: String,
    pub aud: String,
    /// Per-user revocation counter. Bumped on password change / reset so any
    /// previously-issued token (with the lower `tv`) stops validating.
    pub tv: i32,
}

/// Build the validator used everywhere a JWT is decoded. Pins HS256, issuer,
/// audience, and required claims so unrelated tokens (future SSE tickets,
/// password reset tokens, etc.) can never be substituted.
pub fn jwt_validator() -> Validation {
    let mut v = Validation::new(Algorithm::HS256);
    v.set_issuer(&[JWT_ISSUER]);
    v.set_audience(&[JWT_AUDIENCE]);
    v.set_required_spec_claims(&["exp", "sub", "aud", "iss"]);
    v.leeway = 30;
    v
}

#[async_trait]
impl FromRequestParts<AppState> for Claims {
    type Rejection = (StatusCode, String);

    async fn from_request_parts(parts: &mut Parts, state: &AppState) -> Result<Self, Self::Rejection> {
        let auth_header = parts
            .headers
            .get("Authorization")
            .and_then(|v| v.to_str().ok())
            .ok_or((StatusCode::UNAUTHORIZED, "Missing authorization header".into()))?;

        let token = auth_header
            .strip_prefix("Bearer ")
            .ok_or((StatusCode::UNAUTHORIZED, "Invalid authorization format".into()))?;

        let token_data = decode::<Claims>(
            token,
            &DecodingKey::from_secret(state.config.jwt_secret.as_bytes()),
            &jwt_validator(),
        )
        .map_err(|_| (StatusCode::UNAUTHORIZED, "Invalid or expired token".into()))?;

        // Revocation check: token's `tv` must match the user's current
        // token_version. Out-of-date tokens (issued before a password change
        // / reset) are rejected here, even though the JWT signature is valid.
        let user_id = token_data
            .claims
            .sub
            .parse::<Uuid>()
            .map_err(|_| (StatusCode::UNAUTHORIZED, "Invalid token subject".into()))?;
        let current_tv: Option<(i32,)> =
            sqlx::query_as("SELECT token_version FROM users WHERE id = $1")
                .bind(user_id)
                .fetch_optional(&state.pool)
                .await
                .map_err(|_| (StatusCode::INTERNAL_SERVER_ERROR, "auth lookup failed".into()))?;
        let current_tv = current_tv
            .ok_or((StatusCode::UNAUTHORIZED, "Invalid or expired token".into()))?
            .0;
        if current_tv != token_data.claims.tv {
            return Err((StatusCode::UNAUTHORIZED, "Invalid or expired token".into()));
        }

        Ok(token_data.claims)
    }
}
