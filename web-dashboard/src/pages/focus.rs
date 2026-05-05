use std::collections::BTreeMap;

use chrono::{Duration, Local, NaiveDate};
use leptos::either::Either;
use leptos::prelude::*;
use leptos_meta::Title;

use crate::api::sessions::{
    fetch_stats, list_sessions, ProductivitySession, SessionStats, TagStat,
};
use crate::api::sse::{use_sse, SyncEvent};
use crate::components::layout::AppShell;

#[derive(Clone, Copy, PartialEq, Eq)]
enum Range {
    Week,
    Month,
    Quarter,
}

impl Range {
    fn label(&self) -> &'static str {
        match self {
            Self::Week => "Week",
            Self::Month => "Month",
            Self::Quarter => "90d",
        }
    }
    fn stats_param(&self) -> &'static str {
        match self {
            Self::Week => "week",
            _ => "month",
        }
    }
    fn days(&self) -> i64 {
        match self {
            Self::Week => 7,
            Self::Month => 30,
            Self::Quarter => 90,
        }
    }
}

fn format_minutes(m: i64) -> String {
    if m <= 0 {
        return "0m".to_string();
    }
    let h = m / 60;
    let mins = m % 60;
    if h == 0 {
        format!("{}m", mins)
    } else if mins == 0 {
        format!("{}h", h)
    } else {
        format!("{}h {}m", h, mins)
    }
}

#[component]
pub fn FocusPage() -> impl IntoView {
    let today = Local::now().date_naive();
    let range = RwSignal::new(Range::Month);
    let sessions: RwSignal<Vec<ProductivitySession>> = RwSignal::new(Vec::new());
    let stats: RwSignal<Option<SessionStats>> = RwSignal::new(None);
    let loading = RwSignal::new(false);
    let error = RwSignal::new(None::<String>);

    let refresh = move || {
        let r = range.get_untracked();
        let start = today - Duration::days(r.days() - 1);
        loading.set(true);
        error.set(None);
        wasm_bindgen_futures::spawn_local(async move {
            let recs = list_sessions(start, today).await;
            let st = fetch_stats(r.stats_param()).await;
            match (recs, st) {
                (Ok(rs), Ok(s)) => {
                    sessions.set(rs);
                    stats.set(Some(s));
                }
                (Err(e), _) | (_, Err(e)) => error.set(Some(e.message)),
            }
            loading.set(false);
        });
    };

    Effect::new(move |_| {
        let _ = range.get();
        refresh();
    });

    let sse = use_sse();
    Effect::new(move |_| {
        if let Some(ev) = sse.last_event.get() {
            match ev {
                SyncEvent::SessionCreated(_)
                | SyncEvent::SessionUpdated(_)
                | SyncEvent::SessionDeleted(_) => refresh(),
                _ => {}
            }
        }
    });

    view! {
        <Title text="Focus — Ultiq" />
        <AppShell>
            <div class="p-4 md:p-8 max-w-5xl mx-auto">
                <header class="flex items-center justify-between mb-6 flex-wrap gap-3">
                    <h1 class="text-3xl font-bold text-ultiq-indigo">"Focus"</h1>
                    <RangeToggle range=range />
                </header>

                <Show when=move || error.get().is_some()>
                    <div class="bg-ultiq-red/5 text-ultiq-red rounded-lg p-3 mb-4 text-sm">
                        {move || error.get().unwrap_or_default()}
                    </div>
                </Show>

                {move || match (stats.get(), loading.get()) {
                    (Some(s), _) => Either::Left(view! {
                        <StatRow stats=s sessions=sessions />
                    }),
                    (None, true) => Either::Right(view! {
                        <p class="text-ultiq-indigo/50 text-sm">"Loading…"</p>
                    }),
                    (None, false) => Either::Right(view! {
                        <p class="text-ultiq-indigo/50 text-sm">"No data yet."</p>
                    }),
                }}

                <section class="bg-white rounded-2xl shadow p-6 mt-6">
                    <header class="flex items-center justify-between mb-4">
                        <h2 class="text-lg font-semibold text-ultiq-indigo">"Daily focus"</h2>
                        <p class="text-xs text-ultiq-indigo/50">
                            {move || format!("Last {} days · completed vs cancelled", range.get().days())}
                        </p>
                    </header>
                    <DailyFocus sessions=sessions today=today range=range />
                </section>

                <div class="grid grid-cols-1 md:grid-cols-2 gap-6 mt-6">
                    <section class="bg-white rounded-2xl shadow p-6">
                        <h2 class="text-lg font-semibold text-ultiq-indigo mb-4">
                            "Top tags"
                        </h2>
                        {move || stats.get().map(|s| view! { <TopTags tags=s.top_tags /> })}
                    </section>

                    <section class="bg-white rounded-2xl shadow p-6">
                        <h2 class="text-lg font-semibold text-ultiq-indigo mb-4">
                            "Pickups per session"
                        </h2>
                        <PickupsHistogram sessions=sessions />
                    </section>
                </div>
            </div>
        </AppShell>
    }
}

