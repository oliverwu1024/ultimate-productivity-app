use chrono::{Datelike, Duration, Local, NaiveDate, TimeZone, Utc};
use leptos::either::EitherOf3;
use leptos::prelude::*;
use leptos_meta::Title;

use crate::api::calendar::{
    create_event as create_calendar_event, CreateCalendarEvent, EventCategory, EventPriority,
};
use crate::api::checklist::{
    complete_item, create_item, delete_item, list_for_range, update_item, ChecklistItem,
    CreateChecklistItem, Priority, UpdateChecklistItem,
};
use crate::api::sse::{use_sse, SyncEvent};
use crate::components::layout::AppShell;

#[derive(Clone)]
enum DialogState {
    Closed,
    Add,
    Edit(ChecklistItem),
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

const MONTH_NAMES: [&str; 12] = [
    "Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
];

fn fmt_day_header(d: NaiveDate, today: NaiveDate) -> String {
    let suffix = match (d - today).num_days() {
        0 => " · Today".to_string(),
        1 => " · Tomorrow".to_string(),
        -1 => " · Yesterday".to_string(),
        _ => String::new(),
    };
    format!(
        "{}, {} {}{}",
        weekday_label(d.weekday()),
        d.day(),
        MONTH_NAMES[(d.month() - 1) as usize],
        suffix,
    )
}

#[component]
pub fn ChecklistPage() -> impl IntoView {
    let today = Local::now().date_naive();
    let selected_day = RwSignal::new(today);
    let items: RwSignal<Vec<ChecklistItem>> = RwSignal::new(Vec::new());
    let dialog = RwSignal::new(DialogState::Closed);
    let loading = RwSignal::new(false);
    let error = RwSignal::new(None::<String>);
    let show_completed = RwSignal::new(false);

    let refresh = move || {
        let day = selected_day.get_untracked();
        loading.set(true);
        error.set(None);
        wasm_bindgen_futures::spawn_local(async move {
            // Fetch a small window (today + 1 day each side) — backend's GET /checklist defaults
            // to ±30 days, but we only need this single date for the day view; small window keeps
            // payload tight.
            match list_for_range(day, day).await {
                Ok(list) => items.set(list),
                Err(e) => error.set(Some(e.message)),
            }
            loading.set(false);
        });
    };

    Effect::new(move |_| {
        let _ = selected_day.get();
        refresh();
    });

    // Realtime: any checklist event refreshes the day.
    let sse = use_sse();
    Effect::new(move |_| {
        if let Some(ev) = sse.last_event.get() {
            match ev {
                SyncEvent::ChecklistCreated(_)
                | SyncEvent::ChecklistUpdated(_)
                | SyncEvent::ChecklistDeleted(_) => refresh(),
                _ => {}
            }
        }
    });

    let goto_prev = move |_| selected_day.update(|d| *d -= Duration::days(1));
    let goto_next = move |_| selected_day.update(|d| *d += Duration::days(1));
    let goto_today = move |_| selected_day.set(today);

    let header_label = move || {
        let d = selected_day.get();
        fmt_day_header(d, today)
    };

    let toggle_complete = move |item: ChecklistItem| {
        let id = item.id.clone();
        let was_completed = item.completed;
        wasm_bindgen_futures::spawn_local(async move {
            let result = if was_completed {
                update_item(
                    &id,
                    &UpdateChecklistItem {
                        title: None,
                        description: None,
                        due_date: None,
                        estimated_minutes: None,
                        priority: None,
                        completed: Some(false),
                    },
                )
                .await
                .map(|_| ())
            } else {
                complete_item(&id).await.map(|_| ())
            };
            if result.is_err() {
                refresh();
            }
        });
    };

    view! {
        <Title text="Checklist — Ultiq" />
        <AppShell>
            <div class="p-4 md:p-8 max-w-4xl mx-auto">
                <header class="flex items-center justify-between mb-6">
                    <h1 class="text-3xl font-bold text-ultiq-indigo">"Checklist"</h1>
                    <button
                        on:click=move |_| dialog.set(DialogState::Add)
                        class="px-4 py-2 bg-ultiq-indigo text-ultiq-cream rounded-lg font-medium hover:opacity-90 cursor-pointer"
                    >
                        "+ Add item"
                    </button>
                </header>

                <Show when=move || error.get().is_some()>
                    <div class="bg-ultiq-red/5 text-ultiq-red rounded-lg p-3 mb-4 text-sm">
                        {move || error.get().unwrap_or_default()}
                    </div>
                </Show>

                <div class="bg-white rounded-2xl shadow p-6">
                    <div class="flex items-center justify-between mb-5">
                        <button
                            on:click=goto_prev
                            class="px-3 py-1 rounded hover:bg-ultiq-indigo/5 cursor-pointer"
                            aria-label="Previous day"
                        >
                            "←"
                        </button>
                        <div class="flex items-center gap-3">
                            <h2 class="text-lg font-semibold text-ultiq-indigo">
                                {header_label}
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
                            aria-label="Next day"
                        >
                            "→"
                        </button>
                    </div>

                    <ProgressBar items=items />

                    <div class="mt-4 space-y-2">
                        {move || {
                            let mut day_items: Vec<ChecklistItem> = items
                                .get()
                                .into_iter()
                                .filter(|i| !i.completed)
                                .collect();
                            day_items.sort_by_key(|i| (-i.priority, i.created_at));
                            if day_items.is_empty() {
                                view! {
                                    <p class="text-sm text-ultiq-indigo/50 py-4 text-center">
                                        "Nothing open for this day."
                                    </p>
                                }.into_any()
                            } else {
                                view! {
                                    <ul class="space-y-2">
                                        {day_items.into_iter().map(|item| {
                                            let item_for_edit = item.clone();
                                            let item_for_toggle = item.clone();
                                            view! {
                                                <ItemRow
                                                    item=item.clone()
                                                    on_toggle=move || toggle_complete(item_for_toggle.clone())
                                                    on_edit=move || dialog.set(DialogState::Edit(item_for_edit.clone()))
                                                />
                                            }
                                        }).collect_view()}
                                    </ul>
                                }.into_any()
                            }
                        }}
                    </div>

                    <div class="mt-6 pt-4 border-t border-ultiq-indigo/10">
                        <button
                            on:click=move |_| show_completed.update(|s| *s = !*s)
                            class="text-sm text-ultiq-indigo/70 hover:text-ultiq-indigo cursor-pointer"
                        >
                            {move || {
                                let n = items.get().iter().filter(|i| i.completed).count();
                                let arrow = if show_completed.get() { "▼" } else { "▶" };
                                format!("{} Completed ({})", arrow, n)
                            }}
                        </button>
                        <Show when=move || show_completed.get()>
                            <ul class="space-y-2 mt-3">
                                {move || {
                                    let mut done: Vec<ChecklistItem> = items
                                        .get()
                                        .into_iter()
                                        .filter(|i| i.completed)
                                        .collect();
                                    done.sort_by_key(|i| std::cmp::Reverse(i.completed_at));
                                    done.into_iter().map(|item| {
                                        let item_for_toggle = item.clone();
                                        let item_for_edit = item.clone();
                                        view! {
                                            <ItemRow
                                                item=item.clone()
                                                on_toggle=move || toggle_complete(item_for_toggle.clone())
                                                on_edit=move || dialog.set(DialogState::Edit(item_for_edit.clone()))
                                            />
                                        }
                                    }).collect_view()
                                }}
                            </ul>
                        </Show>
                    </div>
                </div>

                {move || match dialog.get() {
                    DialogState::Closed => EitherOf3::A(view! { <></> }),
                    DialogState::Add => EitherOf3::B(view! {
                        <ItemDialog
                            existing=None
                            initial_day=selected_day.get_untracked()
                            on_close=move || dialog.set(DialogState::Closed)
                            on_saved=move || { dialog.set(DialogState::Closed); refresh(); }
                        />
                    }),
                    DialogState::Edit(item) => EitherOf3::C(view! {
                        <ItemDialog
                            existing=Some(item)
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

#[component]
fn ProgressBar(items: RwSignal<Vec<ChecklistItem>>) -> impl IntoView {
    view! {
        {move || {
            let all = items.get();
            let total = all.len();
            let done = all.iter().filter(|i| i.completed).count();
            let pct = if total == 0 { 0.0 } else { (done as f64 / total as f64) * 100.0 };
            view! {
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
            }
        }}
    }
}

#[component]
fn ItemRow(
    item: ChecklistItem,
    on_toggle: impl Fn() + Send + Sync + 'static,
    on_edit: impl Fn() + Send + Sync + 'static,
) -> impl IntoView {
    let priority = item.priority_enum();
    let completed = item.completed;
    let title_class = if completed {
        "line-through text-ultiq-indigo/40"
    } else {
        "text-ultiq-indigo"
    };
    let priority_color = match priority {
        Priority::High => "bg-ultiq-red/10 text-ultiq-red",
        Priority::Medium => "bg-ultiq-yellow/20 text-ultiq-indigo",
        Priority::Low => "bg-ultiq-indigo/5 text-ultiq-indigo/60",
    };

    let title = item.title.clone();
    let estimated = item.estimated_minutes;
    let description = item.description.clone();

    view! {
        <li class="flex items-start gap-3 bg-ultiq-cream/40 rounded-xl p-3 hover:bg-ultiq-cream/70 transition-colors">
            <button
                on:click=move |_| on_toggle()
                class=format!(
                    "mt-0.5 w-5 h-5 rounded border-2 flex items-center justify-center cursor-pointer flex-shrink-0 {}",
                    if completed {
                        "bg-ultiq-indigo border-ultiq-indigo"
                    } else {
                        "border-ultiq-indigo/40 hover:border-ultiq-indigo bg-white"
                    }
                )
                aria-label="Toggle complete"
            >
                <Show when=move || completed>
                    <span class="text-ultiq-cream text-xs leading-none">"✓"</span>
                </Show>
            </button>
            <button
                on:click=move |_| on_edit()
                class="flex-1 text-left cursor-pointer"
            >
                <div class="flex items-center gap-2 flex-wrap">
                    <span class=format!("font-medium {}", title_class)>{title}</span>
                    <span class=format!("text-[10px] px-2 py-0.5 rounded-full font-medium {}", priority_color)>
                        {priority.label()}
                    </span>
                    <Show when=move || estimated.is_some()>
                        <span class="text-xs text-ultiq-indigo/50">
                            {format!("~{} min", estimated.unwrap_or(0))}
                        </span>
                    </Show>
                </div>
                <Show when={
                    let d = description.clone();
                    move || d.as_deref().map(|s| !s.is_empty()).unwrap_or(false)
                }>
                    <p class="text-sm text-ultiq-indigo/60 mt-1">
                        {description.clone().unwrap_or_default()}
                    </p>
                </Show>
            </button>
        </li>
    }
}

#[component]
fn ItemDialog(
    existing: Option<ChecklistItem>,
    initial_day: NaiveDate,
    on_close: impl Fn() + Send + Sync + Copy + 'static,
    on_saved: impl Fn() + Send + Sync + Copy + 'static,
) -> impl IntoView {
    let is_edit = existing.is_some();
    let item_id = existing.as_ref().map(|i| i.id.clone());

    let title = RwSignal::new(existing.as_ref().map(|i| i.title.clone()).unwrap_or_default());
    let description = RwSignal::new(
        existing.as_ref().and_then(|i| i.description.clone()).unwrap_or_default(),
    );
    let due_date = RwSignal::new(
        existing.as_ref().map(|i| i.due_date).unwrap_or(initial_day),
    );
    let estimated_minutes_str = RwSignal::new(
        existing
            .as_ref()
            .and_then(|i| i.estimated_minutes)
            .map(|n| n.to_string())
            .unwrap_or_default(),
    );
    let priority = RwSignal::new(
        existing
            .as_ref()
            .map(|i| i.priority_enum())
            .unwrap_or(Priority::Medium),
    );
    let submitting = RwSignal::new(false);
    let dialog_error = RwSignal::new(None::<String>);
    let scheduled_msg = RwSignal::new(None::<String>);

    let item_id_store = StoredValue::new(item_id);

    let on_submit = move |ev: leptos::ev::SubmitEvent| {
        ev.prevent_default();
        if submitting.get_untracked() {
            return;
        }

        let t = title.get_untracked();
        if t.trim().is_empty() {
            dialog_error.set(Some("Title is required".into()));
            return;
        }
        let est_str = estimated_minutes_str.get_untracked();
        let est: Option<i32> = if est_str.trim().is_empty() {
            None
        } else {
            match est_str.trim().parse::<i32>() {
                Ok(n) if n > 0 => Some(n),
                _ => {
                    dialog_error.set(Some("Estimated minutes must be a positive number".into()));
                    return;
                }
            }
        };

        let desc = description.get_untracked();
        let desc_opt = if desc.trim().is_empty() { None } else { Some(desc) };
        let dd = due_date.get_untracked();
        let pri = priority.get_untracked().to_i16();

        submitting.set(true);
        dialog_error.set(None);

        let id = item_id_store.get_value();
        wasm_bindgen_futures::spawn_local(async move {
            let res = if let Some(id) = id {
                update_item(
                    &id,
                    &UpdateChecklistItem {
                        title: Some(t),
                        description: desc_opt,
                        due_date: Some(dd),
                        estimated_minutes: est,
                        priority: Some(pri),
                        completed: None,
                    },
                )
                .await
                .map(|_| ())
            } else {
                create_item(&CreateChecklistItem {
                    title: t,
                    description: desc_opt,
                    due_date: dd,
                    estimated_minutes: est,
                    priority: pri,
                })
                .await
                .map(|_| ())
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

    let on_schedule = move |_| {
        if submitting.get_untracked() { return; }
        let t = title.get_untracked();
        if t.trim().is_empty() {
            dialog_error.set(Some("Add a title before scheduling".into()));
            return;
        }
        let dd = due_date.get_untracked();
        let est = match estimated_minutes_str.get_untracked().trim().parse::<i64>().ok() {
            Some(n) if n > 0 => n,
            _ => 60,
        };
        // Default time slot: 9:00 AM local, lasting `est` minutes (or 1h fallback).
        let start_local = match Local
            .with_ymd_and_hms(dd.year(), dd.month(), dd.day(), 9, 0, 0)
            .single()
        {
            Some(d) => d,
            None => {
                dialog_error.set(Some("Could not compute a start time".into()));
                return;
            }
        };
        let start_utc = start_local.with_timezone(&Utc);
        let end_utc = start_utc + chrono::Duration::minutes(est);

        let priority_for_event = match priority.get_untracked() {
            Priority::High => EventPriority::High,
            Priority::Medium => EventPriority::Medium,
            Priority::Low => EventPriority::Low,
        };
        let desc = description.get_untracked();
        let body = CreateCalendarEvent {
            title: t.clone(),
            description: if desc.trim().is_empty() { None } else { Some(desc) },
            start_time: start_utc,
            end_time: end_utc,
            category: EventCategory::Other,
            priority: priority_for_event,
            is_recurring: false,
            recurrence_rule: None,
            color: None,
            is_done: None,
        };

        submitting.set(true);
        dialog_error.set(None);
        scheduled_msg.set(None);
        wasm_bindgen_futures::spawn_local(async move {
            match create_calendar_event(&body).await {
                Ok(_) => {
                    let when = start_local.format("%a, %b %d at %H:%M").to_string();
                    scheduled_msg.set(Some(format!(
                        "Scheduled for {} — open Calendar to fine-tune.",
                        when
                    )));
                    submitting.set(false);
                }
                Err(e) => {
                    dialog_error.set(Some(e.message));
                    submitting.set(false);
                }
            }
        });
    };

    let on_delete = move |_| {
        let Some(id) = item_id_store.get_value() else { return };
        let confirmed = web_sys::window()
            .and_then(|w| {
                w.confirm_with_message("Delete this item? This cannot be undone.")
                    .ok()
            })
            .unwrap_or(false);
        if !confirmed {
            return;
        }
        submitting.set(true);
        wasm_bindgen_futures::spawn_local(async move {
            match delete_item(&id).await {
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
                    {if is_edit { "Edit item" } else { "New item" }}
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
                        <span class="text-sm font-medium text-ultiq-indigo">"Due"</span>
                        <input
                            type="date"
                            class="mt-1 w-full px-3 py-2 border border-ultiq-indigo/20 rounded-lg focus:outline-none focus:ring-2 focus:ring-ultiq-indigo"
                            prop:value=move || due_date.get().to_string()
                            on:input=move |ev| {
                                if let Ok(d) = NaiveDate::parse_from_str(&event_target_value(&ev), "%Y-%m-%d") {
                                    due_date.set(d);
                                }
                            }
                            required
                        />
                    </label>
                    <label class="block">
                        <span class="text-sm font-medium text-ultiq-indigo">"Est. minutes"</span>
                        <input
                            type="number"
                            min="0"
                            class="mt-1 w-full px-3 py-2 border border-ultiq-indigo/20 rounded-lg focus:outline-none focus:ring-2 focus:ring-ultiq-indigo"
                            prop:value=move || estimated_minutes_str.get()
                            on:input=move |ev| estimated_minutes_str.set(event_target_value(&ev))
                            placeholder="e.g. 30"
                        />
                    </label>
                </div>

                <label class="block">
                    <span class="text-sm font-medium text-ultiq-indigo">"Priority"</span>
                    <select
                        class="mt-1 w-full px-3 py-2 border border-ultiq-indigo/20 rounded-lg focus:outline-none focus:ring-2 focus:ring-ultiq-indigo bg-white"
                        prop:value=move || priority.get().variant_str().to_string()
                        on:change=move |ev| {
                            if let Some(p) = Priority::from_str(&event_target_value(&ev)) {
                                priority.set(p);
                            }
                        }
                    >
                        {Priority::ALL.iter().map(|p| {
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

                <Show when=move || scheduled_msg.get().is_some()>
                    <p class="text-sm text-emerald-700 bg-emerald-500/10 px-3 py-2 rounded">
                        {move || scheduled_msg.get().unwrap_or_default()}
                    </p>
                </Show>

                <div class="flex flex-wrap justify-between gap-2 pt-2">
                    <div class="flex gap-2">
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
                        <button
                            type="button"
                            on:click=on_schedule
                            class="px-3 py-2 text-ultiq-indigo hover:bg-ultiq-indigo/5 rounded-lg cursor-pointer border border-ultiq-indigo/20"
                            prop:disabled=move || submitting.get()
                            title="Create a calendar event from this todo"
                        >
                            "Schedule…"
                        </button>
                    </div>
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
