use chrono::{Datelike, Duration, Local, NaiveDate, Timelike};
use gloo_storage::{LocalStorage, Storage};
use leptos::prelude::*;
use leptos_meta::Title;
use leptos_router::components::A;

use crate::api::ai::{fetch_weekly_insight, WeeklyInsight};
use crate::api::calendar::{list_events, CalendarEvent, EventCategory};
use crate::api::checklist::{list_for_range, ChecklistItem};
use crate::api::client::ApiError;
use crate::api::sessions::{fetch_stats as fetch_session_stats, list_sessions, ProductivitySession, SessionStats};
use crate::api::sleep::{list_records, SleepRecord};
use crate::api::sse::{use_sse, SyncEvent};
use crate::components::layout::AppShell;
use crate::i18n::{current_locale, t, t_args};

const ONBOARDING_KEY: &str = "ultiq_onboarding_dismissed";

fn greeting_key(d: chrono::DateTime<Local>) -> &'static str {
    let h = d.hour();
    if h < 12 {
        "ov.greeting_morning"
    } else if h < 18 {
        "ov.greeting_afternoon"
    } else {
        "ov.greeting_evening"
    }
}

fn category_label(c: EventCategory) -> String {
    t(match c {
        EventCategory::Study => "common.category_study",
        EventCategory::Project => "common.category_project",
        EventCategory::Exercise => "common.category_exercise",
        EventCategory::Personal => "common.category_personal",
        EventCategory::Other => "common.category_other",
    })
}