#[component]
fn RangeToggle(range: RwSignal<Range>) -> impl IntoView {
    view! {
        <div class="flex items-center gap-1 bg-white rounded-full border border-ultiq-indigo/15 p-1">
            {[Range::Week, Range::Month, Range::Quarter].iter().map(|r| {
                let r = *r;
                let is_active = move || range.get() == r;
                view! {
                    <button
                        on:click=move |_| range.set(r)
                        class=move || {
                            let base = "px-3 py-1 text-sm rounded-full transition-colors cursor-pointer";
                            if is_active() {
                                format!("{} bg-ultiq-indigo text-ultiq-cream", base)
                            } else {
                                format!("{} text-ultiq-indigo/70 hover:text-ultiq-indigo", base)
                            }
                        }
                    >
                        {r.label()}
                    </button>
                }
            }).collect_view()}
        </div>
    }
}

#[component]
fn StatRow(
    stats: SessionStats,
    sessions: RwSignal<Vec<ProductivitySession>>,
) -> impl IntoView {
    // Compute range-bound focus minutes from the actual records (stats only gives today/week).
    let total_minutes: i64 = sessions.get().iter()
        .filter(|s| s.completed)
        .map(|s| s.duration_minutes as i64)
        .sum();
    let completed_count: i64 = sessions.get().iter().filter(|s| s.completed).count() as i64;

    view! {
        <div class="grid grid-cols-2 md:grid-cols-5 gap-3">
            <Stat
                label="Focus (range)"
                value=format_minutes(total_minutes)
                sub=Some(format!("{} sessions", completed_count))
            />
            <Stat
                label="Today"
                value=format_minutes(stats.total_focus_minutes_today)
                sub=Some(format!("{} sessions", stats.sessions_completed_today))
            />
            <Stat
                label="Streak"
                value=format!("{}d", stats.current_streak_days)
                sub=Some(format!("longest {}d", stats.longest_streak_days))
            />
            <Stat
                label="Avg pickups"
                value=format!("{:.1}", stats.avg_phone_pickups_per_session)
                sub=Some("per session".to_string())
            />
            <Stat
                label="Pickups today"
                value=stats.total_phone_pickups_today.to_string()
                sub=None
            />
        </div>
    }
}

#[component]
fn Stat(
    label: &'static str,
    value: String,
    sub: Option<String>,
) -> impl IntoView {
    view! {
        <div class="bg-white rounded-2xl p-4 shadow-sm">
            <p class="text-xs text-ultiq-indigo/60 font-medium uppercase tracking-wider">{label}</p>
            <p class="text-2xl font-bold text-ultiq-indigo mt-1">{value}</p>
            <Show when={
                let s = sub.clone();
                move || s.is_some()
            }>
                <p class="text-xs text-ultiq-indigo/50 mt-1">
                    {sub.clone().unwrap_or_default()}
                </p>
            </Show>
        </div>
    }
}

