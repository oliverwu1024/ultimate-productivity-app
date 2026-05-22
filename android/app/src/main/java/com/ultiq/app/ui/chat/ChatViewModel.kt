package com.ultiq.app.ui.chat

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ultiq.app.data.remote.RetrofitClient
import com.ultiq.app.data.remote.dto.ChatMessageDto
import com.ultiq.app.data.remote.dto.ChatSendRequestDto
import com.ultiq.app.data.remote.dto.CommittedResourceDto
import com.ultiq.app.data.remote.dto.CreateCalendarEventDto
import com.ultiq.app.data.remote.dto.ParsedCalendarFieldsDto
import com.ultiq.app.data.remote.dto.ToolInvocationDto
import com.ultiq.app.util.TokenManager
import com.ultiq.app.util.toUserMessage
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.OffsetDateTime

/// §9.x — Coach Chat view-model. Single active conversation per user; the
/// server tracks it via `ai_conversations.updated_at`, so we don't need a
/// local conversation_id. State is a sealed list of "turns" so a single
/// user send can fan out into N tool-status pills + a calendar proposal
/// card + the assistant's final text, all keyed and animated as separate
/// items in the LazyColumn.

sealed interface ChatTurn {
    /// Stable key for LazyColumn diffing — survives state mutations.
    val key: String

    data class UserText(val message: ChatMessageDto) : ChatTurn {
        override val key: String get() = "u:${message.id}"
    }

    data class AssistantText(val message: ChatMessageDto) : ChatTurn {
        override val key: String get() = "a:${message.id}"
    }

    /// Read-tool status pill or committed write-tool confirmation. The
    /// `invocation.committed_resource` decides whether the UI renders an
    /// Undo affordance.
    data class ToolStatus(val invocation: ToolInvocationDto) : ChatTurn {
        override val key: String get() = "t:${invocation.id}"
    }

    /// Inline confirm card for a proposed calendar event. Lifecycle:
    ///   Pending → Creating → Created
    ///   Pending → Cancelled
    data class CalendarProposal(
        val invocation: ToolInvocationDto,
        val state: ProposalState = ProposalState.Pending,
    ) : ChatTurn {
        override val key: String get() = "p:${invocation.id}"
    }
}

enum class ProposalState { Pending, Creating, Created, Cancelled }

data class ChatUiState(
    val turns: List<ChatTurn> = emptyList(),
    val isLoading: Boolean = false,
    val isSending: Boolean = false,
    val error: String? = null,
)

/// One-shot UI event for the undo snackbar. The screen collects these and
/// shows a snackbar with an Undo action; tapping the action fires
/// [ChatViewModel.undo] with the same resource.
data class UndoCue(
    val resource: CommittedResourceDto,
    /// Short message the screen shows in the snackbar.
    val message: String,
)

class ChatViewModel(application: Application) : AndroidViewModel(application) {
    private val tokenManager = TokenManager(application)
    private val api = RetrofitClient.create(tokenManager)

    private val _uiState = MutableStateFlow(ChatUiState(isLoading = true))
    val uiState: StateFlow<ChatUiState> = _uiState

    private val _undoCues = MutableSharedFlow<UndoCue>(extraBufferCapacity = 4)
    val undoCues: SharedFlow<UndoCue> = _undoCues

    init {
        loadHistory()
    }

    fun loadHistory() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            runCatching { api.listChatMessages(limit = null) }
                .onSuccess { messages ->
                    _uiState.value = _uiState.value.copy(
                        turns = messages.map { msg ->
                            if (msg.role == "user") ChatTurn.UserText(msg)
                            else ChatTurn.AssistantText(msg)
                        },
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
            val now = OffsetDateTime.now().toString()
            runCatching {
                api.sendChatMessage(ChatSendRequestDto(content = trimmed, now_local = now))
            }
                .onSuccess { resp ->
                    val newTurns = buildList {
                        add(ChatTurn.UserText(resp.user_message))
                        for (inv in resp.tool_invocations) {
                            if (inv.status == "proposed" && inv.proposed_event != null) {
                                add(ChatTurn.CalendarProposal(invocation = inv))
                            } else {
                                add(ChatTurn.ToolStatus(inv))
                            }
                        }
                        add(ChatTurn.AssistantText(resp.assistant_message))
                    }
                    _uiState.value = _uiState.value.copy(
                        turns = _uiState.value.turns + newTurns,
                        isSending = false,
                    )
                    // Surface undo cues for any auto-committed writes.
                    for (inv in resp.tool_invocations) {
                        val r = inv.committed_resource
                        if (inv.committed && r != null) {
                            _undoCues.tryEmit(UndoCue(resource = r, message = inv.summary))
                        }
                    }
                }
                .onFailure { e ->
                    _uiState.value = _uiState.value.copy(
                        isSending = false,
                        error = e.toUserMessage("Couldn't reach the coach. Try again."),
                    )
                }
        }
    }

