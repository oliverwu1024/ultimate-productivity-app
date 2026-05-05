use std::collections::HashMap;
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use uuid::Uuid;

/// Short-lived single-use ticket issued so a browser EventSource can authenticate
/// the SSE stream without putting a long-lived JWT in the URL (which would land
/// in browser history, CloudFront access logs, and Referer headers).
///
/// The flow is: client `POST /sync/ticket` with `Authorization: Bearer <jwt>` →
/// gets back `{ ticket }` → opens `GET /sync/events?ticket=<x>`. The ticket is
/// redeemed (and removed) on first use; expires after 30 seconds anyway.
const TICKET_TTL: Duration = Duration::from_secs(30);

#[derive(Clone, Default)]
pub struct TicketStore {
    inner: Arc<Mutex<HashMap<String, Entry>>>,
}

struct Entry {
    user_id: Uuid,
    expires_at: Instant,
}

impl TicketStore {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn issue(&self, user_id: Uuid) -> String {
        let ticket = Uuid::new_v4().to_string();
        let mut map = self.inner.lock().expect("ticket lock poisoned");
        // Cheap GC every issue so expired entries don't accumulate.
        let now = Instant::now();
        map.retain(|_, e| e.expires_at > now);
        map.insert(
            ticket.clone(),
            Entry {
                user_id,
                expires_at: now + TICKET_TTL,
            },
        );
        ticket
    }

    pub fn redeem(&self, ticket: &str) -> Option<Uuid> {
        let mut map = self.inner.lock().expect("ticket lock poisoned");
        let entry = map.remove(ticket)?;
        if entry.expires_at < Instant::now() {
            return None;
        }
        Some(entry.user_id)
    }
}
