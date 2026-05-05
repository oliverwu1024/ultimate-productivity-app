use gloo_net::http::Request;
use leptos::prelude::*;
use leptos_meta::Title;
use leptos_router::components::A;
use serde::Serialize;

use crate::api::client::{api_base_url, ApiError};
use crate::components::theme_corner::ThemeCorner;

#[derive(Serialize)]
struct ForgotRequest<'a> {
    email: &'a str,
}

/// Backend returns 200 with empty body always (anti-enumeration). Tolerant fetch.
async fn submit_forgot(email: &str) -> Result<(), ApiError> {
    let body = ForgotRequest { email };
    let url = format!("{}/auth/password/forgot", api_base_url());
    let resp = Request::post(&url)
        .header("Content-Type", "application/json")
        .json(&body)
        .map_err(|e| ApiError { status: 0, message: e.to_string() })?
        .send()
        .await
        .map_err(|e| ApiError { status: 0, message: e.to_string() })?;
    let status = resp.status();
    if !(200..300).contains(&status) {
        return Err(ApiError {
            status,
            message: format!("HTTP {}", status),
        });
    }
    Ok(())
}

#[component]
pub fn ForgotPasswordPage() -> impl IntoView {
    let email = RwSignal::new(String::new());
    let submitting = RwSignal::new(false);
    let sent = RwSignal::new(false);
    let error = RwSignal::new(None::<String>);

    let on_submit = move |ev: leptos::ev::SubmitEvent| {
        ev.prevent_default();
        if submitting.get_untracked() {
            return;
        }
        submitting.set(true);
        error.set(None);
        let e = email.get_untracked();
        wasm_bindgen_futures::spawn_local(async move {
            match submit_forgot(&e).await {
                Ok(_) => sent.set(true),
                Err(err) => error.set(Some(err.message)),
            }
            submitting.set(false);
        });
    };

    view! {
        <Title text="Forgot password — Ultiq" />
        <ThemeCorner />
        <div class="min-h-screen flex items-center justify-center bg-ultiq-cream px-4">
            <div class="bg-white rounded-2xl shadow-lg p-8 w-full max-w-sm space-y-4">
                <h1 class="text-2xl font-bold text-ultiq-indigo text-center">"Reset your password"</h1>

                <Show
                    when=move || !sent.get()
                    fallback=|| view! {
                        <div class="space-y-3 text-center">
                            <p class="text-sm text-ultiq-indigo/80">
                                "If that email is registered, a reset link is on its way. Check your inbox."
                            </p>
                            <p class="text-xs text-ultiq-indigo/50">"The link expires in 1 hour."</p>
                            <A href="/login" attr:class="block text-ultiq-indigo hover:underline text-sm pt-2">
                                "← Back to sign in"
                            </A>
                        </div>
                    }
                >
                    <p class="text-sm text-ultiq-indigo/60 text-center">
                        "Enter your email and we'll send you a reset link."
                    </p>

                    <form on:submit=on_submit class="space-y-3">
                        <label class="block">
                            <span class="text-sm font-medium text-ultiq-indigo">"Email"</span>
                            <input
                                type="email"
                                class="mt-1 w-full px-3 py-2 border border-ultiq-indigo/20 rounded-lg focus:outline-none focus:ring-2 focus:ring-ultiq-indigo"
                                autocomplete="email"
                                prop:value=move || email.get()
                                on:input=move |ev| email.set(event_target_value(&ev))
                                required
                            />
                        </label>

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
                            {move || if submitting.get() { "Sending…" } else { "Send reset link" }}
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
