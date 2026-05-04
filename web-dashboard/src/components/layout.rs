use leptos::prelude::*;
use leptos_router::components::A;
use leptos_router::hooks::use_navigate;

use crate::api::sse::use_sse;
use crate::auth::{use_auth, AuthContext};

#[component]
pub fn AppShell(children: Children) -> impl IntoView {
    let auth = use_auth();
    let navigate = use_navigate();

    let nav_effect = navigate.clone();
    Effect::new(move |_| {
        if AuthContext::token().is_none() {
            nav_effect("/login", Default::default());
        }
    });

    let on_signout = move |_| {
        crate::api::auth::logout();
        auth.user.set(None);
        navigate("/login", Default::default());
    };

    view! {
        <div class="flex min-h-screen bg-ultiq-cream">
            <aside class="w-56 bg-ultiq-indigo text-ultiq-cream p-6 flex flex-col gap-2">
                <div class="text-xl font-bold mb-6">"Ultiq"</div>
                <nav class="flex flex-col gap-1 text-sm">
                    <A href="/" attr:class="px-3 py-2 rounded hover:bg-white/10">
                        "Overview"
                    </A>
                    <A href="/checklist" attr:class="px-3 py-2 rounded hover:bg-white/10">
                        "Checklist"
                    </A>
                    <A href="/calendar" attr:class="px-3 py-2 rounded hover:bg-white/10">
                        "Calendar"
                    </A>
                    <Show when=move || auth.user.get().map(|u| u.is_admin).unwrap_or(false)>
                        <A href="/admin" attr:class="px-3 py-2 rounded hover:bg-white/10">
                            "Admin"
                        </A>
                    </Show>
                </nav>
                <div class="mt-auto pt-6 border-t border-white/20 text-xs">
                    <div class="opacity-80 break-words">
                        {move || auth.user.get().map(|u| u.email).unwrap_or_else(|| "—".into())}
                    </div>
                    <ConnectionIndicator />
                    <button
                        on:click=on_signout
                        class="mt-2 text-ultiq-yellow hover:underline cursor-pointer"
                    >
                        "Sign out"
                    </button>
                </div>
            </aside>
            <main class="flex-1 overflow-auto">
                {children()}
            </main>
        </div>
    }
}

#[component]
fn ConnectionIndicator() -> impl IntoView {
    let sse = use_sse();
    let dot_class = move || {
        if sse.connected.get() {
            "w-1.5 h-1.5 rounded-full bg-emerald-400"
        } else {
            "w-1.5 h-1.5 rounded-full bg-white/30"
        }
    };
    let label = move || {
        if sse.connected.get() {
            "Realtime sync"
        } else {
            "Offline"
        }
    };
    view! {
        <div class="mt-3 flex items-center gap-2 opacity-70">
            <span class=dot_class />
            <span class="text-[10px] uppercase tracking-wider">{label}</span>
        </div>
    }
}
