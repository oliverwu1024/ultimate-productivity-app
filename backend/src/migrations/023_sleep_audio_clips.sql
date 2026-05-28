-- §10.x — Extend sleep_audio_events to support optional Pro-tier audio clips.
--
-- Phase 10 (v2.11.0) shipped on-device YAMNet detection: only labels +
-- timestamps + peak confidence are uploaded; raw audio never leaves the
-- phone. This migration adds two nullable columns so a clip can optionally
-- be attached to an event when the user has Pro + the "Record events"
-- master toggle on. Existing rows (clip-less, snore/cough) remain valid.
--
--   clip_s3_key       Object key inside the sleep-audio S3 bucket. NULL when
--                     no clip was captured for this event (recording off,
--                     per-type filter excluded the type, or upload pending).
--   clip_duration_ms  Length of the encoded clip. NULL iff clip_s3_key NULL.
--                     Pre-stored so the playback UI can render the duration
--                     pill without a HEAD request to S3.
--
-- Lifecycle: clip objects auto-expire from S3 after 7 days via the bucket's
-- lifecycle rule. The row stays with clip_s3_key non-NULL pointing at an
-- expired object — playback presigning returns 404, and clients are
-- expected to surface that as "clip expired" in the UI. A janitor pass can
-- NULL the column after the 7-day window if we want a clean source of truth.
--
-- §10.x — Sleep-talk detection. YAMNet's `Speech` class is added as an
-- independent on/off (separate from snore/cough), so we widen the event_type
-- CHECK to include 'sleep_talk'. Postgres requires drop + re-add for that.

ALTER TABLE sleep_audio_events
    ADD COLUMN IF NOT EXISTS clip_s3_key      TEXT,
    ADD COLUMN IF NOT EXISTS clip_duration_ms INTEGER;

ALTER TABLE sleep_audio_events
    DROP CONSTRAINT IF EXISTS sleep_audio_events_event_type_check;

ALTER TABLE sleep_audio_events
    ADD CONSTRAINT sleep_audio_events_event_type_check
    CHECK (event_type IN ('snore', 'cough', 'sleep_talk'));

-- The two clip columns travel together: either both are set (clip attached)
-- or both are NULL (no clip). Enforce so a half-written attach doesn't leave
-- a row that the playback route would try to presign but can't render.
ALTER TABLE sleep_audio_events
    ADD CONSTRAINT sleep_audio_events_clip_pairs_check
    CHECK ((clip_s3_key IS NULL AND clip_duration_ms IS NULL)
        OR (clip_s3_key IS NOT NULL AND clip_duration_ms IS NOT NULL
            AND clip_duration_ms > 0
            AND clip_duration_ms <= 60000));
