use std::collections::HashMap;
use std::sync::{Arc, Mutex};

use serde::Serialize;
use tokio::sync::broadcast;
use uuid::Uuid;

use crate::models::alarm::{Alarm, AlarmEvent};
use crate::models::calendar::CalendarEvent;
use crate::models::checklist::ChecklistItem;
use crate::models::session::ProductivitySession;
use crate::models::sleep::SleepRecord;

/// One per-user channel, multiplexed across all that user's connected clients.
/// Single-task in-process for now. Swap for Redis pub/sub when ECS desired_count > 1.
const CHANNEL_CAPACITY: usize = 64;

#[derive(Debug, Clone, Serialize)]
#[serde(tag = "type", content = "data")]
pub enum SyncEvent {
    CalendarCreated(CalendarEvent),
    CalendarUpdated(CalendarEvent),
    CalendarDeleted { id: Uuid },
    ChecklistCreated(ChecklistItem),
    ChecklistUpdated(ChecklistItem),
    ChecklistDeleted { id: Uuid },
    SleepCreated(SleepRecord),
    SleepUpdated(SleepRecord),
    SleepDeleted { id: Uuid },
    SessionCreated(ProductivitySession),
    SessionUpdated(ProductivitySession),
    SessionDeleted { id: Uuid },
    AlarmCreated(Alarm),
    AlarmUpdated(Alarm),
    AlarmDeleted { id: Uuid },
    AlarmEventLogged(AlarmEvent),
    /// §10.x-fix (v2.14.2) — Emitted whenever a Pro-tier audio clip is
    /// attached to or deleted from an event. The web dashboard listens for
    /// this on the Sleep page to refetch the per-event list so newly-
    /// uploaded clips show ▶ without requiring a page reload.
    SleepAudioClipsChanged { sleep_record_id: Uuid },
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
    /// No-op if no client is currently subscribed. Drops the per-user channel
    /// when no receivers remain so the map doesn't grow unbounded.
    pub fn publish(&self, user_id: Uuid, event: SyncEvent) {
        let mut map = self.inner.lock().expect("event bus lock poisoned");
        let drop_entry = match map.get(&user_id) {
            Some(sender) if sender.receiver_count() == 0 => true,
            Some(sender) => {
                let _ = sender.send(event);
                false
            }
            None => false,
        };
        if drop_entry {
            map.remove(&user_id);
        }
    }
}
