use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use serde_json::Value as JsonValue;
use uuid::Uuid;

#[derive(Debug, sqlx::FromRow)]
pub struct User {
    pub id: Uuid,
    pub email: String,
    pub password_hash: String,
    pub created_at: DateTime<Utc>,
    pub sleep_target_minutes: i32,
    pub is_admin: bool,
    pub token_version: i32,
    pub preferences: JsonValue,
    /// §i18n (v2.13.9) — IANA timezone string ("Australia/Sydney",
    /// "America/New_York"). 'UTC' is the safe default and is what every
    /// pre-v2.13.9 client implicitly used. Backend uses this for "today"
    /// bucketing across all routes + for the anomaly scheduler's
    /// per-user 08:00-local fan-out.
    pub timezone: String,
    pub email_verified: bool,
    pub is_pro: bool,
    /// §16 — base32 TOTP seed. Populated when the user calls /auth/2fa/setup
    /// but only trusted once `totp_enabled` flips to true via /confirm.
    pub totp_secret_b32: Option<String>,
    pub totp_enabled: bool,
}

fn is_false(b: &bool) -> bool {
    !*b
}

#[derive(Debug, Deserialize)]
pub struct UpdateProfile {
    pub sleep_target_minutes: Option<i32>,
    /// §sync-prefs: a partial preferences blob, merged into the existing
    /// JSONB column via `||`. Send only the keys you want to change; the
    /// rest stay as they were. Null clears the column to `{}`.
    pub preferences: Option<JsonValue>,
    /// §i18n (v2.13.9) — IANA timezone string. Android sends this on every
    /// login (ZoneId.systemDefault().id); web dashboard sends the
    /// browser's Intl.DateTimeFormat().resolvedOptions().timeZone if/when
    /// it implements its own settings panel. Null = keep the existing
    /// value. Invalid strings are accepted at write time but silently
    /// fall back to UTC at read time (see util::tz::user_today).
    pub timezone: Option<String>,
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

#[derive(Debug, Deserialize)]
pub struct ForgotPassword {
    pub email: String,
}

#[derive(Debug, Deserialize)]
pub struct ResetPassword {
    pub token: String,
    pub new_password: String,
}

#[derive(Debug, Serialize)]
pub struct UserResponse {
    pub id: Uuid,
    pub email: String,
    pub created_at: DateTime<Utc>,
    pub sleep_target_minutes: i32,
    /// Omitted from the JSON response for non-admin users so the
    /// admin tier's existence isn't an info-leak via /auth/me.
    #[serde(skip_serializing_if = "is_false")]
    pub is_admin: bool,
    /// Omitted from the JSON response for non-Pro users for the same
    /// reason — keeps the client view minimal and unsurprising for
    /// the free tier.
    #[serde(skip_serializing_if = "is_false")]
    pub is_pro: bool,
    pub preferences: JsonValue,
    /// §i18n — Mirrored to clients so the Settings → Region row can
    /// display "as known by the server" and let the user override if
    /// auto-detection got it wrong (e.g. phone reports UTC).
    pub timezone: String,
    pub email_verified: bool,
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
            sleep_target_minutes: user.sleep_target_minutes,
            is_admin: user.is_admin,
            is_pro: user.is_pro,
            preferences: user.preferences,
            timezone: user.timezone,
            email_verified: user.email_verified,
        }
    }
}

#[derive(Debug, Deserialize)]
pub struct VerifyEmail {
    pub token: String,
}

#[derive(Debug, Serialize)]
pub struct TotpSetupResponse {
    /// otpauth://totp/... URI. Authenticator apps either accept this
    /// directly or render it as a QR code from the client side.
    pub provisioning_uri: String,
    /// Base32 secret. Apps that can't parse the URI take this manually.
    pub secret_b32: String,
}

#[derive(Debug, Deserialize)]
pub struct TotpCode {
    pub code: String,
}
