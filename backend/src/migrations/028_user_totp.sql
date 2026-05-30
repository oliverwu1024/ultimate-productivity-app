-- §16 — Two-factor auth (TOTP, RFC 6238).
--
-- New columns are nullable / safe-default so every existing user is
-- "TOTP not enrolled, not enforced" until they opt in via the
-- /auth/2fa/setup → /auth/2fa/confirm flow.
--
-- totp_secret_b32: the base32-encoded TOTP seed, populated on /setup but
-- not yet trusted (totp_enabled stays false) until /confirm proves the
-- user actually scanned it into an authenticator. Stored at-rest under
-- the same RDS KMS encryption that protects password_hash. Strictly
-- speaking, leaking this column to an attacker who also has the user's
-- password defeats 2FA — we accept that risk under "DB dump = total
-- compromise" rather than building an HSM/KMS-per-row scheme today.
--
-- totp_enabled: source of truth for "should login require a TOTP code."
-- Only set to true after the user confirms a valid code; cleared by
-- /auth/2fa/disable (user-initiated, requires current code) or
-- /admin/users/:id/disable-2fa (admin lockout recovery).

ALTER TABLE users ADD COLUMN totp_secret_b32 TEXT;
ALTER TABLE users ADD COLUMN totp_enabled BOOLEAN NOT NULL DEFAULT false;
