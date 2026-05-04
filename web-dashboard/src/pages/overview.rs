use leptos::prelude::*;
use leptos_router::components::A;

use crate::auth::use_auth;
use crate::components::layout::AppShell;

#[component]
pub fn OverviewPage() -> impl IntoView {
    let auth = use_auth();

    view! {
        <AppShell>
            <div class="p-8">
                <header class="flex items-center justify-between mb-8">
                    <h1 class="text-3xl font-bold text-ultiq-indigo">"Overview"</h1>
                    <div class="text-sm text-ultiq-indigo/60">
                        {move || auth.user.get().map(|u| u.email).unwrap_or_default()}
                    </div>
                </header>

                <p class="text-ultiq-indigo/70 mb-6">
                    "Analytics pages are under construction. Calendar editing and the admin section are live."
                </p>

                <div class="flex gap-3">
                    <A href="/calendar" attr:class="inline-block px-4 py-2 bg-ultiq-indigo text-ultiq-cream rounded-lg font-medium hover:opacity-90">
                        "Open calendar"
                    </A>
                    <Show when=move || auth.user.get().map(|u| u.is_admin).unwrap_or(false)>
                        <A href="/admin" attr:class="inline-block px-4 py-2 bg-white text-ultiq-indigo rounded-lg font-medium hover:bg-ultiq-indigo/5 border border-ultiq-indigo/20">
                            "Open admin"
                        </A>
                    </Show>
                </div>
            </div>
        </AppShell>
    }
}
