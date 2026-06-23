package com.ultiq.app.data.remote.dto

/// §9.6 — POST /ai/chat/messages body. `now_local` (RFC-3339 with offset)
/// anchors relative dates inside tool calls when the backend tool loop is
/// enabled. Older builds (pre tool loop) can omit it; the backend falls
/// back to UTC now.
data class ChatSendRequestDto(
    val content: String,
    val now_local: String? = null,
)

/// §9.6 — One message in the chat history. Role is "user" or "assistant"
/// (server never returns "system" messages — those are internal).
data class ChatMessageDto(
    val id: String,
    val role: String,
    val content: String,
    val created_at: String,
)

/// §9.6 — POST /ai/chat/messages response. Both the persisted user turn
/// and the assistant's reply are returned so the client can append both
/// without doing a separate /chat/messages re-fetch. `tool_invocations`
/// is empty when tools are disabled or the model answered without any —
/// keep the default so old responses (no field) still deserialize.
data class ChatSendResponseDto(
    val user_message: ChatMessageDto,
    val assistant_message: ChatMessageDto,
    val tool_invocations: List<ToolInvocationDto> = emptyList(),
    /// §clarify — option labels the Coach offered to disambiguate a vague
    /// request (e.g. "past month"). Null/absent on normal turns. Rendered as
    /// tappable chips; a tap sends that label as the next user message.
    val clarification_options: List<String>? = null,
)

/// §9.x — One tool call surfaced by the backend Coach loop. Read-tool
/// invocations render as status pills, committed write tools render with
/// an undo affordance, and proposed calendar events render an inline
/// Create/Cancel card.
data class ToolInvocationDto(
    /// Bedrock-supplied tool_use_id; stable per invocation. Used as the
    /// list-diff key.
    val id: String,
    /// Tool name from the schema (e.g. "create_checklist_item").
    val name: String,
    /// "ok" | "error" | "proposed". `proposed` is exclusive to
    /// `create_calendar_event` — the row was NOT created server-side, the
    /// client must render Create/Cancel buttons.
    val status: String,
    /// One-line UI summary ("Looking at your sleep…", "Added: Buy groceries").
    val summary: String,
    /// True iff the server wrote a row for this invocation. Always false
    /// for reads and for proposed calendar events.
    val committed: Boolean = false,
    /// Identifies the row when `committed = true`. Drives the undo
    /// snackbar action.
    val committed_resource: CommittedResourceDto? = null,
    /// Populated when `status = "proposed"` and `name = "create_calendar_event"`.
    /// The parsed fields the user confirms or cancels.
    val proposed_event: ParsedCalendarFieldsDto? = null,
    /// Populated when `status = "proposed"` and `name = "create_alarm"`.
    /// The parsed alarm fields the user confirms or cancels.
    val proposed_alarm: ProposedAlarmFieldsDto? = null,
)

/// Mirrors backend `ProposedAlarmFields`. `days_of_week` is the same
/// bitmask the AlarmEntity uses (bit 0 = Sun … bit 6 = Sat, 0 = one-shot).
data class ProposedAlarmFieldsDto(
    val trigger_time_local: String,
    val label: String? = null,
    val days_of_week: Short,
    val mission_kind: String,
)

data class CommittedResourceDto(
    /// "checklist" (newly created) | "checklist_complete" (marked done) |
    /// "sleep_record" (Coach logged a night). Tells the client which undo
    /// endpoint to hit.
    val kind: String,
    val id: String,
    val title: String? = null,
    val due_date: String? = null,
)

/// §9.6 — POST /ai/chat/reset response. The client doesn't need this for
/// UI (just clearing local state is enough) but the id is returned for
/// debugging / future "rename" features.
data class ChatResetResponseDto(
    val conversation_id: String,
)
