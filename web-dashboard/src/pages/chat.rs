// §9.x — Coach Chat page (web mirror of the Android ChatScreen).
//
// One active conversation per user; the server tracks it via the latest
// `ai_conversations.updated_at`. We load history on mount, append both the
// user turn and the assistant's reply after each send, auto-scroll to the
// bottom, and expose a "Start fresh" affordance that calls /ai/chat/reset.
//
// When the backend tool loop is enabled (AI_CHAT_TOOLS_ENABLED=true), each
// send may also surface read-tool status pills, committed write-tool
// confirmations with an undo affordance, and inline calendar proposal
// cards that the user confirms via Create / Cancel.

use leptos::ev::SubmitEvent;
use leptos::html::Div;
use leptos::prelude::*;
use leptos_meta::Title;
use wasm_bindgen::JsCast;

use crate::api::ai::{
    list_chat_messages, reset_chat, send_chat_message, ChatMessage, CommittedResource,
    ParsedCalendarFields, ToolInvocation,
};
use crate::api::calendar::{
    create_event, CreateCalendarEvent, EventCategory, EventPriority,
};
use crate::api::checklist::{delete_item, uncomplete_item};
use crate::components::layout::AppShell;

#[derive(Debug, Clone)]
enum ChatTurn {
    UserText(ChatMessage),
    AssistantText(ChatMessage),
    ToolStatus(ToolInvocation),
    CalendarProposal {
        invocation: ToolInvocation,
        state: ProposalState,
    },
}

#[derive(Debug, Clone, PartialEq, Eq)]
enum ProposalState {
    Pending,
    Creating,
    Created,
    Cancelled,
}

impl ChatTurn {
    fn key(&self) -> String {
        match self {
            ChatTurn::UserText(m) => format!("u:{}", m.id),
            ChatTurn::AssistantText(m) => format!("a:{}", m.id),
            ChatTurn::ToolStatus(t) => format!("t:{}", t.id),
            ChatTurn::CalendarProposal { invocation, .. } => format!("p:{}", invocation.id),
        }
    }
}

/// Snackbar-style banner shown after auto-committed writes. Persists until
/// dismissed or the undo button is tapped. Stays simple — one slot for now;
/// if Coach starts firing many writes per turn we'd queue them.
#[derive(Debug, Clone)]
struct UndoBanner {
    resource: CommittedResource,
    message: String,
}

