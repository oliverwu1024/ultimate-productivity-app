CREATE TABLE alarms (
    id                  UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id             UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    label               TEXT,
    trigger_time_local  TIME NOT NULL,
    days_of_week        SMALLINT NOT NULL,
    enabled             BOOLEAN NOT NULL DEFAULT TRUE,
    sound_uri           TEXT,
    volume_pct          SMALLINT NOT NULL DEFAULT 80 CHECK (volume_pct BETWEEN 0 AND 100),
    volume_escalates    BOOLEAN NOT NULL DEFAULT TRUE,
    vibration           BOOLEAN NOT NULL DEFAULT TRUE,
    snooze_minutes      SMALLINT NOT NULL DEFAULT 9,
    snooze_max          INTEGER NOT NULL DEFAULT 3,
    mission_kind        TEXT NOT NULL DEFAULT 'none'
                          CHECK (mission_kind IN ('none', 'math', 'shake', 'photo')),
    mission_config      JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_alarms_user_enabled ON alarms(user_id, enabled);

CREATE TABLE alarm_events (
    id                  UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id             UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    alarm_id            UUID REFERENCES alarms(id) ON DELETE SET NULL,
    fired_at            TIMESTAMPTZ NOT NULL,
    dismissed_at        TIMESTAMPTZ,
    dismiss_method      TEXT CHECK (dismiss_method IN ('mission', 'snooze', 'force', 'abandoned')),
    snooze_count        SMALLINT NOT NULL DEFAULT 0,
    mission_kind        TEXT,
    mission_attempts    SMALLINT NOT NULL DEFAULT 0,
    mission_duration_ms INTEGER,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_alarm_events_user_fired ON alarm_events(user_id, fired_at DESC);
CREATE INDEX idx_alarm_events_alarm_fired ON alarm_events(alarm_id, fired_at DESC);
