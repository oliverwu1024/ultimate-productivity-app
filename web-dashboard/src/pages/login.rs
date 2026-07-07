use leptos::prelude::*;
use leptos_meta::Title;
use leptos_router::components::A;
use leptos_router::hooks::use_navigate;

use crate::api::auth::login;
use crate::auth::use_auth;
use crate::components::language_picker::LanguagePicker;
use crate::components::theme_corner::ThemeCorner;
use crate::i18n::t;

#[component]
pub fn LoginPage() -> impl IntoView {
    let auth = use_auth();
    let navigate = use_navigate();
    let i18n = use_context::<crate::i18n::I18nContext>();

    let email = RwSignal::new(String::new());
    let password = RwSignal::new(String::new());
    let error = RwSignal::new(None::<String>);
    let submitting = RwSignal::new(false);

    let on_submit = move |ev: leptos::ev::SubmitEvent| {
        ev.prevent_default();
        if submitting.get_untracked() {
            return;
        }
        submitting.set(true);
        error.set(None);
        let navigate = navigate.clone();
        let e = email.get_untracked();
        let p = password.get_untracked();
        wasm_bindgen_futures::spawn_local(async move {
            match login(&e, &p).await {
                Ok(resp) => {
                    crate::i18n::adopt(i18n, resp.user.app_language().as_deref());
                    auth.user.set(Some(resp.user));
                    navigate("/admin", Default::default());
                }
                Err(err) => {
                    error.set(Some(err.message));
                    submitting.set(false);
                }
            }
        });
    };

    view! {
        <Title text="Sign in — Ultiq" />
        <ThemeCorner />
        <LanguagePicker class="fixed top-4 left-4 z-20 bg-white/70 dark:bg-ultiq-night-800/70 backdrop-blur rounded-full px-3 py-1.5 text-xs font-medium text-ultiq-indigo shadow-sm cursor-pointer focus:outline-none" />
        <div class="min-h-screen flex items-center justify-center bg-ultiq-cream px-4">
            <form
                on:submit=on_submit
                class="bg-white rounded-2xl shadow-lg p-8 w-full max-w-sm space-y-4"
            >
                <h1 class="text-2xl font-bold text-ultiq-indigo text-center">"Ultiq"</h1>
                <p class="text-sm text-center text-ultiq-indigo/60">
                    {move || t("auth.tagline")}
                </p>

                <label class="block">
                    <span class="text-sm font-medium text-ultiq-indigo">{move || t("auth.email")}</span>
                    <input
                        type="email"
                        class="mt-1 w-full px-3 py-2 border border-ultiq-indigo/20 rounded-lg focus:outline-none focus:ring-2 focus:ring-ultiq-indigo"
                        autocomplete="email"
                        prop:value=move || email.get()
                        on:input=move |ev| email.set(event_target_value(&ev))
                        required
                    />
                </label>

                <label class="block">
                    <span class="text-sm font-medium text-ultiq-indigo">{move || t("auth.password")}</span>
                    <input
                        type="password"
                        class="mt-1 w-full px-3 py-2 border border-ultiq-indigo/20 rounded-lg focus:outline-none focus:ring-2 focus:ring-ultiq-indigo"
                        autocomplete="current-password"
                        prop:value=move || password.get()
                        on:input=move |ev| password.set(event_target_value(&ev))
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
                    class="w-full px-4 py-2 bg-ultiq-indigo text-ultiq-cream rounded-lg font-medium hover:opacity-90 disabled:opacity-50"
                    prop:disabled=move || submitting.get()
                >
                    {move || if submitting.get() { t("auth.signing_in") } else { t("auth.sign_in") }}
                </button>

                <A
                    href="/forgot-password"
                    attr:class="block text-center text-sm text-ultiq-indigo/60 hover:text-ultiq-indigo pt-1"
                >
                    {move || t("auth.forgot_password")}
                </A>
            </form>
        </div>
    }
}
