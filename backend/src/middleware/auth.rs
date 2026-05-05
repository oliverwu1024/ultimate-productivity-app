use axum::async_trait;
use axum::extract::FromRequestParts;
use axum::http::request::Parts;
use axum::http::StatusCode;
use jsonwebtoken::{decode, Algorithm, DecodingKey, Validation};
use serde::{Deserialize, Serialize};

use crate::config::{AppState, JWT_AUDIENCE, JWT_ISSUER};

#[derive(Debug, Serialize, Deserialize)]
pub struct Claims {
    pub sub: String,
    pub exp: usize,
    pub iat: usize,
    pub iss: String,
    pub aud: String,
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

        Ok(token_data.claims)
    }
}
