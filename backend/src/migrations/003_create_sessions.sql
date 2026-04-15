CREATE TABLE productivity_sessions (
    id              UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id         UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    tag             VARCHAR(100) NOT NULL,
    duration_minutes INT NOT NULL DEFAULT 0,
    work_duration   INT NOT NULL,
    break_duration  INT NOT NULL,
    phone_pickups   INT NOT NULL DEFAULT 0,
    started_at      TIMESTAMPTZ NOT NULL,
    ended_at        TIMESTAMPTZ,
    completed       BOOLEAN NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_sessions_user_id ON productivity_sessions(user_id);
CREATE INDEX idx_sessions_started_at ON productivity_sessions(user_id, started_at);

ALTER TABLE phone_pickups
ADD CONSTRAINT fk_phone_pickups_session
FOREIGN KEY (session_id) REFERENCES productivity_sessions(id) ON DELETE SET NULL;

CREATE INDEX idx_phone_pickups_session ON phone_pickups(session_id);
