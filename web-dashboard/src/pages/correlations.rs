use std::collections::HashMap;

use chrono::{Duration, Local, NaiveDate};
use leptos::prelude::*;
use leptos_meta::Title;

use crate::api::sessions::{list_sessions, ProductivitySession};
use crate::api::sleep::{list_records, SleepRecord};
use crate::components::layout::AppShell;
use crate::stats::{interpret_r, linear_fit, pearson};

#[derive(Clone, Copy, PartialEq, Eq)]
enum Range {
    Month,
    Quarter,
    Year,
}

impl Range {
    fn label(&self) -> &'static str {
        match self {
            Self::Month => "Month",
            Self::Quarter => "90d",
            Self::Year => "Year",
        }
    }
    fn days(&self) -> i64 {
        match self {
            Self::Month => 30,
            Self::Quarter => 90,
            Self::Year => 365,
        }
    }
}

fn duration_minutes(record: &SleepRecord) -> f64 {
    ((record.actual_wake_time - record.actual_bedtime).num_seconds() as f64 / 60.0).max(0.0)
}

#[component]
pub fn CorrelationsPage() -> impl IntoView {
    let today = Local::now().date_naive();
    let range = RwSignal::new(Range::Quarter);
    let sleep: RwSignal<Vec<SleepRecord>> = RwSignal::new(Vec::new());
    let sessions: RwSignal<Vec<ProductivitySession>> = RwSignal::new(Vec::new());
    let loading = RwSignal::new(false);
    let error = RwSignal::new(None::<String>);

    let refresh = move || {
        let r = range.get_untracked();
        let start = today - Duration::days(r.days() - 1);
        loading.set(true);
        error.set(None);
        wasm_bindgen_futures::spawn_local(async move {
            let s = list_records(start, today).await;
            let f = list_sessions(start, today).await;
            match (s, f) {
                (Ok(s), Ok(f)) => {
                    sleep.set(s);
                    sessions.set(f);
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

    view! {
        <Title text="Correlations — Ultiq" />
        <AppShell>
            <div class="p-4 md:p-8 max-w-5xl mx-auto">
                <header class="flex items-center justify-between mb-6 flex-wrap gap-3">
                    <div>
                        <h1 class="text-3xl font-bold text-ultiq-indigo">"Correlations"</h1>
                        <p class="text-sm text-ultiq-indigo/60 mt-1">
                            "Cross-feature trends across your sleep + focus history."
                        </p>
                    </div>
                    <RangeToggle range=range />
                </header>

                <Show when=move || error.get().is_some()>
                    <div class="bg-ultiq-red/5 text-ultiq-red rounded-lg p-3 mb-4 text-sm">
                        {move || error.get().unwrap_or_default()}
                    </div>
                </Show>

                {move || {
                    let s_recs = sleep.get();
                    let f_recs = sessions.get();
                    if s_recs.is_empty() || f_recs.is_empty() {
                        return view! {
                            <p class="text-ultiq-indigo/50 text-sm">
                                {if loading.get() { "Loading…" } else { "Need at least a few sleep + focus records to compute correlations." }}
                            </p>
                        }.into_any();
                    }

                    let focus_by_day = focus_minutes_by_day(&f_recs);

                    let duration_pairs = pair_with_focus(
                        &s_recs,
                        &focus_by_day,
                        |r| duration_minutes(r),
                    );
                    let pickup_pairs = pair_with_focus(
                        &s_recs,
                        &focus_by_day,
                        |r| r.phone_pickups as f64,
                    );
                    let quality_pairs = pair_with_focus(
                        &s_recs,
                        &focus_by_day,
                        |r| r.quality_rating as f64,
                    );

                    view! {
                        <div class="grid grid-cols-1 md:grid-cols-2 gap-6">
                            <CorrelationPanel
                                title="Sleep duration → next-day focus"
                                x_label="Sleep duration (hours)"
                                y_label="Focus (minutes)"
                                pairs=duration_pairs
                                x_unit_divisor=60.0
                            />
                            <CorrelationPanel
                                title="Pickups during sleep → next-day focus"
                                x_label="Phone pickups overnight"
                                y_label="Focus (minutes)"
                                pairs=pickup_pairs
                                x_unit_divisor=1.0
                            />
                            <CorrelationPanel
                                title="Sleep quality → next-day focus"
                                x_label="Quality rating (1–5)"
                                y_label="Focus (minutes)"
                                pairs=quality_pairs
                                x_unit_divisor=1.0
                            />
                        </div>
                    }.into_any()
                }}
            </div>
        </AppShell>
    }
}

fn focus_minutes_by_day(sessions: &[ProductivitySession]) -> HashMap<NaiveDate, f64> {
    let mut map: HashMap<NaiveDate, f64> = HashMap::new();
    for s in sessions {
        if !s.completed { continue; }
        let day = s.started_at.with_timezone(&Local).date_naive();
        *map.entry(day).or_insert(0.0) += s.duration_minutes as f64;
    }
    map
}

fn pair_with_focus(
    sleep: &[SleepRecord],
    focus_by_day: &HashMap<NaiveDate, f64>,
    sleep_value: impl Fn(&SleepRecord) -> f64,
) -> Vec<(f64, f64)> {
    let mut pairs = Vec::new();
    for r in sleep {
        if r.is_nap { continue; } // §last-night — correlate nights only
        // The night anchored to the wake date — pair with focus on the same day.
        let day = r.actual_wake_time.with_timezone(&Local).date_naive();
        let focus = focus_by_day.get(&day).copied().unwrap_or(0.0);
        // Only include days where some focus happened — pure-zero days drag everything to (x, 0)
        // which inflates spurious negative correlations.
        if focus > 0.0 {
            pairs.push((sleep_value(r), focus));
        }
    }
    pairs
}

#[component]
fn RangeToggle(range: RwSignal<Range>) -> impl IntoView {
    view! {
        <div class="flex items-center gap-1 bg-white rounded-full border border-ultiq-indigo/15 p-1">
            {[Range::Month, Range::Quarter, Range::Year].iter().map(|r| {
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
fn CorrelationPanel(
    title: &'static str,
    x_label: &'static str,
    y_label: &'static str,
    pairs: Vec<(f64, f64)>,
    /// Display divisor for x-axis (e.g., 60 to show hours instead of minutes).
    x_unit_divisor: f64,
) -> impl IntoView {
    let n = pairs.len();
    let r = pearson(&pairs);
    let fit = linear_fit(&pairs);
    let interpretation = match r {
        Some(rv) => interpret_r(rv, n),
        None => format!("Need at least 2 paired days (have {})", n),
    };

    view! {
        <section class="bg-white rounded-2xl shadow p-6">
            <header class="mb-3">
                <h3 class="text-base font-semibold text-ultiq-indigo">{title}</h3>
                <p class="text-xs text-ultiq-indigo/60 mt-1">{interpretation}</p>
            </header>
            <ScatterChart pairs=pairs fit=fit x_label=x_label y_label=y_label x_unit_divisor=x_unit_divisor />
        </section>
    }
}

#[component]
fn ScatterChart(
    pairs: Vec<(f64, f64)>,
    fit: Option<(f64, f64)>,
    x_label: &'static str,
    y_label: &'static str,
    x_unit_divisor: f64,
) -> impl IntoView {
    if pairs.is_empty() {
        return view! {
            <p class="text-sm text-ultiq-indigo/50 py-12 text-center">
                "Not enough data."
            </p>
        }.into_any();
    }

    let w: f64 = 460.0;
    let h: f64 = 220.0;
    let pad_l = 44.0;
    let pad_r = 12.0;
    let pad_t = 8.0;
    let pad_b = 38.0;
    let plot_w = w - pad_l - pad_r;
    let plot_h = h - pad_t - pad_b;

    let x_min = pairs.iter().map(|(x, _)| *x).fold(f64::INFINITY, f64::min);
    let x_max = pairs.iter().map(|(x, _)| *x).fold(f64::NEG_INFINITY, f64::max);
    let y_max = pairs.iter().map(|(_, y)| *y).fold(0.0_f64, f64::max).max(60.0);
    let x_lo = x_min.min(0.0);
    let x_hi = if x_max <= x_lo { x_lo + 1.0 } else { x_max };
    let x_range = x_hi - x_lo;

    let x_for = move |xv: f64| pad_l + ((xv - x_lo) / x_range) * plot_w;
    let y_for = move |yv: f64| pad_t + (1.0 - (yv / y_max).clamp(0.0, 1.0)) * plot_h;

    let dots = pairs
        .iter()
        .map(|&(x, y)| {
            let cx = x_for(x);
            let cy = y_for(y);
            view! {
                <circle cx=cx cy=cy r=3.5 fill="currentColor" fill-opacity="0.7">
                    <title>{format!("x={:.1}, y={:.0}", x / x_unit_divisor, y)}</title>
                </circle>
            }
        })
        .collect_view();

    // Trend line clamped to plot
    let trend_line = fit.map(|(slope, intercept)| {
        let y_at = |x: f64| slope * x + intercept;
        let (x1, y1) = (x_lo, y_at(x_lo));
        let (x2, y2) = (x_hi, y_at(x_hi));
        view! {
            <line
                x1=x_for(x1) y1=y_for(y1)
                x2=x_for(x2) y2=y_for(y2)
                stroke="#D9474C" stroke-width="1.5" stroke-opacity="0.7" stroke-dasharray="4 3"
            />
        }
    });

    // X-axis labels (3 ticks: lo, mid, hi)
    let x_ticks = [x_lo, (x_lo + x_hi) / 2.0, x_hi];
    let x_tick_labels = x_ticks
        .iter()
        .map(|&xv| {
            view! {
                <text
                    x=x_for(xv) y=h - 12.0
                    text-anchor="middle"
                    font-size="10"
                    fill="currentColor"
                    opacity="0.6"
                >
                    {format!("{:.1}", xv / x_unit_divisor)}
                </text>
            }
        })
        .collect_view();

    // Y-axis labels (0, mid, max)
    let y_ticks = [0.0, y_max / 2.0, y_max];
    let y_tick_labels = y_ticks
        .iter()
        .map(|&yv| {
            view! {
                <text
                    x=pad_l - 6.0 y=y_for(yv) + 3.0
                    text-anchor="end"
                    font-size="10"
                    fill="currentColor"
                    opacity="0.6"
                >
                    {format!("{:.0}", yv)}
                </text>
            }
        })
        .collect_view();

    view! {
        <div class="overflow-x-auto">
            <svg viewBox=format!("0 0 {} {}", w, h) class="w-full h-auto text-ultiq-indigo" preserveAspectRatio="xMidYMid meet">
                // Plot frame
                <rect
                    x=pad_l y=pad_t width=plot_w height=plot_h
                    fill="currentColor" fill-opacity="0.04"
                    stroke="currentColor" stroke-opacity="0.15" stroke-width="1"
                />
                {y_tick_labels}
                {x_tick_labels}
                {trend_line}
                {dots}
                // Axis labels
                <text
                    x=pad_l + plot_w / 2.0 y=h - 2.0
                    text-anchor="middle" font-size="11" fill="currentColor" opacity="0.7"
                >
                    {x_label}
                </text>
                <text
                    transform=format!("translate({}, {}) rotate(-90)", 12.0, pad_t + plot_h / 2.0)
                    text-anchor="middle" font-size="11" fill="currentColor" opacity="0.7"
                >
                    {y_label}
                </text>
            </svg>
        </div>
    }.into_any()
}
