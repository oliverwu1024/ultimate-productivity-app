use chrono::{Datelike, Duration, Local, NaiveDate, Timelike};
use gloo_storage::{LocalStorage, Storage};
use leptos::prelude::*;
use leptos_router::components::A;

use crate::api::calendar::{list_events, CalendarEvent};
use crate::api::checklist::{list_for_range, ChecklistItem};
use crate::api::sessions::{fetch_stats as fetch_session_stats, list_sessions, ProductivitySession, SessionStats};
use crate::api::sleep::{list_records, SleepRecord};
use crate::api::sse::use_sse;
use crate::components::layout::AppShell;

const ONBOARDING_KEY: &str = "ultiq_onboarding_dismissed";

fn weekday_label(w: chrono::Weekday) -> &'static str {
    match w {
        chrono::Weekday::Mon => "Monday",
        chrono::Weekday::Tue => "Tuesday",
        chrono::Weekday::Wed => "Wednesday",
        chrono::Weekday::Thu => "Thursday",
        chrono::Weekday::Fri => "Friday",
        chrono::Weekday::Sat => "Saturday",
        chrono::Weekday::Sun => "Sunday",
    }
}

const MONTH_NAMES: [&str; 12] = [
    "January", "February", "March", "April", "May", "June",
    "July", "August", "September", "October", "November", "December",
];

fn greeting(d: chrono::DateTime<Local>) -> &'static str {
    let h = d.hour();
    if h < 12 { "Good morning" } else if h < 18 { "Good afternoon" } else { "Good evening" }
}

#[component]
pub fn OverviewPage() -> impl IntoView {
    let now = Local::now();
    let today = now.date_naive();

    let events: RwSignal<Vec<CalendarEvent>> = RwSignal::new(Vec::new());
    let items: RwSignal<Vec<ChecklistItem>> = RwSignal::new(Vec::new());
    let sleep: RwSignal<Vec<SleepRecord>> = RwSignal::new(Vec::new());
    let sessions: RwSignal<Vec<ProductivitySession>> = RwSignal::new(Vec::new());
    let session_stats: RwSignal<Option<SessionStats>> = RwSignal::new(None);

    let onboarding_dismissed = RwSignal::new(
        LocalStorage::get::<String>(ONBOARDING_KEY).is_ok(),
    );

    let dismiss_onboarding = move |_| {
        let _ = LocalStorage::set(ONBOARDING_KEY, "1");
        onboarding_dismissed.set(true);
    };

    let refresh = move || {
        let yesterday = today - Duration::days(1);
        wasm_bindgen_futures::spawn_local(async move {
            if let Ok(list) = list_events(today, today).await {
                events.set(list);
            }
        });
        wasm_bindgen_futures::spawn_local(async move {
            if let Ok(list) = list_for_range(today, today).await {
                items.set(list);
            }
        });
        wasm_bindgen_futures::spawn_local(async move {
            // Pull last 2 days so "last night" lands regardless of whether wake-time was
            // before or after midnight.
            if let Ok(list) = list_records(yesterday, today).await {
                sleep.set(list);
            }
        });
        wasm_bindgen_futures::spawn_local(async move {
            if let Ok(list) = list_sessions(today, today).await {
                sessions.set(list);
            }
        });
        wasm_bindgen_futures::spawn_local(async move {
            if let Ok(s) = fetch_session_stats("week").await {
                session_stats.set(Some(s));
            }
        });
    };

    Effect::new(move |prev: Option<()>| {
        if prev.is_none() {
            refresh();
        }
        ()
    });

    let sse = use_sse();
    Effect::new(move |_| {
        if let Some(_ev) = sse.last_event.get() {
            refresh();
        }
    });

    let header_date = format!(
        "{}, {} {}",
        weekday_label(today.weekday()),
        today.day(),
        MONTH_NAMES[(today.month() - 1) as usize],
    );

    view! {
        <AppShell>
            <div class="p-8 max-w-5xl mx-auto">
                <header class="flex items-center justify-between mb-6">
                    <div>
                        <h1 class="text-3xl font-bold text-ultiq-indigo">
                            {greeting(now)}
                        </h1>
                        <p class="text-sm text-ultiq-indigo/60 mt-1">{header_date.clone()}</p>
                    </div>
                </header>

                <Show when=move || !onboarding_dismissed.get()>
                    <div class="bg-ultiq-yellow/15 border border-ultiq-yellow/40 text-ultiq-indigo rounded-2xl p-4 mb-6 flex items-start gap-3">
                        <div class="flex-1 text-sm leading-relaxed">
                            <p class="font-medium">"Calendar vs Checklist — quick guide"</p>
                            <p class="mt-1 text-ultiq-indigo/70">
                                <strong>"Calendar"</strong>" = time slots (lectures, gym, study blocks). "
                                <strong>"Checklist"</strong>" = todos with a due date but no specific time."
                            </p>
                        </div>
                        <button
                            on:click=dismiss_onboarding
                            class="text-ultiq-indigo/50 hover:text-ultiq-indigo px-2 cursor-pointer"
                            aria-label="Dismiss"
                        >
                            "✕"
                        </button>
                    </div>
                </Show>

                <div class="grid grid-cols-1 md:grid-cols-2 gap-6">
                    <LastNightCard sleep=sleep />
                    <TodayFocusCard sessions=sessions stats=session_stats />
                    <TodayChecklistCard items=items />
                    <TodayEventsCard events=events />
                </div>

                <div class="mt-6 flex flex-wrap gap-3">
                    <A href="/checklist" attr:class="px-4 py-2 bg-ultiq-indigo text-ultiq-cream rounded-lg font-medium hover:opacity-90">
                        "Open checklist"
                    </A>
                    <A href="/calendar" attr:class="px-4 py-2 bg-white text-ultiq-indigo rounded-lg font-medium border border-ultiq-indigo/20 hover:bg-ultiq-indigo/5">
                        "Open calendar"
                    </A>
                </div>
            </div>
        </AppShell>
    }
}

