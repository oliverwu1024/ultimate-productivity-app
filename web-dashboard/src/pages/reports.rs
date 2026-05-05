use std::collections::HashMap;

use chrono::{Datelike, Duration, Local, NaiveDate, Weekday};
use leptos::prelude::*;

use crate::api::calendar::{list_events, CalendarEvent, EventCategory};
use crate::api::sessions::{list_sessions, ProductivitySession};
use crate::api::sleep::{list_records, SleepRecord};
use crate::components::layout::AppShell;

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
            Self::ThisWeek => "This week",
            Self::LastWeek => "Last week",
            Self::ThisMonth => "This month",
            Self::LastMonth => "Last month",
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
    const MONTHS: [&str; 12] = [
        "Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
    ];
    format!("{} {} {}", MONTHS[(d.month() - 1) as usize], d.day(), d.year())
}

fn weekday_short(w: Weekday) -> &'static str {
    match w {
        Weekday::Mon => "Mon",
        Weekday::Tue => "Tue",
        Weekday::Wed => "Wed",
        Weekday::Thu => "Thu",
        Weekday::Fri => "Fri",
        Weekday::Sat => "Sat",
        Weekday::Sun => "Sun",
    }
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
        <AppShell>
            <div class="p-8 max-w-4xl mx-auto print:p-0 print:max-w-none">
                <header class="flex items-center justify-between mb-6 flex-wrap gap-3 print:hidden">
                    <h1 class="text-3xl font-bold text-ultiq-indigo">"Reports"</h1>
                    <div class="flex items-center gap-2 flex-wrap">
                        <PeriodToggle period=period />
                        <button
                            on:click=on_print
                            class="px-4 py-1.5 bg-ultiq-indigo text-ultiq-cream rounded-full text-sm font-medium hover:opacity-90 cursor-pointer"
                        >
                            "Print / Save PDF"
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
                        <h2 class="text-2xl font-bold text-ultiq-indigo">"Ultiq report"</h2>
                        <p class="text-sm text-ultiq-indigo/60 mt-1">
                            {move || {
                                let (s, e) = period.get().range(today);
                                format!("{} · {} – {}", period.get().label(), fmt_date(s), fmt_date(e))
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
                        {move || format!("Generated {} · ultiqapp.com", fmt_date(today))}
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
                        {p.label()}
                    </button>
                }
            }).collect_view()}
        </div>
    }
}

#[component]
fn SleepSection(records: Vec<SleepRecord>) -> impl IntoView {
    let n = records.len();
    let total_minutes: f64 = records.iter().map(|r| duration_minutes(r)).sum();
    let avg = if n == 0 { 0.0 } else { total_minutes / n as f64 };
    let avg_quality = if n == 0 {
        0.0
    } else {
        records.iter().map(|r| r.quality_rating as f64).sum::<f64>() / n as f64
    };
    let total_pickups: i32 = records.iter().map(|r| r.phone_pickups).sum();

    let best = records
        .iter()
        .max_by_key(|r| r.quality_rating)
        .map(|r| {
            let day = r.actual_wake_time.with_timezone(&Local).date_naive();
            format!("{} ({}★, {})", fmt_date(day), r.quality_rating, fmt_minutes(duration_minutes(r)))
        })
        .unwrap_or_else(|| "—".to_string());

    view! {
        <section>
            <h3 class="text-lg font-semibold text-ultiq-indigo mb-3">"Sleep"</h3>
            <div class="grid grid-cols-2 md:grid-cols-4 gap-3 mb-3">
                <ReportStat label="Nights logged" value=n.to_string() />
                <ReportStat label="Avg duration" value=fmt_minutes(avg) />
                <ReportStat
                    label="Avg quality"
                    value=if n == 0 { "—".to_string() } else { format!("{:.1}/5", avg_quality) }
                />
                <ReportStat label="Total pickups" value=total_pickups.to_string() />
            </div>
            <p class="text-sm text-ultiq-indigo/70">
                <strong>"Best night: "</strong>{best}
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
            <h3 class="text-lg font-semibold text-ultiq-indigo mb-3">"Focus"</h3>
            <div class="grid grid-cols-2 md:grid-cols-4 gap-3 mb-3">
                <ReportStat label="Sessions" value=session_count.to_string() />
                <ReportStat label="Total focus" value=fmt_minutes_i(total_minutes) />
                <ReportStat
                    label="Avg session"
                    value=if session_count == 0 {
                        "—".to_string()
                    } else {
                        fmt_minutes_i(total_minutes / session_count as i64)
                    }
                />
                <ReportStat label="Pickups" value=total_pickups.to_string() />
            </div>
            <Show when=move || has_top>
                <div>
                    <p class="text-sm font-medium text-ultiq-indigo mb-2">"Top tags"</p>
                    <ul class="text-sm space-y-1 text-ultiq-indigo/80">
                        {top.iter().map(|(tag, m, c)| view! {
                            <li class="flex items-center justify-between gap-3">
                                <span class="font-medium">{tag.clone()}</span>
                                <span class="text-ultiq-indigo/60 text-xs">
                                    {format!("{} · {} sessions", fmt_minutes_i(*m), c)}
                                </span>
                            </li>
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
    let mut by_category: HashMap<&'static str, i32> = HashMap::new();
    for e in &records {
        let label = e.category.label();
        *by_category.entry(label).or_insert(0) += 1;
    }
    let mut cats: Vec<(&'static str, i32)> = by_category.into_iter().collect();
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
        .map(|(d, c)| format!("{} ({} events)", weekday_short(*d), c))
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
            <h3 class="text-lg font-semibold text-ultiq-indigo mb-3">"Calendar"</h3>
            <div class="grid grid-cols-2 md:grid-cols-3 gap-3 mb-3">
                <ReportStat label="Events" value=n.to_string() />
                <ReportStat label="Scheduled time" value=fmt_minutes(total_minutes) />
                <ReportStat label="Busiest day" value=busiest />
            </div>
            <Show when=move || has_cats>
                <div>
                    <p class="text-sm font-medium text-ultiq-indigo mb-2">"By category"</p>
                    <ul class="text-sm space-y-1 text-ultiq-indigo/80">
                        {cats.iter().map(|(label, c)| view! {
                            <li class="flex items-center justify-between gap-3">
                                <span class="font-medium">{*label}</span>
                                <span class="text-ultiq-indigo/60 text-xs">
                                    {format!("{} events", c)}
                                </span>
                            </li>
                        }).collect_view()}
                    </ul>
                </div>
            </Show>
        </section>
    }
}

#[component]
fn ReportStat(label: &'static str, value: String) -> impl IntoView {
    view! {
        <div class="bg-ultiq-cream/50 rounded-xl p-3 print:bg-white print:border print:border-ultiq-indigo/15">
            <p class="text-xs text-ultiq-indigo/60 uppercase tracking-wider font-medium">{label}</p>
            <p class="text-xl font-bold text-ultiq-indigo mt-1">{value}</p>
        </div>
    }
}

// Suppress unused-variant warning on EventCategory imports
#[allow(dead_code)]
fn _force_use_event_category(c: EventCategory) -> &'static str {
    c.label()
}
