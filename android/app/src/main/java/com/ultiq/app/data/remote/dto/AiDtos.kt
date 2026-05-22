package com.ultiq.app.data.remote.dto

/// Response shape for POST /ai/weekly-insight. `cached` is true when the
/// server returned a stored insight from within the 24h cache window.
data class WeeklyInsightDto(
    val id: String,
    val content: String,
    val model: String,
    val generated_at: String,
    val expires_at: String,
    val cached: Boolean,
)

/// §9.7 — Body for POST /ai/session-debrief/{id}.
data class SessionDebriefRequestDto(
    val text: String,
)

/// §9.7 — Response: server returns the saved text plus the Haiku-assigned
/// bucket (deep_work / meetings / admin / other).
data class SessionDebriefResponseDto(
    val id: String,
    val debrief: String,
    val debrief_tag: String,
)

/// §9.5 — Body for POST /ai/parse-event. `text` is the user's natural-language
/// input (e.g. "lunch with Sarah tomorrow at 1pm"). `hint` pins the surface
/// ("calendar" or "checklist"); when set, the backend forces Sonnet to call
/// that specific tool. `now_local` is the client's current local time as an
/// ISO-8601 string with offset (e.g. "2026-05-22T15:30:00+10:00") so the
/// model can resolve "tomorrow", "friday", etc.
data class ParseEventRequestDto(
    val text: String,
    val hint: String? = null,
    val now_local: String? = null,
)

/// §9.5 — Response. `kind` is "calendar" or "checklist"; the matching field
/// holds the parsed values, the other is null. Used to pre-fill the existing
/// create dialogs — the client still confirms before persisting.
data class ParseEventResponseDto(
    val kind: String,
    val calendar: ParsedCalendarFieldsDto? = null,
    val checklist: ParsedChecklistFieldsDto? = null,
)

data class ParsedCalendarFieldsDto(
    val title: String,
    val description: String?,
    val start_time: String,
    val end_time: String,
    /// One of: study | project | exercise | personal | other
    val category: String,
    /// One of: high | medium | low
    val priority: String,
)

data class ParsedChecklistFieldsDto(
    val title: String,
    val description: String?,
    val due_date: String,
    /// 0=low, 1=medium, 2=high. Null = use form default.
    val priority: Int? = null,
    val estimated_minutes: Int? = null,
)

/// §9.8 — Response from GET /ai/anomaly. The daily scheduler stores an
/// alert row when Haiku flags a pattern; the mobile Dashboard polls this
/// endpoint to render the alert card (and the FCM push surfaces the same
/// message in the notification tray).
///
/// `alert=false` is the common case — no scary patterns today, no row in
/// the cache, card stays hidden.
data class LatestAnomalyDto(
    val alert: Boolean,
    val reason: String,
    val insight_id: String? = null,
    val generated_at: String? = null,
)
