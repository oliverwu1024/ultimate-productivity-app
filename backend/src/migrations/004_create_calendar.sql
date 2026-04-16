CREATE TYPE event_category AS ENUM ('study', 'project', 'exercise', 'personal', 'other');
CREATE TYPE event_priority AS ENUM ('high', 'medium', 'low');

CREATE TABLE calendar_events (
    id              UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id         UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    title           VARCHAR(255) NOT NULL,
    description     TEXT,
    start_time      TIMESTAMPTZ NOT NULL,
    end_time        TIMESTAMPTZ NOT NULL,
    category        event_category NOT NULL DEFAULT 'other',
    priority        event_priority NOT NULL DEFAULT 'medium',
    is_recurring    BOOLEAN NOT NULL DEFAULT FALSE,
    recurrence_rule TEXT,            -- e.g. "WEEKLY:MON,WED,FRI"
    color           VARCHAR(7) NOT NULL DEFAULT '#4A90D9',  -- hex color
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_calendar_user_id ON calendar_events(user_id);
CREATE INDEX idx_calendar_start_time ON calendar_events(user_id, start_time);
