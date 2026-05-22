package com.ultiq.app.data.remote.dto

import com.google.gson.Gson
import com.google.gson.JsonParser

/**
 * Mirrors the backend's `SyncEvent` enum (serde-tagged with `type` + `data`).
 * Backend wire format: `{"type":"CalendarCreated","data":{...}}`.
 */
sealed class SyncEvent {
    data class CalendarCreated(val event: CalendarEventDto) : SyncEvent()
    data class CalendarUpdated(val event: CalendarEventDto) : SyncEvent()
    data class CalendarDeleted(val id: String) : SyncEvent()
    data class ChecklistCreated(val item: ChecklistItemDto) : SyncEvent()
    data class ChecklistUpdated(val item: ChecklistItemDto) : SyncEvent()
    data class ChecklistDeleted(val id: String) : SyncEvent()
    data class SleepCreated(val record: SleepRecordDto) : SyncEvent()
    data class SleepUpdated(val record: SleepRecordDto) : SyncEvent()
    data class SleepDeleted(val id: String) : SyncEvent()
    data class SessionCreated(val session: SessionDto) : SyncEvent()
    data class SessionUpdated(val session: SessionDto) : SyncEvent()
    data class SessionDeleted(val id: String) : SyncEvent()
    data class AlarmCreated(val alarm: AlarmDto) : SyncEvent()
    data class AlarmUpdated(val alarm: AlarmDto) : SyncEvent()
    data class AlarmDeleted(val id: String) : SyncEvent()
}

private data class IdPayload(val id: String)

/**
 * Parses one SSE message body into a SyncEvent. Returns null for unknown types
 * or malformed payloads — the caller should treat null as "ignore this event."
 */
fun parseSyncEvent(json: String): SyncEvent? {
    return try {
        val obj = JsonParser.parseString(json).asJsonObject
        val type = obj.get("type")?.asString ?: return null
        val data = obj.get("data") ?: return null
        val gson = Gson()
        when (type) {
            "CalendarCreated" -> SyncEvent.CalendarCreated(
                gson.fromJson(data, CalendarEventDto::class.java)
            )
            "CalendarUpdated" -> SyncEvent.CalendarUpdated(
                gson.fromJson(data, CalendarEventDto::class.java)
            )
            "CalendarDeleted" -> SyncEvent.CalendarDeleted(
                gson.fromJson(data, IdPayload::class.java).id
            )
            "ChecklistCreated" -> SyncEvent.ChecklistCreated(
                gson.fromJson(data, ChecklistItemDto::class.java)
            )
            "ChecklistUpdated" -> SyncEvent.ChecklistUpdated(
                gson.fromJson(data, ChecklistItemDto::class.java)
            )
            "ChecklistDeleted" -> SyncEvent.ChecklistDeleted(
                gson.fromJson(data, IdPayload::class.java).id
            )
            "SleepCreated" -> SyncEvent.SleepCreated(
                gson.fromJson(data, SleepRecordDto::class.java)
            )
            "SleepUpdated" -> SyncEvent.SleepUpdated(
                gson.fromJson(data, SleepRecordDto::class.java)
            )
            "SleepDeleted" -> SyncEvent.SleepDeleted(
                gson.fromJson(data, IdPayload::class.java).id
            )
            "SessionCreated" -> SyncEvent.SessionCreated(
                gson.fromJson(data, SessionDto::class.java)
            )
            "SessionUpdated" -> SyncEvent.SessionUpdated(
                gson.fromJson(data, SessionDto::class.java)
            )
            "SessionDeleted" -> SyncEvent.SessionDeleted(
                gson.fromJson(data, IdPayload::class.java).id
            )
            "AlarmCreated" -> SyncEvent.AlarmCreated(
                gson.fromJson(data, AlarmDto::class.java)
            )
            "AlarmUpdated" -> SyncEvent.AlarmUpdated(
                gson.fromJson(data, AlarmDto::class.java)
            )
            "AlarmDeleted" -> SyncEvent.AlarmDeleted(
                gson.fromJson(data, IdPayload::class.java).id
            )
            // §9.8 `AlarmEventLogged` is telemetry-only (alarm firing
            // history). The mobile clients write their own copies of those
            // rows from `AlarmRingService`, so we don't need to mirror the
            // server's event back into Room.
            else -> null
        }
    } catch (_: Exception) {
        null
    }
}
