-- Per-occurrence completion log for recurring checklist items.
--
-- Pre-024 schema only had a single `last_completed_epoch_day` column on
-- checklist_items, which could remember exactly one tick day. Marking a
-- recurring row done on Tuesday overwrote Monday's stamp, so Monday's
-- view silently flipped back to "open". This table stores one row per
-- (item, day) completion, so every past tick survives independently.
--
-- The non-recurring path is unchanged: it still uses
-- checklist_items.completed / completed_at. This table is only read for
-- rows where recurrence_days_mask != 0.
--
-- last_completed_epoch_day is intentionally NOT dropped — old mobile
-- clients still write to it via PUT /checklist/:id, and we want their
-- writes to no-op against the new logic rather than 500. A later
-- migration can drop the column once the lower-bound client version
-- enforced by the API is past the rollout point.
--
-- Backfill preserves the single tick we already had per recurring row.

CREATE TABLE checklist_completions (
    item_id       UUID        NOT NULL,
    epoch_day     BIGINT      NOT NULL,
    completed_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (item_id, epoch_day),
    FOREIGN KEY (item_id) REFERENCES checklist_items(id) ON DELETE CASCADE
);

CREATE INDEX idx_checklist_completions_item ON checklist_completions(item_id);

INSERT INTO checklist_completions (item_id, epoch_day, completed_at)
SELECT id,
       last_completed_epoch_day,
       COALESCE(updated_at, NOW())
FROM checklist_items
WHERE last_completed_epoch_day IS NOT NULL
  AND recurrence_days_mask <> 0
ON CONFLICT (item_id, epoch_day) DO NOTHING;
