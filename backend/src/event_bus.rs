use std::collections::HashMap;
use std::sync::{Arc, Mutex};

use serde::Serialize;
use tokio::sync::broadcast;
use uuid::Uuid;

use crate::models::calendar::CalendarEvent;

/// One per-user channel, multiplexed across all that user's connected clients.
/// Single-task in-process for now. Swap for Redis pub/sub when ECS desired_count > 1.
const CHANNEL_CAPACITY: usize = 64;

#[derive(Debug, Clone, Serialize)]
#[serde(tag = "type", content = "data")]
pub enum SyncEvent {
    CalendarCreated(CalendarEvent),
    CalendarUpdated(CalendarEvent),
    CalendarDeleted { id: Uuid },
}

#[derive(Clone, Default)]
pub struct EventBus {
    inner: Arc<Mutex<HashMap<Uuid, broadcast::Sender<SyncEvent>>>>,
}

impl EventBus {
    pub fn new() -> Self {
        Self::default()
    }

    /// Subscribe a connected client to its user's event stream.
    /// Creates the per-user channel on first subscribe.
    pub fn subscribe(&self, user_id: Uuid) -> broadcast::Receiver<SyncEvent> {
        let mut map = self.inner.lock().expect("event bus lock poisoned");
        let sender = map.entry(user_id).or_insert_with(|| {
            let (tx, _) = broadcast::channel(CHANNEL_CAPACITY);
            tx
        });
        sender.subscribe()
    }

    /// Publish an event to all connected clients of `user_id`.
    /// No-op if no client is currently subscribed.
    pub fn publish(&self, user_id: Uuid, event: SyncEvent) {
        let map = self.inner.lock().expect("event bus lock poisoned");
        if let Some(sender) = map.get(&user_id) {
            // send returns Err if there are no active receivers — that's fine,
            // just means no clients are connected for that user right now.
            let _ = sender.send(event);
        }
    }
}
