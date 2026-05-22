// §9.8 — Firebase Cloud Messaging HTTP v1 client.
//
// Phase 9.8 (anomaly detection) needs to push notifications to user devices.
// We avoid the firebase-admin SDK (Node/Java/Python only) and talk to FCM's
// HTTP v1 API directly:
//
//   POST https://fcm.googleapis.com/v1/projects/{project_id}/messages:send
//   Authorization: Bearer <oauth2_access_token>
//   Body: { "message": { "token": "...", "notification": {...}, "data": {...} } }
//
// The access token is minted by signing a JWT with the Firebase service-account
// private key (RS256) and exchanging it at Google's OAuth2 token endpoint. The
// token lasts 1h; we cache it in-memory and refresh ~5 min before expiry.
//
// **Credential loading**
// - Local dev: read the path from env `GOOGLE_APPLICATION_CREDENTIALS`. JSON
//   schema follows Google's standard service-account file.
// - Prod: same env var, but the file is materialised at boot from AWS Secrets
//   Manager (wired separately in step 5 of Phase A).
//
// **Failure mode**
// If credentials aren't available (CI, fresh dev, no FCM setup yet), the
// client returns `Ok(None)` from `try_new`. Callers MUST handle this — the
// backend should keep running so non-push features work.

use std::sync::Arc;
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use chrono::Utc;
use jsonwebtoken::{encode, Algorithm, EncodingKey, Header};
use reqwest::StatusCode;
use serde::{Deserialize, Serialize};
use serde_json::json;
use sqlx::PgPool;
use tokio::sync::Mutex;
use uuid::Uuid;

use crate::error::AppError;

const FCM_BASE_URL: &str = "https://fcm.googleapis.com/v1/projects";
const OAUTH_TOKEN_URL: &str = "https://oauth2.googleapis.com/token";
const FCM_OAUTH_SCOPE: &str = "https://www.googleapis.com/auth/firebase.messaging";
/// Refresh the OAuth token this many seconds before it actually expires so
/// we never race with a hot in-flight push attempting the call right at the
/// boundary.
const TOKEN_REFRESH_LEEWAY_SECS: i64 = 300;
/// Cap a single FCM HTTP call at this many seconds. FCM normally responds in
/// ~100-500ms; >10s almost always means a network problem worth surfacing.
const FCM_HTTP_TIMEOUT_SECS: u64 = 10;

/// Service-account JSON shape. Only the four fields we actually need are
/// extracted; the file has ~10 fields total but the rest are ignored.
#[derive(Debug, Deserialize)]
struct ServiceAccount {
    project_id: String,
    private_key: String,
    client_email: String,
    token_uri: Option<String>,
}

/// In-memory token cache. Mutex-protected so concurrent push fan-out shares
/// one round-trip to Google's OAuth endpoint.
struct CachedToken {
    access_token: String,
    expires_at_unix: i64,
}

#[derive(Clone)]
pub struct FcmClient {
    project_id: String,
    client_email: String,
    encoding_key: Arc<EncodingKey>,
    token_uri: String,
    http: reqwest::Client,
    cache: Arc<Mutex<Option<CachedToken>>>,
}