#[component]
pub fn ChatPage() -> impl IntoView {
    let turns: RwSignal<Vec<ChatTurn>> = RwSignal::new(Vec::new());
    let input = RwSignal::new(String::new());
    let loading_history = RwSignal::new(true);
    let sending = RwSignal::new(false);
    let error = RwSignal::new(None::<String>);
    let confirm_reset = RwSignal::new(false);
    let undo_banner = RwSignal::new(None::<UndoBanner>);

    let bottom_ref = NodeRef::<Div>::new();

    let refresh_history = move || {
        loading_history.set(true);
        error.set(None);
        wasm_bindgen_futures::spawn_local(async move {
            match list_chat_messages().await {
                Ok(list) => {
                    let mapped = list
                        .into_iter()
                        .map(|m| {
                            if m.role == "user" {
                                ChatTurn::UserText(m)
                            } else {
                                ChatTurn::AssistantText(m)
                            }
                        })
                        .collect::<Vec<_>>();
                    turns.set(mapped);
                }
                Err(e) => error.set(Some(e.message)),
            }
            loading_history.set(false);
        });
    };

    Effect::new(move |_| refresh_history());

    // Auto-scroll to the bottom whenever the turn count changes.
    Effect::new(move |_| {
        let _ = turns.with(|m| m.len());
        if let Some(el) = bottom_ref.get() {
            if let Ok(html_el) = el.dyn_into::<web_sys::HtmlElement>() {
                html_el.scroll_into_view_with_bool(false);
            }
        }
    });

    let on_submit = move |ev: SubmitEvent| {
        ev.prevent_default();
        if sending.get_untracked() {
            return;
        }
        let text = input.get_untracked();
        let trimmed = text.trim();
        if trimmed.is_empty() {
            return;
        }

        // Optimistic insert: drop a temporary user bubble + flip into the
        // "thinking…" state immediately. Without this the bubble doesn't
        // appear until after the server round-trip lands, which makes the
        // first turn of a fresh chat feel hung.
        let payload = trimmed.to_string();
        input.set(String::new());
        let optimistic_id = format!("local-{}", web_sys::js_sys::Math::random());
        let optimistic = ChatMessage {
            id: optimistic_id.clone(),
            role: "user".to_string(),
            content: payload.clone(),
            created_at: chrono::Utc::now(),
        };
        turns.update(|t| t.push(ChatTurn::UserText(optimistic)));
        sending.set(true);
        error.set(None);

        let now_local = chrono::Local::now().to_rfc3339();
        wasm_bindgen_futures::spawn_local(async move {
            match send_chat_message(payload, Some(now_local)).await {
                Ok(resp) => {
                    let mut first_committed: Option<UndoBanner> = None;
                    turns.update(|t| {
                        // Replace the optimistic stub with the server-persisted
                        // user message (stable id, real created_at).
                        if let Some(idx) = t.iter().position(|x| matches!(
                            x, ChatTurn::UserText(m) if m.id == optimistic_id
                        )) {
                            t[idx] = ChatTurn::UserText(resp.user_message);
                        }
                        // Then append the tool turns and the assistant reply
                        // in order.
                        for inv in resp.tool_invocations.into_iter() {
                            if inv.status == "proposed" && inv.proposed_event.is_some() {
                                t.push(ChatTurn::CalendarProposal {
                                    invocation: inv,
                                    state: ProposalState::Pending,
                                });
                            } else {
                                if inv.committed {
                                    if let Some(res) = inv.committed_resource.clone() {
                                        first_committed = Some(UndoBanner {
                                            resource: res,
                                            message: inv.summary.clone(),
                                        });
                                    }
                                }
                                t.push(ChatTurn::ToolStatus(inv));
                            }
                        }
                        t.push(ChatTurn::AssistantText(resp.assistant_message));
                    });
                    if first_committed.is_some() {
                        undo_banner.set(first_committed);
                    }
                }
                Err(e) => {
                    // Roll the optimistic bubble back so the user can retry
                    // without a phantom message hanging in the conversation.
                    turns.update(|t| t.retain(|x| !matches!(
                        x, ChatTurn::UserText(m) if m.id == optimistic_id
                    )));
                    error.set(Some(e.message));
                }
            }
            sending.set(false);
        });
    };

    let do_reset = move || {
        sending.set(true);
        error.set(None);
        wasm_bindgen_futures::spawn_local(async move {
            match reset_chat().await {
                Ok(_) => {
                    turns.set(Vec::new());
                    undo_banner.set(None);
                }
                Err(e) => error.set(Some(e.message)),
            }
            sending.set(false);
        });
    };

    // ── Calendar proposal handlers ────────────────────────────────────────

    let confirm_proposal = move |inv_id: String| {
        let mut staged_event: Option<ParsedCalendarFields> = None;
        turns.update(|t| {
            if let Some(idx) = t.iter().position(|x| {
                matches!(x, ChatTurn::CalendarProposal { invocation, state }
                    if invocation.id == inv_id && *state == ProposalState::Pending)
            }) {
                if let ChatTurn::CalendarProposal { invocation, state } = &t[idx] {
                    staged_event = invocation.proposed_event.clone();
                    let new_t = ChatTurn::CalendarProposal {
                        invocation: invocation.clone(),
                        state: if staged_event.is_some() {
                            ProposalState::Creating
                        } else {
                            state.clone()
                        },
                    };
                    t[idx] = new_t;
                }
            }
        });
        let Some(fields) = staged_event else {
            return;
        };
        let body = parsed_to_create(&fields);
        wasm_bindgen_futures::spawn_local(async move {
            match create_event(&body).await {
                Ok(_) => {
                    turns.update(|t| {
                        if let Some(idx) = t.iter().position(|x| {
                            matches!(x, ChatTurn::CalendarProposal { invocation, .. }
                                if invocation.id == inv_id)
                        }) {
                            if let ChatTurn::CalendarProposal { invocation, .. } = &t[idx] {
                                t[idx] = ChatTurn::CalendarProposal {
                                    invocation: invocation.clone(),
                                    state: ProposalState::Created,
                                };
                            }
                        }
                    });
                }
                Err(e) => {
                    turns.update(|t| {
                        if let Some(idx) = t.iter().position(|x| {
                            matches!(x, ChatTurn::CalendarProposal { invocation, .. }
                                if invocation.id == inv_id)
                        }) {
                            if let ChatTurn::CalendarProposal { invocation, .. } = &t[idx] {
                                t[idx] = ChatTurn::CalendarProposal {
                                    invocation: invocation.clone(),
                                    state: ProposalState::Pending,
                                };
                            }
                        }
                    });
                    error.set(Some(format!("Couldn't create the event: {}", e.message)));
                }
            }
        });
    };

    let cancel_proposal = move |inv_id: String| {
        turns.update(|t| {
            if let Some(idx) = t.iter().position(|x| {
                matches!(x, ChatTurn::CalendarProposal { invocation, state }
                    if invocation.id == inv_id && *state == ProposalState::Pending)
            }) {
                if let ChatTurn::CalendarProposal { invocation, .. } = &t[idx] {
                    t[idx] = ChatTurn::CalendarProposal {
                        invocation: invocation.clone(),
                        state: ProposalState::Cancelled,
                    };
                }
            }
        });
    };

    // ── Undo committed write ──────────────────────────────────────────────

    let do_undo = move || {
        let Some(banner) = undo_banner.get_untracked() else {
            return;
        };
        undo_banner.set(None);
        wasm_bindgen_futures::spawn_local(async move {
            let res = match banner.resource.kind.as_str() {
                "checklist" => delete_item(&banner.resource.id).await.map(|_| ()),
                "checklist_complete" => uncomplete_item(&banner.resource.id).await.map(|_| ()),
                _ => Ok(()),
            };
            if let Err(e) = res {
                error.set(Some(format!(
                    "Undo failed: {} — you may need to remove it manually.",
                    e.message
                )));
            }
        });
    };

    view! {
        <Title text="Coach — Ultiq" />
        <AppShell>
            <div class="flex flex-col h-screen max-h-screen">
                <header class="flex items-center justify-between px-4 md:px-8 py-4 border-b border-ultiq-indigo/10 bg-white">
                    <h1 class="text-2xl font-bold text-ultiq-indigo">"Coach"</h1>
                    <button
                        on:click=move |_| confirm_reset.set(true)
                        class="text-sm px-3 py-1.5 border border-ultiq-indigo/20 text-ultiq-indigo rounded hover:bg-ultiq-indigo/5 cursor-pointer disabled:opacity-50"
                        prop:disabled=move || sending.get() || turns.with(|m| m.is_empty())
                    >
                        "Start fresh"
                    </button>
                </header>

                <Show when=move || error.get().is_some()>
                    <div class="bg-ultiq-red/5 text-ultiq-red px-4 py-2 text-sm">
                        {move || error.get().unwrap_or_default()}
                    </div>
                </Show>

                <main class="flex-1 overflow-auto px-4 md:px-8 py-4 bg-ultiq-cream">
                    {move || {
                        if loading_history.get() {
                            view! {
                                <div class="flex justify-center py-8 text-ultiq-indigo/50">"Loading…"</div>
                            }.into_any()
                        } else if turns.with(|m| m.is_empty()) {
                            view! {
                                <div class="max-w-md mx-auto mt-12 p-6 bg-white rounded-2xl shadow text-ultiq-indigo">
                                    <h2 class="text-lg font-semibold mb-2">"Talk to your coach"</h2>
                                    <p class="text-sm text-ultiq-indigo/70">
                                        "Ask about sleep, focus blocks, or weekly planning. The coach can look at your data, add checklist items, and draft calendar events you confirm before they land."
                                    </p>
                                </div>
                            }.into_any()
                        } else {
                            let confirm_h = confirm_proposal.clone();
                            let cancel_h = cancel_proposal.clone();
                            view! {
                                <div class="max-w-2xl mx-auto space-y-3">
                                    {move || {
                                        let list = turns.get();
                                        list.into_iter().map(|turn| {
                                            let k = turn.key();
                                            match turn {
                                                ChatTurn::UserText(m) => render_user_bubble(m, k),
                                                ChatTurn::AssistantText(m) => render_assistant_bubble(m, k),
                                                ChatTurn::ToolStatus(inv) => render_tool_pill(inv, k),
                                                ChatTurn::CalendarProposal { invocation, state } => {
                                                    let cf = confirm_h.clone();
                                                    let cc = cancel_h.clone();
                                                    render_proposal_card(invocation, state, k, cf, cc)
                                                }
                                            }
                                        }).collect_view()
                                    }}
                                    <Show when=move || sending.get()>
                                        <div class="flex coach-turn-in">
                                            <div class="mr-auto bg-white text-ultiq-indigo/60 rounded-2xl rounded-bl-sm shadow px-4 py-2.5 text-sm italic">
                                                "thinking…"
                                            </div>
                                        </div>
                                    </Show>
                                    <div node_ref=bottom_ref />
                                </div>
                            }.into_any()
                        }
                    }}
                </main>

                // Undo banner — single slot, appears above the composer
                // when the Coach committed a write.
                <Show when=move || undo_banner.get().is_some()>
                    <div class="px-4 md:px-8 py-2 bg-ultiq-indigo/5 border-t border-ultiq-indigo/10 flex items-center justify-between text-sm text-ultiq-indigo">
                        <span>{move || undo_banner.get().map(|b| b.message).unwrap_or_default()}</span>
                        <div class="flex items-center gap-2">
                            <button
                                on:click=move |_| do_undo()
                                class="font-semibold underline hover:opacity-80 cursor-pointer"
                            >
                                "Undo"
                            </button>
                            <button
                                on:click=move |_| undo_banner.set(None)
                                class="text-ultiq-indigo/50 hover:opacity-80 cursor-pointer"
                                aria-label="Dismiss"
                            >
                                "✕"
                            </button>
                        </div>
                    </div>
                </Show>

                <form
                    on:submit=on_submit
                    class="px-4 md:px-8 py-3 border-t border-ultiq-indigo/10 bg-white flex gap-2"
                >
                    <input
                        type="text"
                        class="flex-1 px-4 py-2.5 border border-ultiq-indigo/20 rounded-lg focus:outline-none focus:ring-2 focus:ring-ultiq-indigo text-ultiq-indigo"
                        placeholder="Message your coach…"
                        prop:value=move || input.get()
                        on:input=move |ev| input.set(event_target_value(&ev))
                        prop:disabled=move || sending.get()
                    />
                    <button
                        type="submit"
                        class="px-4 py-2.5 bg-ultiq-indigo text-ultiq-cream rounded-lg font-medium hover:opacity-90 disabled:opacity-50 cursor-pointer"
                        prop:disabled=move || sending.get() || input.with(|s| s.trim().is_empty())
                    >
                        "Send"
                    </button>
                </form>
            </div>

            // Confirm-reset modal
            <Show when=move || confirm_reset.get()>
                <div
                    class="fixed inset-0 bg-black/40 z-50 flex items-center justify-center p-4"
                    on:click=move |_| confirm_reset.set(false)
                >
                    <div
                        on:click=|ev| ev.stop_propagation()
                        class="bg-white rounded-2xl shadow-xl p-6 w-full max-w-md space-y-4"
                    >
                        <h3 class="text-lg font-semibold text-ultiq-indigo">"Start a new chat?"</h3>
                        <p class="text-sm text-ultiq-indigo/70">
                            "Your current conversation will be archived (we keep it for your records but the coach won't see it from here on)."
                        </p>
                        <div class="flex justify-end gap-2">
                            <button
                                on:click=move |_| confirm_reset.set(false)
                                class="px-4 py-2 text-ultiq-indigo hover:bg-ultiq-indigo/5 rounded-lg cursor-pointer"
                            >
                                "Cancel"
                            </button>
                            <button
                                on:click=move |_| { confirm_reset.set(false); do_reset(); }
                                class="px-4 py-2 bg-ultiq-indigo text-ultiq-cream rounded-lg font-medium hover:opacity-90 cursor-pointer"
                            >
                                "Start fresh"
                            </button>
                        </div>
                    </div>
                </div>
            </Show>
        </AppShell>
    }
}

