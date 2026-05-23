-- §calendar-reminders — per-event reminder offset (v2.13.0).
-- Replaces the client-side hardcoded `EVENT_LEAD_MINUTES = 15` with a
-- per-event value so the user can pick "none / 5 / 15 / 30 / 60 /
-- 1 day before" in the add/edit dialog. NULL means "use the client's
-- default" (currently 15 min on Android — see AlarmScheduler.kt).
-- Pre-migration rows keep their effective behaviour: NULL → 15 min.

ALTER TABLE calendar_events
    ADD COLUMN reminder_minutes INTEGER;

-- No backfill needed — NULL is the explicit "use default" sentinel and
-- the Android client already coerces NULL → 15 in scheduleEventReminder.
