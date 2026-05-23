package com.ultiq.app.data.local.converters

import androidx.room.TypeConverter

/**
 * v2.13.1 — Encodes a [List]<[Int]> as a comma-separated TEXT column for
 * SQLite storage. Used by [com.ultiq.app.data.local.entity.CalendarEventEntity]'s
 * `reminderMinutes` field so a single event can carry multiple reminder
 * offsets (1 day + 1 hour + 5 min, etc.).
 *
 * Encoding contract:
 *   null  → "use client default" (currently a single 15-min reminder)
 *   ""    → explicit "no reminders" (opt-out)
 *   "5"   → one reminder, 5 minutes before
 *   "5,15,60" → three reminders at those offsets
 *
 * Comma-separated instead of JSON to avoid a JSON parser dep + keep the
 * encoded string short (typical event has 0–3 reminders).
 */
class IntListConverter {
    @TypeConverter
    fun fromList(value: List<Int>?): String? = value?.joinToString(",")

    @TypeConverter
    fun toList(value: String?): List<Int>? = when {
        value == null -> null
        value.isEmpty() -> emptyList()
        else -> value.split(",").mapNotNull { it.toIntOrNull() }
    }
}