// ── Render helpers ────────────────────────────────────────────────────────

fn render_user_bubble(m: ChatMessage, key: String) -> AnyView {
    view! {
        <div class="flex coach-turn-in" data-key=key>
            <div class="ml-auto bg-ultiq-indigo text-ultiq-cream rounded-2xl rounded-br-sm max-w-[80%] px-4 py-2.5 whitespace-pre-wrap break-words">
                {m.content}
            </div>
        </div>
    }
    .into_any()
}

fn render_assistant_bubble(m: ChatMessage, key: String) -> AnyView {
    let segments = parse_inline_markdown(&m.content);
    view! {
        <div class="flex coach-turn-in" data-key=key>
            <div class="mr-auto bg-white text-ultiq-indigo rounded-2xl rounded-bl-sm shadow max-w-[80%] px-4 py-2.5 whitespace-pre-wrap break-words">
                <span class="md">
                    {segments.into_iter().map(render_segment).collect_view()}
                </span>
            </div>
        </div>
    }
    .into_any()
}

/// Inline-Markdown token used by the assistant-bubble renderer. Server
/// forbids tables/headers/etc., so we only need to recognise the three
/// inline forms (bold, italic, code) — everything else passes through as
/// plain text.
#[derive(Debug, Clone)]
enum MdSegment {
    Plain(String),
    Bold(String),
    Italic(String),
    Code(String),
}