#[component]
fn DailyFocus(
    sessions: RwSignal<Vec<ProductivitySession>>,
    today: NaiveDate,
    range: RwSignal<Range>,
) -> impl IntoView {
    view! {
        {move || {
            let recs = sessions.get();
            let r = range.get();
            let days = r.days();
            let start = today - Duration::days(days - 1);

            // For each day in range, sum completed and cancelled minutes.
            let mut completed = vec![0_i64; days as usize];
            let mut cancelled = vec![0_i64; days as usize];
            for s in &recs {
                let date = s.started_at.with_timezone(&Local).date_naive();
                let idx = (date - start).num_days();
                if idx < 0 || (idx as usize) >= completed.len() {
                    continue;
                }
                let i = idx as usize;
                if s.completed {
                    completed[i] += s.duration_minutes as i64;
                } else {
                    cancelled[i] += s.duration_minutes as i64;
                }
            }

            let max = completed.iter().chain(cancelled.iter())
                .copied()
                .max()
                .unwrap_or(0)
                .max(60);

            if recs.is_empty() {
                return view! {
                    <p class="text-sm text-ultiq-indigo/50 py-12 text-center">
                        "No focus sessions in this range."
                    </p>
                }.into_any();
            }

            view! {
                <div class="space-y-3">
                    <div class="flex items-end gap-1 h-32">
                        {(0..days as usize).map(|i| {
                            let c = completed[i];
                            let x = cancelled[i];
                            let c_pct = (c as f64 / max as f64) * 100.0;
                            let x_pct = (x as f64 / max as f64) * 100.0;
                            let total = c + x;
                            let day = start + Duration::days(i as i64);
                            view! {
                                <div class="flex-1 flex flex-col justify-end gap-px h-full" title=format!("{}: {} (cancelled {})", day, format_minutes(c), format_minutes(x))>
                                    <Show when=move || { x > 0 }>
                                        <div
                                            class="bg-ultiq-indigo/30 rounded-t"
                                            style:height=format!("{}%", x_pct)
                                        />
                                    </Show>
                                    <div
                                        class="bg-ultiq-indigo rounded-t"
                                        style:height=format!("{}%", c_pct.max(if total == 0 { 0.0 } else { 2.0 }))
                                    />
                                </div>
                            }
                        }).collect_view()}
                    </div>
                    <div class="flex items-center gap-4 text-xs text-ultiq-indigo/60">
                        <span class="flex items-center gap-1.5">
                            <span class="w-2.5 h-2.5 rounded-sm bg-ultiq-indigo" />
                            "Completed"
                        </span>
                        <span class="flex items-center gap-1.5">
                            <span class="w-2.5 h-2.5 rounded-sm bg-ultiq-indigo/30" />
                            "Cancelled"
                        </span>
                    </div>
                </div>
            }.into_any()
        }}
    }
}

#[component]
fn TopTags(tags: Vec<TagStat>) -> impl IntoView {
    if tags.is_empty() {
        return view! {
            <p class="text-sm text-ultiq-indigo/50 py-6 text-center">
                "No tags yet."
            </p>
        }.into_any();
    }

    let max = tags.iter().map(|t| t.total_minutes).max().unwrap_or(1).max(1);
    let top: Vec<TagStat> = tags.into_iter().take(5).collect();

    view! {
        <ul class="space-y-3">
            {top.into_iter().map(|t| {
                let pct = (t.total_minutes as f64 / max as f64) * 100.0;
                view! {
                    <li>
                        <div class="flex items-center justify-between text-sm mb-1">
                            <span class="font-medium text-ultiq-indigo truncate">{t.tag.clone()}</span>
                            <span class="text-ultiq-indigo/60 text-xs">
                                {format!("{} · {} sessions", format_minutes(t.total_minutes), t.session_count)}
                            </span>
                        </div>
                        <div class="h-2 bg-ultiq-indigo/10 rounded-full overflow-hidden">
                            <div
                                class="h-full bg-ultiq-indigo"
                                style:width=format!("{}%", pct)
                            />
                        </div>
                    </li>
                }
            }).collect_view()}
        </ul>
    }.into_any()
}

#[component]
fn PickupsHistogram(sessions: RwSignal<Vec<ProductivitySession>>) -> impl IntoView {
    view! {
        {move || {
            let recs = sessions.get();
            if recs.is_empty() {
                return view! {
                    <p class="text-sm text-ultiq-indigo/50 py-6 text-center">
                        "No sessions yet."
                    </p>
                }.into_any();
            }

            // Bucket: 0, 1, 2, 3, 4, 5+
            let mut buckets: BTreeMap<i32, i32> = BTreeMap::new();
            for k in 0..=5 {
                buckets.insert(k, 0);
            }
            for s in &recs {
                let bucket = s.phone_pickups.min(5);
                *buckets.entry(bucket).or_insert(0) += 1;
            }
            let max = *buckets.values().max().unwrap_or(&1).max(&1);

            view! {
                <div class="space-y-3">
                    <div class="flex items-end gap-2 h-32">
                        {buckets.into_iter().map(|(k, c)| {
                            let pct = (c as f64 / max as f64) * 100.0;
                            let label = if k == 5 { "5+".to_string() } else { k.to_string() };
                            view! {
                                <div class="flex flex-col items-center justify-end h-full flex-1 gap-1">
                                    <span class="text-xs text-ultiq-indigo/60 font-medium">{c}</span>
                                    <div
                                        class="w-full rounded-t bg-ultiq-red/70"
                                        style:height=format!("{}%", pct.max(2.0))
                                    />
                                    <span class="text-xs text-ultiq-indigo/70 mt-1">{label}</span>
                                </div>
                            }
                        }).collect_view()}
                    </div>
                    <p class="text-xs text-ultiq-indigo/50 text-center">"pickups per session"</p>
                </div>
            }.into_any()
        }}
    }
}
