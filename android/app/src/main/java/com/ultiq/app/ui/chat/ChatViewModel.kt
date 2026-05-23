package com.ultiq.app.ui.chat

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ultiq.app.data.local.AppDatabase
import com.ultiq.app.data.remote.RetrofitClient
import com.ultiq.app.data.remote.dto.ChatMessageDto
import com.ultiq.app.data.remote.dto.ChatSendRequestDto
import com.ultiq.app.data.remote.dto.CommittedResourceDto
import com.ultiq.app.data.remote.dto.CreateCalendarEventDto
import com.ultiq.app.data.remote.dto.ParsedCalendarFieldsDto
import com.ultiq.app.data.remote.dto.ToolInvocationDto
import com.ultiq.app.data.repository.CalendarRepository
import com.ultiq.app.util.AlarmScheduler
import com.ultiq.app.util.TokenManager
import com.ultiq.app.util.toUserMessage
import kotlinx.coroutines.flow.firstOrNull
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

    /// Same shape as CalendarProposal but for `create_alarm` — separate
    /// variant because the rendered card and the confirm-action API call
    /// are different.
    data class AlarmProposal(
        val invocation: ToolInvocationDto,
        val state: ProposalState = ProposalState.Pending,
    ) : ChatTurn {
        override val key: String get() = "pa:${invocation.id}"
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
    private val db = AppDatabase.getInstance(application)
    // §10.x (v2.11.7) — Coach confirm-calendar path must route through the
    // repository (not raw Retrofit) so the local Room cache + the Calendar
    // tab's Flow pick up the new event. See confirmCalendarProposal().
    private val calendarRepository = CalendarRepository(
        calendarEventDao = db.calendarEventDao(),
        apiService = api,
        alarmScheduler = AlarmScheduler(application),
    )

    private val _uiState = MutableStateFlow(ChatUiState(isLoading = true))
    val uiState: StateFlow<ChatUiState> = _uiState

    private val _undoCues = MutableSharedFlow<UndoCue>(extraBufferCapacity = 4)
    val undoCues: SharedFlow<UndoCue> = _undoCues

    private var userId: String = ""

    init {
        viewModelScope.launch {
            userId = tokenManager.getUserId().firstOrNull() ?: ""
        }
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

        // Optimistic insert: show the user's bubble + a "thinking…" indicator
        // immediately. The server round-trip can take a few seconds on a
        // cold-cached first turn; without this the chat feels broken until
        // the response lands.
        val optimisticId = "local-${java.util.UUID.randomUUID()}"
        val optimisticTurn = ChatTurn.UserText(
            ChatMessageDto(
                id = optimisticId,
                role = "user",
                content = trimmed,
                created_at = OffsetDateTime.now().toString(),
            )
        )
        _uiState.value = _uiState.value.copy(
            turns = _uiState.value.turns + optimisticTurn,
            isSending = true,
            error = null,
        )

        viewModelScope.launch {
            val now = OffsetDateTime.now().toString()
            runCatching {
                api.sendChatMessage(ChatSendRequestDto(content = trimmed, now_local = now))
            }
                .onSuccess { resp ->
                    // Replace the optimistic turn with the server-persisted
                    // one (so the id stabilises) and append tool turns + the
                    // assistant reply in order.
                    val withReplacedUser = _uiState.value.turns.map { turn ->
                        if (turn is ChatTurn.UserText && turn.message.id == optimisticId) {
                            ChatTurn.UserText(resp.user_message)
                        } else turn
                    }
                    val appendTurns = buildList {
                        for (inv in resp.tool_invocations) {
                            when {
                                inv.status == "proposed" && inv.proposed_event != null ->
                                    add(ChatTurn.CalendarProposal(invocation = inv))
                                inv.status == "proposed" && inv.proposed_alarm != null ->
                                    add(ChatTurn.AlarmProposal(invocation = inv))
                                else ->
                                    add(ChatTurn.ToolStatus(inv))
                            }
                        }
                        add(ChatTurn.AssistantText(resp.assistant_message))
                    }
                    _uiState.value = _uiState.value.copy(
                        turns = withReplacedUser + appendTurns,
                        isSending = false,
                    )
                    for (inv in resp.tool_invocations) {
                        val r = inv.committed_resource
                        if (inv.committed && r != null) {
                            _undoCues.tryEmit(UndoCue(resource = r, message = inv.summary))
                        }
                    }
                }
                .onFailure { e ->
                    // Roll the optimistic bubble back on failure so the user
                    // can edit + retry without a phantom message lingering.
                    val rolled = _uiState.value.turns.filterNot {
                        it is ChatTurn.UserText && it.message.id == optimisticId
                    }
                    _uiState.value = _uiState.value.copy(
                        turns = rolled,
                        isSending = false,
                        error = e.toUserMessage("Couldn't reach the coach. Try again."),
                    )
                }
        }
    }

    /// User tapped Create on a proposed calendar event. Goes through
    /// CalendarRepository so the local Room cache is updated alongside the
    /// backend POST — without this, the Calendar tab's Flow has nothing to
    /// re-emit and the new event only shows up after the user navigates
    /// away + back (which triggers CalendarViewModel.sync()). v2.11.7
    /// fixes this: pre-2.11.7 the call was a raw `api.createCalendarEvent`
    /// and the comment claimed "the SSE bus + Room flow will pick up the
    /// new event automatically" — but Android has no SSE subscriber for
    /// calendar, that's a web-dashboard-only feature. Optimistically
    /// mutate the turn's state so the card stays in the conversation as a
    /// "Created" badge.
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
            calendarRepository.createEvent(body, userId)
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
    /// the Checklist / Sleep screen.
    fun undo(resource: CommittedResourceDto) {
        viewModelScope.launch {
            runCatching {
                when (resource.kind) {
                    "checklist" -> api.deleteChecklistItem(resource.id)
                    "checklist_complete" -> api.uncompleteChecklistItem(resource.id)
                    "sleep_record" -> api.deleteSleepRecord(resource.id)
                    else -> Unit
                }
            }.onFailure { e ->
                _uiState.value = _uiState.value.copy(
                    error = e.toUserMessage("Undo failed. You may need to delete it manually.")
                )
            }
        }
    }

    /// User tapped Create on a proposed alarm. POSTs the alarm with sane
    /// defaults (volume_pct=80, snooze 9/3, vibration on) and the mission
    /// config matching the requested mission_kind. SSE will replicate the
    /// new row to the Alarms tab.
    fun confirmAlarmProposal(invocationId: String) {
        val current = _uiState.value.turns
        val idx = current.indexOfFirst {
            it is ChatTurn.AlarmProposal && it.invocation.id == invocationId
        }
        if (idx < 0) return
        val proposal = current[idx] as ChatTurn.AlarmProposal
        if (proposal.state != ProposalState.Pending) return
        val fields = proposal.invocation.proposed_alarm ?: return

        _uiState.value = _uiState.value.copy(
            turns = current.toMutableList().also { it[idx] = proposal.copy(state = ProposalState.Creating) }
        )

        viewModelScope.launch {
            val body = proposedAlarmToCreateDto(fields)
            runCatching { api.createAlarm(body) }
                .onSuccess {
                    val turns = _uiState.value.turns.toMutableList()
                    val i = turns.indexOfFirst {
                        it is ChatTurn.AlarmProposal && it.invocation.id == invocationId
                    }
                    if (i >= 0) {
                        turns[i] = (turns[i] as ChatTurn.AlarmProposal)
                            .copy(state = ProposalState.Created)
                        _uiState.value = _uiState.value.copy(turns = turns)
                    }
                }
                .onFailure { e ->
                    val turns = _uiState.value.turns.toMutableList()
                    val i = turns.indexOfFirst {
                        it is ChatTurn.AlarmProposal && it.invocation.id == invocationId
                    }
                    if (i >= 0) {
                        turns[i] = (turns[i] as ChatTurn.AlarmProposal)
                            .copy(state = ProposalState.Pending)
                    }
                    _uiState.value = _uiState.value.copy(
                        turns = turns,
                        error = e.toUserMessage("Couldn't create the alarm. Try again."),
                    )
                }
        }
    }

    fun cancelAlarmProposal(invocationId: String) {
        val turns = _uiState.value.turns.toMutableList()
        val idx = turns.indexOfFirst {
            it is ChatTurn.AlarmProposal && it.invocation.id == invocationId
        }
        if (idx < 0) return
        val proposal = turns[idx] as ChatTurn.AlarmProposal
        if (proposal.state != ProposalState.Pending) return
        turns[idx] = proposal.copy(state = ProposalState.Cancelled)
        _uiState.value = _uiState.value.copy(turns = turns)
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
        // v2.13.4 — Carry the AI-parsed reminder offsets through. Null
        // here means "no explicit pref" → backend stores NULL → scheduler
        // uses the 15-min default. Coach can now emit ["1 hour and 5 min
        // before"] and have it actually take effect.
        reminder_minutes = fields.reminder_minutes,
    )

/// Map a Coach `proposed_alarm` into the wire-format CreateAlarmDto. The
/// fields Coach doesn't carry — volume, vibration, snooze — get filled
/// with the same defaults the Add-alarm screen uses for a fresh draft,
/// so the user can edit afterwards if they want different behaviour.
private fun proposedAlarmToCreateDto(fields: com.ultiq.app.data.remote.dto.ProposedAlarmFieldsDto): com.ultiq.app.data.remote.dto.CreateAlarmDto {
    val parts = fields.trigger_time_local.split(":")
    val hh = parts.getOrNull(0)?.toIntOrNull() ?: 7
    val mm = parts.getOrNull(1)?.toIntOrNull() ?: 0
    val triggerSql = "%02d:%02d:00".format(hh, mm)
    val missionConfig = when (fields.mission_kind) {
        "math" -> com.google.gson.JsonParser.parseString(
            com.ultiq.app.alarm.mission.MissionConfig.buildMath(
                com.ultiq.app.alarm.mission.MathDifficulty.MEDIUM,
                count = 3,
            )
        ).asJsonObject
        "shake" -> com.google.gson.JsonParser.parseString(
            com.ultiq.app.alarm.mission.MissionConfig.buildShake(
                com.ultiq.app.alarm.mission.ShakeIntensity.MEDIUM,
                shakesRequired = 30,
            )
        ).asJsonObject
        else -> com.google.gson.JsonObject()
    }
    return com.ultiq.app.data.remote.dto.CreateAlarmDto(
        id = null,
        label = fields.label,
        trigger_time_local = triggerSql,
        days_of_week = fields.days_of_week.toInt(),
        enabled = true,
        sound_uri = null,
        volume_pct = 80,
        volume_escalates = true,
        vibration = true,
        snooze_minutes = 9,
        snooze_max = 3,
        mission_kind = fields.mission_kind,
        mission_config = missionConfig,
    )
}

