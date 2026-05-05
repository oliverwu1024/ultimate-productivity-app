use gloo_net::http::Request;
use leptos::prelude::*;
use serde::Deserialize;
use wasm_bindgen::prelude::*;
use wasm_bindgen::JsCast;
use web_sys::{EventSource, MessageEvent};

use crate::api::client::api_base_url;
use crate::api::calendar::CalendarEvent;
use crate::api::checklist::ChecklistItem;
use crate::api::sessions::ProductivitySession;
use crate::api::sleep::SleepRecord;
use crate::auth::AuthContext;

#[derive(Debug, Clone, Deserialize)]
#[serde(tag = "type", content = "data")]
pub enum SyncEvent {
    CalendarCreated(CalendarEvent),
    CalendarUpdated(CalendarEvent),
    #[serde(rename = "CalendarDeleted")]
    CalendarDeleted(IdPayload),
    ChecklistCreated(ChecklistItem),
    ChecklistUpdated(ChecklistItem),
    #[serde(rename = "ChecklistDeleted")]
    ChecklistDeleted(IdPayload),
    SleepCreated(SleepRecord),
    SleepUpdated(SleepRecord),
    #[serde(rename = "SleepDeleted")]
    SleepDeleted(IdPayload),
    SessionCreated(ProductivitySession),
    SessionUpdated(ProductivitySession),
    #[serde(rename = "SessionDeleted")]
    SessionDeleted(IdPayload),
}

#[derive(Debug, Clone, Deserialize)]
pub struct IdPayload {
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

#[derive(Deserialize)]
struct TicketResponse {
    ticket: String,
}

/// Trade the stored JWT for a single-use SSE ticket so the long-lived bearer
/// token never appears in the EventSource URL (which would otherwise land in
/// browser history, CloudFront access logs, and Referer headers).
async fn fetch_ticket() -> Option<String> {
    let token = AuthContext::token()?;
    let url = format!("{}/sync/ticket", api_base_url());
    let resp = Request::post(&url)
        .header("Authorization", &format!("Bearer {}", token))
        .send()
        .await
        .ok()?;
    if !(200..300).contains(&resp.status()) {
        return None;
    }
    let body: TicketResponse = resp.json().await.ok()?;
    Some(body.ticket)
}

pub fn connect_for_current_user() {
    if AuthContext::token().is_none() {
        return;
    }
    // Capture the SSE context signals NOW, while we're still inside the
    // reactive owner. `expect_context` would not be reliable from inside
    // the spawned future.
    let stream = use_sse();
    wasm_bindgen_futures::spawn_local(async move {
        let Some(ticket) = fetch_ticket().await else { return };
        open_event_source(stream, ticket);
    });
}

fn open_event_source(stream: SseStream, ticket: String) {
    let url = format!("{}/sync/events?ticket={}", api_base_url(), ticket);

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
                // ignore — keep-alive ticks, lagged markers, etc.
            }
        }
    });
    source.set_onmessage(Some(on_message.as_ref().unchecked_ref()));

    let connected_for_err = stream.connected;
    // The single-use ticket is spent the moment the stream opens. The
    // browser's built-in EventSource auto-retry would fire `on_error`
    // repeatedly during the 5-second backoff and each fire would schedule
    // a fresh reconnect — a storm of POST /sync/ticket requests that
    // tripped the global rate limit. Close the source SYNCHRONOUSLY here
    // so auto-retry stops, and use CURRENT.take() as a debounce: only
    // the first on_error after a connection schedules the next attempt.
    let on_error = Closure::<dyn FnMut(_)>::new(move |_ev: web_sys::Event| {
        connected_for_err.set(false);
        let prior = CURRENT.with(|cell| cell.borrow_mut().take());
        let Some(conn) = prior else {
            // A previous on_error already started the reconnect path —
            // drop this duplicate fire.
            return;
        };
        conn._source.close();
        wasm_bindgen_futures::spawn_local(async move {
            gloo_timers::future::TimeoutFuture::new(5_000).await;
            let Some(ticket) = fetch_ticket().await else { return };
            open_event_source(stream, ticket);
        });
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
