use gloo_net::http::Request;
use leptos::prelude::*;
use leptos_meta::Title;
use leptos_router::components::A;
use leptos_router::hooks::{use_navigate, use_query_map};
use serde::Serialize;

use crate::api::client::{api_base_url, ApiError};
use crate::components::theme_corner::ThemeCorner;

#[derive(Serialize)]
struct ResetRequest<'a> {
    token: &'a str,
    new_password: &'a str,
}

async fn submit_reset(token: &str, new_password: &str) -> Result<(), ApiError> {
    let body = ResetRequest { token, new_password };
    let url = format!("{}/auth/password/reset", api_base_url());
    let resp = Request::post(&url)
        .header("Content-Type", "application/json")
        .json(&body)
        .map_err(|e| ApiError { status: 0, message: e.to_string() })?
        .send()
        .await
        .map_err(|e| ApiError { status: 0, message: e.to_string() })?;
    let status = resp.status();
    if !(200..300).contains(&status) {
        let msg = resp.text().await.ok().and_then(|body| {
            serde_json::from_str::<serde_json::Value>(&body)
                .ok()
                .and_then(|v| v.get("error").and_then(|e| e.as_str()).map(|s| s.to_string()))
        }).unwrap_or_else(|| format!("HTTP {}", status));
        return Err(ApiError { status, message: msg });
    }
    Ok(())
}

#[component]
pub fn ResetPasswordPage() -> impl IntoView {
    let query = use_query_map();
    let navigate_store = StoredValue::new(use_navigate());

    let token = move || query.read().get("token").map(|s| s.to_string()).unwrap_or_default();

    let new_password = RwSignal::new(String::new());
    let confirm_password = RwSignal::new(String::new());
    let submitting = RwSignal::new(false);
    let error = RwSignal::new(None::<String>);
    let success = RwSignal::new(false);

    let on_submit = move |ev: leptos::ev::SubmitEvent| {
        ev.prevent_default();
        if submitting.get_untracked() {
            return;
        }
        let p1 = new_password.get_untracked();
        let p2 = confirm_password.get_untracked();
        if p1 != p2 {
            error.set(Some("Passwords don't match".into()));
            return;
        }
        let t = token();
        if t.is_empty() {
            error.set(Some(
                "Reset token missing. Open the reset link from the email.".into(),
            ));
            return;
        }
        submitting.set(true);
        error.set(None);
        wasm_bindgen_futures::spawn_local(async move {
            match submit_reset(&t, &p1).await {
                Ok(_) => {
                    success.set(true);
                    gloo_timers::future::TimeoutFuture::new(1500).await;
                    navigate_store.with_value(|n| n("/login", Default::default()));
                }
                Err(err) => {
                    error.set(Some(err.message));
                    submitting.set(false);
                }
            }
        });
    };

    view! {
        <Title text="Reset password — Ultiq" />
        <ThemeCorner />
        <div class="min-h-screen flex items-center justify-center bg-ultiq-cream px-4">
            <div class="bg-white rounded-2xl shadow-lg p-8 w-full max-w-sm space-y-4">
                <h1 class="text-2xl font-bold text-ultiq-indigo text-center">"Choose a new password"</h1>

                <Show
                    when=move || !success.get()
                    fallback=|| view! {
                        <div class="space-y-3 text-center">
                            <p class="text-sm text-emerald-700 bg-emerald-500/10 px-3 py-2 rounded">
                                "Password updated. Redirecting to sign in…"
                            </p>
                        </div>
                    }
                >
                    <form on:submit=on_submit class="space-y-3">
                        <label class="block">
                            <span class="text-sm font-medium text-ultiq-indigo">"New password"</span>
                            <input
                                type="password"
                                class="mt-1 w-full px-3 py-2 border border-ultiq-indigo/20 rounded-lg focus:outline-none focus:ring-2 focus:ring-ultiq-indigo"
                                autocomplete="new-password"
                                prop:value=move || new_password.get()
                                on:input=move |ev| new_password.set(event_target_value(&ev))
                                required
                                minlength="8"
                            />
                        </label>

                        <label class="block">
                            <span class="text-sm font-medium text-ultiq-indigo">"Confirm new password"</span>
                            <input
                                type="password"
                                class="mt-1 w-full px-3 py-2 border border-ultiq-indigo/20 rounded-lg focus:outline-none focus:ring-2 focus:ring-ultiq-indigo"
                                autocomplete="new-password"
                                prop:value=move || confirm_password.get()
                                on:input=move |ev| confirm_password.set(event_target_value(&ev))
                                required
                                minlength="8"
                            />
                        </label>

                        <p class="text-xs text-ultiq-indigo/50">
                            "8+ chars including upper, lower, digit, and a special character."
                        </p>

                        <Show when=move || error.get().is_some()>
                            <p class="text-sm text-ultiq-red bg-ultiq-red/5 px-3 py-2 rounded">
                                {move || error.get().unwrap_or_default()}
                            </p>
                        </Show>

                        <button
                            type="submit"
                            class="w-full px-4 py-2 bg-ultiq-indigo text-ultiq-cream rounded-lg font-medium hover:opacity-90 disabled:opacity-50 cursor-pointer"
                            prop:disabled=move || submitting.get()
                        >
                            {move || if submitting.get() { "Saving…" } else { "Update password" }}
                        </button>

                        <A href="/login" attr:class="block text-center text-sm text-ultiq-indigo/70 hover:text-ultiq-indigo pt-2">
                            "← Back to sign in"
                        </A>
                    </form>
                </Show>
            </div>
        </div>
    }
}
