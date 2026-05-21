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
