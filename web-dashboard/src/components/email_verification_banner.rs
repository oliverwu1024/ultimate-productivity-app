use leptos::prelude::*;

use crate::api::auth::resend_verification_email;
use crate::auth::use_auth;
use crate::i18n::t;

#[component]
pub fn EmailVerificationBanner() -> impl IntoView {
    let auth = use_auth();

    let unverified = move || {
        auth.user
            .get()
            .map(|u| !u.email_verified)
            .unwrap_or(false)
    };

    let sending = RwSignal::new(false);
    let result = RwSignal::new(None::<Result<(), String>>);

    let on_resend = move |_: leptos::ev::MouseEvent| {
        if sending.get_untracked() {
            return;
        }
        sending.set(true);
        result.set(None);
        wasm_bindgen_futures::spawn_local(async move {
            let outcome = resend_verification_email()
                .await
                .map_err(|e| e.message);
            result.set(Some(outcome));
            sending.set(false);
        });
    };

    view! {
        <Show when=unverified>
            <div class="bg-amber-50 border-b border-amber-200 px-4 py-3 print:hidden">
                <div class="max-w-5xl mx-auto flex flex-col md:flex-row md:items-center gap-2 md:gap-4">
                    <div class="flex-1 text-sm text-amber-900">
                        <span class="font-medium">{move || t("auth.verify_title")}</span>
                        {move || format!(" — {}", t("auth.banner_body"))}
                    </div>
                    <div class="flex items-center gap-3">
                        <Show when=move || {
                            matches!(result.get(), Some(Ok(())))
                        }>
                            <span class="text-xs text-emerald-700 bg-emerald-500/10 px-2 py-1 rounded">
                                {move || t("auth.banner_sent")}
                            </span>
                        </Show>
                        <Show when=move || {
                            matches!(result.get(), Some(Err(_)))
                        }>
                            <span class="text-xs text-ultiq-red bg-ultiq-red/5 px-2 py-1 rounded">
                                {move || match result.get() {
                                    Some(Err(msg)) => msg,
                                    _ => String::new(),
                                }}
                            </span>
                        </Show>
                        <button
                            on:click=on_resend
                            prop:disabled=move || sending.get()
                            class="text-sm font-medium px-3 py-1.5 rounded border border-amber-300 bg-white text-amber-900 hover:bg-amber-100 disabled:opacity-50 cursor-pointer whitespace-nowrap"
                        >
                            {move || if sending.get() { t("auth.sending") } else { t("auth.resend_email") }}
                        </button>
                    </div>
                </div>
            </div>
        </Show>
    }
}
