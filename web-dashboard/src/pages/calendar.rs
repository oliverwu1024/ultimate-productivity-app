use std::collections::HashMap;

use chrono::{DateTime, Datelike, Duration, Local, NaiveDate, NaiveDateTime, TimeZone, Timelike, Utc};
use leptos::either::{Either, EitherOf4};
use leptos::prelude::*;
use leptos_meta::Title;

use crate::api::ai::{parse_event, ParseEventRequest, ParsedCalendarFields};
use crate::api::calendar::{
    create_event, delete_event, delete_occurrence, list_events, update_event, update_occurrence,
    CalendarEvent, CreateCalendarEvent, EventCategory, EventPriority,
};
use crate::api::sse::{use_sse, SyncEvent};
use crate::components::ai_parse_dialog::{AiParsePromptDialog, AiSurface};
use crate::components::layout::AppShell;
use crate::i18n::{current_locale, t, t_args, tu};

#[derive(Clone)]
enum DialogState {
    Closed,
    Add,
    Edit(CalendarEvent),
    /// §9.5 — AI quick-add opened the create dialog with these prefilled
    /// values. The user still confirms before save; behaves identically to
    /// `Add` apart from the initial form state.
    AddPrefilled(ParsedCalendarFields),
}

fn first_of(date: NaiveDate) -> NaiveDate {
    NaiveDate::from_ymd_opt(date.year(), date.month(), 1).unwrap()
}

fn next_month(month_first: NaiveDate) -> NaiveDate {
    let (y, m) = if month_first.month() == 12 {
        (month_first.year() + 1, 1)
    } else {
        (month_first.year(), month_first.month() + 1)
    };
    NaiveDate::from_ymd_opt(y, m, 1).unwrap()
}

fn prev_month(month_first: NaiveDate) -> NaiveDate {
    let (y, m) = if month_first.month() == 1 {
        (month_first.year() - 1, 12)
    } else {
        (month_first.year(), month_first.month() - 1)
    };
    NaiveDate::from_ymd_opt(y, m, 1).unwrap()
}

fn month_grid(month_first: NaiveDate) -> Vec<NaiveDate> {
    let weekday_offset = month_first.weekday().num_days_from_monday() as i64;
    let grid_start = month_first - Duration::days(weekday_offset);
    (0..42).map(|i| grid_start + Duration::days(i)).collect()
}

fn local_date(dt: DateTime<Utc>) -> NaiveDate {
    dt.with_timezone(&Local).date_naive()
}

/// True iff the event's [start_local_date, end_local_date] range covers
/// `day`. Used to render multi-day events on every day they span, not
/// just their start day (Android v2.11.9 parity).
fn event_spans_day(event: &CalendarEvent, day: NaiveDate) -> bool {
    let start_date = local_date(event.start_time);
    let end_date = local_date(event.end_time);
    day >= start_date && day <= end_date
}

/// True iff the event's local start and end dates differ — i.e. it
/// extends past midnight in the user's timezone.
fn is_multi_day(event: &CalendarEvent) -> bool {
    local_date(event.start_time) != local_date(event.end_time)
}

/// v2.12.4 — Deterministic palette for multi-day ribbon colors. The
/// user-chosen `event.color` still drives the left border on each event
/// card in the day list, but ribbons on the month grid use a hash-of-id
/// palette slot so two overlapping multi-day events never share a stripe
/// (every Study-category event defaults to the same blue — ribbons were
/// visually identical before this).
const MULTI_DAY_PALETTE: [&str; 8] = [
    "#4A90D9", "#E67E22", "#2ECC71", "#9B59B6",
    "#E74C3C", "#F1C40F", "#1ABC9C", "#FF6F61",
];
fn multi_day_ribbon_color(event_id: &str) -> &'static str {
    // Same FNV-1a-style accumulation as Java String.hashCode so the
    // ribbon for a given event id matches between Android + web.
    let mut h: i32 = 0;
    for c in event_id.chars() {
        h = h.wrapping_mul(31).wrapping_add(c as i32);
    }
    let idx = (h as i64).abs() as usize % MULTI_DAY_PALETTE.len();
    MULTI_DAY_PALETTE[idx]
}

fn dt_to_input(dt: DateTime<Utc>) -> String {
    dt.with_timezone(&Local).format("%Y-%m-%dT%H:%M").to_string()
}

fn input_to_dt(s: &str) -> Option<DateTime<Utc>> {
    let naive = NaiveDateTime::parse_from_str(s, "%Y-%m-%dT%H:%M").ok()?;
    Local.from_local_datetime(&naive).single().map(|d| d.with_timezone(&Utc))
}

fn default_start_for(day: NaiveDate) -> String {
    let now_time = Local::now().time();
    let dt = day.and_time(now_time);
    dt.format("%Y-%m-%dT%H:%M").to_string()
}

fn default_end_for(day: NaiveDate) -> String {
    let now_time = Local::now().time();
    let start = day.and_time(now_time);
    let end = start + chrono::Duration::hours(1);
    end.format("%Y-%m-%dT%H:%M").to_string()
}

