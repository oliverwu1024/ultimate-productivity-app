use leptos::prelude::*;
use serde::Deserialize;
use wasm_bindgen::prelude::*;
use wasm_bindgen::JsCast;
use web_sys::{EventSource, MessageEvent};

use crate::api::client::api_base_url;
use crate::api::calendar::CalendarEvent;
use crate::auth::AuthContext;

#[derive(Debug, Clone, Deserialize)]
#[serde(tag = "type", content = "data")]
pub enum SyncEvent {
    CalendarCreated(CalendarEvent),
    CalendarUpdated(CalendarEvent),
    #[serde(rename = "CalendarDeleted")]
    CalendarDeleted(CalendarDeletedPayload),
}

#[derive(Debug, Clone, Deserialize)]
pub struct CalendarDeletedPayload {
    pub id: String,
}

#[derive(Clone, Copy)]
pub struct SseStream {
    pub last_event: RwSignal<Option<SyncEvent>>,
    pub connected: RwSignal<bool>,
}

pub fn provide_sse() {
    let last_event = RwSignal::new(None::<SyncEvent>);
    let connected = RwSignal::new(false);
    provide_context(SseStream { last_event, connected });
}

pub fn use_sse() -> SseStream {
    expect_context::<SseStream>()
}

/// Stored across app lifetime; closures keep their JS handles alive.
struct SseConnection {
    _source: EventSource,
    _on_message: Closure<dyn FnMut(MessageEvent)>,
    _on_error: Closure<dyn FnMut(web_sys::Event)>,
    _on_open: Closure<dyn FnMut(web_sys::Event)>,
}

thread_local! {
    static CURRENT: std::cell::RefCell<Option<SseConnection>> = const { std::cell::RefCell::new(None) };
}

pub fn connect_for_current_user() {
    let Some(token) = AuthContext::token() else { return };
    let stream = use_sse();
    let url = format!("{}/sync/events?token={}", api_base_url(), token);

    let source = match EventSource::new(&url) {
        Ok(s) => s,
        Err(_) => return,
    };

    let connected_signal = stream.connected;
    let on_open = Closure::<dyn FnMut(_)>::new(move |_ev: web_sys::Event| {
        connected_signal.set(true);
    });
    source.set_onopen(Some(on_open.as_ref().unchecked_ref()));

    let last_event_signal = stream.last_event;
    let on_message = Closure::<dyn FnMut(_)>::new(move |ev: MessageEvent| {
        let Some(data) = ev.data().as_string() else { return };
        match serde_json::from_str::<SyncEvent>(&data) {
            Ok(event) => last_event_signal.set(Some(event)),
            Err(_e) => {
                // ignore — keep-alive ticks etc.
            }
        }
    });
    source.set_onmessage(Some(on_message.as_ref().unchecked_ref()));

    let connected_for_err = stream.connected;
    let on_error = Closure::<dyn FnMut(_)>::new(move |_ev: web_sys::Event| {
        connected_for_err.set(false);
        // Browser EventSource auto-reconnects; nothing else to do.
    });
    source.set_onerror(Some(on_error.as_ref().unchecked_ref()));

    let conn = SseConnection {
        _source: source,
        _on_message: on_message,
        _on_error: on_error,
        _on_open: on_open,
    };
    CURRENT.with(|cell| *cell.borrow_mut() = Some(conn));
}

pub fn disconnect() {
    CURRENT.with(|cell| {
        if let Some(conn) = cell.borrow_mut().take() {
            conn._source.close();
        }
    });
}
