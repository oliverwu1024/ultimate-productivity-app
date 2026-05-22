package com.ultiq.app.data.remote.dto

/// §9.6 — POST /ai/chat/messages body. Single user-message string.
data class ChatSendRequestDto(
    val content: String,
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
/// without doing a separate /chat/messages re-fetch.
data class ChatSendResponseDto(
    val user_message: ChatMessageDto,
    val assistant_message: ChatMessageDto,
)

/// §9.6 — POST /ai/chat/reset response. The client doesn't need this for
/// UI (just clearing local state is enough) but the id is returned for
/// debugging / future "rename" features.
data class ChatResetResponseDto(
    val conversation_id: String,
)