fn fmt_minutes(m: f64) -> String {
    if m <= 0.0 {
        return "0m".to_string();
    }
    let h = (m / 60.0).floor() as i64;
    let mins = (m - h as f64 * 60.0).round() as i64;
    if h == 0 {
        format!("{}m", mins)
    } else if mins == 0 {
        format!("{}h", h)
    } else {
        format!("{}h {}m", h, mins)
    }
}

fn duration_minutes(record: &SleepRecord) -> f64 {
    ((record.actual_wake_time - record.actual_bedtime).num_seconds() as f64 / 60.0).max(0.0)
}

#[component]
fn LastNightCard(sleep: RwSignal<Vec<SleepRecord>>) -> impl IntoView {
    view! {
        <section class="bg-white rounded-2xl shadow p-6 flex flex-col">
            <header class="flex items-center justify-between mb-3">
                <h2 class="text-lg font-semibold text-ultiq-indigo">"Last night"</h2>
                <A href="/sleep" attr:class="text-sm text-ultiq-indigo/60 hover:text-ultiq-indigo">
                    "Sleep analytics →"
                </A>
            </header>
            {move || {
                let recs = sleep.get();
                // Most-recent record by wake time.
                let latest = recs.iter().max_by_key(|r| r.actual_wake_time).cloned();
                match latest {
                    None => view! {
                        <p class="text-sm text-ultiq-indigo/50">
                            "No sleep records yet. Start tracking from the Android app."
                        </p>
                    }.into_any(),
                    Some(r) => {
                        let dur = duration_minutes(&r);
                        let stars = "★".repeat(r.quality_rating.max(0) as usize);
                        let dim_stars = "☆".repeat((5 - r.quality_rating.max(0).min(5)) as usize);
                        let bedtime = r.actual_bedtime
                            .with_timezone(&Local)
                            .format("%H:%M").to_string();
                        let waketime = r.actual_wake_time
                            .with_timezone(&Local)
                            .format("%H:%M").to_string();
                        view! {
                            <div class="space-y-3">
                                <div class="flex items-baseline gap-3">
                                    <span class="text-3xl font-bold text-ultiq-indigo">
                                        {fmt_minutes(dur)}
                                    </span>
                                    <span class="text-sm text-ultiq-indigo/60">
                                        {format!("{} → {}", bedtime, waketime)}
                                    </span>
                                </div>
                                <div class="flex items-center gap-3 text-sm">
                                    <span class="text-ultiq-yellow text-base">
                                        {stars}<span class="text-ultiq-indigo/20">{dim_stars}</span>
                                    </span>
                                    <span class="text-ultiq-indigo/60">
                                        {format!("· {} pickups", r.phone_pickups)}
                                    </span>
                                </div>
                            </div>
                        }.into_any()
                    }
                }
            }}
        </section>
    }
}

