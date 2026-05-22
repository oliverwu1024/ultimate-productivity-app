// §9.5 — "Quick add with AI" prompt dialog shared between Calendar and
// Checklist. Purely a controlled view: the caller owns loading + error
// state and the response, and handles what to do once parsing succeeds.

use leptos::ev::SubmitEvent;
use leptos::prelude::*;

/// Which surface the user is on. Drives the title and placeholder text and
/// is passed back to the caller to forward to the backend as a hint.
#[derive(Clone, Copy, PartialEq, Eq)]
pub enum AiSurface {
    Calendar,
    Checklist,
}

impl AiSurface {
    fn title(self) -> &'static str {
        match self {
            Self::Calendar => "Quick add event",
            Self::Checklist => "Quick add task",
        }
    }

    fn placeholder(self) -> &'static str {
        match self {
            Self::Calendar => "e.g. lunch with Sarah tomorrow at 1pm",
            Self::Checklist => "e.g. buy groceries on friday",
        }
    }
}

#[component]
pub fn AiParsePromptDialog(
    surface: AiSurface,
    loading: RwSignal<bool>,
    error: RwSignal<Option<String>>,
    on_submit: impl Fn(String) + Send + Sync + Copy + 'static,
    on_close: impl Fn() + Send + Sync + Copy + 'static,
) -> impl IntoView {
    let text = RwSignal::new(String::new());

    let on_form_submit = move |ev: SubmitEvent| {
        ev.prevent_default();
        if loading.get_untracked() {
            return;
        }
        let t = text.get_untracked();
        let trimmed = t.trim();
        if trimmed.is_empty() {
            return;
        }
        on_submit(trimmed.to_string());
    };

    view! {
        <div
            class="fixed inset-0 bg-black/40 z-50 flex items-center justify-center p-4"
            on:click=move |_| if !loading.get_untracked() { on_close() }
        >
            <form
                on:submit=on_form_submit
                on:click=|ev| ev.stop_propagation()
                class="bg-white rounded-2xl shadow-xl p-6 w-full max-w-md space-y-4"
            >
                <h3 class="text-xl font-semibold text-ultiq-indigo">
                    {surface.title()}
                </h3>
                <p class="text-sm text-ultiq-indigo/60">
                    "Type a sentence and we'll fill in the details for you to review."
                </p>

                <textarea
                    autofocus
                    rows="3"
                    placeholder=surface.placeholder()
                    class="w-full px-3 py-2 border border-ultiq-indigo/20 rounded-lg focus:outline-none focus:ring-2 focus:ring-ultiq-indigo text-ultiq-indigo"
                    prop:value=move || text.get()
                    on:input=move |ev| text.set(event_target_value(&ev))
                    prop:disabled=move || loading.get()
                />

                <Show when=move || error.get().is_some()>
                    <p class="text-sm text-ultiq-red bg-ultiq-red/5 px-3 py-2 rounded">
                        {move || error.get().unwrap_or_default()}
                    </p>
                </Show>

                <div class="flex justify-end gap-2 pt-1">
                    <button
                        type="button"
                        on:click=move |_| on_close()
                        class="px-4 py-2 text-ultiq-indigo hover:bg-ultiq-indigo/5 rounded-lg cursor-pointer"
                        prop:disabled=move || loading.get()
                    >
                        "Cancel"
                    </button>
                    <button
                        type="submit"
                        class="px-4 py-2 bg-ultiq-indigo text-ultiq-cream rounded-lg font-medium hover:opacity-90 disabled:opacity-50 cursor-pointer"
                        prop:disabled=move || loading.get() || text.get().trim().is_empty()
                    >
                        {move || if loading.get() { "Generating…" } else { "Generate" }}
                    </button>
                </div>
            </form>
        </div>
    }
}
