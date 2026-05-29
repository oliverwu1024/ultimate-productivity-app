use leptos::either::Either;
use leptos::prelude::*;
use leptos_meta::Title;

use crate::api::admin::{
    fetch_metrics, fetch_stats, fetch_user_summary, fetch_users, revoke_user_tokens, ActiveUsers,
    AdminMetrics, AdminStats, AdminUserEntry, AdminUserSummary, SignupCount, TopAiUser, UserCounts,
};
use crate::auth::use_auth;
use crate::components::layout::AppShell;

#[component]
pub fn AdminPage() -> impl IntoView {
    let auth = use_auth();
    let stats = LocalResource::new(|| async move { fetch_stats().await });
    let users = LocalResource::new(|| async move { fetch_users().await });
    let metrics = LocalResource::new(|| async move { fetch_metrics().await });

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
                            <ErrorBanner label="stats" status=e.status message=e.message />
                        }),
                    })}
                </Suspense>

                <section class="bg-white rounded-2xl p-6 shadow mt-8">
                    <h2 class="text-lg font-semibold text-ultiq-indigo mb-4">"Activity"</h2>
                    <Suspense fallback=|| view! {
                        <p class="text-ultiq-indigo/60">"Loading metrics…"</p>
                    }>
                        {move || metrics.get().map(|res| match res {
                            Ok(m) => Either::Left(view! { <MetricsBody metrics=m /> }),
                            Err(e) => Either::Right(view! {
                                <ErrorBanner label="metrics" status=e.status message=e.message />
                            }),
                        })}
                    </Suspense>
                </section>

                <section class="bg-white rounded-2xl p-6 shadow mt-8">
                    <h2 class="text-lg font-semibold text-ultiq-indigo mb-4">"Users"</h2>
                    <Suspense fallback=|| view! {
                        <p class="text-ultiq-indigo/60">"Loading users…"</p>
                    }>
                        {move || users.get().map(|res| match res {
                            Ok(list) => Either::Left(view! { <UsersTable users=list /> }),
                            Err(e) => Either::Right(view! {
                                <ErrorBanner label="users" status=e.status message=e.message />
                            }),
                        })}
                    </Suspense>
                </section>
            </div>
        </AppShell>
    }
}

