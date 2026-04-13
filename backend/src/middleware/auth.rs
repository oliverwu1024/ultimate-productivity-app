use axum::async_trait;
use axum::extract::FromRequestParts;
use axum::http::request::Parts;
use axum::http::StatusCode;
use jsonwebtoken::{decode, DecodingKey, Validation};
use serde::{Deserialize, Serialize};

use crate::config::AppState;

#[derive(Debug, Serialize, Deserialize)]
pub struct Claims {
    pub sub: String,
    pub exp: usize,
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
            &Validation::default(),
        )
        .map_err(|e| (StatusCode::UNAUTHORIZED, format!("Invalid token: {e}")))?;

        Ok(token_data.claims)
    }
}
