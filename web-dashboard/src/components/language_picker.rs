use leptos::prelude::*;

use crate::auth::AuthContext;
use crate::i18n::{t, use_i18n, Locale};

/// Language `<select>`. Writing a choice updates the reactive locale signal
/// (which the i18n `Effect` persists to LocalStorage and reflects onto
/// `<html lang dir>`), and — when signed in — syncs it to the server
/// `preferences` blob so the language follows the account across devices and
/// surfaces. The `class` prop lets the same control style itself for both the
/// dark sidebar footer and the light login corner.
#[component]
pub fn LanguagePicker(#[prop(into)] class: String) -> impl IntoView {
    let ctx = use_i18n();
    let on_change = move |ev: leptos::ev::Event| {
        let loc = Locale::from_tag(&event_target_value(&ev));
        ctx.locale.set(loc);
        if AuthContext::token().is_some() {
            let tag = loc.tag().to_string();
            wasm_bindgen_futures::spawn_local(async move {
                let _ = crate::api::auth::update_language(&tag).await;
            });
        }
    };
    view! {
        <select
            class=class
            aria-label=move || t("common.language")
            prop:value=move || ctx.locale.get().tag()
            on:change=on_change
        >
            {Locale::ALL
                .into_iter()
                .map(|loc| view! { <option value=loc.tag()>{loc.endonym()}</option> })
                .collect_view()}
        </select>
    }
}