    /// User tapped Create on a proposed calendar event. Hits POST /calendar
    /// directly — the SSE bus + Room flow will pick up the new event on
    /// the Calendar screen automatically. Optimistically mutate the turn's
    /// state so the card stays in the conversation as a "Created" badge.
    fun confirmCalendarProposal(invocationId: String) {
        val current = _uiState.value.turns
        val idx = current.indexOfFirst {
            it is ChatTurn.CalendarProposal && it.invocation.id == invocationId
        }
        if (idx < 0) return
        val proposal = current[idx] as ChatTurn.CalendarProposal
        if (proposal.state != ProposalState.Pending) return
        val fields = proposal.invocation.proposed_event ?: return

        // Optimistic UI: flip to Creating.
        _uiState.value = _uiState.value.copy(
            turns = current.toMutableList().also { it[idx] = proposal.copy(state = ProposalState.Creating) }
        )

        viewModelScope.launch {
            val body = parsedFieldsToCreateDto(fields)
            runCatching { api.createCalendarEvent(body) }
                .onSuccess {
                    val turns = _uiState.value.turns.toMutableList()
                    val i = turns.indexOfFirst {
                        it is ChatTurn.CalendarProposal && it.invocation.id == invocationId
                    }
                    if (i >= 0) {
                        turns[i] = (turns[i] as ChatTurn.CalendarProposal)
                            .copy(state = ProposalState.Created)
                        _uiState.value = _uiState.value.copy(turns = turns)
                    }
                }
                .onFailure { e ->
                    // Roll the optimistic flip back; bubble the error up.
                    val turns = _uiState.value.turns.toMutableList()
                    val i = turns.indexOfFirst {
                        it is ChatTurn.CalendarProposal && it.invocation.id == invocationId
                    }
                    if (i >= 0) {
                        turns[i] = (turns[i] as ChatTurn.CalendarProposal)
                            .copy(state = ProposalState.Pending)
                    }
                    _uiState.value = _uiState.value.copy(
                        turns = turns,
                        error = e.toUserMessage("Couldn't create the event. Try again."),
                    )
                }
        }
    }

    fun cancelCalendarProposal(invocationId: String) {
        val turns = _uiState.value.turns.toMutableList()
        val idx = turns.indexOfFirst {
            it is ChatTurn.CalendarProposal && it.invocation.id == invocationId
        }
        if (idx < 0) return
        val proposal = turns[idx] as ChatTurn.CalendarProposal
        if (proposal.state != ProposalState.Pending) return
        turns[idx] = proposal.copy(state = ProposalState.Cancelled)
        _uiState.value = _uiState.value.copy(turns = turns)
    }

    /// User tapped Undo on the snackbar for a committed write. Routes to
    /// the matching reversal endpoint. SSE will replicate the reversal on
    /// the Checklist screen.
    fun undo(resource: CommittedResourceDto) {
        viewModelScope.launch {
            runCatching {
                when (resource.kind) {
                    "checklist" -> api.deleteChecklistItem(resource.id)
                    "checklist_complete" -> api.uncompleteChecklistItem(resource.id)
                    else -> Unit
                }
            }.onFailure { e ->
                _uiState.value = _uiState.value.copy(
                    error = e.toUserMessage("Undo failed. You may need to delete it manually.")
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
                        turns = emptyList(),
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

private fun parsedFieldsToCreateDto(fields: ParsedCalendarFieldsDto): CreateCalendarEventDto =
    CreateCalendarEventDto(
        title = fields.title,
        description = fields.description,
        start_time = fields.start_time,
        end_time = fields.end_time,
        category = fields.category,
        priority = fields.priority,
        is_recurring = false,
        recurrence_rule = null,
        color = null,
        is_done = false,
    )

