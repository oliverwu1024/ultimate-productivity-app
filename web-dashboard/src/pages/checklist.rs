use chrono::{Datelike, Duration, Local, NaiveDate, TimeZone, Utc};
use leptos::either::{Either, EitherOf4};
use leptos::prelude::*;
use leptos_meta::Title;

use crate::api::ai::{parse_event, ParseEventRequest, ParsedChecklistFields};
use crate::api::calendar::{
    create_event as create_calendar_event, CreateCalendarEvent, EventCategory, EventPriority,
};
use crate::api::checklist::{
    complete_item, complete_item_on, create_item, delete_item, list_for_range,
    naive_date_to_epoch_day, uncomplete_item, uncomplete_item_on, update_item, ChecklistItem,
    CreateChecklistItem, Priority, UpdateChecklistItem,
};
use crate::api::sse::{use_sse, SyncEvent};
use crate::components::ai_parse_dialog::{AiParsePromptDialog, AiSurface};
use crate::components::layout::AppShell;
use crate::i18n::{current_locale, current_locale_untracked, t, t_args, tu};

/// Thin wrapper to keep the call sites tidy; logic lives on the model.
fn shows_on(item: &ChecklistItem, day: NaiveDate) -> bool {
    item.shows_on(day)
}

#[derive(Clone)]
enum DialogState {
    Closed,
    Add,
    Edit(ChecklistItem),
    /// §9.5 — AI quick-add hands its parsed values to the create dialog.
    /// User still confirms before save.
    AddPrefilled(ParsedChecklistFields),
}

#[derive(Clone, Copy, PartialEq, Eq)]
enum ScheduleMode {
    OneOff,
    Recurring,
    UntilDue,
}

fn fmt_day_header(d: NaiveDate, today: NaiveDate) -> String {
    let loc = current_locale().chrono();
    let base = format!(
        "{}, {} {}",
        d.format_localized("%A", loc),
        d.day(),
        d.format_localized("%b", loc)
    );
    let rel = match (d - today).num_days() {
        0 => Some(t("common.today")),
        1 => Some(t("chk.rel_tomorrow")),
        -1 => Some(t("chk.rel_yesterday")),
        _ => None,
    };
    match rel {
        Some(r) => format!("{base} · {r}"),
        None => base,
    }
}

fn priority_label_chk(p: Priority) -> String {
    t(match p {
        Priority::High => "common.priority_high",
        Priority::Medium => "common.priority_medium",
        Priority::Low => "common.priority_low",
    })
}

