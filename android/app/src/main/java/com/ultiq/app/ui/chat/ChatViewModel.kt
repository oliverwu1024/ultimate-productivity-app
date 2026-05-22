package com.ultiq.app.ui.chat

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ultiq.app.data.remote.RetrofitClient
import com.ultiq.app.data.remote.dto.ChatMessageDto
import com.ultiq.app.data.remote.dto.ChatSendRequestDto
import com.ultiq.app.util.TokenManager
import com.ultiq.app.util.toUserMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/// §9.6 — Coach Chat view-model. One active conversation per user; the
/// server tracks it via `ai_conversations.updated_at` so we don't need a
/// local conversation_id. State is simply the message list + send state.
data class ChatUiState(
    val messages: List<ChatMessageDto> = emptyList(),
    /// True while the initial /chat/messages fetch is in flight after
    /// entering the screen. Drives the centred spinner empty state.
    val isLoading: Boolean = false,
    /// True while a user-initiated /chat/messages POST is in flight.
    /// Drives the disabled send button + the "typing…" indicator.
    val isSending: Boolean = false,
    val error: String? = null,
)

class ChatViewModel(application: Application) : AndroidViewModel(application) {
    private val tokenManager = TokenManager(application)
    private val api = RetrofitClient.create(tokenManager)

    private val _uiState = MutableStateFlow(ChatUiState(isLoading = true))
    val uiState: StateFlow<ChatUiState> = _uiState

    init {
        loadHistory()
    }

    fun loadHistory() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            runCatching { api.listChatMessages(limit = null) }
                .onSuccess { messages ->
                    _uiState.value = _uiState.value.copy(
                        messages = messages,
                        isLoading = false,
                    )
                }
                .onFailure { e ->
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = e.toUserMessage("Couldn't load your chat history."),
                    )
                }
        }
    }

    fun send(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || _uiState.value.isSending) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSending = true, error = null)
            runCatching { api.sendChatMessage(ChatSendRequestDto(trimmed)) }
                .onSuccess { resp ->
                    _uiState.value = _uiState.value.copy(
                        messages = _uiState.value.messages +
                            resp.user_message +
                            resp.assistant_message,
                        isSending = false,
                    )
                }
                .onFailure { e ->
                    _uiState.value = _uiState.value.copy(
                        isSending = false,
                        error = e.toUserMessage("Couldn't reach the coach. Try again."),
                    )
                }
        }
    }

    fun resetConversation() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSending = true, error = null)
            runCatching { api.resetChat() }
                .onSuccess {
                    _uiState.value = _uiState.value.copy(
                        messages = emptyList(),
                        isSending = false,
                    )
                }
                .onFailure { e ->
                    _uiState.value = _uiState.value.copy(
                        isSending = false,
                        error = e.toUserMessage("Couldn't reset the chat. Try again."),
                    )
                }
        }
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }
}