#[component]
pub fn CalendarPage() -> impl IntoView {
    let today = Local::now().date_naive();
    let current_month = RwSignal::new(first_of(today));
    let selected_day = RwSignal::new(today);
    let events: RwSignal<Vec<CalendarEvent>> = RwSignal::new(Vec::new());
    let dialog = RwSignal::new(DialogState::Closed);
    let loading = RwSignal::new(false);
    let error = RwSignal::new(None::<String>);

    // §9.5 — AI quick-add state. `ai_open` controls the prompt dialog;
    // success replaces the dialog state with `AddPrefilled` and closes the
    // prompt.
    let ai_open = RwSignal::new(false);
    let ai_loading = RwSignal::new(false);
    let ai_error = RwSignal::new(None::<String>);
    // StoredValue (Copy) so the AI dialog's Copy-bound on_submit closure can
    // still hold this localized message without capturing a bare String.
    let ai_parse_failed = StoredValue::new(tu("common.ai_parse_failed"));

    let refresh = move || {
        let month = current_month.get_untracked();
        let start = month;
        let end = next_month(month);
        loading.set(true);
        error.set(None);
        wasm_bindgen_futures::spawn_local(async move {
            match list_events(start, end).await {
                Ok(list) => events.set(list),
                Err(e) => error.set(Some(e.message)),
            }
            loading.set(false);
        });
    };

    Effect::new(move |_| {
        let _ = current_month.get();
        refresh();
    });

    // Realtime: refresh the month when a calendar event arrives over SSE.
    let sse = use_sse();
    Effect::new(move |_| {
        if let Some(ev) = sse.last_event_debounced.get() {
            match ev {
                SyncEvent::CalendarCreated(_)
                | SyncEvent::CalendarUpdated(_)
                | SyncEvent::CalendarDeleted(_) => refresh(),
                _ => {}
            }
        }
    });

    let goto_prev = move |_| current_month.update(|m| *m = prev_month(*m));
    let goto_next = move |_| current_month.update(|m| *m = next_month(*m));
    let goto_today = move |_| {
        current_month.set(first_of(today));
        selected_day.set(today);
    };

    let header_title = move || {
        let m = current_month.get();
        m.format_localized("%B %Y", current_locale().chrono()).to_string()
    };

    view! {
        <Title text="Calendar — Ultiq" />
        <AppShell>
            <div class="p-4 md:p-8 max-w-6xl mx-auto">
                <header class="flex items-center justify-between mb-6">
                    <h1 class="text-3xl font-bold text-ultiq-indigo">{move || t("nav.calendar")}</h1>
                    <div class="flex items-center gap-2">
                        <button
                            on:click=move |_| {
                                ai_error.set(None);
                                ai_open.set(true);
                            }
                            class="px-3 py-2 border border-ultiq-indigo/30 text-ultiq-indigo rounded-lg font-medium hover:bg-ultiq-indigo/5 cursor-pointer"
                        >
                            {move || format!("✨ {}", t("common.ai"))}
                        </button>
                        <button
                            on:click=move |_| dialog.set(DialogState::Add)
                            class="px-4 py-2 bg-ultiq-indigo text-ultiq-cream rounded-lg font-medium hover:opacity-90 cursor-pointer"
                        >
                            {move || format!("+ {}", t("cal.add_event"))}
                        </button>
                    </div>
                </header>

                <Show when=move || error.get().is_some()>
                    <div class="bg-ultiq-red/5 text-ultiq-red rounded-lg p-3 mb-4 text-sm">
                        {move || error.get().unwrap_or_default()}
                    </div>
                </Show>

                <div class="bg-white rounded-2xl shadow p-6">
                    <div class="flex items-center justify-between mb-4">
                        <button
                            on:click=goto_prev
                            class="px-3 py-1 rounded hover:bg-ultiq-indigo/5 cursor-pointer"
                        >
                            "←"
                        </button>
                        <div class="flex items-center gap-3">
                            <h2 class="text-lg font-semibold text-ultiq-indigo">
                                {header_title}
                            </h2>
                            <button
                                on:click=goto_today
                                class="text-xs px-2 py-1 rounded border border-ultiq-indigo/20 text-ultiq-indigo hover:bg-ultiq-indigo/5 cursor-pointer"
                            >
                                {move || t("common.today")}
                            </button>
                        </div>
                        <button
                            on:click=goto_next
                            class="px-3 py-1 rounded hover:bg-ultiq-indigo/5 cursor-pointer"
                        >
                            "→"
                        </button>
                    </div>

                    <div class="grid grid-cols-7 gap-px text-xs font-medium text-ultiq-indigo/50 mb-1">
                        {move || {
                            let loc = current_locale().chrono();
                            (0..7).map(|i| {
                                let lbl = (NaiveDate::from_ymd_opt(2024, 1, 1).unwrap()
                                    + Duration::days(i))
                                .format_localized("%a", loc)
                                .to_string();
                                view! { <div class="text-center py-1">{lbl}</div> }
                            }).collect_view()
                        }}
                    </div>

                    <div class="grid grid-cols-7 gap-px bg-ultiq-indigo/10 rounded overflow-hidden">
                        {move || {
                            let month = current_month.get();
                            let evs = events.get();
                            let sel = selected_day.get();
                            month_grid(month).into_iter().map(|day| {
                                let in_month = day.month() == month.month();
                                let is_today = day == today;
                                let is_selected = day == sel;
                                // v2.12.x — Include every event that spans this
                                // day, not just events that start on it. Split
                                // single-day from multi-day so DayCell can render
                                // the count badge vs the ribbon strip separately.
                                let spanning: Vec<CalendarEvent> = evs.iter()
                                    .filter(|e| event_spans_day(e, day))
                                    .cloned()
                                    .collect();
                                let (multi_day_evs, single_day_evs): (Vec<_>, Vec<_>) =
                                    spanning.into_iter().partition(|e| is_multi_day(e));
                                let ribbon_colors: Vec<String> = multi_day_evs
                                    .iter()
                                    .take(3)
                                    .map(|e| multi_day_ribbon_color(&e.id).to_string())
                                    .collect();
                                view! {
                                    <DayCell
                                        day=day
                                        in_month=in_month
                                        is_today=is_today
                                        is_selected=is_selected
                                        events=single_day_evs
                                        ribbon_colors=ribbon_colors
                                        on_click=move || selected_day.set(day)
                                    />
                                }
                            }).collect_view()
                        }}
                    </div>
                </div>

                <section class="mt-8">
                    <h2 class="text-lg font-semibold text-ultiq-indigo mb-3">
                        {move || {
                            let d = selected_day.get();
                            let loc = current_locale().chrono();
                            format!(
                                "{}, {} {}",
                                d.format_localized("%A", loc),
                                d.day(),
                                d.format_localized("%B", loc)
                            )
                        }}
                    </h2>
                    <DayEvents
                        events=events
                        selected_day=selected_day
                        on_edit=move |e: CalendarEvent| dialog.set(DialogState::Edit(e))
                    />
                </section>

                <section class="mt-10">
                    <h2 class="text-lg font-semibold text-ultiq-indigo mb-4">
                        {move || {
                            let m = current_month.get();
                            format!(
                                "{} — {}",
                                t("cal.analytics"),
                                m.format_localized("%B %Y", current_locale().chrono())
                            )
                        }}
                    </h2>
                    <div class="grid grid-cols-1 md:grid-cols-2 gap-6">
                        <div class="bg-white rounded-2xl shadow p-6">
                            <h3 class="text-base font-semibold text-ultiq-indigo mb-4">{move || t("cal.chart_time_by_category")}</h3>
                            <CategoryDonut events=events />
                        </div>
                        <div class="bg-white rounded-2xl shadow p-6">
                            <h3 class="text-base font-semibold text-ultiq-indigo mb-4">{move || t("cal.chart_events_per_day")}</h3>
                            <EventsPerDayBar events=events current_month=current_month />
                        </div>
                        <div class="bg-white rounded-2xl shadow p-6">
                            <h3 class="text-base font-semibold text-ultiq-indigo mb-4">{move || t("cal.chart_priority_distribution")}</h3>
                            <PriorityBars events=events />
                        </div>
                        <div class="bg-white rounded-2xl shadow p-6">
                            <h3 class="text-base font-semibold text-ultiq-indigo mb-4">{move || t("cal.chart_by_day_of_week")}</h3>
                            <DayOfWeekBar events=events />
                        </div>
                    </div>
                </section>

                {move || match dialog.get() {
                    DialogState::Closed => EitherOf4::A(view! { <></> }),
                    DialogState::Add => EitherOf4::B(view! {
                        <EventDialog
                            existing=None
                            prefill=None
                            initial_day=selected_day.get_untracked()
                            existing_events=events.get_untracked()
                            on_close=move || dialog.set(DialogState::Closed)
                            on_saved=move || { dialog.set(DialogState::Closed); refresh(); }
                        />
                    }),
                    DialogState::Edit(event) => EitherOf4::C(view! {
                        <EventDialog
                            existing=Some(event)
                            prefill=None
                            initial_day=selected_day.get_untracked()
                            existing_events=events.get_untracked()
                            on_close=move || dialog.set(DialogState::Closed)
                            on_saved=move || { dialog.set(DialogState::Closed); refresh(); }
                        />
                    }),
                    DialogState::AddPrefilled(parsed) => EitherOf4::D(view! {
                        <EventDialog
                            existing=None
                            prefill=Some(parsed)
                            initial_day=selected_day.get_untracked()
                            existing_events=events.get_untracked()
                            on_close=move || dialog.set(DialogState::Closed)
                            on_saved=move || { dialog.set(DialogState::Closed); refresh(); }
                        />
                    }),
                }}

                {move || if ai_open.get() {
                    Either::Left(view! {
                        <AiParsePromptDialog
                            surface=AiSurface::Calendar
                            loading=ai_loading
                            error=ai_error
                            on_submit=move |text| {
                                if ai_loading.get_untracked() { return; }
                                ai_loading.set(true);
                                ai_error.set(None);
                                let now_local = chrono::Local::now().to_rfc3339();
                                wasm_bindgen_futures::spawn_local(async move {
                                    let req = ParseEventRequest {
                                        text,
                                        hint: Some("calendar".into()),
                                        now_local: Some(now_local),
                                    };
                                    match parse_event(&req).await {
                                        Ok(resp) => {
                                            if let Some(cal) = resp.calendar {
                                                ai_loading.set(false);
                                                ai_open.set(false);
                                                dialog.set(DialogState::AddPrefilled(cal));
                                            } else {
                                                ai_loading.set(false);
                                                ai_error.set(Some(ai_parse_failed.get_value()));
                                            }
                                        }
                                        Err(e) => {
                                            ai_loading.set(false);
                                            ai_error.set(Some(e.message));
                                        }
                                    }
                                });
                            }
                            on_close=move || ai_open.set(false)
                        />
                    })
                } else {
                    Either::Right(view! { <></> })
                }}
            </div>
        </AppShell>
    }
}

