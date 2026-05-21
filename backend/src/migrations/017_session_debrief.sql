-- §9.7 Session debriefs + Haiku auto-tagging.
--
-- After a focus session completes the user can optionally type a 1-line
-- "what did you work on?" debrief. The backend classifies it via Claude
-- Haiku 4.5 into one of four buckets so the weekly insight can say
-- things like "you spent 60% of focus time on deep work".
--
-- Both fields are nullable: legacy sessions stay untouched, and skipping
-- the prompt simply leaves them NULL.

ALTER TABLE productivity_sessions
    ADD COLUMN debrief     TEXT,
    ADD COLUMN debrief_tag TEXT
        CHECK (debrief_tag IS NULL
               OR debrief_tag IN ('deep_work', 'meetings', 'admin', 'other'));

-- Partial index: lets the §9.4 data-card aggregate query (and any future
-- "show me my deep-work sessions this week") hit the index instead of
-- scanning the full sessions table.
CREATE INDEX idx_sessions_user_tag_started
    ON productivity_sessions(user_id, debrief_tag, started_at)
    WHERE debrief_tag IS NOT NULL;
