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

    if AuthContext::token().is_some() {
        wasm_bindgen_futures::spawn_local(async move {
            match crate::api::auth::fetch_me().await {
                Ok(u) => user.set(Some(u)),
                Err(_) => AuthContext::clear_token(),
            }
        });
    }
}

pub fn use_auth() -> AuthContext {
    expect_context::<AuthContext>()
}
