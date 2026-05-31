-- §17 — Per-occurrence overrides for recurring calendar events.
--
-- Recurring events are stored as ONE row with a recurrence_rule like
-- "WEEKLY:MON,WED" and expanded into individual instances on read. Before
-- this migration, marking one Tuesday "done" flipped the master row's
-- is_done flag and propagated to every expanded copy; deleting one
-- Tuesday dropped the master row and erased every occurrence.
--
-- New columns:
--   done_dates: comma-separated YYYY-MM-DD list of occurrence dates the
--     user has explicitly marked done. Expansion sets is_done=true on
--     instances whose local date appears in this set.
--   excluded_dates: comma-separated YYYY-MM-DD list of dates to skip
--     entirely (EXDATE in iCal terms). Expansion drops instances whose
--     local date appears in this set. Powers "Just this one" delete and
--     "Just this one" edit (which adds the date here, then spawns a new
--     non-recurring event for that single date with the edited fields).
--
-- The recurrence_rule string itself gains an optional ":UNTIL=YYYY-MM-DD"
-- suffix (e.g. "WEEKLY:MON,WED:UNTIL=2026-06-15") for "This and following"
-- — no new column needed, the format is backward-compatible (older clients
-- that don't parse UNTIL still get a valid expansion up to the limit
-- because their range query caps it anyway).

ALTER TABLE calendar_events ADD COLUMN done_dates TEXT;
ALTER TABLE calendar_events ADD COLUMN excluded_dates TEXT;