impl FcmClient {
    /// Construct a client from either:
    /// 1. `FIREBASE_ADMIN_JSON` — the service-account JSON contents inline.
    ///    Pairs with AWS Secrets Manager's task-def `secrets` block, which
    ///    pulls the secret value into the env var at container boot.
    /// 2. `GOOGLE_APPLICATION_CREDENTIALS` — a filesystem path to the JSON.
    ///    Google's standard convention; the natural fit for local dev.
    ///
    /// Returns `Ok(None)` when neither is set — the "running without push"
    /// mode (CI, fresh dev, etc.) — so the rest of the backend stays up.
    pub fn try_new() -> Result<Option<Self>, String> {
        // Inline env first (prod path); file path as the fallback (local dev).
        let raw = if let Ok(json) = std::env::var("FIREBASE_ADMIN_JSON") {
            if json.trim().is_empty() {
                tracing::warn!(
                    target: "fcm",
                    "FIREBASE_ADMIN_JSON is empty — FCM push disabled",
                );
                return Ok(None);
            }
            json
        } else {
            let path = match std::env::var("GOOGLE_APPLICATION_CREDENTIALS") {
                Ok(p) if !p.trim().is_empty() => p,
                _ => {
                    tracing::warn!(
                        target: "fcm",
                        "neither FIREBASE_ADMIN_JSON nor GOOGLE_APPLICATION_CREDENTIALS \
                         set — FCM push disabled",
                    );
                    return Ok(None);
                }
            };
            match std::fs::read_to_string(&path) {
                Ok(s) => s,
                Err(e) => {
                    tracing::error!(
                        target: "fcm",
                        "GOOGLE_APPLICATION_CREDENTIALS={path} but file read failed: {e}",
                    );
                    return Ok(None);
                }
            }
        };
        let account: ServiceAccount = serde_json::from_str(&raw)
            .map_err(|e| format!("service account JSON parse failed: {e}"))?;
        // PKCS#8 PEM from Google ("BEGIN PRIVATE KEY"); jsonwebtoken accepts
        // both PKCS#1 and PKCS#8 via from_rsa_pem.
        let encoding_key = EncodingKey::from_rsa_pem(account.private_key.as_bytes())
            .map_err(|e| format!("service account private_key not a valid RSA PEM: {e}"))?;
        let http = reqwest::Client::builder()
            .timeout(Duration::from_secs(FCM_HTTP_TIMEOUT_SECS))
            .build()
            .map_err(|e| format!("reqwest build failed: {e}"))?;
        Ok(Some(Self {
            project_id: account.project_id,
            client_email: account.client_email,
            encoding_key: Arc::new(encoding_key),
            token_uri: account
                .token_uri
                .unwrap_or_else(|| OAUTH_TOKEN_URL.to_string()),
            http,
            cache: Arc::new(Mutex::new(None)),
        }))
    }

    /// Project ID from the loaded service account. Used by the FCM endpoint URL.
    pub fn project_id(&self) -> &str {
        &self.project_id
    }

    /// Send a notification to one FCM token. Returns Ok(()) on success or
    /// a typed error tagged with the FCM response so the caller can decide
    /// whether to prune the token from `device_tokens` (e.g. on NOT_FOUND
    /// / UNREGISTERED).
    pub async fn send_to_token(
        &self,
        token: &str,
        title: &str,
        body: &str,
        data: Option<serde_json::Value>,
    ) -> Result<(), FcmSendError> {
        let access_token = self.access_token().await?;
        let url = format!("{FCM_BASE_URL}/{}/messages:send", self.project_id);

        let mut message = json!({
            "token": token,
            "notification": { "title": title, "body": body },
            "android": {
                // High priority so the message wakes the device on Doze.
                // FCM's "normal" priority can defer by minutes-to-hours.
                "priority": "high",
            },
        });
        if let Some(d) = data {
            // FCM "data" payload values must all be strings. Coerce by
            // serialising — caller is expected to pass a JSON object whose
            // values are already strings, but we don't enforce here; FCM
            // will reject with a 400 if types are wrong, and the error
            // body is included in `FcmSendError::Http`.
            message["data"] = d;
        }

        let resp = self
            .http
            .post(&url)
            .bearer_auth(&access_token)
            .json(&json!({ "message": message }))
            .send()
            .await
            .map_err(|e| FcmSendError::Transport(e.to_string()))?;

        let status = resp.status();
        if status.is_success() {
            return Ok(());
        }
        let body = resp.text().await.unwrap_or_default();
        // Map common terminal-failure cases. FCM v1 returns a JSON body with
        // `error.status` (e.g. "NOT_FOUND" or "UNREGISTERED") when the token
        // is invalid — the caller should delete that row from device_tokens.
        let is_invalid_token = status == StatusCode::NOT_FOUND
            || body.contains("UNREGISTERED")
            || body.contains("INVALID_ARGUMENT");
        if is_invalid_token {
            return Err(FcmSendError::InvalidToken(body));
        }
        Err(FcmSendError::Http {
            status: status.as_u16(),
            body,
        })
    }

