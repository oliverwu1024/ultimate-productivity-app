use chrono::{DateTime, Datelike, Duration, Local, NaiveDate, NaiveDateTime, TimeZone, Utc};
use leptos::either::EitherOf3;
use leptos::prelude::*;

use crate::api::calendar::{
    create_event, delete_event, list_events, update_event, CalendarEvent, CreateCalendarEvent,
    EventCategory, EventPriority,
};
use crate::api::sse::{use_sse, SyncEvent};
use crate::components::layout::AppShell;

#[derive(Clone)]
enum DialogState {
    Closed,
    Add,
    Edit(CalendarEvent),
}

const MONTH_NAMES: [&str; 12] = [
    "January", "February", "March", "April", "May", "June",
    "July", "August", "September", "October", "November", "December",
];

const DOW_LABELS: [&str; 7] = ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"];

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

fn dt_to_input(dt: DateTime<Utc>) -> String {
    dt.with_timezone(&Local).format("%Y-%m-%dT%H:%M").to_string()
}

fn input_to_dt(s: &str) -> Option<DateTime<Utc>> {
    let naive = NaiveDateTime::parse_from_str(s, "%Y-%m-%dT%H:%M").ok()?;
    Local.from_local_datetime(&naive).single().map(|d| d.with_timezone(&Utc))
}

fn default_start_for(day: NaiveDate) -> String {
    let dt = day.and_hms_opt(9, 0, 0).unwrap();
    dt.format("%Y-%m-%dT%H:%M").to_string()
}

fn default_end_for(day: NaiveDate) -> String {
    let dt = day.and_hms_opt(10, 0, 0).unwrap();
    dt.format("%Y-%m-%dT%H:%M").to_string()
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
        if let Some(ev) = sse.last_event.get() {
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
        format!("{} {}", MONTH_NAMES[(m.month() - 1) as usize], m.year())
    };

    view! {
        <AppShell>
            <div class="p-8 max-w-6xl mx-auto">
                <header class="flex items-center justify-between mb-6">
                    <h1 class="text-3xl font-bold text-ultiq-indigo">"Calendar"</h1>
                    <button
                        on:click=move |_| dialog.set(DialogState::Add)
                        class="px-4 py-2 bg-ultiq-indigo text-ultiq-cream rounded-lg font-medium hover:opacity-90 cursor-pointer"
                    >
                        "+ Add event"
                    </button>
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
                                "Today"
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
                        {DOW_LABELS.iter().map(|d| view! {
                            <div class="text-center py-1">{*d}</div>
                        }).collect_view()}
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
                                let day_events: Vec<CalendarEvent> = evs.iter()
                                    .filter(|e| local_date(e.start_time) == day)
                                    .cloned()
                                    .collect();
                                view! {
                                    <DayCell
                                        day=day
                                        in_month=in_month
                                        is_today=is_today
                                        is_selected=is_selected
                                        events=day_events
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
                            format!("{} {}, {}", weekday_label(d.weekday()), d.day(), MONTH_NAMES[(d.month() - 1) as usize])
                        }}
                    </h2>
                    <DayEvents
                        events=events
                        selected_day=selected_day
                        on_edit=move |e: CalendarEvent| dialog.set(DialogState::Edit(e))
                    />
                </section>

                {move || match dialog.get() {
                    DialogState::Closed => EitherOf3::A(view! { <></> }),
                    DialogState::Add => EitherOf3::B(view! {
                        <EventDialog
                            existing=None
                            initial_day=selected_day.get_untracked()
                            on_close=move || dialog.set(DialogState::Closed)
                            on_saved=move || { dialog.set(DialogState::Closed); refresh(); }
                        />
                    }),
                    DialogState::Edit(event) => EitherOf3::C(view! {
                        <EventDialog
                            existing=Some(event)
                            initial_day=selected_day.get_untracked()
                            on_close=move || dialog.set(DialogState::Closed)
                            on_saved=move || { dialog.set(DialogState::Closed); refresh(); }
                        />
                    }),
                }}
            </div>
        </AppShell>
    }
}

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

