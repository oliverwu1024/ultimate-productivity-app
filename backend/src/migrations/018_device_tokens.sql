-- §9.8 — FCM device tokens for push delivery (Phase 9 anomaly detection).
--
-- One row per (token) is the right uniqueness: an FCM token is bound to
-- the app install on a specific device, so if user A logs out and user B
-- logs in on the same phone the token row should follow ownership rather
-- than fan out to two users. The ON CONFLICT upsert in `POST /devices/register`
-- enforces this.
CREATE TABLE IF NOT EXISTS device_tokens (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id       UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token         TEXT NOT NULL,
    platform      TEXT NOT NULL CHECK (platform IN ('android', 'ios', 'web')),
    last_seen_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_device_tokens_token
    ON device_tokens(token);

-- The push fan-out path is "fetch all tokens for a user", run during the
-- daily anomaly scan. Index keeps that O(log n) per user.
CREATE INDEX IF NOT EXISTS idx_device_tokens_user_id
    ON device_tokens(user_id);
