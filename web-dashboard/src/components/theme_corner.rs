use leptos::prelude::*;

use crate::theme::{use_theme, Theme};

/// Floating theme-toggle in the top-right corner.
/// Use on unauthed pages (login, forgot-password, reset) where the AppShell
/// sidebar — which contains the main toggle — isn't rendered.
#[component]
pub fn ThemeCorner() -> impl IntoView {
    let ctx = use_theme();
    let cycle = move |_| {
        let next = match ctx.theme.get() {
            Theme::Light => Theme::Dark,
            Theme::Dark => Theme::System,
            Theme::System => Theme::Light,
        };
        ctx.theme.set(next);
    };
    let symbol = move || match ctx.theme.get() {
        Theme::Light => "☼",
        Theme::Dark => "☾",
        Theme::System => "⚙",
    };
    let label = move || match ctx.theme.get() {
        Theme::Light => "Light",
        Theme::Dark => "Dark",
        Theme::System => "System",
    };
    view! {
        <button
            on:click=cycle
            title="Cycle theme"
            aria-label=label
            class="fixed top-4 right-4 z-20 flex items-center gap-1.5 rounded-full bg-white/70 dark:bg-ultiq-night-800/70 backdrop-blur px-3 py-1.5 text-xs font-medium text-ultiq-indigo shadow-sm hover:shadow cursor-pointer"
        >
            <span aria-hidden="true">{symbol}</span>
            <span class="hidden sm:inline">{label}</span>
        </button>
    }
}
