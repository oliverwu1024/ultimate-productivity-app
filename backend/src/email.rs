use axum::http::StatusCode;
use reqwest::Client;
use serde_json::json;

use crate::error::AppError;

#[derive(Clone)]
pub struct EmailClient {
    http: Client,
    api_key: String,
}

impl EmailClient {
    pub fn new(api_key: String) -> Self {
        Self { http: Client::new(), api_key }
    }

    pub async fn send(
        &self,
        from: &str,
        reply_to: &str,
        to: &str,
        subject: &str,
        text: &str,
        html: &str,
    ) -> Result<(), AppError> {
        let body = json!({
            "from": from,
            "reply_to": reply_to,
            "to": [to],
            "subject": subject,
            "text": text,
            "html": html,
        });

        let resp = self
            .http
            .post("https://api.resend.com/emails")
            .bearer_auth(&self.api_key)
            .json(&body)
            .send()
            .await
            .map_err(|e| {
                tracing::error!("Resend request failed: {:?}", e);
                AppError::new(StatusCode::INTERNAL_SERVER_ERROR, "Failed to send email")
            })?;

        if !resp.status().is_success() {
            let status = resp.status();
            let body = resp.text().await.unwrap_or_default();
            tracing::error!("Resend returned {}: {}", status, body);
            return Err(AppError::new(
                StatusCode::INTERNAL_SERVER_ERROR,
                "Failed to send email",
            ));
        }

        Ok(())
    }
}
