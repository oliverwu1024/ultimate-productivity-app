use chrono::{Duration, Local, NaiveDate};
use leptos::either::Either;
use leptos::prelude::*;
use leptos_meta::Title;

use crate::api::sleep::{
    fetch_stats, list_audio_events_for_record, list_records, SleepAudioEvent, SleepRecord,
    SleepStats,
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
            Self::Month => "month",
            Self::Quarter => "month",
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

#[component]
pub fn SleepPage() -> impl IntoView {
    let today = Local::now().date_naive();
    let range = RwSignal::new(Range::Month);
    let records: RwSignal<Vec<SleepRecord>> = RwSignal::new(Vec::new());
    let stats: RwSignal<Option<SleepStats>> = RwSignal::new(None);
    let loading = RwSignal::new(false);
    let error = RwSignal::new(None::<String>);
    // §10 — Audio events for the most-recent sleep_record, populated after
    // records load. Drives the "Sleep sounds — Last night" card; stays empty
    // (and the card hides) when audio tracking was off / no events captured.
    let latest_audio_events: RwSignal<Vec<SleepAudioEvent>> = RwSignal::new(Vec::new());

    let refresh = move || {
        let r = range.get_untracked();
        let start = today - Duration::days(r.days() - 1);
        loading.set(true);
        error.set(None);
        wasm_bindgen_futures::spawn_local(async move {
            let recs = list_records(start, today).await;
            let st = fetch_stats(r.stats_param()).await;
            match (recs, st) {
                (Ok(rs), Ok(s)) => {
                    let latest_id = rs.first().map(|r| r.id.clone());
                    records.set(rs);
                    stats.set(Some(s));
                    // Fetch audio events for the most-recent record (if any).
                    if let Some(id) = latest_id {
                        wasm_bindgen_futures::spawn_local(async move {
                            if let Ok(events) = list_audio_events_for_record(&id).await {
                                latest_audio_events.set(events);
                            } else {
                                latest_audio_events.set(Vec::new());
                            }
                        });
                    } else {
                        latest_audio_events.set(Vec::new());
                    }
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
                SyncEvent::SleepCreated(_)
                | SyncEvent::SleepUpdated(_)
                | SyncEvent::SleepDeleted(_) => refresh(),
                _ => {}
            }
        }
    });

    view! {
        <Title text="Sleep — Ultiq" />
        <AppShell>
            <div class="p-4 md:p-8 max-w-5xl mx-auto">
                <header class="flex items-center justify-between mb-6 flex-wrap gap-3">
                    <h1 class="text-3xl font-bold text-ultiq-indigo">"Sleep"</h1>
                    <RangeToggle range=range />
                </header>

                <Show when=move || error.get().is_some()>
                    <div class="bg-ultiq-red/5 text-ultiq-red rounded-lg p-3 mb-4 text-sm">
                        {move || error.get().unwrap_or_default()}
                    </div>
                </Show>

                {move || match (stats.get(), loading.get()) {
                    (Some(s), _) => Either::Left(view! {
                        <StatRow stats=s />
                    }),
                    (None, true) => Either::Right(view! {
                        <p class="text-ultiq-indigo/50 text-sm">"Loading…"</p>
                    }),
                    (None, false) => Either::Right(view! {
                        <p class="text-ultiq-indigo/50 text-sm">"No data yet."</p>
                    }),
                }}

                <LastNightSoundsCard
                    events=latest_audio_events
                    records=records
                />


                <section class="bg-white rounded-2xl shadow p-6 mt-6">
                    <header class="flex items-center justify-between mb-4">
                        <h2 class="text-lg font-semibold text-ultiq-indigo">"Duration over time"</h2>
                        <p class="text-xs text-ultiq-indigo/50">
                            {move || format!("Last {} nights", range.get().days())}
                        </p>
                    </header>
                    <DurationChart records=records stats=stats today=today range=range />
                </section>

                <div class="grid grid-cols-1 md:grid-cols-2 gap-6 mt-6">
                    <section class="bg-white rounded-2xl shadow p-6">
                        <h2 class="text-lg font-semibold text-ultiq-indigo mb-4">
                            "Quality distribution"
                        </h2>
                        <QualityHistogram records=records />
                    </section>

                    <section class="bg-white rounded-2xl shadow p-6">
                        <h2 class="text-lg font-semibold text-ultiq-indigo mb-4">
                            "Phone pickups during sleep"
                        </h2>
                        <PickupsBar records=records today=today range=range />
                    </section>
                </div>
            </div>
        </AppShell>
    }
}

/// §10 — Renders snore + cough counts for the most-recent sleep_record so
/// the web dashboard matches the mobile "Sleep sounds — Last night" card.
/// Auto-hides when no audio events were captured (users who never turned the
/// toggle on never see this surface).
#[component]
fn LastNightSoundsCard(
    events: RwSignal<Vec<SleepAudioEvent>>,
    records: RwSignal<Vec<SleepRecord>>,
) -> impl IntoView {
    view! {
        {move || {
            let evs = events.get();
            let snore_count = evs.iter().filter(|e| e.event_type == "snore").count();
            let cough_count = evs.iter().filter(|e| e.event_type == "cough").count();
            if snore_count == 0 && cough_count == 0 {
                return Either::Left(view! { <></> });
            }
            // Date label: "Last night" for records within ~36h, else MMM dd.
            let label = records
                .get()
                .first()
                .map(|r| {
                    let now = chrono::Utc::now();
                    let age = now - r.actual_bedtime;
                    if age.num_hours() <= 36 {
                        "Last night".to_string()
                    } else {
                        r.actual_bedtime
                            .with_timezone(&Local)
                            .format("%a, %b %d")
                            .to_string()
                    }
                })
                .unwrap_or_else(|| "Last night".to_string());
            Either::Right(view! {
                <section class="bg-white rounded-2xl shadow p-6 mt-6">
                    <header class="flex items-center justify-between mb-4">
                        <h2 class="text-lg font-semibold text-ultiq-indigo">
                            "Sleep sounds — " {label}
                        </h2>
                        <p class="text-xs text-ultiq-indigo/50">
                            "On-device · audio never uploaded"
                        </p>
                    </header>
                    <div class="grid grid-cols-2 gap-6">
                        {(snore_count > 0).then(|| view! {
                            <div>
                                <p class="text-3xl font-semibold text-ultiq-indigo">
                                    {snore_count}
                                </p>
                                <p class="text-sm text-ultiq-indigo/70">
                                    {if snore_count == 1 { "Snoring episode" } else { "Snoring episodes" }}
                                </p>
                            </div>
                        })}
                        {(cough_count > 0).then(|| view! {
                            <div>
                                <p class="text-3xl font-semibold text-ultiq-indigo">
                                    {cough_count}
                                </p>
                                <p class="text-sm text-ultiq-indigo/70">
                                    {if cough_count == 1 { "Coughing episode" } else { "Coughing episodes" }}
                                </p>
                            </div>
                        })}
                    </div>
                </section>
            })
        }}
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
fn StatRow(stats: SleepStats) -> impl IntoView {
    let avg_duration = format_minutes(stats.avg_duration_minutes);
    let avg_quality = if stats.total_records == 0 {
        "—".to_string()
    } else {
        format!("{:.1}/5", stats.avg_quality)
    };
    let debt = if stats.debt_minutes > 0.0 {
        format_minutes(stats.debt_minutes)
    } else {
        "—".to_string()
    };
    let extra = if stats.extra_minutes > 0.0 {
        format_minutes(stats.extra_minutes)
    } else {
        "—".to_string()
    };
    let pickups = if stats.total_records == 0 {
        "—".to_string()
    } else {
        format!("{:.1}", stats.avg_phone_pickups)
    };
    let target = format_minutes(stats.sleep_target_minutes as f64);

    view! {
        <div class="grid grid-cols-2 md:grid-cols-5 gap-3">
            <Stat label="Avg duration" value=avg_duration sub=Some(format!("target {}", target)) />
            <Stat label="Avg quality" value=avg_quality sub=None />
            <Stat label="Sleep debt" value=debt sub=None />
            <Stat label="Extra sleep" value=extra sub=None />
            <Stat label="Avg pickups" value=pickups sub=None />
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

fn format_minutes(m: f64) -> String {
    if m <= 0.0 {
        return "0m".to_string();
    }
    let h = (m / 60.0).floor() as i64;
    let mins = (m - (h as f64 * 60.0)).round() as i64;
    if h == 0 {
        format!("{}m", mins)
    } else if mins == 0 {
        format!("{}h", h)
    } else {
        format!("{}h {}m", h, mins)
    }
}

fn duration_minutes(record: &SleepRecord) -> f64 {
    let secs = (record.actual_wake_time - record.actual_bedtime).num_seconds() as f64;
    (secs / 60.0).max(0.0)
}

fn quality_color(rating: i16) -> &'static str {
    match rating {
        5 => "#2ECC71",
        4 => "#7ED957",
        3 => "#FFC83D",
        2 => "#E67E22",
        _ => "#D9474C",
    }
}

#[component]
fn DurationChart(
    records: RwSignal<Vec<SleepRecord>>,
    stats: RwSignal<Option<SleepStats>>,
    today: NaiveDate,
    range: RwSignal<Range>,
) -> impl IntoView {
    view! {
        {move || {
            let recs = records.get();
            let r = range.get();
            let st = stats.get();
            let target_min = st.as_ref().map(|s| s.sleep_target_minutes as f64).unwrap_or(480.0);
            let days = r.days();
            let start = today - Duration::days(days - 1);

            // Map records to (day_index, duration_minutes) using the wake date as the night's "anchor day".
            let mut points: Vec<(i64, f64, i16)> = Vec::new();
            for rec in &recs {
                let wake_date = rec.actual_wake_time
                    .with_timezone(&Local)
                    .date_naive();
                let idx = (wake_date - start).num_days();
                if idx >= 0 && idx < days {
                    points.push((idx, duration_minutes(rec), rec.quality_rating));
                }
            }

            if points.is_empty() {
                return view! {
                    <p class="text-sm text-ultiq-indigo/50 py-12 text-center">
                        "No sleep records in this range."
                    </p>
                }.into_any();
            }

            // Y-axis: 0 to max(target * 1.4, max_duration * 1.1) hours capped at 12h
            let max_duration = points.iter().map(|p| p.1).fold(0.0_f64, f64::max);
            let y_max = (target_min * 1.4).max(max_duration * 1.1).min(720.0).max(target_min + 60.0);

            // SVG dims
            let w: f64 = 720.0;
            let h: f64 = 240.0;
            let pad_l = 40.0;
            let pad_r = 16.0;
            let pad_t = 16.0;
            let pad_b = 32.0;
            let plot_w = w - pad_l - pad_r;
            let plot_h = h - pad_t - pad_b;

            let x_for = |idx: i64| -> f64 {
                if days <= 1 {
                    pad_l + plot_w / 2.0
                } else {
                    pad_l + (idx as f64) * plot_w / (days - 1) as f64
                }
            };
            let y_for = |val: f64| -> f64 {
                pad_t + (1.0 - (val / y_max).clamp(0.0, 1.0)) * plot_h
            };

            let target_y = y_for(target_min);

            // Bars (one per record)
            let bars = points.iter().map(|&(idx, dur, q)| {
                let cx = x_for(idx);
                let bw = (plot_w / (days as f64) * 0.55).max(4.0);
                let by = y_for(dur);
                let bh = (pad_t + plot_h - by).max(2.0);
                view! {
                    <rect
                        x=cx - bw / 2.0
                        y=by
                        width=bw
                        height=bh
                        rx=2
                        fill=quality_color(q)
                    >
                        <title>
                            {format!("{} · quality {}/5", format_minutes(dur), q)}
                        </title>
                    </rect>
                }
            }).collect_view();

            // Y-axis ticks every 2 hours
            let mut ticks: Vec<i64> = Vec::new();
            let mut t = 0;
            while (t as f64) <= y_max {
                ticks.push(t);
                t += 120;
            }
            let tick_lines = ticks.iter().map(|&m| {
                let y = y_for(m as f64);
                view! {
                    <g>
                        <line
                            x1=pad_l y1=y x2=pad_l + plot_w y2=y
                            stroke="currentColor" stroke-opacity="0.06" stroke-width="1"
                        />
                        <text
                            x=pad_l - 8.0 y=y + 4.0
                            text-anchor="end"
                            font-size="10"
                            fill="currentColor"
                            opacity="0.5"
                        >
                            {format!("{}h", m / 60)}
                        </text>
                    </g>
                }
            }).collect_view();

            view! {
                <div class="overflow-x-auto">
                    <svg
                        viewBox=format!("0 0 {} {}", w, h)
                        class="w-full h-auto text-ultiq-indigo"
                        preserveAspectRatio="xMidYMid meet"
                    >
                        {tick_lines}
                        {bars}
                        // Target line
                        <line
                            x1=pad_l y1=target_y x2=pad_l + plot_w y2=target_y
                            stroke="currentColor" stroke-opacity="0.5" stroke-width="1.5" stroke-dasharray="4 4"
                        />
                        <text
                            x=pad_l + plot_w - 4.0 y=target_y - 6.0
                            text-anchor="end" font-size="10" fill="currentColor" opacity="0.7"
                        >
                            {format!("target {}", format_minutes(target_min))}
                        </text>
                    </svg>
                </div>
            }.into_any()
        }}
    }
}

#[component]
fn QualityHistogram(records: RwSignal<Vec<SleepRecord>>) -> impl IntoView {
    view! {
        {move || {
            let recs = records.get();
            if recs.is_empty() {
                return view! {
                    <p class="text-sm text-ultiq-indigo/50 py-6 text-center">
                        "No nights recorded."
                    </p>
                }.into_any();
            }
            let mut counts = [0_i32; 6]; // index 1..=5 used
            for r in &recs {
                let q = r.quality_rating.clamp(1, 5) as usize;
                counts[q] += 1;
            }
            let max = *counts.iter().max().unwrap_or(&1).max(&1);
            view! {
                <div class="flex items-end justify-around gap-2 h-32">
                    {(1..=5).map(|q| {
                        let c = counts[q];
                        let pct = (c as f64 / max as f64) * 100.0;
                        let color = quality_color(q as i16);
                        view! {
                            <div class="flex flex-col items-center justify-end h-full flex-1 gap-1">
                                <span class="text-xs text-ultiq-indigo/60 font-medium">{c}</span>
                                <div
                                    class="w-full rounded-t transition-all"
                                    style:height=format!("{}%", pct.max(2.0))
                                    style:background-color=color
                                />
                                <span class="text-xs text-ultiq-indigo/70 mt-1">
                                    {format!("{}★", q)}
                                </span>
                            </div>
                        }
                    }).collect_view()}
                </div>
            }.into_any()
        }}
    }
}

#[component]
fn PickupsBar(
    records: RwSignal<Vec<SleepRecord>>,
    today: NaiveDate,
    range: RwSignal<Range>,
) -> impl IntoView {
    view! {
        {move || {
            let recs = records.get();
            let r = range.get();
            let days = r.days();
            let start = today - Duration::days(days - 1);

            if recs.is_empty() {
                return view! {
                    <p class="text-sm text-ultiq-indigo/50 py-6 text-center">
                        "No nights recorded."
                    </p>
                }.into_any();
            }

            // Map per night: (idx, pickups)
            let mut series: Vec<(i64, i32)> = Vec::with_capacity(days as usize);
            for d in 0..days {
                series.push((d, 0));
            }
            for rec in &recs {
                let wake_date = rec.actual_wake_time.with_timezone(&Local).date_naive();
                let idx = (wake_date - start).num_days();
                if idx >= 0 && (idx as usize) < series.len() {
                    series[idx as usize].1 = series[idx as usize].1.max(rec.phone_pickups);
                }
            }

            let max = series.iter().map(|s| s.1).max().unwrap_or(0).max(1);
            let avg = (recs.iter().map(|r| r.phone_pickups as i64).sum::<i64>() as f64)
                / (recs.len() as f64);

            view! {
                <div class="space-y-3">
                    <div class="flex items-end gap-1 h-32">
                        {series.into_iter().map(|(_idx, count)| {
                            let pct = (count as f64 / max as f64) * 100.0;
                            view! {
                                <div
                                    class="flex-1 bg-ultiq-red/70 rounded-t hover:bg-ultiq-red transition-colors"
                                    style:height=format!("{}%", pct.max(2.0))
                                    title=format!("{} pickups", count)
                                />
                            }
                        }).collect_view()}
                    </div>
                    <p class="text-xs text-ultiq-indigo/60">
                        {format!("Avg {:.1} pickups/night · max {} on a single night", avg, max)}
                    </p>
                </div>
            }.into_any()
        }}
    }
}
