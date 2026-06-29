-- §last-night — Distinguish daytime naps / short test sessions from overnight
-- sleep, so the "last night" surfaces (Android dashboard + sleep tab, web
-- dashboard) can skip naps when picking the most-recent night. Existing rows
-- default to overnight sleep (is_nap = false).
ALTER TABLE sleep_records ADD COLUMN IF NOT EXISTS is_nap BOOLEAN NOT NULL DEFAULT false;