#[component]
fn ErrorBanner(label: &'static str, status: u16, message: String) -> impl IntoView {
    view! {
        <div class="bg-ultiq-red/5 text-ultiq-red rounded-lg p-4">
            <p class="font-medium">{format!("Failed to load {}", label)}</p>
            <p class="text-sm mt-1">{format!("HTTP {}: {}", status, message)}</p>
        </div>
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
fn MetricsBody(metrics: AdminMetrics) -> impl IntoView {
    let counts = metrics.user_counts;
    let active = metrics.active_users;
    let adoption = format!("{:.1}%", metrics.sleep_adoption_pct);
    let ai_cost = format!("${:.2}", metrics.ai_estimated_cost_7d_usd);
    let ai_reqs = metrics.ai_requests_7d.to_string();
    let top = metrics.top_ai_users_7d;
    let has_top = !top.is_empty();
    let empty_label = if has_top { "" } else { "No AI usage in the last 7 days." };
    view! {
        <div class="grid grid-cols-2 md:grid-cols-4 gap-3">
            <SmallStat label="DAU" value=active.dau.to_string() />
            <SmallStat label="WAU" value=active.wau.to_string() />
            <SmallStat label="MAU" value=active.mau.to_string() />
            <SmallStat label="Sleep adoption" value=adoption />
            <SmallStat label="Verified" value=format!("{} / {}", counts.verified, counts.total) />
            <SmallStat label="Pro" value=counts.pro.to_string() />
            <SmallStat label="AI requests (7d)" value=ai_reqs />
            <SmallStat label="AI cost est. (7d)" value=ai_cost />
        </div>

        <div class="mt-6">
            <h3 class="text-sm font-semibold text-ultiq-indigo/80 mb-2">
                "Top AI users (7d)"
            </h3>
            {if has_top {
                Either::Left(view! { <TopAiTable users=top /> })
            } else {
                Either::Right(view! {
                    <p class="text-xs text-ultiq-indigo/50">{empty_label}</p>
                })
            }}
        </div>
    }
}

#[component]
fn SmallStat(label: &'static str, value: String) -> impl IntoView {
    view! {
        <div class="bg-ultiq-cream/40 rounded-lg p-3">
            <p class="text-xs text-ultiq-indigo/60 font-medium uppercase tracking-wider">{label}</p>
            <p class="text-xl font-semibold text-ultiq-indigo mt-1">{value}</p>
        </div>
    }
}

#[component]
fn TopAiTable(users: Vec<TopAiUser>) -> impl IntoView {
    let rows = users
        .into_iter()
        .map(|u| {
            let cost = format!("${:.2}", u.estimated_cost_usd);
            view! {
                <tr class="border-t border-ultiq-indigo/10">
                    <td class="py-1.5 pr-4 text-ultiq-indigo">{u.email}</td>
                    <td class="py-1.5 text-ultiq-indigo/70 text-right">{u.requests.to_string()}</td>
                    <td class="py-1.5 pl-4 text-ultiq-indigo/70 text-right">{cost}</td>
                </tr>
            }
        })
        .collect_view();
    view! {
        <table class="w-full text-sm">
            <thead>
                <tr class="text-xs uppercase tracking-wider text-ultiq-indigo/60">
                    <th class="text-left py-1.5 pr-4">"Email"</th>
                    <th class="text-right py-1.5">"Requests"</th>
                    <th class="text-right py-1.5 pl-4">"Cost (est.)"</th>
                </tr>
            </thead>
            <tbody>{rows}</tbody>
        </table>
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
    let expanded = RwSignal::new(None::<String>);
    let rows = users
        .into_iter()
        .map(|u| {
            let id = u.id.clone();
            let email = u.email.clone();
            let joined = u.created_at.format("%Y-%m-%d").to_string();
            let id_for_toggle = id.clone();
            let id_for_check = id.clone();
            let toggle = move |_| {
                expanded.update(|cur| {
                    if cur.as_deref() == Some(id_for_toggle.as_str()) {
                        *cur = None;
                    } else {
                        *cur = Some(id_for_toggle.clone());
                    }
                });
            };
            // Signal::derive yields a Copy signal so we can read it from both
            // the chevron and the Show condition without juggling clones.
            let is_open =
                Signal::derive(move || expanded.get().as_deref() == Some(id_for_check.as_str()));
            let id_for_panel = id.clone();
            view! {
                <>
                    <tr class="border-t border-ultiq-indigo/10 cursor-pointer hover:bg-ultiq-cream/30" on:click=toggle>
                        <td class="py-2 pr-4 text-ultiq-indigo">
                            {email}
                            {u.is_admin.then(|| view! {
                                <span class="ml-2 text-xs uppercase tracking-wider text-ultiq-indigo/50">
                                    "admin"
                                </span>
                            })}
                        </td>
                        <td class="py-2 text-ultiq-indigo/70 text-right">{joined}</td>
                        <td class="py-2 pl-4 text-ultiq-indigo/40 text-right text-xs">
                            {move || if is_open.get() { "▾" } else { "▸" }}
                        </td>
                    </tr>
                    <Show when=move || is_open.get()>
                        <tr>
                            <td colspan="3" class="px-4 pb-3 bg-ultiq-cream/30">
                                <UserSummaryPanel user_id=id_for_panel.clone() />
                            </td>
                        </tr>
                    </Show>
                </>
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
                        <th class="text-right py-2 pl-4 w-8"></th>
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
fn UserSummaryPanel(user_id: String) -> impl IntoView {
    let id_for_fetch = user_id.clone();
    let summary = LocalResource::new(move || {
        let id = id_for_fetch.clone();
        async move { fetch_user_summary(&id).await }
    });
    view! {
        <div class="pt-3 pb-2">
            <Suspense fallback=|| view! {
                <p class="text-xs text-ultiq-indigo/60">"Loading summary…"</p>
            }>
                {move || summary.get().map(|res| match res {
                    Ok(s) => Either::Left(view! { <SummaryView summary=s /> }),
                    Err(e) => Either::Right(view! {
                        <p class="text-xs text-ultiq-red">
                            {format!("Couldn't load summary (HTTP {}): {}", e.status, e.message)}
                        </p>
                    }),
                })}
            </Suspense>
        </div>
    }
}

#[component]
fn SummaryView(summary: AdminUserSummary) -> impl IntoView {
    let last = summary
        .last_activity_at
        .map(|t| t.format("%Y-%m-%d %H:%M UTC").to_string())
        .unwrap_or_else(|| "Never".into());
    let cost = format!("${:.2}", summary.ai_estimated_cost_usd);
    let id_for_revoke = summary.id.clone();
    let revoking = RwSignal::new(false);
    let revoke_result = RwSignal::new(None::<Result<(), String>>);
    let on_revoke = move |_: leptos::ev::MouseEvent| {
        if revoking.get_untracked() {
            return;
        }
        let confirmed = web_sys::window()
            .and_then(|w| w.confirm_with_message(
                "Revoke ALL session tokens for this user? They'll be logged out on every device."
            ).ok())
            .unwrap_or(false);
        if !confirmed {
            return;
        }
        revoking.set(true);
        revoke_result.set(None);
        let id = id_for_revoke.clone();
        wasm_bindgen_futures::spawn_local(async move {
            let outcome = revoke_user_tokens(&id).await.map_err(|e| e.message);
            revoke_result.set(Some(outcome));
            revoking.set(false);
        });
    };
    view! {
        <div class="grid grid-cols-2 md:grid-cols-4 gap-2 text-xs text-ultiq-indigo">
            <FactRow label="Verified" value=if summary.email_verified { "yes" } else { "no" }.to_string() />
            <FactRow label="Pro" value=if summary.is_pro { "yes" } else { "no" }.to_string() />
            <FactRow label="Timezone" value=summary.timezone />
            <FactRow label="Last activity" value=last />
            <FactRow label="Sleep records" value=summary.sleep_record_count.to_string() />
            <FactRow label="Focus sessions" value=summary.session_count.to_string() />
            <FactRow label="Checklist items" value=summary.checklist_item_count.to_string() />
            <FactRow label="Calendar events" value=summary.calendar_event_count.to_string() />
            <FactRow label="Alarms" value=summary.alarm_count.to_string() />
            <FactRow label="AI requests" value=summary.ai_requests_total.to_string() />
            <FactRow label="AI input tok" value=summary.ai_input_tokens_total.to_string() />
            <FactRow label="AI cost (est.)" value=cost />
        </div>

        <div class="flex items-center gap-3 mt-3">
            <button
                on:click=on_revoke
                prop:disabled=move || revoking.get()
                class="text-xs px-3 py-1.5 rounded border border-ultiq-red/40 text-ultiq-red hover:bg-ultiq-red/5 disabled:opacity-50 cursor-pointer"
            >
                {move || if revoking.get() { "Revoking…" } else { "Revoke session tokens" }}
            </button>
            <Show when=move || matches!(revoke_result.get(), Some(Ok(())))>
                <span class="text-xs text-emerald-700">"Tokens revoked. User will be logged out on next request."</span>
            </Show>
            <Show when=move || matches!(revoke_result.get(), Some(Err(_)))>
                <span class="text-xs text-ultiq-red">
                    {move || match revoke_result.get() {
                        Some(Err(msg)) => msg,
                        _ => String::new(),
                    }}
                </span>
            </Show>
        </div>
    }
}

#[component]
fn FactRow(label: &'static str, value: String) -> impl IntoView {
    view! {
        <div>
            <p class="uppercase tracking-wider text-[10px] text-ultiq-indigo/50">{label}</p>
            <p class="font-medium">{value}</p>
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