fn parse_inline_markdown(text: &str) -> Vec<MdSegment> {
    let bytes = text.as_bytes();
    let n = bytes.len();
    let mut out: Vec<MdSegment> = Vec::new();
    let mut plain = String::new();
    let mut i = 0;
    let flush_plain = |plain: &mut String, out: &mut Vec<MdSegment>| {
        if !plain.is_empty() {
            out.push(MdSegment::Plain(std::mem::take(plain)));
        }
    };
    while i < n {
        // **bold**
        if i + 1 < n && bytes[i] == b'*' && bytes[i + 1] == b'*' {
            if let Some(end_rel) = text[i + 2..].find("**") {
                let end = i + 2 + end_rel;
                flush_plain(&mut plain, &mut out);
                out.push(MdSegment::Bold(text[i + 2..end].to_string()));
                i = end + 2;
                continue;
            }
        }
        // *italic* (skip when adjacent to another star — that's bold)
        if bytes[i] == b'*' {
            let prev = if i == 0 { 0 } else { bytes[i - 1] };
            let next = if i + 1 < n { bytes[i + 1] } else { 0 };
            if prev != b'*' && next != b'*' {
                if let Some(end_rel) = text[i + 1..].find('*') {
                    let end = i + 1 + end_rel;
                    flush_plain(&mut plain, &mut out);
                    out.push(MdSegment::Italic(text[i + 1..end].to_string()));
                    i = end + 1;
                    continue;
                }
            }
        }
        // `code`
        if bytes[i] == b'`' {
            if let Some(end_rel) = text[i + 1..].find('`') {
                let end = i + 1 + end_rel;
                flush_plain(&mut plain, &mut out);
                out.push(MdSegment::Code(text[i + 1..end].to_string()));
                i = end + 1;
                continue;
            }
        }
        plain.push(text[i..].chars().next().unwrap());
        i += text[i..].chars().next().unwrap().len_utf8();
    }
    flush_plain(&mut plain, &mut out);
    out
}

