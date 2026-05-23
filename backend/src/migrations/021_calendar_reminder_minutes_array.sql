-- §calendar-multi-reminders — v2.13.1 lets the user attach multiple
-- reminders to a single event ("notify me 1 day before AND 1 hour before").
-- Converts the column from a single nullable INTEGER (migration 020) to
-- an INTEGER[] array. Migration 020 just shipped, so any rows that did
-- pick a custom value get wrapped in a single-element array.

ALTER TABLE calendar_events
    ALTER COLUMN reminder_minutes TYPE INTEGER[] USING
        CASE
            WHEN reminder_minutes IS NULL THEN NULL                  -- "use client default"
            WHEN reminder_minutes = 0 THEN ARRAY[]::INTEGER[]        -- explicit opt-out
            ELSE ARRAY[reminder_minutes]                             -- one explicit offset
        END;

-- Semantics for the new array column:
--   NULL          → use client default (currently a single 15-min reminder)
--   ARRAY[]::int  → explicit "no reminders" (opt-out)
--   ARRAY[n, m..] → fire one reminder n min before, another m min before, etc.
