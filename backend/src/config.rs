use std::env;

use crate::ai::AiClient;
use crate::email::EmailClient;
use crate::event_bus::EventBus;
use crate::fcm::FcmClient;
use crate::sleep_audio_clips::SleepAudioClipStore;
use crate::ticket::TicketStore;

pub const JWT_ISSUER: &str = "ultiq-api";
pub const JWT_AUDIENCE: &str = "ultiq-mobile";

#[derive(Clone)]
pub struct Config {
    pub database_url: String,
    pub jwt_secret: String,
    pub from_address: String,
    pub reply_to: String,
    pub resend_api_key: String,
    pub allowed_origins: Vec<String>,
    pub reset_link_base: String,
    pub verify_link_base: String,
}

impl Config {
    pub fn from_env() -> Self {
        let origins = env::var("ALLOWED_ORIGINS")
            .unwrap_or_else(|_| "https://app.ultiqapp.com,https://ultiqapp.com".to_string())
            .split(',')
            .map(|s| s.trim().to_string())
            .filter(|s| !s.is_empty())
            .collect::<Vec<_>>();
        Self {
            database_url: env::var("DATABASE_URL").expect("DATABASE_URL must be set"),
            jwt_secret: env::var("JWT_SECRET").expect("JWT_SECRET must be set"),
            from_address: env::var("FROM_ADDRESS")
                .unwrap_or_else(|_| "no-reply@mail.ultiqapp.com".to_string()),
            reply_to: env::var("REPLY_TO")
                .unwrap_or_else(|_| "support@ultiqapp.com".to_string()),
            resend_api_key: env::var("RESEND_API_KEY").expect("RESEND_API_KEY must be set"),
            allowed_origins: origins,
            reset_link_base: env::var("RESET_LINK_BASE")
                .unwrap_or_else(|_| "https://app.ultiqapp.com/reset".to_string()),
            verify_link_base: env::var("VERIFY_LINK_BASE")
                .unwrap_or_else(|_| "https://app.ultiqapp.com/verify-email".to_string()),
        }
    }
}

#[derive(Clone)]
pub struct AppState {
    pub pool: sqlx::PgPool,
    pub config: Config,
    pub email: EmailClient,
    pub events: EventBus,
    pub tickets: TicketStore,
    pub ai: AiClient,
    /// §9.8 — FCM client. `None` when GOOGLE_APPLICATION_CREDENTIALS is
    /// unset or the file failed to load (CI, fresh dev). Routes that need
    /// push must check and return 503 ServiceUnavailable rather than crash.
    pub fcm: Option<FcmClient>,
    /// §10.x — Pro-tier sleep-audio clip store. `None` when
    /// SLEEP_AUDIO_S3_BUCKET is unset (dev / freshly-cut env). Pro-clip
    /// routes return 503 instead of crashing; non-clip detection (labels +
    /// timestamps) keeps working since it doesn't touch S3.
    pub sleep_audio_clips: Option<SleepAudioClipStore>,
}
