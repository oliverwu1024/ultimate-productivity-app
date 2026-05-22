// §9.6 — Coach Chat page (web mirror of the Android ChatScreen).
//
// Single active conversation per user; server tracks it via the latest
// `ai_conversations.updated_at`. We load history on mount, append both
// turns after each send, auto-scroll to the bottom on new messages, and
// expose a "Start fresh" affordance that calls POST /ai/chat/reset.

use leptos::ev::SubmitEvent;
use leptos::html::Div;
use leptos::prelude::*;
use leptos_meta::Title;
use wasm_bindgen::JsCast;

use crate::api::ai::{list_chat_messages, reset_chat, send_chat_message, ChatMessage};
use crate::components::layout::AppShell;

#[component]
pub fn ChatPage() -> impl IntoView {
    let messages: RwSignal<Vec<ChatMessage>> = RwSignal::new(Vec::new());
    let input = RwSignal::new(String::new());
    let loading_history = RwSignal::new(true);
    let sending = RwSignal::new(false);
    let error = RwSignal::new(None::<String>);
    let confirm_reset = RwSignal::new(false);

    // Bottom-anchor ref for auto-scroll. Re-fires every time the message
    // count changes so the user always sees the latest turn.
    let bottom_ref = NodeRef::<Div>::new();

    let refresh_history = move || {
        loading_history.set(true);
        error.set(None);
        wasm_bindgen_futures::spawn_local(async move {
            match list_chat_messages().await {
                Ok(list) => messages.set(list),
                Err(e) => error.set(Some(e.message)),
            }
            loading_history.set(false);
        });
    };

    Effect::new(move |_| refresh_history());

    // Auto-scroll: depend on message count so we run after every append.
    Effect::new(move |_| {
        let _ = messages.with(|m| m.len());
        if let Some(el) = bottom_ref.get() {
            // Cast Element to HtmlElement so scroll_into_view is available.
            if let Ok(html_el) = el.dyn_into::<web_sys::HtmlElement>() {
                html_el.scroll_into_view_with_bool(false);
            }
        }
    });

    let on_submit = move |ev: SubmitEvent| {
        ev.prevent_default();
        if sending.get_untracked() { return; }
        let text = input.get_untracked();
        let trimmed = text.trim();
        if trimmed.is_empty() { return; }

        sending.set(true);
        error.set(None);
        let payload = trimmed.to_string();
        input.set(String::new());
        wasm_bindgen_futures::spawn_local(async move {
            match send_chat_message(payload).await {
                Ok(resp) => {
                    messages.update(|m| {
                        m.push(resp.user_message);
                        m.push(resp.assistant_message);
                    });
                }
                Err(e) => error.set(Some(e.message)),
            }
            sending.set(false);
        });
    };

    let do_reset = move || {
        sending.set(true);
        error.set(None);
        wasm_bindgen_futures::spawn_local(async move {
            match reset_chat().await {
                Ok(_) => messages.set(Vec::new()),
                Err(e) => error.set(Some(e.message)),
            }
            sending.set(false);
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
                        prop:disabled=move || sending.get() || messages.with(|m| m.is_empty())
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
                        } else if messages.with(|m| m.is_empty()) {
                            view! {
                                <div class="max-w-md mx-auto mt-12 p-6 bg-white rounded-2xl shadow text-ultiq-indigo">
                                    <h2 class="text-lg font-semibold mb-2">"Talk to your coach"</h2>
                                    <p class="text-sm text-ultiq-indigo/70">
                                        "Ask about sleep habits, focus blocks, weekly planning, or anything you'd want a thoughtful friend to think through with you."
                                    </p>
                                </div>
                            }.into_any()
                        } else {
                            view! {
                                <div class="max-w-2xl mx-auto space-y-3">
                                    {move || messages.get().into_iter().map(|m| {
                                        let is_user = m.role == "user";
                                        let bubble_class = if is_user {
                                            "ml-auto bg-ultiq-indigo text-ultiq-cream rounded-2xl rounded-br-sm"
                                        } else {
                                            "mr-auto bg-white text-ultiq-indigo rounded-2xl rounded-bl-sm shadow"
                                        };
                                        view! {
                                            <div class="flex">
                                                <div class={format!("{} max-w-[80%] px-4 py-2.5 whitespace-pre-wrap break-words", bubble_class)}>
                                                    {m.content}
                                                </div>
                                            </div>
                                        }
                                    }).collect_view()}
                                    <Show when=move || sending.get()>
                                        <div class="flex">
                                            <div class="mr-auto bg-white text-ultiq-indigo/60 rounded-2xl rounded-bl-sm shadow px-4 py-2.5 text-sm italic">
                                                "thinking…"
                                            </div>
                                        </div>
                                    </Show>
                                    // Anchor used by the auto-scroll effect.
                                    <div node_ref=bottom_ref />
                                </div>
                            }.into_any()
                        }
                    }}
                </main>

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
