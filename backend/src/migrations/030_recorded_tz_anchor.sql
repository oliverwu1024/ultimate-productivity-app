-- §tz-anchor (Phase A) — pin each sleep record and focus session to the
-- timezone it was recorded in. Past records then keep their original local
-- wall-clock after the user travels (i.e. changes users.timezone): display +
-- AI convert each row using its OWN recorded_tz instead of the user's CURRENT
-- tz. A NULL recorded_tz falls back to the user's current tz at render time,
-- so any un-stamped row behaves exactly as it did before this migration.

ALTER TABLE sleep_records         ADD COLUMN IF NOT EXISTS recorded_tz TEXT;
ALTER TABLE productivity_sessions ADD COLUMN IF NOT EXISTS recorded_tz TEXT;

-- Backfill existing rows with the owner's current timezone — the best
-- available guess, since they were logged before anchoring existed and a
-- non-travelling user's current tz IS their recording tz. Idempotent: only
-- touches rows not yet stamped.
UPDATE sleep_records sr
   SET recorded_tz = u.timezone
  FROM users u
 WHERE sr.user_id = u.id AND sr.recorded_tz IS NULL;

UPDATE productivity_sessions ps
   SET recorded_tz = u.timezone
  FROM users u
 WHERE ps.user_id = u.id AND ps.recorded_tz IS NULL;
