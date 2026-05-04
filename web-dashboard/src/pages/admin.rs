use leptos::either::Either;
use leptos::prelude::*;

use crate::api::admin::{fetch_stats, AdminStats, SignupCount};
use crate::auth::use_auth;
use crate::components::layout::AppShell;

#[component]
pub fn AdminPage() -> impl IntoView {
    let auth = use_auth();
    let stats = LocalResource::new(|| async move { fetch_stats().await });

    view! {
        <AppShell>
            <div class="p-8">
                <header class="flex items-center justify-between mb-8">
                    <div>
                        <h1 class="text-3xl font-bold text-ultiq-indigo">"Admin"</h1>
                        <p class="text-sm text-ultiq-indigo/60 mt-1">
                            "Aggregate stats only — no per-user data is exposed."
                        </p>
                    </div>
                    <div class="text-sm text-ultiq-indigo/60">
                        {move || auth.user.get().map(|u| u.email).unwrap_or_default()}
                    </div>
                </header>

                <Suspense fallback=|| view! {
                    <p class="text-ultiq-indigo/60">"Loading…"</p>
                }>
                    {move || stats.get().map(|res| match res.take() {
                        Ok(s) => Either::Left(view! { <AdminBody stats=s /> }),
                        Err(e) => Either::Right(view! {
                            <div class="bg-ultiq-red/5 text-ultiq-red rounded-lg p-4">
                                <p class="font-medium">"Failed to load stats"</p>
                                <p class="text-sm mt-1">
                                    {format!("HTTP {}: {}", e.status, e.message)}
                                </p>
                            </div>
                        }),
                    })}
                </Suspense>
            </div>
        </AppShell>
    }
}

#[component]
fn AdminBody(stats: AdminStats) -> impl IntoView {
    let signups = stats.signups_by_day.clone();
    view! {
        <div class="grid grid-cols-1 md:grid-cols-3 gap-4 mb-8">
            <StatCard label="Total users" value=stats.total_users.to_string() />
            <StatCard label="Signups (7d)" value=stats.signups_last_7d.to_string() />
            <StatCard label="Signups (30d)" value=stats.signups_last_30d.to_string() />
        </div>

        <section class="bg-white rounded-2xl p-6 shadow">
            <h2 class="text-lg font-semibold text-ultiq-indigo mb-4">
                "Daily signups (last 90 days)"
            </h2>
            <SignupsBar signups=signups />
        </section>
    }
}

#[component]
fn StatCard(label: &'static str, value: String) -> impl IntoView {
    view! {
        <div class="bg-white rounded-2xl p-6 shadow">
            <p class="text-sm text-ultiq-indigo/60 font-medium">{label}</p>
            <p class="text-4xl font-bold text-ultiq-indigo mt-2">{value}</p>
        </div>
    }
}

#[component]
fn SignupsBar(signups: Vec<SignupCount>) -> impl IntoView {
    let max = signups
        .iter()
        .map(|s| s.count)
        .max()
        .unwrap_or(0)
        .max(1) as f64;
    let bars = signups
        .into_iter()
        .map(|s| {
            let height = (s.count as f64 / max * 100.0).max(2.0);
            let title = format!("{}: {}", s.date, s.count);
            view! {
                <div
                    class="flex-1 bg-ultiq-indigo/80 rounded-t hover:bg-ultiq-indigo transition-colors"
                    style:height=format!("{}%", height)
                    title=title
                />
            }
        })
        .collect_view();
    view! {
        <div class="flex items-end gap-1 h-32">{bars}</div>
    }
}
