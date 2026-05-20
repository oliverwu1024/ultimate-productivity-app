-- §L13: snooze_max validates as 0..10. INTEGER (i32) is wildly oversized;
-- SMALLINT (i16) matches the validation range and aligns with snooze_minutes.
ALTER TABLE alarms
    ALTER COLUMN snooze_max TYPE SMALLINT USING snooze_max::SMALLINT;
