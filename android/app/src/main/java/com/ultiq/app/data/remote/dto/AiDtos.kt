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
