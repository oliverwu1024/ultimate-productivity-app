use gloo_storage::{LocalStorage, Storage};
use leptos::prelude::*;
use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct User {
    pub id: String,
    pub email: String,
    pub created_at: String,
    pub sleep_target_minutes: i32,
    pub is_admin: bool,
    #[serde(default)]
    pub email_verified: bool,
    /// The synced preferences JSONB blob (theme, sleep/focus prefs, and the
    /// `app_language` tag that drives §13.3). Kept opaque here — the dashboard
    /// only reads `app_language` from it, via [`User::app_language`].
    #[serde(default)]
    pub preferences: serde_json::Value,
}

impl User {
    /// The synced app-language BCP-47 tag, if set and non-empty. An empty tag
    /// means "follow the device", so it is treated as absent (same as Android).
    pub fn app_language(&self) -> Option<String> {
        self.preferences
            .get("app_language")
            .and_then(|v| v.as_str())
            .map(str::trim)
            .filter(|s| !s.is_empty())
            .map(String::from)
    }
}

const TOKEN_KEY: &str = "ultiq_jwt";

#[derive(Clone, Copy)]
pub struct AuthContext {
    pub user: RwSignal<Option<User>>,
}

impl AuthContext {
    pub fn token() -> Option<String> {
        LocalStorage::get::<String>(TOKEN_KEY).ok()
    }

    pub fn set_token(token: &str) {
        let _ = LocalStorage::set(TOKEN_KEY, token);
    }

    pub fn clear_token() {
        LocalStorage::delete(TOKEN_KEY);
    }
}

pub fn provide_auth() {
    let user = RwSignal::new(None::<User>);
    provide_context(AuthContext { user });

    // Captured before the future so `adopt` runs with the context in hand —
    // `use_context` inside `spawn_local` would execute outside the owner.
    let i18n = use_context::<crate::i18n::I18nContext>();
    if AuthContext::token().is_some() {
        wasm_bindgen_futures::spawn_local(async move {
            match crate::api::auth::fetch_me().await {
                Ok(u) => {
                    crate::i18n::adopt(i18n, u.app_language().as_deref());
                    user.set(Some(u));
                }
                Err(_) => AuthContext::clear_token(),
            }
        });
    }
}

pub fn use_auth() -> AuthContext {
    expect_context::<AuthContext>()
}
