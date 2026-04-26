CREATE TABLE checklist_items (
    id                UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id           UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    title             VARCHAR(255) NOT NULL,
    description       TEXT,
    due_date          DATE NOT NULL,
    estimated_minutes INT,
    priority          SMALLINT NOT NULL DEFAULT 1 CHECK (priority IN (0, 1, 2)),
    completed         BOOLEAN NOT NULL DEFAULT FALSE,
    completed_at      TIMESTAMPTZ,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_checklist_user_due ON checklist_items(user_id, due_date);
CREATE INDEX idx_checklist_user_open ON checklist_items(user_id, completed, due_date);

ALTER TABLE productivity_sessions
ADD COLUMN checklist_item_id UUID REFERENCES checklist_items(id) ON DELETE SET NULL;

CREATE INDEX idx_sessions_checklist ON productivity_sessions(checklist_item_id);