#[component]
fn TodayFocusCard(
    sessions: RwSignal<Vec<ProductivitySession>>,
    stats: RwSignal<Option<SessionStats>>,
) -> impl IntoView {
    view! {
        <section class="bg-white rounded-2xl shadow p-6 flex flex-col">
            <header class="flex items-center justify-between mb-3">
                <h2 class="text-lg font-semibold text-ultiq-indigo">"Today's focus"</h2>
                <A href="/focus" attr:class="text-sm text-ultiq-indigo/60 hover:text-ultiq-indigo">
                    "Focus analytics →"
                </A>
            </header>
            {move || {
                let recs = sessions.get();
                let st = stats.get();
                let completed: Vec<&ProductivitySession> = recs.iter().filter(|s| s.completed).collect();
                let total_min: i64 = completed.iter().map(|s| s.duration_minutes as i64).sum();
                let n = completed.len();
                let pickups: i32 = recs.iter().map(|s| s.phone_pickups).sum();
                let streak = st.as_ref().map(|s| s.current_streak_days).unwrap_or(0);

                if n == 0 {
                    view! {
                        <div class="space-y-3">
                            <p class="text-sm text-ultiq-indigo/50">
                                "No focus sessions yet today."
                            </p>
                            <Show when=move || { streak > 0 }>
                                <p class="text-xs text-ultiq-indigo/60">
                                    {format!("Current streak: {} day(s)", streak)}
                                </p>
                            </Show>
                        </div>
                    }.into_any()
                } else {
                    view! {
                        <div class="space-y-3">
                            <div class="flex items-baseline gap-3">
                                <span class="text-3xl font-bold text-ultiq-indigo">
                                    {fmt_minutes(total_min as f64)}
                                </span>
                                <span class="text-sm text-ultiq-indigo/60">
                                    {format!("{} session{}", n, if n == 1 { "" } else { "s" })}
                                </span>
                            </div>
                            <div class="flex items-center gap-3 text-sm text-ultiq-indigo/60">
                                <span>
                                    "🔥 "
                                    {format!("{} day streak", streak)}
                                </span>
                                <Show when=move || { pickups > 0 }>
                                    <span>{format!("· {} pickups", pickups)}</span>
                                </Show>
                            </div>
                        </div>
                    }.into_any()
                }
            }}
        </section>
    }
}

