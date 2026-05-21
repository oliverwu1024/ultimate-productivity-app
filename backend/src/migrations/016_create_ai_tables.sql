-- §9.3 AI integration foundation.
--
-- Four tables backing the Phase 9 feature set:
--   ai_quota          — per-user-per-day token + request counters (rate limit)
--   ai_insights       — generated outputs (weekly summary, anomaly, debrief)
--   ai_conversations  — coach-chat threads
--   ai_messages       — coach-chat turns
--
-- Quota is enforced server-side in src/ai.rs before any Bedrock call; the
-- limits live in env vars (not DB) so the Pro/Free split can change without
-- a migration. AI features are Pro-tier only at launch.

CREATE TABLE ai_quota (
    user_id              UUID    NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    day                  DATE    NOT NULL,
    requests_used        INTEGER NOT NULL DEFAULT 0,
    input_tokens_used    BIGINT  NOT NULL DEFAULT 0,
    output_tokens_used   BIGINT  NOT NULL DEFAULT 0,
    cache_read_tokens    BIGINT  NOT NULL DEFAULT 0,
    cache_write_tokens   BIGINT  NOT NULL DEFAULT 0,
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (user_id, day)
);

CREATE TABLE ai_insights (
    id           UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id      UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    kind         TEXT NOT NULL
                   CHECK (kind IN ('weekly', 'anomaly', 'session_debrief')),
    content      TEXT NOT NULL,
    source_data  JSONB NOT NULL DEFAULT '{}'::jsonb,
    model        TEXT NOT NULL,
    generated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expires_at   TIMESTAMPTZ
);

CREATE INDEX idx_ai_insights_user_kind_gen ON ai_insights(user_id, kind, generated_at DESC);
CREATE INDEX idx_ai_insights_expires_at    ON ai_insights(expires_at) WHERE expires_at IS NOT NULL;

CREATE TABLE ai_conversations (
    id         UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id    UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    title      TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_ai_conversations_user_updated ON ai_conversations(user_id, updated_at DESC);

CREATE TABLE ai_messages (
    id              UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    conversation_id UUID NOT NULL REFERENCES ai_conversations(id) ON DELETE CASCADE,
    role            TEXT NOT NULL
                      CHECK (role IN ('user', 'assistant', 'system')),
    content         TEXT NOT NULL,
    input_tokens    INTEGER,
    output_tokens   INTEGER,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_ai_messages_conv_created ON ai_messages(conversation_id, created_at ASC);