#[component]
fn DayCell(
    day: NaiveDate,
    in_month: bool,
    is_today: bool,
    is_selected: bool,
    events: Vec<CalendarEvent>,
    ribbon_colors: Vec<String>,
    on_click: impl Fn() + Send + Sync + 'static,
) -> impl IntoView {
    let count = events.len();
    let cs = count.to_string();
    let label = if count == 1 {
        t_args("cal.events_one", &[("count", cs.as_str())])
    } else {
        t_args("cal.events_other", &[("count", cs.as_str())])
    };
    let has_ribbons = !ribbon_colors.is_empty();

    let bg_class = if is_selected {
        "bg-ultiq-indigo/10 ring-2 ring-ultiq-indigo"
    } else {
        "bg-white hover:bg-ultiq-indigo/5"
    };
    let text_class = if !in_month {
        "text-ultiq-indigo/30"
    } else if is_today {
        "text-ultiq-red font-bold"
    } else {
        "text-ultiq-indigo"
    };

    view! {
        <button
            on:click=move |_| on_click()
            class=format!("min-h-20 p-2 text-left flex flex-col gap-1 cursor-pointer transition-colors {}", bg_class)
        >
            <span class=format!("text-sm {}", text_class)>{day.day().to_string()}</span>
            <Show when=move || { count > 0 }>
                <span
                    class="text-[10px] leading-none px-1.5 py-0.5 rounded-full bg-ultiq-indigo/10 text-ultiq-indigo font-medium self-start"
                    title=label.clone()
                >
                    {count}
                </span>
            </Show>
            // v2.12.x — Multi-day ribbons at the bottom of the cell. Each
            // ribbon is a full-width 3px colored stripe; consecutive cells
            // spanned by the same event render matching stripes that read
            // as a continuous bar across the week (Android v2.11.9 parity).
            <Show when=move || has_ribbons>
                <div class="mt-auto flex flex-col gap-px w-full">
                    {ribbon_colors.iter().map(|c| {
                        let style = format!("background-color: {}", c);
                        view! {
                            <div class="h-[3px] rounded-sm w-full" style=style />
                        }
                    }).collect_view()}
                </div>
            </Show>
        </button>
    }
}

#[component]
fn DayEvents(
    events: RwSignal<Vec<CalendarEvent>>,
    selected_day: RwSignal<NaiveDate>,
    on_edit: impl Fn(CalendarEvent) + Send + Sync + Copy + 'static,
) -> impl IntoView {
    view! {
        {move || {
            let day = selected_day.get();
            // v2.12.x — Show every event that spans this day (not just
            // events starting on it). Multi-day events sort first so the
            // "all-day band" appears above timed events — same convention
            // as Android v2.11.9.
            let mut day_events: Vec<CalendarEvent> = events.get().into_iter()
                .filter(|e| event_spans_day(e, day))
                .collect();
            day_events.sort_by(|a, b| {
                let a_multi = is_multi_day(a);
                let b_multi = is_multi_day(b);
                if a_multi != b_multi {
                    b_multi.cmp(&a_multi)
                } else {
                    a.start_time.cmp(&b.start_time)
                }
            });

            if day_events.is_empty() {
                view! {
                    <p class="text-ultiq-indigo/50 text-sm">{move || t("cal.no_events_day")}</p>
                }.into_any()
            } else {
                let now = Utc::now();
                view! {
                    <ul class="space-y-2">
                        {day_events.into_iter().map(|e| {
                            let event_for_click = e.clone();
                            let start_local = e.start_time.with_timezone(&Local);
                            let end_local = e.end_time.with_timezone(&Local);
                            let start_date = start_local.date_naive();
                            let end_date = end_local.date_naive();
                            // v2.12.x — Adaptive time text for multi-day events
                            // depending on which day is being viewed. Single-day
                            // events keep the original "HH:MM – HH:MM" format.
                            let cal_loc = current_locale().chrono();
                            let time_range = if start_date == end_date {
                                format!("{} – {}", start_local.format("%H:%M"), end_local.format("%H:%M"))
                            } else if day == start_date {
                                let st = start_local.format("%H:%M").to_string();
                                let wd = end_local.format_localized("%a", cal_loc).to_string();
                                let tm = end_local.format("%H:%M").to_string();
                                t_args("cal.time_multi_start", &[("start", st.as_str()), ("day", wd.as_str()), ("time", tm.as_str())])
                            } else if day == end_date {
                                let tm = end_local.format("%H:%M").to_string();
                                let wd = start_local.format_localized("%a", cal_loc).to_string();
                                let st = start_local.format("%H:%M").to_string();
                                t_args("cal.time_multi_end", &[("time", tm.as_str()), ("day", wd.as_str()), ("start", st.as_str())])
                            } else {
                                let wd = start_local.format_localized("%a", cal_loc).to_string();
                                let tm = start_local.format("%H:%M").to_string();
                                t_args("cal.time_multi_all_day", &[("day", wd.as_str()), ("time", tm.as_str())])
                            };
                            let color = e.color.clone();
                            let cat = e.category;
                            let pr = e.priority;
                            // Mark-done only applies once the slot has actually
                            // finished. Future events stay clean / no checkbox.
                            let is_past = e.end_time < now;
                            let is_done = e.is_done;
                            let event_id = e.id.clone();
                            let event_for_toggle = e.clone();
                            let title_class = if is_done {
                                "font-medium text-ultiq-indigo/50 line-through"
                            } else {
                                "font-medium text-ultiq-indigo"
                            };
                            view! {
                                <li class="flex items-stretch gap-2 bg-white rounded-xl shadow-sm hover:shadow">
                                    <button
                                        on:click=move |_| on_edit(event_for_click.clone())
                                        class="flex-1 min-w-0 p-4 flex items-start gap-3 text-left cursor-pointer"
                                    >
                                        <span
                                            class="w-1 h-12 rounded-full flex-shrink-0 self-stretch"
                                            style:background-color=color
                                        />
                                        <div class="flex-1 min-w-0">
                                            <div class="flex items-center gap-2 flex-wrap">
                                                <span class=title_class>{e.title.clone()}</span>
                                                <span class="text-xs px-2 py-0.5 rounded bg-ultiq-indigo/5 text-ultiq-indigo/70">
                                                    {move || category_label(cat)}
                                                </span>
                                                <span class="text-xs text-ultiq-indigo/50">
                                                    {move || priority_label(pr)}
                                                </span>
                                            </div>
                                            <div class="text-sm text-ultiq-indigo/60 mt-1">{time_range}</div>
                                            <Show when={
                                                let desc = e.description.clone();
                                                move || desc.as_deref().map(|s| !s.is_empty()).unwrap_or(false)
                                            }>
                                                <div class="text-sm text-ultiq-indigo/70 mt-1">
                                                    {e.description.clone().unwrap_or_default()}
                                                </div>
                                            </Show>
                                        </div>
                                    </button>
                                    <Show when=move || is_past>
                                        <label
                                            class="flex items-center pr-4 cursor-pointer"
                                            title=move || if is_done { t("cal.mark_not_done") } else { t("cal.mark_done") }
                                        >
                                            <input
                                                type="checkbox"
                                                prop:checked=is_done
                                                class="w-5 h-5 accent-ultiq-indigo cursor-pointer"
                                                on:change={
                                                    let event_id = event_id.clone();
                                                    let src = event_for_toggle.clone();
                                                    move |_| {
                                                        let new_done = !is_done;
                                                        // Optimistic local flip — the row re-renders immediately.
                                                        // v2.16.0 — Recurring expansions share an id, so for the
                                                        // optimistic update we narrow by id + start_time to flip
                                                        // only the specific instance the user clicked.
                                                        let click_start = src.start_time;
                                                        let is_recurring = src.is_recurring;
                                                        events.update(|list| {
                                                            for item in list.iter_mut().filter(|x| x.id == event_id) {
                                                                if !is_recurring || item.start_time == click_start {
                                                                    item.is_done = new_done;
                                                                }
                                                            }
                                                        });
                                                        // Backend's update endpoint is full-replacement, so we
                                                        // re-send every field of the current event with only
                                                        // is_done flipped. The COALESCE on the server treats
                                                        // is_done = Some(value) as the new value.
                                                        let event_id = event_id.clone();
                                                        let body = CreateCalendarEvent {
                                                            title: src.title.clone(),
                                                            description: src.description.clone(),
                                                            start_time: src.start_time,
                                                            end_time: src.end_time,
                                                            category: src.category,
                                                            priority: src.priority,
                                                            is_recurring: src.is_recurring,
                                                            recurrence_rule: src.recurrence_rule.clone(),
                                                            color: Some(src.color.clone()),
                                                            is_done: Some(new_done),
                                                            // None so the server-side COALESCE
                                                            // preserves whatever reminder was set.
                                                            reminder_minutes: None,
                                                        };
                                                        // v2.16.0 — Recurring events flip per-occurrence so a
                                                        // single tap on Tuesday doesn't mark every Monday +
                                                        // Wednesday done. The occurrence_date is the LOCAL date
                                                        // of the clicked instance (matches Android).
                                                        let occ_local = src.start_time
                                                            .with_timezone(&Local)
                                                            .date_naive();
                                                        wasm_bindgen_futures::spawn_local(async move {
                                                            if is_recurring {
                                                                let _ = update_occurrence(&event_id, occ_local, &body).await;
                                                            } else {
                                                                let _ = update_event(&event_id, &body).await;
                                                            }
                                                        });
                                                    }
                                                }
                                            />
                                        </label>
                                    </Show>
                                </li>
                            }
                        }).collect_view()}
                    </ul>
                }.into_any()
            }
        }}
    }
}