#[component]
fn TodayChecklistCard(items: RwSignal<Vec<ChecklistItem>>) -> impl IntoView {
    view! {
        <section class="bg-white rounded-2xl shadow p-6 flex flex-col">
            <header class="flex items-center justify-between mb-3">
                <h2 class="text-lg font-semibold text-ultiq-indigo">"Today's checklist"</h2>
                <A href="/checklist" attr:class="text-sm text-ultiq-indigo/60 hover:text-ultiq-indigo">
                    "View all →"
                </A>
            </header>
            {move || {
                let all = items.get();
                if all.is_empty() {
                    view! {
                        <p class="text-sm text-ultiq-indigo/50">
                            "No items for today. Add one from the Checklist tab."
                        </p>
                    }.into_any()
                } else {
                    let total = all.len();
                    let done = all.iter().filter(|i| i.completed).count();
                    let pct = (done as f64 / total as f64) * 100.0;
                    let mut open: Vec<&ChecklistItem> = all.iter().filter(|i| !i.completed).collect();
                    open.sort_by_key(|i| -i.priority);
                    let preview: Vec<String> = open.iter().take(4).map(|i| i.title.clone()).collect();
                    let more = open.len().saturating_sub(4);
                    view! {
                        <div class="space-y-3">
                            <div>
                                <div class="flex items-center justify-between text-sm text-ultiq-indigo/70 mb-1.5">
                                    <span>{format!("{} of {} done", done, total)}</span>
                                </div>
                                <div class="h-2 bg-ultiq-indigo/10 rounded-full overflow-hidden">
                                    <div
                                        class="h-full bg-ultiq-indigo transition-all"
                                        style:width=format!("{}%", pct)
                                    />
                                </div>
                            </div>
                            <ul class="space-y-1.5">
                                {preview.into_iter().map(|t| view! {
                                    <li class="text-sm text-ultiq-indigo flex items-center gap-2">
                                        <span class="w-1.5 h-1.5 rounded-full bg-ultiq-indigo/40" />
                                        {t}
                                    </li>
                                }).collect_view()}
                            </ul>
                            <Show when=move || { more > 0 }>
                                <p class="text-xs text-ultiq-indigo/50">
                                    {format!("+ {} more open", more)}
                                </p>
                            </Show>
                        </div>
                    }.into_any()
                }
            }}
        </section>
    }
}

#[component]
fn TodayEventsCard(events: RwSignal<Vec<CalendarEvent>>) -> impl IntoView {
    let today = Local::now().date_naive();

    view! {
        <section class="bg-white rounded-2xl shadow p-6 flex flex-col">
            <header class="flex items-center justify-between mb-3">
                <h2 class="text-lg font-semibold text-ultiq-indigo">"Today's events"</h2>
                <A href="/calendar" attr:class="text-sm text-ultiq-indigo/60 hover:text-ultiq-indigo">
                    "Open calendar →"
                </A>
            </header>
            {move || {
                let mut today_events: Vec<CalendarEvent> = events.get()
                    .into_iter()
                    .filter(|e| e.start_time.with_timezone(&Local).date_naive() == today)
                    .collect();
                today_events.sort_by_key(|e| e.start_time);

                if today_events.is_empty() {
                    view! {
                        <p class="text-sm text-ultiq-indigo/50">
                            "No events scheduled for today."
                        </p>
                    }.into_any()
                } else {
                    view! {
                        <ul class="space-y-2">
                            {today_events.into_iter().take(5).map(|e| {
                                let start = e.start_time.with_timezone(&Local).format("%H:%M").to_string();
                                let end = e.end_time.with_timezone(&Local).format("%H:%M").to_string();
                                let color = e.color.clone();
                                view! {
                                    <li class="flex items-start gap-3">
                                        <span
                                            class="w-1 self-stretch rounded-full"
                                            style:background-color=color
                                        />
                                        <div class="flex-1 min-w-0">
                                            <div class="font-medium text-ultiq-indigo">{e.title.clone()}</div>
                                            <div class="text-xs text-ultiq-indigo/60">
                                                {format!("{} – {}", start, end)}
                                                " · "
                                                {e.category.label()}
                                            </div>
                                        </div>
                                    </li>
                                }
                            }).collect_view()}
                        </ul>
                    }.into_any()
                }
            }}
        </section>
    }
}
