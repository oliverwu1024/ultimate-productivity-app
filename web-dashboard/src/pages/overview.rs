use leptos::prelude::*;
use leptos_router::components::A;
use leptos_router::hooks::use_navigate;

use crate::auth::{use_auth, AuthContext};

#[component]
pub fn OverviewPage() -> impl IntoView {
    let auth = use_auth();
    let navigate = use_navigate();

    Effect::new(move |_| {
        if AuthContext::token().is_none() {
            navigate("/login", Default::default());
        }
    });

    view! {
        <div class="min-h-screen p-8 bg-ultiq-cream">
            <header class="flex items-center justify-between mb-8">
                <h1 class="text-3xl font-bold text-ultiq-indigo">"Overview"</h1>
                <div class="text-sm text-ultiq-indigo/60">
                    {move || auth.user.get().map(|u| u.email).unwrap_or_default()}
                </div>
            </header>

            <p class="text-ultiq-indigo/70 mb-6">
                "Analytics pages are under construction. The admin section is live."
            </p>

            <Show when=move || auth.user.get().map(|u| u.is_admin).unwrap_or(false)>
                <A href="/admin" attr:class="inline-block px-4 py-2 bg-ultiq-indigo text-ultiq-cream rounded-lg font-medium hover:opacity-90">
                    "Open admin"
                </A>
            </Show>
        </div>
    }
}