#[component]
fn DayCell(
    day: NaiveDate,
    in_month: bool,
    is_today: bool,
    is_selected: bool,
    events: Vec<CalendarEvent>,
    on_click: impl Fn() + Send + Sync + 'static,
) -> impl IntoView {
    let count = events.len();
    let label = format!("{} {}", count, if count == 1 { "event" } else { "events" });

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
            let mut day_events: Vec<CalendarEvent> = events.get().into_iter()
                .filter(|e| local_date(e.start_time) == day)
                .collect();
            day_events.sort_by_key(|e| e.start_time);

            if day_events.is_empty() {
                view! {
                    <p class="text-ultiq-indigo/50 text-sm">"No events on this day."</p>
                }.into_any()
            } else {
                view! {
                    <ul class="space-y-2">
                        {day_events.into_iter().map(|e| {
                            let event_for_click = e.clone();
                            let start_local = e.start_time.with_timezone(&Local);
                            let end_local = e.end_time.with_timezone(&Local);
                            let time_range = format!(
                                "{} – {}",
                                start_local.format("%H:%M"),
                                end_local.format("%H:%M"),
                            );
                            let color = e.color.clone();
                            view! {
                                <li>
                                    <button
                                        on:click=move |_| on_edit(event_for_click.clone())
                                        class="w-full bg-white rounded-xl shadow-sm hover:shadow p-4 flex items-start gap-3 text-left cursor-pointer"
                                    >
                                        <span
                                            class="w-1 h-12 rounded-full flex-shrink-0 self-stretch"
                                            style:background-color=color
                                        />
                                        <div class="flex-1 min-w-0">
                                            <div class="flex items-center gap-2 flex-wrap">
                                                <span class="font-medium text-ultiq-indigo">{e.title.clone()}</span>
                                                <span class="text-xs px-2 py-0.5 rounded bg-ultiq-indigo/5 text-ultiq-indigo/70">
                                                    {e.category.label()}
                                                </span>
                                                <span class="text-xs text-ultiq-indigo/50">
                                                    {priority_label(e.priority)}
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
                                </li>
                            }
                        }).collect_view()}
                    </ul>
                }.into_any()
            }
        }}
    }
}

fn priority_label(p: EventPriority) -> &'static str {
    match p {
        EventPriority::High => "● High",
        EventPriority::Medium => "● Medium",
        EventPriority::Low => "● Low",
    }
}

