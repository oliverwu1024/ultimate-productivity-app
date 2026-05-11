use leptos::either::Either;
use leptos::prelude::*;
use leptos_meta::Title;

use crate::api::admin::{fetch_stats, fetch_users, AdminStats, AdminUserEntry, SignupCount};
use crate::auth::use_auth;
use crate::components::layout::AppShell;

#[component]
pub fn AdminPage() -> impl IntoView {
    let auth = use_auth();
    let stats = LocalResource::new(|| async move { fetch_stats().await });
    let users = LocalResource::new(|| async move { fetch_users().await });

    view! {
        <Title text="Admin — Ultiq" />
        <AppShell>
            <div class="p-8">
                <header class="flex items-center justify-between mb-8">
                    <h1 class="text-3xl font-bold text-ultiq-indigo">"Admin"</h1>
                    <div class="text-sm text-ultiq-indigo/60">
                        {move || auth.user.get().map(|u| u.email).unwrap_or_default()}
                    </div>
                </header>

                <Suspense fallback=|| view! {
                    <p class="text-ultiq-indigo/60">"Loading…"</p>
                }>
                    {move || stats.get().map(|res| match res {
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

                <section class="bg-white rounded-2xl p-6 shadow mt-8">
                    <h2 class="text-lg font-semibold text-ultiq-indigo mb-4">"Users"</h2>
                    <Suspense fallback=|| view! {
                        <p class="text-ultiq-indigo/60">"Loading users…"</p>
                    }>
                        {move || users.get().map(|res| match res {
                            Ok(list) => Either::Left(view! { <UsersTable users=list /> }),
                            Err(e) => Either::Right(view! {
                                <div class="bg-ultiq-red/5 text-ultiq-red rounded-lg p-4">
                                    <p class="font-medium">"Failed to load users"</p>
                                    <p class="text-sm mt-1">
                                        {format!("HTTP {}: {}", e.status, e.message)}
                                    </p>
                                </div>
                            }),
                        })}
                    </Suspense>
                </section>
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
fn UsersTable(users: Vec<AdminUserEntry>) -> impl IntoView {
    let count = users.len();
    let rows = users
        .into_iter()
        .map(|u| {
            let joined = u.created_at.format("%Y-%m-%d").to_string();
            view! {
                <tr class="border-t border-ultiq-indigo/10">
                    <td class="py-2 pr-4 text-ultiq-indigo">
                        {u.email}
                        {u.is_admin.then(|| view! {
                            <span class="ml-2 text-xs uppercase tracking-wider text-ultiq-indigo/50">
                                "admin"
                            </span>
                        })}
                    </td>
                    <td class="py-2 text-ultiq-indigo/70 text-right">{joined}</td>
                </tr>
            }
        })
        .collect_view();
    view! {
        <div class="overflow-x-auto">
            <table class="w-full text-sm">
                <thead>
                    <tr class="text-xs uppercase tracking-wider text-ultiq-indigo/60">
                        <th class="text-left py-2 pr-4">"Email"</th>
                        <th class="text-right py-2">"Joined"</th>
                    </tr>
                </thead>
                <tbody>{rows}</tbody>
            </table>
            <p class="text-xs text-ultiq-indigo/50 mt-3">
                {format!("{} user{}", count, if count == 1 { "" } else { "s" })}
            </p>
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
