-- Email verification.
--
-- Prior to this migration, anyone could register and immediately call
-- /ai/* — which is the Bedrock-cost-attack vector (script N throwaway
-- accounts, drain the per-user quota, run up the bill).
--
-- Existing users are backfilled to verified=true so current sessions
-- and the admin keep working without a re-verify dance. Only signups
-- created after this migration go through verification.

ALTER TABLE users ADD COLUMN email_verified BOOLEAN NOT NULL DEFAULT false;
UPDATE users SET email_verified = true;

CREATE TABLE email_verifications (
    id          BIGSERIAL    PRIMARY KEY,
    user_id     UUID         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash  TEXT         NOT NULL,
    expires_at  TIMESTAMPTZ  NOT NULL,
    used_at     TIMESTAMPTZ  NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_email_verifications_open
    ON email_verifications(user_id)
    WHERE used_at IS NULL;
