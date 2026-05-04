use gloo_net::http::Request;
use serde::de::DeserializeOwned;
use serde::{Deserialize, Serialize};

use crate::auth::AuthContext;

fn api_base_url() -> &'static str {
    option_env!("API_BASE_URL").unwrap_or("https://api.ultiqapp.com")
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ApiError {
    pub status: u16,
    pub message: String,
}

impl ApiError {
    pub fn other(message: impl Into<String>) -> Self {
        Self {
            status: 0,
            message: message.into(),
        }
    }
}

pub async fn get<T: DeserializeOwned>(path: &str) -> Result<T, ApiError> {
    let url = format!("{}{}", api_base_url(), path);
    let mut req = Request::get(&url);
    if let Some(token) = AuthContext::token() {
        req = req.header("Authorization", &format!("Bearer {}", token));
    }
    let resp = req.send().await.map_err(|e| ApiError::other(e.to_string()))?;
    let status = resp.status();
    if !(200..300).contains(&status) {
        let message = error_message(resp).await;
        return Err(ApiError { status, message });
    }
    resp.json::<T>().await.map_err(|e| ApiError::other(e.to_string()))
}

pub async fn post<B: Serialize, T: DeserializeOwned>(
    path: &str,
    body: &B,
) -> Result<T, ApiError> {
    let url = format!("{}{}", api_base_url(), path);
    let mut req = Request::post(&url).header("Content-Type", "application/json");
    if let Some(token) = AuthContext::token() {
        req = req.header("Authorization", &format!("Bearer {}", token));
    }
    let resp = req
        .json(body)
        .map_err(|e| ApiError::other(e.to_string()))?
        .send()
        .await
        .map_err(|e| ApiError::other(e.to_string()))?;
    let status = resp.status();
    if !(200..300).contains(&status) {
        let message = error_message(resp).await;
        return Err(ApiError { status, message });
    }
    resp.json::<T>().await.map_err(|e| ApiError::other(e.to_string()))
}

async fn error_message(resp: gloo_net::http::Response) -> String {
    let body = resp.text().await.unwrap_or_default();
    if body.is_empty() {
        return "(no body)".to_string();
    }
    if let Ok(v) = serde_json::from_str::<serde_json::Value>(&body) {
        if let Some(msg) = v.get("error").and_then(|m| m.as_str()) {
            return msg.to_string();
        }
    }
    body
}
