-- Pro tier flag — gates sleep-audio clip recording today, will gate
-- additional Pro-only features as Phase 11 monetization lands.
--
-- Until this migration, sleep-audio gated on is_admin as a stand-in
-- (see backend/src/routes/sleep_audio.rs require_pro_tier). Once
-- is_admin starts being granted to support staff or co-admins, that
-- accidentally hands them Pro features. Decoupling now so the bug
-- can't ship with monetization.
--
-- Backfill: any current admin gets is_pro=true so the dev account
-- (the only admin today) keeps its sleep-clip access without manual
-- intervention.

ALTER TABLE users ADD COLUMN is_pro BOOLEAN NOT NULL DEFAULT false;
UPDATE users SET is_pro = true WHERE is_admin = true;