fn priority_word(p: EventPriority) -> String {
    t(match p {
        EventPriority::High => "common.priority_high",
        EventPriority::Medium => "common.priority_medium",
        EventPriority::Low => "common.priority_low",
    })
}

fn priority_label(p: EventPriority) -> String {
    format!("● {}", priority_word(p))
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

/// §9.5 — Map the lowercase category strings the AI tool returns (matching
/// the backend's serde rename) back to the dashboard's enum. `EventCategory::
/// from_str` only accepts PascalCase variants, so we need this dedicated
/// parser for AI-sourced values.
fn parsed_category(s: &str) -> Option<EventCategory> {
    match s {
        "study" => Some(EventCategory::Study),
        "project" => Some(EventCategory::Project),
        "exercise" => Some(EventCategory::Exercise),
        "personal" => Some(EventCategory::Personal),
        "other" => Some(EventCategory::Other),
        _ => None,
    }
}

fn parsed_priority(s: &str) -> Option<EventPriority> {
    match s {
        "high" => Some(EventPriority::High),
        "medium" => Some(EventPriority::Medium),
        "low" => Some(EventPriority::Low),
        _ => None,
    }
}

#[component]
fn EventDialog(
    existing: Option<CalendarEvent>,
    /// §9.5 — AI-parsed values used as initial state when the dialog opens
    /// via the quick-add flow. Only consulted when `existing` is None.
    prefill: Option<ParsedCalendarFields>,
    initial_day: NaiveDate,
    /// v2.12.4 — Snapshot of the current month's events for the inline
    /// conflict warning. Excludes the edited event's own id so editing
    /// doesn't flag itself.
    existing_events: Vec<CalendarEvent>,
    on_close: impl Fn() + Send + Sync + Copy + 'static,
    on_saved: impl Fn() + Send + Sync + Copy + 'static,
) -> impl IntoView {
    let is_edit = existing.is_some();
    let event_id = existing.as_ref().map(|e| e.id.clone());
    let editing_event_id = event_id.clone();
    // v2.12.4 — All-day detection mirrors Android: schema has no flag, so
    // we infer from "start at 00:00 and end at 23:59:*" in local time.
    let initial_all_day = existing.as_ref().map(|e| {
        let s = e.start_time.with_timezone(&Local);
        let en = e.end_time.with_timezone(&Local);
        s.hour() == 0 && s.minute() == 0 && en.hour() == 23 && en.minute() == 59
    }).unwrap_or(false);

    // Resolution order for each field: existing → prefill → blank/default.
    let title = RwSignal::new(
        existing
            .as_ref()
            .map(|e| e.title.clone())
            .or_else(|| prefill.as_ref().map(|p| p.title.clone()))
            .unwrap_or_default(),
    );
    let description = RwSignal::new(
        existing
            .as_ref()
            .and_then(|e| e.description.clone())
            .or_else(|| prefill.as_ref().and_then(|p| p.description.clone()))
            .unwrap_or_default(),
    );
    let start_time = RwSignal::new(
        existing
            .as_ref()
            .map(|e| dt_to_input(e.start_time))
            .or_else(|| prefill.as_ref().map(|p| dt_to_input(p.start_time)))
            .unwrap_or_else(|| default_start_for(initial_day)),
    );
    let end_time = RwSignal::new(
        existing
            .as_ref()
            .map(|e| dt_to_input(e.end_time))
            .or_else(|| prefill.as_ref().map(|p| dt_to_input(p.end_time)))
            .unwrap_or_else(|| default_end_for(initial_day)),
    );
    let category = RwSignal::new(
        existing
            .as_ref()
            .map(|e| e.category)
            .or_else(|| {
                prefill
                    .as_ref()
                    .and_then(|p| parsed_category(&p.category))
            })
            .unwrap_or(EventCategory::Study),
    );
    let priority = RwSignal::new(
        existing
            .as_ref()
            .map(|e| e.priority)
            .or_else(|| {
                prefill
                    .as_ref()
                    .and_then(|p| parsed_priority(&p.priority))
            })
            .unwrap_or(EventPriority::Medium),
    );
    // v2.13.1 — Multi-reminder per event. None = "use client default"
    // (single 15-min reminder via Android scheduler); Some(vec![]) =
    // explicit opt-out; Some(non-empty) = the user's exact picks.
    let reminder_minutes = RwSignal::new(
        existing.as_ref().and_then(|e| e.reminder_minutes.clone()),
    );
    let initial_reminder_minutes = reminder_minutes.get_untracked();
    let submitting = RwSignal::new(false);
    let dialog_error = RwSignal::new(None::<String>);

    // v2.12.4 — All-day + discard-guard state.
    let is_all_day = RwSignal::new(initial_all_day);
    let show_discard_confirm = RwSignal::new(false);
    // Snapshot of the initial form values so we can compute `dirty` cheaply
    // without subscribing every change. Re-snapped when toggling all-day.
    let initial_title = title.get_untracked();
    let initial_description = description.get_untracked();
    let initial_category = category.get_untracked();
    let initial_priority = priority.get_untracked();

    // v2.12.4 — Delta-shift: when the user changes start, end shifts by the
    // same delta so the original duration is preserved (matches Android
    // v2.11.9). Anchored to the start_time's previous value via a closure.
    let prev_start_store = StoredValue::new(start_time.get_untracked());
    let on_start_change = move |ev: leptos::ev::Event| {
        let new_val = event_target_value(&ev);
        let prev = prev_start_store.get_value();
        prev_start_store.set_value(new_val.clone());
        if let (Some(prev_dt), Some(new_dt), Some(end_dt)) = (
            input_to_dt(&prev),
            input_to_dt(&new_val),
            input_to_dt(&end_time.get_untracked()),
        ) {
            let delta = new_dt - prev_dt;
            let shifted = end_dt + delta;
            end_time.set(dt_to_input(shifted));
        }
        start_time.set(new_val);
    };

    // Persist the explicit times so toggling all-day off restores them.
    let saved_start_time = StoredValue::new(start_time.get_untracked());
    let saved_end_time = StoredValue::new(end_time.get_untracked());

    let existing_events_store = StoredValue::new(existing_events);

    let event_id_store = StoredValue::new(event_id.clone());
    // Captured at render (context is live here) with the non-reactive `tu()`,
    // so the event handlers / futures below emit them in the right language.
    let err_title_required = tu("cal.err_title_required");
    let err_invalid_start = tu("cal.err_invalid_start");
    let err_invalid_end = tu("cal.err_invalid_end");
    let err_start_before_end = tu("cal.err_start_before_end");
    // StoredValue (Copy) — `on_delete` lives inside a `<Show>`, whose children
    // closure must be Copy, so it can't capture bare Strings.
    let confirm_delete_occurrence = StoredValue::new(tu("cal.confirm_delete_occurrence"));
    let confirm_delete_series = StoredValue::new(tu("cal.confirm_delete_series"));
    let confirm_delete_one = StoredValue::new(tu("cal.confirm_delete_one"));
    let on_submit = move |ev: leptos::ev::SubmitEvent| {
        ev.prevent_default();
        if submitting.get_untracked() { return; }

        let t = title.get_untracked();
        if t.trim().is_empty() {
            dialog_error.set(Some(err_title_required.clone()));
            return;
        }
        let mut st = match input_to_dt(&start_time.get_untracked()) {
            Some(dt) => dt,
            None => { dialog_error.set(Some(err_invalid_start.clone())); return; }
        };
        let mut et = match input_to_dt(&end_time.get_untracked()) {
            Some(dt) => dt,
            None => { dialog_error.set(Some(err_invalid_end.clone())); return; }
        };
        // v2.12.4 — All-day events: snap start to local midnight and end
        // to 23:59 of its local date. Mirrors the Android save behavior
        // so both surfaces emit the same timestamp pattern.
        if is_all_day.get_untracked() {
            let s_local = st.with_timezone(&Local);
            let e_local = et.with_timezone(&Local);
            let s_midnight = Local
                .from_local_datetime(&s_local.date_naive().and_hms_opt(0, 0, 0).unwrap())
                .single();
            let e_eod = Local
                .from_local_datetime(&e_local.date_naive().and_hms_opt(23, 59, 0).unwrap())
                .single();
            if let (Some(s), Some(e)) = (s_midnight, e_eod) {
                st = s.with_timezone(&Utc);
                et = e.with_timezone(&Utc);
            }
        }
        if st >= et {
            dialog_error.set(Some(err_start_before_end.clone()));
            return;
        }

        let desc = description.get_untracked();
        let body = CreateCalendarEvent {
            title: t,
            description: if desc.trim().is_empty() { None } else { Some(desc) },
            start_time: st,
            end_time: et,
            category: category.get_untracked(),
            priority: priority.get_untracked(),
            is_recurring: false,
            recurrence_rule: None,
            color: None,
            is_done: None,
            reminder_minutes: reminder_minutes.get_untracked(),
        };

        submitting.set(true);
        dialog_error.set(None);
        let id = event_id_store.get_value();
        wasm_bindgen_futures::spawn_local(async move {
            let res = if let Some(id) = id {
                update_event(&id, &body).await.map(|_| ())
            } else {
                create_event(&body).await.map(|_| ())
            };
            match res {
                Ok(_) => on_saved(),
                Err(e) => {
                    dialog_error.set(Some(e.message));
                    submitting.set(false);
                }
            }
        });
    };

    // v2.16.0 — Recurring events get a per-occurrence delete path. The
    // browser's native confirm dialog only does yes/no so we two-step
    // it: first ask "just this one?" (OK = JustThis, Cancel = continue
    // to whole-series prompt), then ask "delete the whole series?". Not
    // as nice as the Android three-button dialog but keeps the change
    // contained to the existing dialog. The full custom modal can come
    // in a follow-up. One-shot events fall through to the original
    // single confirm.
    let existing_is_recurring = existing.as_ref().map(|e| e.is_recurring).unwrap_or(false);
    let occurrence_local_date = existing
        .as_ref()
        .map(|e| e.start_time.with_timezone(&Local).date_naive());
    let on_delete = move |_| {
        let Some(id) = event_id_store.get_value() else { return; };
        let win = web_sys::window();
        if existing_is_recurring {
            let just_this = win
                .as_ref()
                .and_then(|w| {
                    w.confirm_with_message(&confirm_delete_occurrence.get_value())
                        .ok()
                })
                .unwrap_or(false);
            if just_this {
                let Some(date) = occurrence_local_date else { return; };
                submitting.set(true);
                wasm_bindgen_futures::spawn_local(async move {
                    match delete_occurrence(&id, date).await {
                        Ok(_) => on_saved(),
                        Err(e) => {
                            dialog_error.set(Some(e.message));
                            submitting.set(false);
                        }
                    }
                });
                return;
            }
            let confirmed_all = win
                .and_then(|w| {
                    w.confirm_with_message(&confirm_delete_series.get_value()).ok()
                })
                .unwrap_or(false);
            if !confirmed_all { return; }
            submitting.set(true);
            wasm_bindgen_futures::spawn_local(async move {
                match delete_event(&id).await {
                    Ok(_) => on_saved(),
                    Err(e) => {
                        dialog_error.set(Some(e.message));
                        submitting.set(false);
                    }
                }
            });
            return;
        }
        let confirmed = win
            .and_then(|w| w.confirm_with_message(&confirm_delete_one.get_value()).ok())
            .unwrap_or(false);
        if !confirmed { return; }
        submitting.set(true);
        wasm_bindgen_futures::spawn_local(async move {
            match delete_event(&id).await {
                Ok(_) => on_saved(),
                Err(e) => {
                    dialog_error.set(Some(e.message));
                    submitting.set(false);
                }
            }
        });
    };

    // v2.12.4 — Discard-confirm guard: clicking the backdrop, the X, or
    // Cancel only closes immediately when the form is clean. Otherwise
    // a "Discard changes?" prompt comes up first. Stops the very-easy
    // accidental cancellation that the Android dialog also had.
    // Cheaply-cloneable closure for the dismiss-guard. Wrapped in StoredValue
    // so both the backdrop click and the Cancel button can fire it without
    // moving capture. `move ||` would only be callable once.
    let initial_title_sv = StoredValue::new(initial_title);
    let initial_description_sv = StoredValue::new(initial_description);
    let initial_category_sv = StoredValue::new(initial_category);
    let initial_priority_sv = StoredValue::new(initial_priority);
    // v2.13.1 — Option<Vec<i32>> isn't Copy, so wrap it in StoredValue
    // (was Option<i32> in v2.13.0, which was Copy and worked in the
    // closure capture by value).
    let initial_reminder_sv = StoredValue::new(initial_reminder_minutes);
    let attempt_close = move || {
        let dirty = title.get_untracked() != initial_title_sv.get_value()
            || description.get_untracked() != initial_description_sv.get_value()
            || category.get_untracked() != initial_category_sv.get_value()
            || priority.get_untracked() != initial_priority_sv.get_value()
            || start_time.get_untracked() != saved_start_time.get_value()
            || end_time.get_untracked() != saved_end_time.get_value()
            || is_all_day.get_untracked() != initial_all_day
            || reminder_minutes.get_untracked() != initial_reminder_sv.get_value();
        if dirty {
            show_discard_confirm.set(true);
        } else {
            on_close();
        }
    };

    view! {
        <div
            class="fixed inset-0 bg-black/40 z-50 flex items-center justify-center p-4"
            on:click=move |_| attempt_close()
        >
            <form
                on:submit=on_submit
                on:click=|ev| ev.stop_propagation()
                class="bg-white rounded-2xl shadow-xl p-6 w-full max-w-md space-y-4 max-h-[90vh] overflow-auto"
            >
                <h3 class="text-xl font-semibold text-ultiq-indigo">
                    {move || if is_edit { t("cal.edit_event") } else { t("cal.new_event") }}
                </h3>

                <Show when=move || show_discard_confirm.get()>
                    <div class="fixed inset-0 bg-black/40 z-[60] flex items-center justify-center p-4"
                        on:click=move |_| show_discard_confirm.set(false)>
                        <div class="bg-white rounded-xl shadow-xl p-5 w-full max-w-xs space-y-3"
                            on:click=|ev| ev.stop_propagation()>
                            <h4 class="text-base font-semibold text-ultiq-indigo">{move || t("cal.discard_q")}</h4>
                            <p class="text-sm text-ultiq-indigo/70">{move || t("cal.discard_body")}</p>
                            <div class="flex justify-end gap-2 pt-1">
                                <button type="button"
                                    on:click=move |_| show_discard_confirm.set(false)
                                    class="px-3 py-1.5 text-sm text-ultiq-indigo hover:bg-ultiq-indigo/5 rounded cursor-pointer">
                                    {move || t("cal.keep_editing")}
                                </button>
                                <button type="button"
                                    on:click=move |_| { show_discard_confirm.set(false); on_close(); }
                                    class="px-3 py-1.5 text-sm text-ultiq-red hover:bg-ultiq-red/5 rounded cursor-pointer">
                                    {move || t("cal.discard")}
                                </button>
                            </div>
                        </div>
                    </div>
                </Show>

                <label class="block">
                    <span class="text-sm font-medium text-ultiq-indigo">{move || t("cal.field_title")}</span>
                    <input
                        type="text"
                        class="mt-1 w-full px-3 py-2 border border-ultiq-indigo/20 rounded-lg focus:outline-none focus:ring-2 focus:ring-ultiq-indigo"
                        prop:value=move || title.get()
                        on:input=move |ev| title.set(event_target_value(&ev))
                        required
                    />
                </label>

                <label class="block">
                    <span class="text-sm font-medium text-ultiq-indigo">{move || t("cal.field_description")}</span>
                    <textarea
                        rows="2"
                        class="mt-1 w-full px-3 py-2 border border-ultiq-indigo/20 rounded-lg focus:outline-none focus:ring-2 focus:ring-ultiq-indigo"
                        prop:value=move || description.get()
                        on:input=move |ev| description.set(event_target_value(&ev))
                    />
                </label>

                // v2.12.4 — All-day toggle. Switches the date/time fields to
                // date-only inputs and snaps the saved times to midnight /
                // end-of-day on submit. Toggling off restores the explicit
                // times the user last picked.
                <label class="flex items-center justify-between">
                    <span class="text-sm font-medium text-ultiq-indigo">{move || t("cal.field_all_day")}</span>
                    <input
                        type="checkbox"
                        class="w-5 h-5 accent-ultiq-indigo cursor-pointer"
                        prop:checked=move || is_all_day.get()
                        on:change=move |ev| {
                            let checked = event_target_checked(&ev);
                            if checked {
                                saved_start_time.set_value(start_time.get_untracked());
                                saved_end_time.set_value(end_time.get_untracked());
                            } else {
                                start_time.set(saved_start_time.get_value());
                                end_time.set(saved_end_time.get_value());
                            }
                            is_all_day.set(checked);
                        }
                    />
                </label>

                <div class="grid grid-cols-2 gap-3">
                    <label class="block">
                        <span class="text-sm font-medium text-ultiq-indigo">{move || t("cal.field_start")}</span>
                        <input
                            type=move || if is_all_day.get() { "date" } else { "datetime-local" }
                            class="mt-1 w-full px-3 py-2 border border-ultiq-indigo/20 rounded-lg focus:outline-none focus:ring-2 focus:ring-ultiq-indigo"
                            prop:value=move || if is_all_day.get() {
                                start_time.get().split('T').next().unwrap_or("").to_string()
                            } else {
                                start_time.get()
                            }
                            on:input=on_start_change
                            required
                        />
                    </label>
                    <label class="block">
                        <span class="text-sm font-medium text-ultiq-indigo">{move || t("cal.field_end")}</span>
                        <input
                            type=move || if is_all_day.get() { "date" } else { "datetime-local" }
                            class="mt-1 w-full px-3 py-2 border border-ultiq-indigo/20 rounded-lg focus:outline-none focus:ring-2 focus:ring-ultiq-indigo"
                            prop:value=move || if is_all_day.get() {
                                end_time.get().split('T').next().unwrap_or("").to_string()
                            } else {
                                end_time.get()
                            }
                            on:input=move |ev| end_time.set(event_target_value(&ev))
                            required
                        />
                    </label>
                </div>

                // v2.12.4 — Inline conflict warning. Computed from existing
                // events in the month (snapshotted at dialog open); excludes
                // the editingEvent's own id so editing isn't flagged.
                {move || {
                    let st = input_to_dt(&start_time.get());
                    let et = input_to_dt(&end_time.get());
                    let editing_id_ref = editing_event_id.clone();
                    if let (Some(start), Some(end)) = (st, et) {
                        if start >= end { return view! { <div></div> }.into_any(); }
                        let all_events = existing_events_store.get_value();
                        let conflicts: Vec<CalendarEvent> = all_events.into_iter()
                            .filter(|ev| {
                                editing_id_ref.as_deref().map(|id| id != ev.id).unwrap_or(true)
                                    && start < ev.end_time
                                    && end > ev.start_time
                            })
                            .collect();
                        if conflicts.is_empty() { return view! { <div></div> }.into_any(); }
                        let shown: Vec<_> = conflicts.iter().take(3).cloned().collect();
                        let extra = conflicts.len().saturating_sub(shown.len());
                        view! {
                            <div class="bg-ultiq-yellow/10 border border-ultiq-yellow/30 rounded-lg px-3 py-2 text-sm">
                                <p class="font-medium text-ultiq-indigo/80 mb-1">
                                    {
                                        let n = conflicts.len();
                                        let ns = n.to_string();
                                        if n == 1 {
                                            t_args("cal.conflicts_one", &[("count", ns.as_str())])
                                        } else {
                                            t_args("cal.conflicts_other", &[("count", ns.as_str())])
                                        }
                                    }
                                </p>
                                <ul class="space-y-0.5 text-ultiq-indigo/70">
                                    {shown.into_iter().map(|c| {
                                        let s = c.start_time.with_timezone(&Local).format("%H:%M").to_string();
                                        let e = c.end_time.with_timezone(&Local).format("%H:%M").to_string();
                                        view! { <li>{format!("• {} ({}–{})", c.title, s, e)}</li> }
                                    }).collect_view()}
                                    <Show when=move || { extra > 0 }>
                                        <li class="italic">{
                                            let es = extra.to_string();
                                            t_args("cal.more", &[("count", es.as_str())])
                                        }</li>
                                    </Show>
                                </ul>
                            </div>
                        }.into_any()
                    } else {
                        view! { <div></div> }.into_any()
                    }
                }}

                <label class="block">
                    <span class="text-sm font-medium text-ultiq-indigo">{move || t("cal.field_category")}</span>
                    <select
                        class="mt-1 w-full px-3 py-2 border border-ultiq-indigo/20 rounded-lg focus:outline-none focus:ring-2 focus:ring-ultiq-indigo bg-white"
                        prop:value=move || category.get().variant_str().to_string()
                        on:change=move |ev| {
                            if let Some(c) = EventCategory::from_str(&event_target_value(&ev)) {
                                category.set(c);
                            }
                        }
                    >
                        {EventCategory::ALL.iter().map(|c| {
                            let v = c.variant_str();
                            let cat = *c;
                            view! { <option value=v>{move || category_label(cat)}</option> }
                        }).collect_view()}
                    </select>
                </label>

                <label class="block">
                    <span class="text-sm font-medium text-ultiq-indigo">{move || t("cal.field_priority")}</span>
                    <select
                        class="mt-1 w-full px-3 py-2 border border-ultiq-indigo/20 rounded-lg focus:outline-none focus:ring-2 focus:ring-ultiq-indigo bg-white"
                        prop:value=move || priority.get().variant_str().to_string()
                        on:change=move |ev| {
                            if let Some(p) = EventPriority::from_str(&event_target_value(&ev)) {
                                priority.set(p);
                            }
                        }
                    >
                        {EventPriority::ALL.iter().map(|p| {
                            let v = p.variant_str();
                            let pr = *p;
                            view! { <option value=v>{move || priority_word(pr)}</option> }
                        }).collect_view()}
                    </select>
                </label>

                // v2.13.1 — Multi-select reminder picker. Each chip
                // toggles independently. "Default" = None (single 15-min
                // reminder); "None" = Some(vec![]) opt-out; per-offset
                // chips append to / remove from the explicit list.
                // Selecting a per-offset chip clears Default/None;
                // selecting Default/None clears the explicit list.
                <div>
                    <span class="text-sm font-medium text-ultiq-indigo">{move || t("cal.field_reminders")}</span>
                    <div class="mt-1 flex flex-wrap gap-2">
                        {move || {
                            let current = reminder_minutes.get();
                            let is_default = current.is_none();
                            let is_none = matches!(&current, Some(v) if v.is_empty());
                            let chip_class = |selected: bool| -> &'static str {
                                if selected {
                                    "px-3 py-1 rounded-full text-sm bg-ultiq-indigo text-white cursor-pointer"
                                } else {
                                    "px-3 py-1 rounded-full text-sm bg-ultiq-indigo/10 text-ultiq-indigo hover:bg-ultiq-indigo/20 cursor-pointer"
                                }
                            };
                            let options: &[(i32, &'static str)] = &[
                                (5, "cal.reminder_5m"),
                                (15, "cal.reminder_15m"),
                                (30, "cal.reminder_30m"),
                                (60, "cal.reminder_1h"),
                                (120, "cal.reminder_2h"),
                                (240, "cal.reminder_4h"),
                                (1440, "cal.reminder_1d"),
                                (2880, "cal.reminder_2d"),
                                (10080, "cal.reminder_1w"),
                            ];
                            view! {
                                <button type="button" class=chip_class(is_default)
                                    on:click=move |_| reminder_minutes.set(None)>{move || t("cal.reminder_default")}</button>
                                <button type="button" class=chip_class(is_none)
                                    on:click=move |_| reminder_minutes.set(Some(vec![]))>{move || t("cal.reminder_none")}</button>
                                {options.iter().map(|&(mins, label)| {
                                    let checked = matches!(&current, Some(v) if v.contains(&mins));
                                    view! {
                                        <button type="button" class=chip_class(checked)
                                            on:click=move |_| {
                                                let mut base: Vec<i32> = reminder_minutes
                                                    .get_untracked()
                                                    .unwrap_or_default();
                                                if let Some(i) = base.iter().position(|&x| x == mins) {
                                                    base.remove(i);
                                                } else {
                                                    base.push(mins);
                                                    base.sort();
                                                }
                                                reminder_minutes.set(Some(base));
                                            }>
                                            {move || t(label)}
                                        </button>
                                    }
                                }).collect_view()}
                            }
                        }}
                    </div>
                </div>

                <Show when=move || dialog_error.get().is_some()>
                    <p class="text-sm text-ultiq-red bg-ultiq-red/5 px-3 py-2 rounded">
                        {move || dialog_error.get().unwrap_or_default()}
                    </p>
                </Show>

                <div class="flex justify-between gap-2 pt-2">
                    <Show when=move || is_edit>
                        <button
                            type="button"
                            on:click=on_delete
                            class="px-3 py-2 text-ultiq-red hover:bg-ultiq-red/5 rounded-lg cursor-pointer"
                            prop:disabled=move || submitting.get()
                        >
                            {move || t("common.delete")}
                        </button>
                    </Show>
                    <div class="flex gap-2 ml-auto">
                        <button
                            type="button"
                            on:click=move |_| attempt_close()
                            class="px-4 py-2 text-ultiq-indigo hover:bg-ultiq-indigo/5 rounded-lg cursor-pointer"
                            prop:disabled=move || submitting.get()
                        >
                            {move || t("common.cancel")}
                        </button>
                        <button
                            type="submit"
                            class="px-4 py-2 bg-ultiq-indigo text-ultiq-cream rounded-lg font-medium hover:opacity-90 disabled:opacity-50 cursor-pointer"
                            prop:disabled=move || submitting.get()
                        >
                            {move || if submitting.get() { t("common.saving") } else { t("common.save") }}
                        </button>
                    </div>
                </div>
            </form>
        </div>
    }
}

// ─── Analytics components ───────────────────────────────────────────────

#[component]
fn CategoryDonut(events: RwSignal<Vec<CalendarEvent>>) -> impl IntoView {
    view! {
        {move || {
            let evs = events.get();
            if evs.is_empty() {
                return view! {
                    <p class="text-sm text-ultiq-indigo/50 py-6 text-center">
                        {move || t("cal.no_events_month")}
                    </p>
                }.into_any();
            }
            let mut by_cat: HashMap<EventCategory, i64> = HashMap::new();
            for e in &evs {
                let dur = (e.end_time - e.start_time).num_minutes().max(0);
                *by_cat.entry(e.category).or_insert(0) += dur;
            }
            let total: i64 = by_cat.values().sum();
            if total == 0 {
                return view! {
                    <p class="text-sm text-ultiq-indigo/50 py-6 text-center">
                        {move || t("cal.all_zero_duration")}
                    </p>
                }.into_any();
            }

            // Donut: fixed circumference, each slice is a stroke-dasharray segment.
            // Circle radius 36, circumference = 2π·36 ≈ 226.19.
            let circumference: f64 = 2.0 * std::f64::consts::PI * 36.0;
            let mut offset = 0.0_f64;
            let mut slices = Vec::new();
            let mut legend = Vec::new();
            // Stable ordering by category enum order so the donut doesn't flip on every render.
            for cat in EventCategory::ALL {
                let mins = *by_cat.get(&cat).unwrap_or(&0);
                if mins == 0 { continue; }
                let frac = mins as f64 / total as f64;
                let len = frac * circumference;
                let color = cat.color();
                let dash = format!("{} {}", len, circumference - len);
                let off = -offset;
                slices.push(view! {
                    <circle
                        cx=50 cy=50 r=36
                        fill="none"
                        stroke=color
                        stroke-width=12
                        stroke-dasharray=dash
                        stroke-dashoffset=off
                        transform="rotate(-90 50 50)"
                    />
                });
                offset += len;
                let pct = (frac * 100.0).round() as i64;
                legend.push(view! {
                    <li class="flex items-center justify-between gap-2 text-sm">
                        <div class="flex items-center gap-2 min-w-0">
                            <span
                                class="w-2.5 h-2.5 rounded-full flex-shrink-0"
                                style:background-color=color
                            />
                            <span class="truncate text-ultiq-indigo">{move || category_label(cat)}</span>
                        </div>
                        <span class="text-ultiq-indigo/60 text-xs">
                            {format!("{} · {}%", format_duration(mins), pct)}
                        </span>
                    </li>
                });
            }

            view! {
                <div class="flex items-center gap-6">
                    <svg viewBox="0 0 100 100" class="w-32 h-32 flex-shrink-0 text-ultiq-indigo">
                        {slices}
                        <text
                            x=50 y=52
                            text-anchor="middle"
                            font-size="11"
                            font-weight="600"
                            fill="currentColor"
                        >
                            {format_duration(total)}
                        </text>
                    </svg>
                    <ul class="flex-1 space-y-1.5 min-w-0">{legend}</ul>
                </div>
            }.into_any()
        }}
    }
}

#[component]
fn EventsPerDayBar(
    events: RwSignal<Vec<CalendarEvent>>,
    current_month: RwSignal<NaiveDate>,
) -> impl IntoView {
    view! {
        {move || {
            let evs = events.get();
            let month = current_month.get();
            let days_in_month = next_month(month).signed_duration_since(month).num_days() as usize;

            let mut counts = vec![0_i64; days_in_month];
            for e in &evs {
                let local_date = e.start_time.with_timezone(&Local).date_naive();
                if local_date.year() == month.year() && local_date.month() == month.month() {
                    let idx = (local_date.day() - 1) as usize;
                    if idx < counts.len() {
                        counts[idx] += 1;
                    }
                }
            }
            let max = counts.iter().copied().max().unwrap_or(0);
            if max == 0 {
                return view! {
                    <p class="text-sm text-ultiq-indigo/50 py-6 text-center">
                        {move || t("cal.no_events_month")}
                    </p>
                }.into_any();
            }
            view! {
                <div class="flex items-end gap-px h-32">
                    {counts.into_iter().enumerate().map(|(i, c)| {
                        let pct = (c as f64 / max as f64) * 100.0;
                        let day_n = (i + 1).to_string();
                        let cs = c.to_string();
                        let title = if c == 1 {
                            t_args("cal.per_day_one", &[("day", day_n.as_str()), ("count", cs.as_str())])
                        } else {
                            t_args("cal.per_day_other", &[("day", day_n.as_str()), ("count", cs.as_str())])
                        };
                        view! {
                            <div
                                class="flex-1 bg-ultiq-indigo/70 rounded-t hover:bg-ultiq-indigo transition-colors"
                                style:height=format!("{}%", if c == 0 { 0.0 } else { pct.max(4.0) })
                                title=title
                            />
                        }
                    }).collect_view()}
                </div>
            }.into_any()
        }}
    }
}

#[component]
fn PriorityBars(events: RwSignal<Vec<CalendarEvent>>) -> impl IntoView {
    view! {
        {move || {
            let evs = events.get();
            if evs.is_empty() {
                return view! {
                    <p class="text-sm text-ultiq-indigo/50 py-6 text-center">
                        {move || t("cal.no_events_month")}
                    </p>
                }.into_any();
            }
            let mut counts: HashMap<EventPriority, i64> = HashMap::new();
            for e in &evs {
                *counts.entry(e.priority).or_insert(0) += 1;
            }
            let max = counts.values().copied().max().unwrap_or(0).max(1);
            view! {
                <ul class="space-y-3">
                    {EventPriority::ALL.iter().map(|p| {
                        let c = *counts.get(p).unwrap_or(&0);
                        let pr = *p;
                        let pct = (c as f64 / max as f64) * 100.0;
                        let color = match p {
                            EventPriority::High => "#D9474C",
                            EventPriority::Medium => "#FFC83D",
                            EventPriority::Low => "#A8C5E8",
                        };
                        view! {
                            <li>
                                <div class="flex items-center justify-between text-sm mb-1">
                                    <span class="font-medium text-ultiq-indigo">{move || priority_word(pr)}</span>
                                    <span class="text-ultiq-indigo/60 text-xs">
                                        {
                                            let cs = c.to_string();
                                            if c == 1 { t_args("cal.events_one", &[("count", cs.as_str())]) }
                                            else { t_args("cal.events_other", &[("count", cs.as_str())]) }
                                        }
                                    </span>
                                </div>
                                <div class="h-2 bg-ultiq-indigo/10 rounded-full overflow-hidden">
                                    <div
                                        class="h-full rounded-full"
                                        style:width=format!("{}%", pct)
                                        style:background-color=color
                                    />
                                </div>
                            </li>
                        }
                    }).collect_view()}
                </ul>
            }.into_any()
        }}
    }
}

#[component]
fn DayOfWeekBar(events: RwSignal<Vec<CalendarEvent>>) -> impl IntoView {
    view! {
        {move || {
            let evs = events.get();
            let dow_loc = current_locale().chrono();
            let dow_labels: Vec<String> = (0..7)
                .map(|i| {
                    (NaiveDate::from_ymd_opt(2024, 1, 1).unwrap() + Duration::days(i))
                        .format_localized("%a", dow_loc)
                        .to_string()
                })
                .collect();
            if evs.is_empty() {
                return view! {
                    <p class="text-sm text-ultiq-indigo/50 py-6 text-center">
                        {move || t("cal.no_events_month")}
                    </p>
                }.into_any();
            }
            let mut counts: [i64; 7] = [0; 7];
            for e in &evs {
                let local_date = e.start_time.with_timezone(&Local).date_naive();
                let dow = local_date.weekday().num_days_from_monday() as usize;
                if dow < 7 {
                    counts[dow] += 1;
                }
            }
            let max = *counts.iter().max().unwrap_or(&0).max(&1);
            view! {
                <div class="flex items-end justify-around gap-2 h-28">
                    {(0..7).map(|i| {
                        let c = counts[i];
                        let pct = (c as f64 / max as f64) * 100.0;
                        view! {
                            <div class="flex flex-col items-center justify-end h-full flex-1 gap-1">
                                <span class="text-xs text-ultiq-indigo/60 font-medium">{c}</span>
                                <div
                                    class="w-full rounded-t bg-ultiq-indigo/70"
                                    style:height=format!("{}%", if c == 0 { 0.0 } else { pct.max(4.0) })
                                />
                                <span class="text-xs text-ultiq-indigo/70 mt-1">{dow_labels[i].clone()}</span>
                            </div>
                        }
                    }).collect_view()}
                </div>
            }.into_any()
        }}
    }
}

fn format_duration(minutes: i64) -> String {
    if minutes <= 0 {
        return "0m".to_string();
    }
    let h = minutes / 60;
    let m = minutes % 60;
    if h == 0 {
        format!("{}m", m)
    } else if m == 0 {
        format!("{}h", h)
    } else {
        format!("{}h {}m", h, m)
    }
}
