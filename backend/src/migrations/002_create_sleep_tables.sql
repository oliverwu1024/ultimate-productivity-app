CREATE TABLE sleep_records (
    id                UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id           UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    target_bedtime    TIME NOT NULL,
    target_wake_time  TIME NOT NULL,
    actual_bedtime    TIMESTAMPTZ NOT NULL,
    actual_wake_time  TIMESTAMPTZ NOT NULL,
    quality_rating    SMALLINT NOT NULL CHECK (quality_rating BETWEEN 1 AND 5),
    phone_pickups     INT NOT NULL DEFAULT 0,
    total_phone_minutes INT,
    notes             TEXT,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_sleep_records_user_id ON sleep_records(user_id);
CREATE INDEX idx_sleep_records_actual_bedtime ON sleep_records(user_id, actual_bedtime);

CREATE TABLE phone_pickups (
    id                UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id           UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    sleep_record_id   UUID REFERENCES sleep_records(id) ON DELETE SET NULL,
    session_id        UUID,  -- FK added later when sessions table exists
    picked_up_at      TIMESTAMPTZ NOT NULL,
    duration_seconds  INT NOT NULL DEFAULT 0,
    app_category      VARCHAR(50),
    created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_phone_pickups_sleep ON phone_pickups(sleep_record_id);
