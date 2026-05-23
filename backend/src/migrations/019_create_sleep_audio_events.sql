-- §10 — Sleep audio events (snore + cough) detected on-device during a sleep session.
--
-- YAMNet (Apache 2.0, ~4 MB TFLite) runs locally on the phone over the user's
-- sleep session audio. Only labels + timestamps + peak confidence are persisted
-- here — raw audio never leaves the device. The Android side debounces
-- consecutive 0.96 s YAMNet windows into a single event (≥ N adjacent hits
-- become one event with combined start/end and the peak confidence observed).
--
-- Lifecycle: rows can only exist linked to a sleep_record, so ON DELETE CASCADE
-- keeps the table self-cleaning when a sleep record is removed. user_id is
-- denormalised (same pattern as phone_pickups) so user-scoped time-range
-- queries (§9.4 weekly insight, §9.8 anomaly detection) don't need the join.

CREATE TABLE IF NOT EXISTS sleep_audio_events (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    sleep_record_id UUID NOT NULL REFERENCES sleep_records(id) ON DELETE CASCADE,
    event_type      TEXT NOT NULL CHECK (event_type IN ('snore', 'cough')),
    started_at      TIMESTAMPTZ NOT NULL,
    ended_at        TIMESTAMPTZ NOT NULL,
    peak_confidence REAL NOT NULL CHECK (peak_confidence BETWEEN 0.0 AND 1.0),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CHECK (ended_at >= started_at)
);

-- Per-session fetch path: the "Tonight's sounds" card on the Sleep tab loads
-- all events for the most recent sleep_record ordered by start time.
CREATE INDEX IF NOT EXISTS idx_sleep_audio_events_sleep_record
    ON sleep_audio_events(sleep_record_id, started_at);

-- User-scoped time-range fetch path: §9.4 weekly insight aggregation and §9.8
-- anomaly detection (e.g. "cough event count trending up over the past week"
-- signal). Stays O(log n) per user even after months of nightly events.
CREATE INDEX IF NOT EXISTS idx_sleep_audio_events_user_started
    ON sleep_audio_events(user_id, started_at);
