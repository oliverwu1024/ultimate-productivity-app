package com.ultiq.app.data.repository

import java.util.concurrent.ConcurrentHashMap

/// §v2.16.9 — Echo-suppression for SSE-delivered checklist events that
/// originate from this device's own actions.
///
/// Why: every user action on a checklist row (toggle, edit, delete) does
/// a local optimistic write first, then an API call. The backend processes
/// the call and broadcasts the change back to ALL connected clients —
/// including the device that originated the change — via the
/// `/sync/events` SSE channel. The receiving SyncEventClient then runs
/// `checklistDao.insert(server.toEntity()) + applyChecklistCompletions`
/// ~50-300 ms after the local write, which is the second Room
/// invalidation that re-lays out the LazyColumn and shows up as the
/// recurring-mark-complete flicker the user reported across v2.16.5
/// through v2.16.8. v2.16.8 removed the equivalent write from
/// ChecklistRepository's toggle paths, but the SSE handler does
/// independently the same writes via a parallel code path.
///
/// Strategy: any time the local code initiates a write that will be
/// echoed back by the server, call [noteLocalChange] with the item id
/// first. When the SSE event arrives, [shouldSuppress] returns true for
/// up to [WINDOW_MS] and the SSE handler skips the redundant write.
/// Cross-device events (no recent local action for that id) still apply
/// normally so a change made on the web dashboard or another device
/// continues to propagate in real time.
///
/// 5 s window: generous enough for slow networks (typical SSE echo
/// arrives within 300 ms; 5 s gives ~16x margin) while still letting
/// genuine cross-device conflicts surface — a remote change made more
/// than 5 s after the local one is not considered an echo.
object ChecklistEchoSuppressor {
    private const val WINDOW_MS = 5_000L

    private val recent = ConcurrentHashMap<String, Long>()

    fun noteLocalChange(id: String) {
        val now = System.currentTimeMillis()
        recent[id] = now
        // Opportunistic cleanup so a long-lived process doesn't grow the
        // map unbounded. Removes entries that are stale enough to no
        // longer be "recent" under any reasonable network condition.
        val cutoff = now - WINDOW_MS * 2
        val iter = recent.entries.iterator()
        while (iter.hasNext()) {
            if (iter.next().value < cutoff) iter.remove()
        }
    }

    fun shouldSuppress(id: String): Boolean {
        val ts = recent[id] ?: return false
        return System.currentTimeMillis() - ts < WINDOW_MS
    }
}
