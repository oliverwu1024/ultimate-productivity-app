use leptos::prelude::*;
use leptos_meta::Title;
use leptos_router::components::A;
use leptos_router::hooks::use_query_map;

use crate::api::auth::verify_email;
use crate::components::theme_corner::ThemeCorner;

#[derive(Clone, Copy, PartialEq)]
enum Status {
    Pending,
    Success,
    Failure,
    MissingToken,
}

#[component]
pub fn VerifyEmailPage() -> impl IntoView {
    let query = use_query_map();

    // Capture the token once, then scrub it from window.history so it stops
    // persisting in browser history / leaking via Referer to any link the
    // success view exposes. Same hygiene as reset_password.rs.
    let initial_token = query
        .read_untracked()
        .get("token")
        .map(|s| s.to_string())
        .unwrap_or_default();
    if !initial_token.is_empty() {
        if let Some(window) = web_sys::window() {
            if let Ok(history) = window.history() {
                let _ = history.replace_state_with_url(
                    &wasm_bindgen::JsValue::NULL,
                    "",
                    Some("/verify-email"),
                );
            }
        }
    }

    let status = RwSignal::new(if initial_token.is_empty() {
        Status::MissingToken
    } else {
        Status::Pending
    });
    let error_message = RwSignal::new(None::<String>);

    if !initial_token.is_empty() {
        let token = initial_token.clone();
        wasm_bindgen_futures::spawn_local(async move {
            match verify_email(&token).await {
                Ok(()) => status.set(Status::Success),
                Err(err) => {
                    error_message.set(Some(err.message));
                    status.set(Status::Failure);
                }
            }
        });
    }

    view! {
        <Title text="Verify email — Ultiq" />
        <ThemeCorner />
        <div class="min-h-screen flex items-center justify-center bg-ultiq-cream px-4">
            <div class="bg-white rounded-2xl shadow-lg p-8 w-full max-w-sm space-y-4 text-center">
                <h1 class="text-2xl font-bold text-ultiq-indigo">"Verify your email"</h1>

                <Show when=move || status.get() == Status::Pending>
                    <p class="text-sm text-ultiq-indigo/70">"Verifying…"</p>
                </Show>

                <Show when=move || status.get() == Status::Success>
                    <div class="space-y-3">
                        <p class="text-sm text-emerald-700 bg-emerald-500/10 px-3 py-2 rounded">
                            "Email verified. AI features are now unlocked."
                        </p>
                        <A href="/" attr:class="block text-sm text-ultiq-indigo/70 hover:text-ultiq-indigo pt-2">
                            "Open dashboard →"
                        </A>
                    </div>
                </Show>

                <Show when=move || status.get() == Status::Failure>
                    <div class="space-y-3">
                        <p class="text-sm text-ultiq-red bg-ultiq-red/5 px-3 py-2 rounded">
                            {move || error_message.get().unwrap_or_else(|| "Invalid or expired link.".into())}
                        </p>
                        <p class="text-xs text-ultiq-indigo/60">
                            "Verification links expire after 24 hours. Sign in and tap "
                            <em>"Resend verification email"</em>
                            " to get a fresh one."
                        </p>
                        <A href="/login" attr:class="block text-sm text-ultiq-indigo/70 hover:text-ultiq-indigo pt-2">
                            "Go to sign in →"
                        </A>
                    </div>
                </Show>

                <Show when=move || status.get() == Status::MissingToken>
                    <div class="space-y-3">
                        <p class="text-sm text-ultiq-red bg-ultiq-red/5 px-3 py-2 rounded">
                            "No verification token in the link."
                        </p>
                        <p class="text-xs text-ultiq-indigo/60">
                            "Open this page from the link in the verification email we sent."
                        </p>
                        <A href="/login" attr:class="block text-sm text-ultiq-indigo/70 hover:text-ultiq-indigo pt-2">
                            "Go to sign in →"
                        </A>
                    </div>
                </Show>
            </div>
        </div>
    }
}