fn fmt_header_date(d: NaiveDate) -> String {
    let loc = current_locale().chrono();
    format!(
        "{}, {} {}",
        d.format_localized("%A", loc),
        d.day(),
        d.format_localized("%B", loc)
    )
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

    // §9.4 Weekly AI insight. Three states: None=not yet fetched,
    // Ok=loaded, Err=fetch failed (network, 429 quota, 502 from Bedrock).
    let weekly_insight: RwSignal<Option<Result<WeeklyInsight, ApiError>>> = RwSignal::new(None);
    let insight_loading: RwSignal<bool> = RwSignal::new(false);

    let load_insight = move || {
        if insight_loading.get_untracked() {
            return;
        }
        insight_loading.set(true);
        wasm_bindgen_futures::spawn_local(async move {
            let result = fetch_weekly_insight().await;
            weekly_insight.set(Some(result));
            insight_loading.set(false);
        });
    };

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
            // Pull 60 days back so recurring items (whose `due_date` is the
            // start date, often months ago) are in the visible window.
            let start = today - Duration::days(60);
            if let Ok(list) = list_for_range(start, today).await {
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
            load_insight();
        }
        ()
    });

    let sse = use_sse();
    Effect::new(move |_| {
        if let Some(ev) = sse.last_event_debounced.get() {
            // Overview shows Calendar / Checklist / Sleep / Session data.
            // SleepAudioClipsChanged fires once per clip attached and is
            // consumed only by the Sleep page, so explicitly skip it here
            // — a snore-heavy night would otherwise re-run all five
            // refresh fetches per clip with no visible change.
            match ev {
                SyncEvent::CalendarCreated(_)
                | SyncEvent::CalendarUpdated(_)
                | SyncEvent::CalendarDeleted(_)
                | SyncEvent::ChecklistCreated(_)
                | SyncEvent::ChecklistUpdated(_)
                | SyncEvent::ChecklistDeleted(_)
                | SyncEvent::SleepCreated(_)
                | SyncEvent::SleepUpdated(_)
                | SyncEvent::SleepDeleted(_)
                | SyncEvent::SessionCreated(_)
                | SyncEvent::SessionUpdated(_)
                | SyncEvent::SessionDeleted(_) => refresh(),
                SyncEvent::SleepAudioClipsChanged(_) => {}
            }
        }
    });

    view! {
        <Title text="Overview — Ultiq" />
        <AppShell>
            <div class="p-4 md:p-8 max-w-5xl mx-auto">
                <header class="flex items-center justify-between mb-6">
                    <div>
                        <h1 class="text-3xl font-bold text-ultiq-indigo">
                            {move || t(greeting_key(now))}
                        </h1>
                        <p class="text-sm text-ultiq-indigo/60 mt-1">{move || fmt_header_date(today)}</p>
                    </div>
                </header>

                <Show when=move || !onboarding_dismissed.get()>
                    <div class="bg-ultiq-yellow/15 border border-ultiq-yellow/40 text-ultiq-indigo rounded-2xl p-4 mb-6 flex items-start gap-3">
                        <div class="flex-1 text-sm leading-relaxed">
                            <p class="font-medium">{move || t("ov.guide_title")}</p>
                            <p class="mt-1 text-ultiq-indigo/70">
                                <strong>{move || t("nav.calendar")}</strong>" = "{move || t("ov.guide_calendar_desc")}" "
                                <strong>{move || t("nav.checklist")}</strong>" = "{move || t("ov.guide_checklist_desc")}
                            </p>
                        </div>
                        <button
                            on:click=dismiss_onboarding
                            class="text-ultiq-indigo/50 hover:text-ultiq-indigo px-2 cursor-pointer"
                            aria-label=move || t("common.dismiss")
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

                <section class="mt-6 bg-white border border-ultiq-indigo/10 rounded-2xl p-6">
                    <div class="flex items-center justify-between mb-3">
                        <h2 class="text-sm font-semibold text-ultiq-indigo/70">
                            {move || t("ov.week_summary")}
                        </h2>
                        <button
                            on:click=move |_| load_insight()
                            disabled=move || insight_loading.get()
                            class="text-ultiq-indigo/50 hover:text-ultiq-indigo px-2 cursor-pointer disabled:opacity-40 disabled:cursor-not-allowed"
                            title=move || t("common.refresh")
                            aria-label=move || t("ov.refresh_insight")
                        >
                            "↻"
                        </button>
                    </div>
                    {move || {
                        if insight_loading.get() {
                            return view! {
                                <p class="text-sm text-ultiq-indigo/60">{move || t("ov.generating")}</p>
                            }.into_any();
                        }
                        match weekly_insight.get() {
                            None => view! {
                                <p class="text-sm text-ultiq-indigo/60">{move || t("ov.generating")}</p>
                            }.into_any(),
                            Some(Ok(i)) => {
                                let paragraphs: Vec<String> = i.content
                                    .split("\n\n")
                                    .map(|p| p.trim().to_string())
                                    .filter(|p| !p.is_empty())
                                    .collect();
                                let cached = i.cached;
                                view! {
                                    <div class="space-y-3 text-sm leading-relaxed text-ultiq-indigo">
                                        {paragraphs.into_iter().map(|p| view! {
                                            <p>{p}</p>
                                        }).collect::<Vec<_>>()}
                                        <Show when=move || cached>
                                            <p class="text-xs text-ultiq-indigo/40 pt-2">
                                                {move || t("ov.refresh_note")}
                                            </p>
                                        </Show>
                                    </div>
                                }.into_any()
                            }
                            Some(Err(e)) => {
                                let msg = if e.status == 429 {
                                    t("ov.err_quota")
                                } else if e.status == 401 {
                                    t("ov.err_signin")
                                } else {
                                    t("ov.err_load")
                                };
                                view! { <p class="text-sm text-red-600">{msg}</p> }.into_any()
                            }
                        }
                    }}
                </section>

                <div class="mt-6 flex flex-wrap gap-3">
                    <A href="/checklist" attr:class="px-4 py-2 bg-ultiq-indigo text-ultiq-cream rounded-lg font-medium hover:opacity-90">
                        {move || t("ov.open_checklist")}
                    </A>
                    <A href="/calendar" attr:class="px-4 py-2 bg-white text-ultiq-indigo rounded-lg font-medium border border-ultiq-indigo/20 hover:bg-ultiq-indigo/5">
                        {move || t("ov.open_calendar")}
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
                <h2 class="text-lg font-semibold text-ultiq-indigo">{move || t("ov.last_night")}</h2>
                <A href="/sleep" attr:class="text-sm text-ultiq-indigo/60 hover:text-ultiq-indigo">
                    {move || t("ov.sleep_analytics")}" →"
                </A>
            </header>
            {move || {
                let recs = sleep.get();
                // Most-recent overnight sleep by wake time (skip daytime naps).
                let latest = recs.iter().filter(|r| !r.is_nap).max_by_key(|r| r.actual_wake_time).cloned();
                match latest {
                    None => view! {
                        <p class="text-sm text-ultiq-indigo/50">
                            {move || t("ov.no_sleep")}
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
                                        {
                                            let p = r.phone_pickups.to_string();
                                            format!("· {}", t_args("ov.pickups", &[("count", p.as_str())]))
                                        }
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
                <h2 class="text-lg font-semibold text-ultiq-indigo">{move || t("ov.today_focus")}</h2>
                <A href="/focus" attr:class="text-sm text-ultiq-indigo/60 hover:text-ultiq-indigo">
                    {move || t("ov.focus_analytics")}" →"
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
                                {move || t("ov.no_focus")}
                            </p>
                            <Show when=move || { streak > 0 }>
                                <p class="text-xs text-ultiq-indigo/60">
                                    {
                                        let s = streak.to_string();
                                        t_args("ov.streak_days", &[("count", s.as_str())])
                                    }
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
                                    {
                                        let ns = n.to_string();
                                        if n == 1 {
                                            t_args("common.sessions_one", &[("count", ns.as_str())])
                                        } else {
                                            t_args("common.sessions_other", &[("count", ns.as_str())])
                                        }
                                    }
                                </span>
                            </div>
                            <div class="flex items-center gap-3 text-sm text-ultiq-indigo/60">
                                <span>
                                    "🔥 "
                                    {
                                        let s = streak.to_string();
                                        t_args("ov.day_streak", &[("count", s.as_str())])
                                    }
                                </span>
                                <Show when=move || { pickups > 0 }>
                                    <span>{
                                        let p = pickups.to_string();
                                        format!("· {}", t_args("ov.pickups", &[("count", p.as_str())]))
                                    }</span>
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
                <h2 class="text-lg font-semibold text-ultiq-indigo">{move || t("ov.today_checklist")}</h2>
                <A href="/checklist" attr:class="text-sm text-ultiq-indigo/60 hover:text-ultiq-indigo">
                    {move || t("ov.view_all")}" →"
                </A>
            </header>
            {move || {
                let today = Local::now().date_naive();
                let visible: Vec<ChecklistItem> = items
                    .get()
                    .into_iter()
                    .filter(|i| i.shows_on(today))
                    .collect();
                if visible.is_empty() {
                    view! {
                        <p class="text-sm text-ultiq-indigo/50">
                            {move || t("ov.no_items")}
                        </p>
                    }.into_any()
                } else {
                    let total = visible.len();
                    let done = visible.iter().filter(|i| i.is_done_on(today)).count();
                    let pct = (done as f64 / total as f64) * 100.0;
                    let mut open: Vec<&ChecklistItem> = visible.iter().filter(|i| !i.is_done_on(today)).collect();
                    open.sort_by_key(|i| -i.priority);
                    let preview: Vec<String> = open.iter().take(4).map(|i| i.title.clone()).collect();
                    let more = open.len().saturating_sub(4);
                    view! {
                        <div class="space-y-3">
                            <div>
                                <div class="flex items-center justify-between text-sm text-ultiq-indigo/70 mb-1.5">
                                    <span>{
                                        let d = done.to_string();
                                        let tot = total.to_string();
                                        t_args("common.progress_done", &[("done", d.as_str()), ("total", tot.as_str())])
                                    }</span>
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
                                    {
                                        let m = more.to_string();
                                        t_args("ov.more_open", &[("count", m.as_str())])
                                    }
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
                <h2 class="text-lg font-semibold text-ultiq-indigo">{move || t("ov.today_events")}</h2>
                <A href="/calendar" attr:class="text-sm text-ultiq-indigo/60 hover:text-ultiq-indigo">
                    {move || t("ov.open_calendar")}" →"
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
                            {move || t("ov.no_events")}
                        </p>
                    }.into_any()
                } else {
                    view! {
                        <ul class="space-y-2">
                            {today_events.into_iter().take(5).map(|e| {
                                let start = e.start_time.with_timezone(&Local).format("%H:%M").to_string();
                                let end = e.end_time.with_timezone(&Local).format("%H:%M").to_string();
                                let color = e.color.clone();
                                let cat = e.category;
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
                                                {move || category_label(cat)}
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
