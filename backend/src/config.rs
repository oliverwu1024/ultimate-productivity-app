use std::env;

#[derive(Clone)]
pub struct Config {
    pub database_url: String,
    pub jwt_secret: String,
}

impl Config {
    pub fn from_env() -> Self {
        Self {
            database_url: env::var("DATABASE_URL").expect("DATABASE_URL must be set"),
            jwt_secret: env::var("JWT_SECRET").expect("JWT_SECRET must be set"),
        }
    }
}

#[derive(Clone)]
pub struct AppState {
    pub pool: sqlx::PgPool,
    pub config: Config,
}
