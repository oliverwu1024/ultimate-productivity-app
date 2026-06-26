-- §tz/calendar (Phase B) — give each calendar event its own timezone so a
-- RECURRING event repeats at a stable LOCAL wall-clock in that zone (Google
-- Calendar semantics) instead of drifting ±1h across DST. `event_tz` = the
-- creator's IANA zone captured at save time. Single timed events are still
-- absolute instants shown in the viewer's current tz (unchanged); event_tz
-- only governs recurrence expansion.

ALTER TABLE calendar_events ADD COLUMN IF NOT EXISTS event_tz TEXT NOT NULL DEFAULT 'UTC';

-- Backfill existing events with the owner's current timezone — best guess,
-- since they predate the column. Idempotent: only rows still at the default.
UPDATE calendar_events ce
   SET event_tz = u.timezone
  FROM users u
 WHERE ce.user_id = u.id AND ce.event_tz = 'UTC';
