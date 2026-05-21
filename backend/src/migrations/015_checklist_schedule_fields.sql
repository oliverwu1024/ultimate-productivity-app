-- Recurrence + due-date carry-through for checklist items.
--
-- recurrence_days_mask: bitmask, bit 0 = Sun … bit 6 = Sat. 0 = one-off.
-- show_until_due: when true and recurrence_days_mask = 0, the item is treated
--                 as a deadline task that appears every day from creation
--                 through due_date until completed.
-- last_completed_epoch_day: per-day done stamp for recurring items so each
--                           occurrence can reopen overnight without touching
--                           the master row's `completed` flag.

ALTER TABLE checklist_items
    ADD COLUMN recurrence_days_mask SMALLINT NOT NULL DEFAULT 0;

ALTER TABLE checklist_items
    ADD COLUMN show_until_due BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE checklist_items
    ADD COLUMN last_completed_epoch_day BIGINT;
