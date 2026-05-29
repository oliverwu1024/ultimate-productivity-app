-- Forensic record of every admin action.
--
-- The payload column intentionally stores parameter shapes only (e.g. the
-- title of a test push), not request body content for endpoints that
-- touch user data — admins must not be able to retroactively read
-- checklist text, coach chat, etc. from the audit log.
--
-- ip is best-effort: pulled from the leftmost X-Forwarded-For value set
-- by the ALB. Null when the header is missing (direct hits, internal
-- callers).

CREATE TABLE admin_audit_log (
    id              BIGSERIAL    PRIMARY KEY,
    admin_id        UUID         NOT NULL REFERENCES users(id),
    action          TEXT         NOT NULL,
    target_user_id  UUID         NULL REFERENCES users(id) ON DELETE SET NULL,
    payload         JSONB        NULL,
    ip              TEXT         NULL,
    ts              TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_admin_audit_admin_ts ON admin_audit_log(admin_id, ts DESC);
CREATE INDEX idx_admin_audit_target_ts ON admin_audit_log(target_user_id, ts DESC) WHERE target_user_id IS NOT NULL;