#[component]
fn EventDialog(
    existing: Option<CalendarEvent>,
    initial_day: NaiveDate,
    on_close: impl Fn() + Send + Sync + Copy + 'static,
    on_saved: impl Fn() + Send + Sync + Copy + 'static,
) -> impl IntoView {
    let is_edit = existing.is_some();
    let event_id = existing.as_ref().map(|e| e.id.clone());

    let title = RwSignal::new(existing.as_ref().map(|e| e.title.clone()).unwrap_or_default());
    let description = RwSignal::new(
        existing.as_ref().and_then(|e| e.description.clone()).unwrap_or_default(),
    );
    let start_time = RwSignal::new(
        existing.as_ref().map(|e| dt_to_input(e.start_time)).unwrap_or_else(|| default_start_for(initial_day)),
    );
    let end_time = RwSignal::new(
        existing.as_ref().map(|e| dt_to_input(e.end_time)).unwrap_or_else(|| default_end_for(initial_day)),
    );
    let category = RwSignal::new(existing.as_ref().map(|e| e.category).unwrap_or(EventCategory::Study));
    let priority = RwSignal::new(existing.as_ref().map(|e| e.priority).unwrap_or(EventPriority::Medium));
    let submitting = RwSignal::new(false);
    let dialog_error = RwSignal::new(None::<String>);

    let event_id_store = StoredValue::new(event_id.clone());
    let on_submit = move |ev: leptos::ev::SubmitEvent| {
        ev.prevent_default();
        if submitting.get_untracked() { return; }

        let t = title.get_untracked();
        if t.trim().is_empty() {
            dialog_error.set(Some("Title is required".into()));
            return;
        }
        let st = match input_to_dt(&start_time.get_untracked()) {
            Some(dt) => dt,
            None => { dialog_error.set(Some("Invalid start time".into())); return; }
        };
        let et = match input_to_dt(&end_time.get_untracked()) {
            Some(dt) => dt,
            None => { dialog_error.set(Some("Invalid end time".into())); return; }
        };
        if st >= et {
            dialog_error.set(Some("Start time must be before end time".into()));
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

    let on_delete = move |_| {
        let Some(id) = event_id_store.get_value() else { return; };
        let confirmed = web_sys::window()
            .and_then(|w| w.confirm_with_message("Delete this event? This cannot be undone.").ok())
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

    view! {
        <div
            class="fixed inset-0 bg-black/40 z-50 flex items-center justify-center p-4"
            on:click=move |_| on_close()
        >
            <form
                on:submit=on_submit
                on:click=|ev| ev.stop_propagation()
                class="bg-white rounded-2xl shadow-xl p-6 w-full max-w-md space-y-4 max-h-[90vh] overflow-auto"
            >
                <h3 class="text-xl font-semibold text-ultiq-indigo">
                    {if is_edit { "Edit event" } else { "New event" }}
                </h3>

                <label class="block">
                    <span class="text-sm font-medium text-ultiq-indigo">"Title"</span>
                    <input
                        type="text"
                        class="mt-1 w-full px-3 py-2 border border-ultiq-indigo/20 rounded-lg focus:outline-none focus:ring-2 focus:ring-ultiq-indigo"
                        prop:value=move || title.get()
                        on:input=move |ev| title.set(event_target_value(&ev))
                        required
                    />
                </label>

                <label class="block">
                    <span class="text-sm font-medium text-ultiq-indigo">"Description"</span>
                    <textarea
                        rows="2"
                        class="mt-1 w-full px-3 py-2 border border-ultiq-indigo/20 rounded-lg focus:outline-none focus:ring-2 focus:ring-ultiq-indigo"
                        prop:value=move || description.get()
                        on:input=move |ev| description.set(event_target_value(&ev))
                    />
                </label>

                <div class="grid grid-cols-2 gap-3">
                    <label class="block">
                        <span class="text-sm font-medium text-ultiq-indigo">"Start"</span>
                        <input
                            type="datetime-local"
                            class="mt-1 w-full px-3 py-2 border border-ultiq-indigo/20 rounded-lg focus:outline-none focus:ring-2 focus:ring-ultiq-indigo"
                            prop:value=move || start_time.get()
                            on:input=move |ev| start_time.set(event_target_value(&ev))
                            required
                        />
                    </label>
                    <label class="block">
                        <span class="text-sm font-medium text-ultiq-indigo">"End"</span>
                        <input
                            type="datetime-local"
                            class="mt-1 w-full px-3 py-2 border border-ultiq-indigo/20 rounded-lg focus:outline-none focus:ring-2 focus:ring-ultiq-indigo"
                            prop:value=move || end_time.get()
                            on:input=move |ev| end_time.set(event_target_value(&ev))
                            required
                        />
                    </label>
                </div>

                <label class="block">
                    <span class="text-sm font-medium text-ultiq-indigo">"Category"</span>
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
                            view! { <option value=v>{c.label()}</option> }
                        }).collect_view()}
                    </select>
                </label>

                <label class="block">
                    <span class="text-sm font-medium text-ultiq-indigo">"Priority"</span>
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
                            view! { <option value=v>{p.label()}</option> }
                        }).collect_view()}
                    </select>
                </label>

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
                            "Delete"
                        </button>
                    </Show>
                    <div class="flex gap-2 ml-auto">
                        <button
                            type="button"
                            on:click=move |_| on_close()
                            class="px-4 py-2 text-ultiq-indigo hover:bg-ultiq-indigo/5 rounded-lg cursor-pointer"
                            prop:disabled=move || submitting.get()
                        >
                            "Cancel"
                        </button>
                        <button
                            type="submit"
                            class="px-4 py-2 bg-ultiq-indigo text-ultiq-cream rounded-lg font-medium hover:opacity-90 disabled:opacity-50 cursor-pointer"
                            prop:disabled=move || submitting.get()
                        >
                            {move || if submitting.get() { "Saving…" } else { "Save" }}
                        </button>
                    </div>
                </div>
            </form>
        </div>
    }
}