/// Localized single-letter weekday initial. `i`: 0=Sun … 6=Sat (matches the
/// recurrence-mask bit order). Uses the first character of the locale's
/// abbreviated weekday name, uppercased.
fn weekday_initial(i: usize) -> String {
    let loc = current_locale().chrono();
    let name = (NaiveDate::from_ymd_opt(2023, 12, 31).unwrap() + Duration::days(i as i64))
        .format_localized("%a", loc)
        .to_string();
    name.chars()
        .next()
        .map(|c| c.to_uppercase().to_string())
        .unwrap_or_default()
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

    // §9.5 — AI quick-add state.
    let ai_open = RwSignal::new(false);
    let ai_loading = RwSignal::new(false);
    let ai_error = RwSignal::new(None::<String>);
    let ai_parse_failed = StoredValue::new(tu("common.ai_parse_failed"));

    let refresh = move || {
        let day = selected_day.get_untracked();
        loading.set(true);
        error.set(None);
        // Recurring items only have a single `due_date` row in the DB
        // (their "start" date), so to surface them on a given day we
        // have to pull a back-window. 60 days back / 7 days forward is
        // plenty for the typical user; the client filter below is what
        // actually picks which items show.
        let start = day - Duration::days(60);
        let end = day + Duration::days(7);
        wasm_bindgen_futures::spawn_local(async move {
            match list_for_range(start, end).await {
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
        if let Some(ev) = sse.last_event_debounced.get() {
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
        let day = selected_day.get_untracked();
        let day_epoch = naive_date_to_epoch_day(day);
        let was_done = item.is_done_on(day);
        let recurring = item.is_recurring();
        wasm_bindgen_futures::spawn_local(async move {
            // §024 — Recurring rows now use the per-day endpoints which
            // touch checklist_completions, so a tick on Tue stays tied
            // to Tue and doesn't undo Mon's stamp. Non-recurring rows
            // keep the legacy complete/uncomplete pair.
            let result = if recurring {
                if was_done {
                    uncomplete_item_on(&id, day_epoch).await.map(|_| ())
                } else {
                    complete_item_on(&id, day_epoch).await.map(|_| ())
                }
            } else if was_done {
                uncomplete_item(&id).await.map(|_| ())
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
                    <h1 class="text-3xl font-bold text-ultiq-indigo">{move || t("nav.checklist")}</h1>
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
                            {move || format!("+ {}", t("chk.add_item"))}
                        </button>
                    </div>
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
                            aria-label=move || t("chk.prev_day")
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
                                {move || t("common.today")}
                            </button>
                        </div>
                        <button
                            on:click=goto_next
                            class="px-3 py-1 rounded hover:bg-ultiq-indigo/5 cursor-pointer"
                            aria-label=move || t("chk.next_day")
                        >
                            "→"
                        </button>
                    </div>

                    <ProgressBar items=items day=selected_day />

                    <div class="mt-4 space-y-2">
                        {move || {
                            let day = selected_day.get();
                            let mut day_items: Vec<ChecklistItem> = items
                                .get()
                                .into_iter()
                                .filter(|i| shows_on(i, day) && !i.is_done_on(day))
                                .collect();
                            day_items.sort_by_key(|i| (-i.priority, i.created_at));
                            if day_items.is_empty() {
                                view! {
                                    <p class="text-sm text-ultiq-indigo/50 py-4 text-center">
                                        {move || t("chk.nothing_open")}
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
                                                    is_done_now=false
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
                                let day = selected_day.get();
                                let n = items
                                    .get()
                                    .iter()
                                    .filter(|i| shows_on(i, day) && i.is_done_on(day))
                                    .count();
                                let arrow = if show_completed.get() { "▼" } else { "▶" };
                                let ns = n.to_string();
                                format!("{} {}", arrow, t_args("chk.completed", &[("count", ns.as_str())]))
                            }}
                        </button>
                        <Show when=move || show_completed.get()>
                            <ul class="space-y-2 mt-3">
                                {move || {
                                    let day = selected_day.get();
                                    let mut done: Vec<ChecklistItem> = items
                                        .get()
                                        .into_iter()
                                        .filter(|i| shows_on(i, day) && i.is_done_on(day))
                                        .collect();
                                    done.sort_by_key(|i| std::cmp::Reverse(i.completed_at));
                                    done.into_iter().map(|item| {
                                        let item_for_toggle = item.clone();
                                        let item_for_edit = item.clone();
                                        view! {
                                            <ItemRow
                                                item=item.clone()
                                                is_done_now=true
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
                    DialogState::Closed => EitherOf4::A(view! { <></> }),
                    DialogState::Add => EitherOf4::B(view! {
                        <ItemDialog
                            existing=None
                            prefill=None
                            initial_day=selected_day.get_untracked()
                            on_close=move || dialog.set(DialogState::Closed)
                            on_saved=move || { dialog.set(DialogState::Closed); refresh(); }
                        />
                    }),
                    DialogState::Edit(item) => EitherOf4::C(view! {
                        <ItemDialog
                            existing=Some(item)
                            prefill=None
                            initial_day=selected_day.get_untracked()
                            on_close=move || dialog.set(DialogState::Closed)
                            on_saved=move || { dialog.set(DialogState::Closed); refresh(); }
                        />
                    }),
                    DialogState::AddPrefilled(parsed) => EitherOf4::D(view! {
                        <ItemDialog
                            existing=None
                            prefill=Some(parsed)
                            initial_day=selected_day.get_untracked()
                            on_close=move || dialog.set(DialogState::Closed)
                            on_saved=move || { dialog.set(DialogState::Closed); refresh(); }
                        />
                    }),
                }}

                {move || if ai_open.get() {
                    Either::Left(view! {
                        <AiParsePromptDialog
                            surface=AiSurface::Checklist
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
                                        hint: Some("checklist".into()),
                                        now_local: Some(now_local),
                                    };
                                    match parse_event(&req).await {
                                        Ok(resp) => {
                                            if let Some(cl) = resp.checklist {
                                                ai_loading.set(false);
                                                ai_open.set(false);
                                                dialog.set(DialogState::AddPrefilled(cl));
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
fn ProgressBar(items: RwSignal<Vec<ChecklistItem>>, day: RwSignal<NaiveDate>) -> impl IntoView {
    view! {
        {move || {
            let d = day.get();
            let visible: Vec<_> = items.get().into_iter().filter(|i| shows_on(i, d)).collect();
            let total = visible.len();
            let done = visible.iter().filter(|i| i.is_done_on(d)).count();
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
    /// True when the row should render as "done for the displayed day".
    /// The parent picks based on `shows_on(day)` + `is_done_on(day)`.
    is_done_now: bool,
    on_toggle: impl Fn() + Send + Sync + 'static,
    on_edit: impl Fn() + Send + Sync + 'static,
) -> impl IntoView {
    let priority = item.priority_enum();
    let title_class = if is_done_now {
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
    let sched_item = item.clone();

    view! {
        <li class="flex items-start gap-3 bg-ultiq-cream/40 rounded-xl p-3 hover:bg-ultiq-cream/70 transition-colors">
            <button
                on:click=move |_| on_toggle()
                class=format!(
                    "mt-0.5 w-5 h-5 rounded border-2 flex items-center justify-center cursor-pointer flex-shrink-0 {}",
                    if is_done_now {
                        "bg-ultiq-indigo border-ultiq-indigo"
                    } else {
                        "border-ultiq-indigo/40 hover:border-ultiq-indigo bg-white"
                    }
                )
                aria-label=move || t("chk.toggle_complete")
            >
                <Show when=move || is_done_now>
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
                        {move || priority_label_chk(priority)}
                    </span>
                    <Show when=move || estimated.is_some()>
                        <span class="text-xs text-ultiq-indigo/50">
                            {move || {
                                let m = estimated.unwrap_or(0).to_string();
                                t_args("chk.est_min", &[("min", m.as_str())])
                            }}
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
                <Show when={
                    let it = sched_item.clone();
                    move || schedule_label(&it).is_some()
                }>
                    <p class="text-xs text-ultiq-indigo/50 mt-1">
                        {
                            let it = sched_item.clone();
                            move || schedule_label(&it).unwrap_or_default()
                        }
                    </p>
                </Show>
            </button>
        </li>
    }
}

/// Human-readable schedule summary — matches the Android `scheduleLabel`
/// shape so users see the same wording across platforms.
fn schedule_label(item: &ChecklistItem) -> Option<String> {
    if item.recurrence_days_mask != 0 {
        if item.recurrence_days_mask == 0b1111111 {
            return Some(t("chk.every_day"));
        }
        if item.recurrence_days_mask == 0b0111110 {
            return Some(t("chk.weekdays"));
        }
        if item.recurrence_days_mask == 0b1000001 {
            return Some(t("chk.weekends"));
        }
        let loc = current_locale().chrono();
        // Reference Sunday (2023-12-31); bit i (0=Sun … 6=Sat) → that weekday.
        let picked: Vec<String> = (0..7)
            .filter(|i| (item.recurrence_days_mask >> i) & 1 == 1)
            .map(|i| {
                (NaiveDate::from_ymd_opt(2023, 12, 31).unwrap() + Duration::days(i as i64))
                    .format_localized("%a", loc)
                    .to_string()
            })
            .collect();
        let days = picked.join(", ");
        return Some(t_args("chk.repeats", &[("days", days.as_str())]));
    }
    if item.show_until_due {
        let loc = current_locale().chrono();
        let date = item.due_date.format_localized("%b %d", loc).to_string();
        return Some(t_args("chk.due_summary", &[("date", date.as_str())]));
    }
    None
}

#[component]
fn ItemDialog(
    existing: Option<ChecklistItem>,
    /// §9.5 — AI-parsed initial state. Only consulted when `existing` is None.
    prefill: Option<ParsedChecklistFields>,
    initial_day: NaiveDate,
    on_close: impl Fn() + Send + Sync + Copy + 'static,
    on_saved: impl Fn() + Send + Sync + Copy + 'static,
) -> impl IntoView {
    let is_edit = existing.is_some();
    let item_id = existing.as_ref().map(|i| i.id.clone());

    // Resolution order: existing → prefill → blank/default.
    let title = RwSignal::new(
        existing
            .as_ref()
            .map(|i| i.title.clone())
            .or_else(|| prefill.as_ref().map(|p| p.title.clone()))
            .unwrap_or_default(),
    );
    let description = RwSignal::new(
        existing
            .as_ref()
            .and_then(|i| i.description.clone())
            .or_else(|| prefill.as_ref().and_then(|p| p.description.clone()))
            .unwrap_or_default(),
    );
    let due_date = RwSignal::new(
        existing
            .as_ref()
            .map(|i| i.due_date)
            .or_else(|| prefill.as_ref().map(|p| p.due_date))
            .unwrap_or(initial_day),
    );
    let estimated_minutes_str = RwSignal::new(
        existing
            .as_ref()
            .and_then(|i| i.estimated_minutes)
            .or_else(|| prefill.as_ref().and_then(|p| p.estimated_minutes))
            .map(|n| n.to_string())
            .unwrap_or_default(),
    );
    let priority = RwSignal::new(
        existing
            .as_ref()
            .map(|i| i.priority_enum())
            .or_else(|| {
                prefill
                    .as_ref()
                    .and_then(|p| p.priority)
                    .map(|n| Priority::from_i16(n as i16))
            })
            .unwrap_or(Priority::Medium),
    );

    // §1 — schedule mode + recurrence picker on web, mirroring the Android
    // ChecklistEditDialog. Existing items hydrate their mode from the row.
    let initial_mode = match existing.as_ref() {
        None => ScheduleMode::OneOff,
        Some(i) if i.recurrence_days_mask != 0 => ScheduleMode::Recurring,
        Some(i) if i.show_until_due => ScheduleMode::UntilDue,
        _ => ScheduleMode::OneOff,
    };
    let schedule_mode = RwSignal::new(initial_mode);
    let recurrence_mask = RwSignal::new(
        existing.as_ref().map(|i| i.recurrence_days_mask).unwrap_or(0),
    );
    let show_include_today_prompt = RwSignal::new(false);

    let submitting = RwSignal::new(false);
    let dialog_error = RwSignal::new(None::<String>);
    let scheduled_msg = RwSignal::new(None::<String>);

    let item_id_store = StoredValue::new(item_id);
    let existing_is_some = StoredValue::new(existing.is_some());
    // Captured at render with non-reactive `tu()`/untracked locale, so the
    // handlers + futures below emit them in the right language without
    // re-rendering (and stay Copy for closures used inside `<Show>`).
    let err_est_positive = StoredValue::new(tu("chk.err_est_positive"));
    let err_title_required = StoredValue::new(tu("cal.err_title_required"));
    let err_title_before_schedule = StoredValue::new(tu("chk.err_title_before_schedule"));
    let err_start_time = StoredValue::new(tu("chk.err_start_time"));
    let confirm_delete = StoredValue::new(tu("chk.confirm_delete"));
    let scheduled_tpl = StoredValue::new(tu("chk.scheduled_msg"));
    let sched_loc = current_locale_untracked().chrono();

    /// Inner save shared between the direct path and the "include today"
    /// follow-up. `also_today` is only honored on new recurring items.
    let do_save = move |also_today: bool| {
        let t = title.get_untracked();
        let est_str = estimated_minutes_str.get_untracked();
        let est: Option<i32> = if est_str.trim().is_empty() {
            None
        } else {
            match est_str.trim().parse::<i32>() {
                Ok(n) if n > 0 => Some(n),
                _ => {
                    dialog_error.set(Some(err_est_positive.get_value()));
                    submitting.set(false);
                    return;
                }
            }
        };
        let desc = description.get_untracked();
        let desc_opt = if desc.trim().is_empty() { None } else { Some(desc) };
        let dd = due_date.get_untracked();
        let pri = priority.get_untracked().to_i16();
        let mode = schedule_mode.get_untracked();
        let mask: i16 = if mode == ScheduleMode::Recurring {
            // Treat empty mask as "every day" so a recurring row is never
            // invisible — mirrors the Android coerce.
            let m = recurrence_mask.get_untracked();
            if m == 0 { 0b1111111 } else { m }
        } else {
            0
        };
        let show_until_due = mode == ScheduleMode::UntilDue;

        let id = item_id_store.get_value();
        let is_new = id.is_none();
        wasm_bindgen_futures::spawn_local(async move {
            let res = if let Some(id) = id {
                update_item(
                    &id,
                    &UpdateChecklistItem {
                        title: Some(t.clone()),
                        description: desc_opt.clone(),
                        due_date: Some(dd),
                        estimated_minutes: est,
                        priority: Some(pri),
                        completed: None,
                        recurrence_days_mask: Some(mask),
                        show_until_due: Some(show_until_due),
                        last_completed_epoch_day: None,
                    },
                )
                .await
                .map(|_| ())
            } else {
                create_item(&CreateChecklistItem {
                    title: t.clone(),
                    description: desc_opt.clone(),
                    due_date: dd,
                    estimated_minutes: est,
                    priority: pri,
                    recurrence_days_mask: mask,
                    show_until_due,
                })
                .await
                .map(|_| ())
            };
            match res {
                Ok(_) => {
                    if also_today && is_new {
                        // §4 — separate one-off row for today alongside the
                        // recurring schedule. Failure here is non-fatal; the
                        // main item already saved.
                        let _ = create_item(&CreateChecklistItem {
                            title: t,
                            description: desc_opt,
                            due_date: Local::now().date_naive(),
                            estimated_minutes: est,
                            priority: pri,
                            recurrence_days_mask: 0,
                            show_until_due: false,
                        })
                        .await;
                    }
                    on_saved();
                }
                Err(e) => {
                    dialog_error.set(Some(e.message));
                    submitting.set(false);
                }
            }
        });
    };

    let on_submit = move |ev: leptos::ev::SubmitEvent| {
        ev.prevent_default();
        if submitting.get_untracked() {
            return;
        }
        if title.get_untracked().trim().is_empty() {
            dialog_error.set(Some(err_title_required.get_value()));
            return;
        }

        submitting.set(true);
        dialog_error.set(None);

        // §4 — when the user creates a new recurring task whose mask
        // doesn't already cover today's weekday, pause and ask whether to
        // also create a one-off for today.
        let is_new = !existing_is_some.get_value();
        let is_recurring = schedule_mode.get_untracked() == ScheduleMode::Recurring;
        let mask_after_coerce: i16 = {
            let m = recurrence_mask.get_untracked();
            if m == 0 { 0b1111111 } else { m }
        };
        let today_bit: i16 = 1i16 << Local::now().date_naive().weekday().num_days_from_sunday();
        if is_new && is_recurring && (mask_after_coerce & today_bit) == 0 {
            show_include_today_prompt.set(true);
        } else {
            do_save(false);
        }
    };

    let on_schedule = move |_| {
        if submitting.get_untracked() { return; }
        let t = title.get_untracked();
        if t.trim().is_empty() {
            dialog_error.set(Some(err_title_before_schedule.get_value()));
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
                dialog_error.set(Some(err_start_time.get_value()));
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
            reminder_minutes: None,
        };

        submitting.set(true);
        dialog_error.set(None);
        scheduled_msg.set(None);
        wasm_bindgen_futures::spawn_local(async move {
            match create_calendar_event(&body).await {
                Ok(_) => {
                    let when = start_local
                        .format_localized("%a, %b %d, %H:%M", sched_loc)
                        .to_string();
                    scheduled_msg.set(Some(scheduled_tpl.get_value().replace("{when}", &when)));
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
                w.confirm_with_message(&confirm_delete.get_value())
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
                    {move || if is_edit { t("chk.edit_item") } else { t("chk.new_item") }}
                </h3>

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

                // §1 — schedule mode + recurrence picker. Mirrors the
                // Android dialog's One-off / Repeat / By due segments.
                <div>
                    <span class="text-sm font-medium text-ultiq-indigo">{move || t("chk.schedule")}</span>
                    <div class="mt-1 grid grid-cols-3 gap-1 bg-ultiq-indigo/5 rounded-lg p-1">
                        {[
                            (ScheduleMode::OneOff, "common.today"),
                            (ScheduleMode::Recurring, "chk.mode_repeat"),
                            (ScheduleMode::UntilDue, "chk.mode_by_due"),
                        ].into_iter().map(|(m, label)| {
                            view! {
                                <button
                                    type="button"
                                    on:click=move |_| schedule_mode.set(m)
                                    class=move || {
                                        let selected = schedule_mode.get() == m;
                                        if selected {
                                            "py-1.5 text-sm rounded-md bg-white text-ultiq-indigo font-medium shadow-sm cursor-pointer".to_string()
                                        } else {
                                            "py-1.5 text-sm rounded-md text-ultiq-indigo/70 hover:text-ultiq-indigo cursor-pointer".to_string()
                                        }
                                    }
                                >
                                    {move || t(label)}
                                </button>
                            }
                        }).collect_view()}
                    </div>
                    <p class="text-xs text-ultiq-indigo/60 mt-1">
                        {move || t(match schedule_mode.get() {
                            ScheduleMode::OneOff => "chk.mode_desc_oneoff",
                            ScheduleMode::Recurring => "chk.mode_desc_recurring",
                            ScheduleMode::UntilDue => "chk.mode_desc_untildue",
                        })}
                    </p>
                </div>

                <Show when=move || schedule_mode.get() == ScheduleMode::Recurring>
                    <div>
                        <span class="text-sm font-medium text-ultiq-indigo">{move || t("chk.days")}</span>
                        <div class="mt-1 flex gap-1">
                            {(0..7).map(|i| {
                                let bit_idx = i as i16;
                                view! {
                                    <button
                                        type="button"
                                        on:click=move |_| recurrence_mask.update(|m| {
                                            *m ^= 1i16 << bit_idx;
                                        })
                                        class=move || {
                                            let on = (recurrence_mask.get() >> bit_idx) & 1 == 1;
                                            if on {
                                                "flex-1 py-1.5 text-sm rounded-md bg-ultiq-indigo text-ultiq-cream font-medium cursor-pointer".to_string()
                                            } else {
                                                "flex-1 py-1.5 text-sm rounded-md border border-ultiq-indigo/20 text-ultiq-indigo hover:bg-ultiq-indigo/5 cursor-pointer".to_string()
                                            }
                                        }
                                    >
                                        {move || weekday_initial(i)}
                                    </button>
                                }
                            }).collect_view()}
                        </div>
                        <div class="mt-2 flex gap-2 text-xs">
                            <button
                                type="button"
                                on:click=move |_| recurrence_mask.set(0b1111111)
                                class="text-ultiq-indigo/60 hover:text-ultiq-indigo cursor-pointer"
                            >{move || t("chk.every_day")}</button>
                            <button
                                type="button"
                                on:click=move |_| recurrence_mask.set(0b0111110)
                                class="text-ultiq-indigo/60 hover:text-ultiq-indigo cursor-pointer"
                            >{move || t("chk.weekdays")}</button>
                            <button
                                type="button"
                                on:click=move |_| recurrence_mask.set(0b1000001)
                                class="text-ultiq-indigo/60 hover:text-ultiq-indigo cursor-pointer"
                            >{move || t("chk.weekends")}</button>
                        </div>
                    </div>
                </Show>

                <div class="grid grid-cols-2 gap-3">
                    <label class="block">
                        <span class="text-sm font-medium text-ultiq-indigo">
                            {move || t(match schedule_mode.get() {
                                ScheduleMode::OneOff => "chk.field_on",
                                ScheduleMode::Recurring => "chk.field_starts",
                                ScheduleMode::UntilDue => "chk.field_due",
                            })}
                        </span>
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
                        <span class="text-sm font-medium text-ultiq-indigo">{move || t("chk.est_minutes")}</span>
                        <input
                            type="number"
                            min="0"
                            class="mt-1 w-full px-3 py-2 border border-ultiq-indigo/20 rounded-lg focus:outline-none focus:ring-2 focus:ring-ultiq-indigo"
                            prop:value=move || estimated_minutes_str.get()
                            on:input=move |ev| estimated_minutes_str.set(event_target_value(&ev))
                            placeholder=move || t("chk.est_placeholder")
                        />
                    </label>
                </div>

                <label class="block">
                    <span class="text-sm font-medium text-ultiq-indigo">{move || t("cal.field_priority")}</span>
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
                            let pr = *p;
                            view! { <option value=v>{move || priority_label_chk(pr)}</option> }
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
                                {move || t("common.delete")}
                            </button>
                        </Show>
                        <button
                            type="button"
                            on:click=on_schedule
                            class="px-3 py-2 text-ultiq-indigo hover:bg-ultiq-indigo/5 rounded-lg cursor-pointer border border-ultiq-indigo/20"
                            prop:disabled=move || submitting.get()
                            title=move || t("chk.schedule_tooltip")
                        >
                            {move || t("chk.schedule_btn")}
                        </button>
                    </div>
                    <div class="flex gap-2 ml-auto">
                        <button
                            type="button"
                            on:click=move |_| on_close()
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

            // §4 — "Include today?" follow-up. Renders above the main
            // dialog when the user tries to save a new recurring task
            // whose mask doesn't already cover today.
            <Show when=move || show_include_today_prompt.get()>
                <div
                    class="fixed inset-0 bg-black/50 z-[60] flex items-center justify-center p-4"
                    on:click=|ev| ev.stop_propagation()
                >
                    <div class="bg-white rounded-2xl shadow-xl p-6 w-full max-w-sm space-y-4">
                        <h3 class="text-lg font-semibold text-ultiq-indigo">{move || t("chk.include_today_q")}</h3>
                        <p class="text-sm text-ultiq-indigo/70">
                            {move || {
                                let today = Local::now().date_naive();
                                let wd = today
                                    .format_localized("%A", current_locale().chrono())
                                    .to_string();
                                t_args("chk.include_today_body", &[("weekday", wd.as_str())])
                            }}
                        </p>
                        <div class="flex gap-2 justify-end">
                            <button
                                type="button"
                                on:click=move |_| {
                                    show_include_today_prompt.set(false);
                                    do_save(false);
                                }
                                class="px-3 py-2 text-ultiq-indigo hover:bg-ultiq-indigo/5 rounded-lg cursor-pointer"
                            >
                                {move || t("chk.include_no")}
                            </button>
                            <button
                                type="button"
                                on:click=move |_| {
                                    show_include_today_prompt.set(false);
                                    do_save(true);
                                }
                                class="px-3 py-2 bg-ultiq-indigo text-ultiq-cream rounded-lg font-medium hover:opacity-90 cursor-pointer"
                            >
                                {move || t("chk.include_yes")}
                            </button>
                        </div>
                    </div>
                </div>
            </Show>
        </div>
    }
}
