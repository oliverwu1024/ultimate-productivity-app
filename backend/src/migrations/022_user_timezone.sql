-- §i18n (v2.13.9) — Per-user IANA timezone string. Used by the backend for
-- "what day is it" date bucketing (checklist.today, sessions.stats today
-- bucket, streak math, anomaly aggregate_daily, weekly insight aggregate)
-- and by the anomaly scheduler to fire the daily check at 08:00 user-local
-- instead of a single global UTC hour.
--
-- Default 'UTC' is a safe fallback: it's what every existing call was already
-- using (Utc::now().date_naive()), so behavior stays identical for any user
-- whose client hasn't yet sent its detected timezone. New Android logins
-- will PATCH /auth/me with ZoneId.systemDefault().id immediately after
-- saving the auth token, so the column populates organically on first
-- v2.13.9+ login.
--
-- Stored as TEXT (not an enum) because IANA tz strings evolve — new zones
-- get added, old ones rename. We just hand the string to chrono-tz at read
-- time; invalid strings fall back to UTC inside the helper. No CHECK
-- constraint for the same reason.
ALTER TABLE users
    ADD COLUMN timezone TEXT NOT NULL DEFAULT 'UTC';

-- Backfill the existing primary user (oliver) to AU east coast so their
-- historical aggregations don't subtly shift on first read after deploy.
-- New users come in with the column default ('UTC') and their client
-- overwrites within seconds of first login.
UPDATE users
   SET timezone = 'Australia/Sydney'
 WHERE email = 'wucowya@gmail.com';
