use axum::http::StatusCode;
use axum::response::{IntoResponse, Response};
use serde_json::json;

#[derive(Debug)]
pub struct AppError {
    pub status: StatusCode,
    pub message: String,
}

impl AppError {
    pub fn new(status: StatusCode, message: impl Into<String>) -> Self {
        Self {
            status,
            message: message.into(),
        }
    }
}

impl IntoResponse for AppError {
    fn into_response(self) -> Response {
        let body = json!({ "error": self.message });
        (self.status, axum::Json(body)).into_response()
    }
}

impl From<sqlx::Error> for AppError {
    fn from(err: sqlx::Error) -> Self {
        // Display, not Debug — the Debug impl can include bound parameter
        // previews (user emails, sleep notes, etc.) which would persist in
        // CloudWatch with default retention.
        tracing::error!("Database error: {}", err);
        AppError::new(StatusCode::INTERNAL_SERVER_ERROR, "Internal server error")
    }
}