fn render_segment(seg: MdSegment) -> AnyView {
    match seg {
        MdSegment::Plain(t) => view! { <>{t}</> }.into_any(),
        MdSegment::Bold(t) => view! { <strong>{t}</strong> }.into_any(),
        MdSegment::Italic(t) => view! { <em>{t}</em> }.into_any(),
        MdSegment::Code(t) => view! { <code>{t}</code> }.into_any(),
    }
}

fn render_tool_pill(inv: ToolInvocation, key: String) -> AnyView {
    let is_read = inv.name.starts_with("get_");
    let classes = if inv.status == "error" {
        "mr-auto bg-ultiq-red/10 text-ultiq-red"
    } else if inv.committed {
        "mr-auto bg-emerald-100 text-emerald-800"
    } else if is_read {
        "mr-auto bg-ultiq-indigo/5 text-ultiq-indigo/70"
    } else {
        "mr-auto bg-ultiq-indigo/5 text-ultiq-indigo/70"
    };
    let icon = if inv.status == "error" {
        "⚠"
    } else if inv.committed {
        "✓"
    } else if is_read {
        "⌕"
    } else {
        "·"
    };
    view! {
        <div class="flex coach-turn-in" data-key=key>
            <div class={format!("{} max-w-[80%] px-3 py-1.5 rounded-full text-xs flex items-center gap-1.5", classes)}>
                <span aria-hidden="true">{icon}</span>
                <span>{inv.summary}</span>
            </div>
        </div>
    }
    .into_any()
}