    /// Fan-out helper: send the same notification to every device registered
    /// for `user_id`. Per-device failures are logged and individually
    /// classified; if a token comes back as invalid we delete it. Returns
    /// the number of devices that received the push successfully.
    pub async fn send_to_user(
        &self,
        pool: &PgPool,
        user_id: Uuid,
        title: &str,
        body: &str,
        data: Option<serde_json::Value>,
    ) -> Result<usize, AppError> {
        let tokens: Vec<(Uuid, String)> = sqlx::query_as(
            "SELECT id, token FROM device_tokens WHERE user_id = $1",
        )
        .bind(user_id)
        .fetch_all(pool)
        .await?;

        if tokens.is_empty() {
            tracing::info!(
                target: "fcm",
                user = %user_id,
                "send_to_user: no device tokens registered",
            );
            return Ok(0);
        }

        let mut delivered = 0usize;
        for (row_id, tok) in &tokens {
            match self.send_to_token(tok, title, body, data.clone()).await {
                Ok(()) => delivered += 1,
                Err(FcmSendError::InvalidToken(detail)) => {
                    tracing::warn!(
                        target: "fcm",
                        user = %user_id,
                        device = %row_id,
                        "FCM reported invalid token; pruning row: {detail}",
                    );
                    let _ = sqlx::query("DELETE FROM device_tokens WHERE id = $1")
                        .bind(row_id)
                        .execute(pool)
                        .await;
                }
                Err(e) => {
                    tracing::warn!(
                        target: "fcm",
                        user = %user_id,
                        device = %row_id,
                        "FCM send failed: {e:?}",
                    );
                }
            }
        }
        Ok(delivered)
    }

    /// Mint a fresh OAuth2 bearer token via JWT-bearer grant, or return the
    /// cached one if it's still within the refresh window.
    async fn access_token(&self) -> Result<String, FcmSendError> {
        let now = Utc::now().timestamp();
        {
            let cache = self.cache.lock().await;
            if let Some(c) = cache.as_ref() {
                if c.expires_at_unix - now > TOKEN_REFRESH_LEEWAY_SECS {
                    return Ok(c.access_token.clone());
                }
            }
        }

        // Build the assertion JWT.
        let iat = now;
        let exp = iat + 3600; // Max allowed by Google.
        let claims = OauthAssertion {
            iss: self.client_email.clone(),
            scope: FCM_OAUTH_SCOPE.to_string(),
            aud: self.token_uri.clone(),
            iat,
            exp,
        };
        let header = Header::new(Algorithm::RS256);
        let assertion = encode(&header, &claims, &self.encoding_key)
            .map_err(|e| FcmSendError::Transport(format!("JWT sign failed: {e}")))?;

        let resp = self
            .http
            .post(&self.token_uri)
            .form(&[
                ("grant_type", "urn:ietf:params:oauth:grant-type:jwt-bearer"),
                ("assertion", &assertion),
            ])
            .send()
            .await
            .map_err(|e| FcmSendError::Transport(e.to_string()))?;
        if !resp.status().is_success() {
            let status = resp.status().as_u16();
            let body = resp.text().await.unwrap_or_default();
            return Err(FcmSendError::Http { status, body });
        }
        let body: OauthTokenResponse = resp
            .json()
            .await
            .map_err(|e| FcmSendError::Transport(format!("oauth body parse failed: {e}")))?;

        let mut cache = self.cache.lock().await;
        // expires_in is seconds; subtract leeway again so we don't keep
        // serving a token that the next caller will immediately re-mint.
        let unix_now = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .map(|d| d.as_secs() as i64)
            .unwrap_or(now);
        *cache = Some(CachedToken {
            access_token: body.access_token.clone(),
            expires_at_unix: unix_now + body.expires_in,
        });
        Ok(body.access_token)
    }
}

#[derive(Debug)]
pub enum FcmSendError {
    /// Network/transport-level failure — DNS, TLS, connect timeout, etc.
    Transport(String),
    /// FCM rejected the message and the token will never work again.
    /// Caller should prune the row from `device_tokens`.
    InvalidToken(String),
    /// Any other non-2xx HTTP response (retryable in principle, but we don't
    /// retry automatically — push is best-effort).
    Http { status: u16, body: String },
}

impl std::fmt::Display for FcmSendError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::Transport(m) => write!(f, "FCM transport: {m}"),
            Self::InvalidToken(m) => write!(f, "FCM invalid token: {m}"),
            Self::Http { status, body } => write!(f, "FCM HTTP {status}: {body}"),
        }
    }
}

impl std::error::Error for FcmSendError {}

#[derive(Serialize)]
struct OauthAssertion {
    iss: String,
    scope: String,
    aud: String,
    iat: i64,
    exp: i64,
}

#[derive(Deserialize)]
struct OauthTokenResponse {
    access_token: String,
    /// Seconds until expiry (3600 in practice).
    expires_in: i64,
}
