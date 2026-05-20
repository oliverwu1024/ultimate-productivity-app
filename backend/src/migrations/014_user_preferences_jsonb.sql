-- §sync-prefs: a single JSONB column for user preferences (bedtime, wake time,
-- default work duration, lockout toggles, per-mode grace minutes, etc.). A
-- JSONB blob is the right shape here — these are tiny, evolving knobs we
-- only ever read for a single user at a time, never query across users.
-- Individual columns per knob would mean a schema migration every time we
-- add another setting; a JSONB blob lets us evolve the shape client-side
-- without touching the schema.
ALTER TABLE users
    ADD COLUMN preferences JSONB NOT NULL DEFAULT '{}'::jsonb;
