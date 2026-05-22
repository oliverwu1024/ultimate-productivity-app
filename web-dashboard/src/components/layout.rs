use leptos::prelude::*;
use leptos_router::components::A;
use leptos_router::hooks::{use_location, use_navigate};

use crate::api::sse::use_sse;
use crate::auth::{use_auth, AuthContext};
use crate::theme::{use_theme, Theme};

#[component]
pub fn AppShell(children: Children) -> impl IntoView {
    let auth = use_auth();
    let nav_store = StoredValue::new(use_navigate());
    let mobile_open = RwSignal::new(false);

    Effect::new(move |_| {
        if AuthContext::token().is_none() {
            nav_store.with_value(|n| n("/login", Default::default()));
        }
    });

    // Close the mobile drawer whenever the route changes.
    let location = use_location();
    Effect::new(move |prev: Option<String>| {
        let path = location.pathname.get();
        if let Some(prev_path) = prev {
            if prev_path != path {
                mobile_open.set(false);
            }
        }
        path
    });

    let on_signout = move |_: leptos::ev::MouseEvent| {
        crate::api::auth::logout();
        auth.user.set(None);
        nav_store.with_value(|n| n("/login", Default::default()));
    };

    view! {
        <div class="md:flex min-h-screen bg-ultiq-cream print:bg-white">
            // Desktop sidebar (md+ only)
            <aside class="hidden md:flex w-56 bg-ultiq-indigo text-ultiq-cream p-6 flex-col gap-2 print:hidden">
                <div class="text-xl font-bold mb-6">"Ultiq"</div>
                <SidebarNav />
                <SidebarFooter on_signout=on_signout />
            </aside>

            // Main column with mobile top-bar
            <div class="flex-1 flex flex-col min-w-0">
                <header class="md:hidden flex items-center justify-between px-4 py-3 bg-ultiq-indigo text-ultiq-cream sticky top-0 z-30 print:hidden">
                    <button
                        on:click=move |_| mobile_open.set(true)
                        class="p-2 -ml-2 cursor-pointer"
                        aria-label="Open menu"
                    >
                        <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                            <line x1="4" y1="6" x2="20" y2="6"/>
                            <line x1="4" y1="12" x2="20" y2="12"/>
                            <line x1="4" y1="18" x2="20" y2="18"/>
                        </svg>
                    </button>
                    <span class="font-bold">"Ultiq"</span>
                    <span class="w-10" />
                </header>
                <main class="flex-1 overflow-auto min-w-0">
                    {children()}
                </main>
            </div>

            // Mobile drawer overlay
            <Show when=move || mobile_open.get()>
                <div class="md:hidden fixed inset-0 z-40 print:hidden">
                    <div
                        class="absolute inset-0 bg-black/50"
                        on:click=move |_| mobile_open.set(false)
                    />
                    <aside
                        class="absolute inset-y-0 left-0 w-64 bg-ultiq-indigo text-ultiq-cream p-6 flex flex-col gap-2 shadow-xl"
                        on:click=|ev: leptos::ev::MouseEvent| ev.stop_propagation()
                    >
                        <div class="text-xl font-bold mb-6">"Ultiq"</div>
                        <SidebarNav />
                        <SidebarFooter on_signout=on_signout />
                    </aside>
                </div>
            </Show>
        </div>
    }
}

#[component]
fn SidebarNav() -> impl IntoView {
    let auth = use_auth();
    view! {
        <nav class="flex flex-col gap-1 text-sm">
            <NavLink href="/" label="Overview" />
            <NavLink href="/checklist" label="Checklist" />
            <NavLink href="/calendar" label="Calendar" />
            <NavLink href="/sleep" label="Sleep" />
            <NavLink href="/focus" label="Focus" />
            <NavLink href="/correlations" label="Correlations" />
            <NavLink href="/reports" label="Reports" />
            <NavLink href="/chat" label="Coach" />
            <Show when=move || auth.user.get().map(|u| u.is_admin).unwrap_or(false)>
                <NavLink href="/admin" label="Admin" />
            </Show>
        </nav>
    }
}

#[component]
fn SidebarFooter(
    on_signout: impl Fn(leptos::ev::MouseEvent) + Send + Sync + Copy + 'static,
) -> impl IntoView {
    let auth = use_auth();
    view! {
        <div class="mt-auto pt-6 border-t border-white/20 text-xs">
            <div class="opacity-80 break-words">
                {move || auth.user.get().map(|u| u.email).unwrap_or_else(|| "—".into())}
            </div>
            <ConnectionIndicator />
            <ThemeToggle />
            <button
                on:click=on_signout
                class="mt-2 text-ultiq-yellow hover:underline cursor-pointer"
            >
                "Sign out"
            </button>
        </div>
    }
}

#[component]
fn NavLink(href: &'static str, label: &'static str) -> impl IntoView {
    let location = use_location();
    let is_active = move || {
        let path = location.pathname.get();
        if href == "/" {
            path == "/"
        } else {
            path == href || path.starts_with(&format!("{}/", href))
        }
    };
    let class = move || {
        if is_active() {
            "px-3 py-2 rounded bg-white/15 text-ultiq-cream font-medium"
        } else {
            "px-3 py-2 rounded text-ultiq-cream/80 hover:bg-white/10"
        }
    };
    view! {
        <A href=href attr:class=class>
            {label}
        </A>
    }
}

#[component]
fn ThemeToggle() -> impl IntoView {
    let ctx = use_theme();
    let cycle = move |_| {
        let next = match ctx.theme.get() {
            Theme::Light => Theme::Dark,
            Theme::Dark => Theme::System,
            Theme::System => Theme::Light,
        };
        ctx.theme.set(next);
    };
    let label = move || match ctx.theme.get() {
        Theme::Light => "☼ Light",
        Theme::Dark => "☾ Dark",
        Theme::System => "⚙ System",
    };
    view! {
        <button
            on:click=cycle
            class="mt-3 flex items-center gap-2 text-[11px] uppercase tracking-wider opacity-70 hover:opacity-100 cursor-pointer"
            title="Cycle theme"
        >
            {label}
        </button>
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
