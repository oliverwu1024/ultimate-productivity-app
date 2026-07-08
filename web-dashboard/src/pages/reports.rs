use std::collections::HashMap;

use chrono::{Datelike, Duration, Local, NaiveDate, Weekday};
use leptos::prelude::*;
use leptos_meta::Title;

use crate::api::calendar::{list_events, CalendarEvent, EventCategory};
use crate::api::sessions::{list_sessions, ProductivitySession};
use crate::api::sleep::{list_records, SleepRecord};
use crate::components::layout::AppShell;
use crate::i18n::{current_locale, t, t_args};

#[derive(Clone, Copy, PartialEq, Eq)]
enum Period {
    ThisWeek,
    LastWeek,
    ThisMonth,
    LastMonth,
}

impl Period {
    fn label(&self) -> &'static str {
        match self {
            Self::ThisWeek => "rep.period_this_week",
            Self::LastWeek => "rep.period_last_week",
            Self::ThisMonth => "rep.period_this_month",
            Self::LastMonth => "rep.period_last_month",
        }
    }
    fn range(&self, today: NaiveDate) -> (NaiveDate, NaiveDate) {
        match self {
            Self::ThisWeek => {
                let monday = today - Duration::days(today.weekday().num_days_from_monday() as i64);
                (monday, monday + Duration::days(6))
            }
            Self::LastWeek => {
                let this_mon = today - Duration::days(today.weekday().num_days_from_monday() as i64);
                let last_mon = this_mon - Duration::days(7);
                (last_mon, last_mon + Duration::days(6))
            }
            Self::ThisMonth => {
                let first = NaiveDate::from_ymd_opt(today.year(), today.month(), 1).unwrap();
                let next = if today.month() == 12 {
                    NaiveDate::from_ymd_opt(today.year() + 1, 1, 1).unwrap()
                } else {
                    NaiveDate::from_ymd_opt(today.year(), today.month() + 1, 1).unwrap()
                };
                (first, next - Duration::days(1))
            }
            Self::LastMonth => {
                let first_this = NaiveDate::from_ymd_opt(today.year(), today.month(), 1).unwrap();
                let last_last = first_this - Duration::days(1);
                let first_last = NaiveDate::from_ymd_opt(last_last.year(), last_last.month(), 1).unwrap();
                (first_last, last_last)
            }
        }
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

fn fmt_minutes_i(m: i64) -> String {
    fmt_minutes(m as f64)
}

fn fmt_date(d: NaiveDate) -> String {
    d.format_localized("%b %d %Y", current_locale().chrono()).to_string()
}

fn weekday_short_localized(w: Weekday) -> String {
    // Reference week (2024-01-01 is a Monday) → format the matching weekday.
    let d = NaiveDate::from_ymd_opt(2024, 1, 1).unwrap()
        + Duration::days(w.num_days_from_monday() as i64);
    d.format_localized("%a", current_locale().chrono()).to_string()
}

fn sessions_sub(n: i64) -> String {
    let s = n.to_string();
    if n == 1 {
        t_args("common.sessions_one", &[("count", s.as_str())])
    } else {
        t_args("common.sessions_other", &[("count", s.as_str())])
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

fn duration_minutes(record: &SleepRecord) -> f64 {
    ((record.actual_wake_time - record.actual_bedtime).num_seconds() as f64 / 60.0).max(0.0)
}

#[component]
pub fn ReportsPage() -> impl IntoView {
    let today = Local::now().date_naive();
    let period = RwSignal::new(Period::ThisWeek);
    let sleep: RwSignal<Vec<SleepRecord>> = RwSignal::new(Vec::new());
    let sessions: RwSignal<Vec<ProductivitySession>> = RwSignal::new(Vec::new());
    let events: RwSignal<Vec<CalendarEvent>> = RwSignal::new(Vec::new());
    let loading = RwSignal::new(false);
    let error = RwSignal::new(None::<String>);

    let refresh = move || {
        let p = period.get_untracked();
        let (start, end) = p.range(today);
        loading.set(true);
        error.set(None);
        wasm_bindgen_futures::spawn_local(async move {
            let s = list_records(start, end).await;
            let f = list_sessions(start, end).await;
            let c = list_events(start, end).await;
            match (s, f, c) {
                (Ok(s), Ok(f), Ok(c)) => {
                    sleep.set(s);
                    sessions.set(f);
                    events.set(c);
                }
                (Err(e), _, _) | (_, Err(e), _) | (_, _, Err(e)) => {
                    error.set(Some(e.message))
                }
            }
            loading.set(false);
        });
    };

    Effect::new(move |_| {
        let _ = period.get();
        refresh();
    });

    let on_print = move |_| {
        if let Some(w) = web_sys::window() {
            let _ = w.print();
        }
    };

    view! {
        <Title text="Reports — Ultiq" />
        <AppShell>
            <div class="p-4 md:p-8 max-w-4xl mx-auto print:p-0 print:max-w-none">
                <header class="flex items-center justify-between mb-6 flex-wrap gap-3 print:hidden">
                    <h1 class="text-3xl font-bold text-ultiq-indigo">{move || t("nav.reports")}</h1>
                    <div class="flex items-center gap-2 flex-wrap">
                        <PeriodToggle period=period />
                        <button
                            on:click=on_print
                            class="px-4 py-1.5 bg-ultiq-indigo text-ultiq-cream rounded-full text-sm font-medium hover:opacity-90 cursor-pointer"
                        >
                            {move || t("rep.print")}
                        </button>
                    </div>
                </header>

                <Show when=move || error.get().is_some()>
                    <div class="bg-ultiq-red/5 text-ultiq-red rounded-lg p-3 mb-4 text-sm">
                        {move || error.get().unwrap_or_default()}
                    </div>
                </Show>

                <article class="bg-white rounded-2xl shadow p-8 print:shadow-none print:rounded-none print:p-0 space-y-8">
                    <header class="border-b border-ultiq-indigo/10 pb-4">
                        <h2 class="text-2xl font-bold text-ultiq-indigo">{move || t("rep.report_title")}</h2>
                        <p class="text-sm text-ultiq-indigo/60 mt-1">
                            {move || {
                                let (s, e) = period.get().range(today);
                                format!("{} · {} – {}", t(period.get().label()), fmt_date(s), fmt_date(e))
                            }}
                        </p>
                    </header>

                    {move || {
                        let p = period.get();
                        let (start, end) = p.range(today);
                        let sleep_recs = sleep.get();
                        let session_recs = sessions.get();
                        let event_recs = events.get();

                        view! {
                            <SleepSection records=sleep_recs />
                            <FocusSection records=session_recs />
                            <CalendarSection records=event_recs start=start end=end />
                        }
                    }}

                    <footer class="text-xs text-ultiq-indigo/50 border-t border-ultiq-indigo/10 pt-4">
                        {move || {
                            let d = fmt_date(today);
                            t_args("rep.generated", &[("date", d.as_str())])
                        }}
                    </footer>
                </article>
            </div>
        </AppShell>
    }
}

#[component]
fn PeriodToggle(period: RwSignal<Period>) -> impl IntoView {
    view! {
        <div class="flex items-center gap-1 bg-white rounded-full border border-ultiq-indigo/15 p-1">
            {[Period::ThisWeek, Period::LastWeek, Period::ThisMonth, Period::LastMonth].iter().map(|p| {
                let p = *p;
                let is_active = move || period.get() == p;
                view! {
                    <button
                        on:click=move |_| period.set(p)
                        class=move || {
                            let base = "px-3 py-1 text-sm rounded-full transition-colors cursor-pointer";
                            if is_active() {
                                format!("{} bg-ultiq-indigo text-ultiq-cream", base)
                            } else {
                                format!("{} text-ultiq-indigo/70 hover:text-ultiq-indigo", base)
                            }
                        }
                    >
                        {move || t(p.label())}
                    </button>
                }
            }).collect_view()}
        </div>
    }
}

#[component]
fn SleepSection(records: Vec<SleepRecord>) -> impl IntoView {
    // §last-night — night-only aggregates; naps counted separately.
    let n = records.iter().filter(|r| !r.is_nap).count();
    let nap_count = records.len() - n;
    let total_minutes: f64 = records.iter().filter(|r| !r.is_nap).map(|r| duration_minutes(r)).sum();
    let avg = if n == 0 { 0.0 } else { total_minutes / n as f64 };
    let avg_quality = if n == 0 {
        0.0
    } else {
        records.iter().filter(|r| !r.is_nap).map(|r| r.quality_rating as f64).sum::<f64>() / n as f64
    };
    let total_pickups: i32 = records.iter().filter(|r| !r.is_nap).map(|r| r.phone_pickups).sum();

    // §sleep-day — Label the best-quality night by its sleep_day so a
    // Tue 02:00 bedtime reads as "Mon 25" (the night it was) instead
    // of "Tue 26" (the morning it ended). Matches Android + chart.
    let best = records
        .iter()
        .filter(|r| !r.is_nap)
        .max_by_key(|r| r.quality_rating)
        .map(|r| {
            let day = crate::sleep_day::sleep_day_for(r.actual_bedtime);
            format!("{} ({}★, {})", fmt_date(day), r.quality_rating, fmt_minutes(duration_minutes(r)))
        })
        .unwrap_or_else(|| "—".to_string());

    view! {
        <section>
            <h3 class="text-lg font-semibold text-ultiq-indigo mb-3">{move || t("nav.sleep")}</h3>
            <div class="grid grid-cols-2 md:grid-cols-4 gap-3 mb-3">
                <ReportStat label_key="rep.sleep_nights" value=n.to_string() />
                <ReportStat label_key="slp.stat_avg_duration" value=fmt_minutes(avg) />
                <ReportStat
                    label_key="slp.stat_avg_quality"
                    value=if n == 0 { "—".to_string() } else { format!("{:.1}/5", avg_quality) }
                />
                <ReportStat label_key="rep.sleep_total_pickups" value=total_pickups.to_string() />
                {(nap_count > 0).then(|| view! { <ReportStat label_key="slp.stat_naps" value=nap_count.to_string() /> })}
            </div>
            <p class="text-sm text-ultiq-indigo/70">
                <strong>{move || t("rep.best_night")}</strong>" "{best}
            </p>
        </section>
    }
}

#[component]
fn FocusSection(records: Vec<ProductivitySession>) -> impl IntoView {
    let completed: Vec<&ProductivitySession> = records.iter().filter(|s| s.completed).collect();
    let total_minutes: i64 = completed.iter().map(|s| s.duration_minutes as i64).sum();
    let session_count = completed.len();
    let total_pickups: i32 = completed.iter().map(|s| s.phone_pickups).sum();

    // Top tags by total minutes
    let mut by_tag: HashMap<String, (i64, i64)> = HashMap::new(); // (minutes, sessions)
    for s in &completed {
        let entry = by_tag.entry(s.tag.clone()).or_insert((0, 0));
        entry.0 += s.duration_minutes as i64;
        entry.1 += 1;
    }
    let mut top: Vec<(String, i64, i64)> = by_tag
        .into_iter()
        .map(|(t, (m, c))| (t, m, c))
        .collect();
    top.sort_by_key(|(_, m, _)| std::cmp::Reverse(*m));
    let top: Vec<(String, i64, i64)> = top.into_iter().take(5).collect();
    let has_top = !top.is_empty();

    view! {
        <section>
            <h3 class="text-lg font-semibold text-ultiq-indigo mb-3">{move || t("nav.focus")}</h3>
            <div class="grid grid-cols-2 md:grid-cols-4 gap-3 mb-3">
                <ReportStat label_key="rep.focus_sessions" value=session_count.to_string() />
                <ReportStat label_key="rep.focus_total" value=fmt_minutes_i(total_minutes) />
                <ReportStat
                    label_key="rep.focus_avg_session"
                    value=if session_count == 0 {
                        "—".to_string()
                    } else {
                        fmt_minutes_i(total_minutes / session_count as i64)
                    }
                />
                <ReportStat label_key="rep.focus_pickups" value=total_pickups.to_string() />
            </div>
            <Show when=move || has_top>
                <div>
                    <p class="text-sm font-medium text-ultiq-indigo mb-2">{move || t("foc.top_tags")}</p>
                    <ul class="text-sm space-y-1 text-ultiq-indigo/80">
                        {top.iter().map(|(tag, m, c)| {
                            let stat = format!("{} · {}", fmt_minutes_i(*m), sessions_sub(*c));
                            view! {
                                <li class="flex items-center justify-between gap-3">
                                    <span class="font-medium">{tag.clone()}</span>
                                    <span class="text-ultiq-indigo/60 text-xs">{stat}</span>
                                </li>
                            }
                        }).collect_view()}
                    </ul>
                </div>
            </Show>
        </section>
    }
}

#[component]
fn CalendarSection(
    records: Vec<CalendarEvent>,
    start: NaiveDate,
    end: NaiveDate,
) -> impl IntoView {
    let n = records.len();

    // Category breakdown
    let mut by_category: HashMap<EventCategory, i32> = HashMap::new();
    for e in &records {
        *by_category.entry(e.category).or_insert(0) += 1;
    }
    let mut cats: Vec<(EventCategory, i32)> = by_category.into_iter().collect();
    cats.sort_by_key(|(_, c)| std::cmp::Reverse(*c));

    // Busiest day-of-week
    let mut by_dow: HashMap<Weekday, i32> = HashMap::new();
    for e in &records {
        let dow = e.start_time.with_timezone(&Local).date_naive().weekday();
        *by_dow.entry(dow).or_insert(0) += 1;
    }
    let busiest = by_dow
        .iter()
        .max_by_key(|(_, c)| **c)
        .map(|(d, c)| {
            let wd = weekday_short_localized(*d);
            let cs = c.to_string();
            t_args("rep.busiest_value", &[("day", wd.as_str()), ("count", cs.as_str())])
        })
        .unwrap_or_else(|| "—".to_string());

    // Total scheduled hours (start_time → end_time)
    let total_minutes: f64 = records
        .iter()
        .map(|e| (e.end_time - e.start_time).num_seconds() as f64 / 60.0)
        .sum();

    let _ = (start, end); // currently unused — could show coverage % later
    let has_cats = !cats.is_empty();

    view! {
        <section>
            <h3 class="text-lg font-semibold text-ultiq-indigo mb-3">{move || t("nav.calendar")}</h3>
            <div class="grid grid-cols-2 md:grid-cols-3 gap-3 mb-3">
                <ReportStat label_key="rep.cal_events" value=n.to_string() />
                <ReportStat label_key="rep.cal_scheduled" value=fmt_minutes(total_minutes) />
                <ReportStat label_key="rep.cal_busiest" value=busiest />
            </div>
            <Show when=move || has_cats>
                <div>
                    <p class="text-sm font-medium text-ultiq-indigo mb-2">{move || t("rep.by_category")}</p>
                    <ul class="text-sm space-y-1 text-ultiq-indigo/80">
                        {cats.iter().map(|(cat, c)| {
                            let cat = *cat;
                            let cs = c.to_string();
                            let ev = t_args("rep.n_events", &[("count", cs.as_str())]);
                            view! {
                                <li class="flex items-center justify-between gap-3">
                                    <span class="font-medium">{move || category_label(cat)}</span>
                                    <span class="text-ultiq-indigo/60 text-xs">{ev}</span>
                                </li>
                            }
                        }).collect_view()}
                    </ul>
                </div>
            </Show>
        </section>
    }
}

#[component]
fn ReportStat(label_key: &'static str, value: String) -> impl IntoView {
    view! {
        <div class="bg-ultiq-cream/50 rounded-xl p-3 print:bg-white print:border print:border-ultiq-indigo/15">
            <p class="text-xs text-ultiq-indigo/60 uppercase tracking-wider font-medium">{move || t(label_key)}</p>
            <p class="text-xl font-bold text-ultiq-indigo mt-1">{value}</p>
        </div>
    }
}
