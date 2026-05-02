use std::env;

#[derive(Clone)]
pub struct Config {
    pub database_url: String,
    pub jwt_secret: String,
    pub from_address: String,
    pub reply_to: String,
}

impl Config {
    pub fn from_env() -> Self {
        Self {
            database_url: env::var("DATABASE_URL").expect("DATABASE_URL must be set"),
            jwt_secret: env::var("JWT_SECRET").expect("JWT_SECRET must be set"),
            from_address: env::var("FROM_ADDRESS")
                .unwrap_or_else(|_| "no-reply@mail.ultiqapp.com".to_string()),
            reply_to: env::var("REPLY_TO")
                .unwrap_or_else(|_| "support@ultiqapp.com".to_string()),
        }
    }
}

#[derive(Clone)]
pub struct AppState {
    pub pool: sqlx::PgPool,
    pub config: Config,
    pub ses: aws_sdk_sesv2::Client,
}