fn render_proposal_card<F1, F2>(
    invocation: ToolInvocation,
    state: ProposalState,
    key: String,
    on_create: F1,
    on_cancel: F2,
) -> AnyView
where
    F1: Fn(String) + Clone + 'static,
    F2: Fn(String) + Clone + 'static,
{
    let event = match invocation.proposed_event.clone() {
        Some(e) => e,
        None => return ().into_any(),
    };
    let inv_id_for_create = invocation.id.clone();
    let inv_id_for_cancel = invocation.id.clone();
    let body = format_proposal_body(&event);
    view! {
        <div class="flex coach-turn-in" data-key=key>
            <div class="mr-auto max-w-[80%] bg-white border-2 border-ultiq-indigo/20 rounded-2xl p-4 space-y-2">
                <div class="text-xs uppercase tracking-wide font-semibold text-ultiq-indigo/70">
                    "Proposed event"
                </div>
                <div class="text-base font-semibold text-ultiq-indigo">{event.title.clone()}</div>
                <div class="text-sm text-ultiq-indigo/70">{body}</div>
                {match state {
                    ProposalState::Pending => view! {
                        <div class="flex gap-2 pt-2">
                            <button
                                class="flex-1 px-3 py-1.5 border border-ultiq-indigo/20 text-ultiq-indigo rounded-lg text-sm hover:bg-ultiq-indigo/5 cursor-pointer"
                                on:click=move |_| on_cancel(inv_id_for_cancel.clone())
                            >
                                "Cancel"
                            </button>
                            <button
                                class="flex-1 px-3 py-1.5 bg-ultiq-indigo text-ultiq-cream rounded-lg text-sm font-medium hover:opacity-90 cursor-pointer"
                                on:click=move |_| on_create(inv_id_for_create.clone())
                            >
                                "Create"
                            </button>
                        </div>
                    }.into_any(),
                    ProposalState::Creating => view! {
                        <div class="text-sm text-ultiq-indigo/60 pt-1">"Creating…"</div>
                    }.into_any(),
                    ProposalState::Created => view! {
                        <div class="text-sm text-emerald-700 font-medium pt-1">"✓ Added to your calendar"</div>
                    }.into_any(),
                    ProposalState::Cancelled => view! {
                        <div class="text-sm text-ultiq-indigo/50 pt-1">"Cancelled"</div>
                    }.into_any(),
                }}
            </div>
        </div>
    }
    .into_any()
}

fn format_proposal_body(fields: &ParsedCalendarFields) -> String {
    // Display in the user's local TZ via chrono::Local conversion. Failing
    // to parse falls back to the raw UTC strings.
    let start_local = fields.start_time.with_timezone(&chrono::Local);
    let end_local = fields.end_time.with_timezone(&chrono::Local);
    let same_day = start_local.date_naive() == end_local.date_naive();
    if same_day {
        format!(
            "{} · {} – {} · {}",
            start_local.format("%a %d %b"),
            start_local.format("%H:%M"),
            end_local.format("%H:%M"),
            fields.category
        )
    } else {
        format!(
            "{} {} → {} {} · {}",
            start_local.format("%a %d %b"),
            start_local.format("%H:%M"),
            end_local.format("%a %d %b"),
            end_local.format("%H:%M"),
            fields.category
        )
    }
}

fn parsed_to_create(fields: &ParsedCalendarFields) -> CreateCalendarEvent {
    let category = parse_category(&fields.category).unwrap_or(EventCategory::Other);
    let priority = parse_priority(&fields.priority).unwrap_or(EventPriority::Medium);
    CreateCalendarEvent {
        title: fields.title.clone(),
        description: fields.description.clone(),
        start_time: fields.start_time,
        end_time: fields.end_time,
        category,
        priority,
        is_recurring: false,
        recurrence_rule: None,
        color: None,
        is_done: Some(false),
        // v2.13.4 — Carry the AI-parsed reminder offsets through. Null
        // means "no explicit pref" → server stores NULL → scheduler
        // applies its default. Non-null = honour what Coach extracted.
        reminder_minutes: fields.reminder_minutes.clone(),
    }
}

fn parse_category(s: &str) -> Option<EventCategory> {
    // Backend emits lowercase ("study"/"project"/…); the web enum
    // deserialises via rename_all = lowercase, so route through serde.
    serde_json::from_value(serde_json::Value::String(s.to_string())).ok()
}

fn parse_priority(s: &str) -> Option<EventPriority> {
    serde_json::from_value(serde_json::Value::String(s.to_string())).ok()
}

