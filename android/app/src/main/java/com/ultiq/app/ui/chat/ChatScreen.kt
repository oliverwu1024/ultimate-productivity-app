package com.ultiq.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ultiq.app.data.remote.dto.ChatMessageDto
import com.ultiq.app.data.remote.dto.ParsedCalendarFieldsDto
import com.ultiq.app.data.remote.dto.ToolInvocationDto
import java.time.OffsetDateTime

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    onBack: () -> Unit,
    viewModel: ChatViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val snackbarHost = remember { SnackbarHostState() }
    val listState = rememberLazyListState()
    var showResetDialog by remember { mutableStateOf(false) }

    // Auto-scroll to the bottom whenever the turn count changes so the
    // user sees the assistant's reply (and any tool pills) without
    // manual scrolling.
    LaunchedEffect(state.turns.size) {
        if (state.turns.isNotEmpty()) {
            listState.animateScrollToItem(state.turns.size - 1)
        }
    }

    LaunchedEffect(state.error) {
        state.error?.let {
            snackbarHost.showSnackbar(it)
            viewModel.clearError()
        }
    }

    // Subscribe once for the lifetime of the screen — undo cues from
    // auto-committed writes drive the snackbar's Undo action.
    LaunchedEffect(Unit) {
        viewModel.undoCues.collect { cue ->
            val result = snackbarHost.showSnackbar(
                message = cue.message,
                actionLabel = "Undo",
                duration = SnackbarDuration.Long,
                withDismissAction = true,
            )
            if (result == SnackbarResult.ActionPerformed) {
                viewModel.undo(cue.resource)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Coach") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(
                        onClick = { showResetDialog = true },
                        enabled = !state.isSending && state.turns.isNotEmpty(),
                    ) {
                        Icon(Icons.Default.RestartAlt, contentDescription = "Start a new chat")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHost) },
        modifier = Modifier.imePadding(),
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                when {
                    state.isLoading -> {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) { CircularProgressIndicator() }
                    }
                    state.turns.isEmpty() -> {
                        EmptyState()
                    }
                    else -> {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(
                                count = state.turns.size,
                                key = { i -> state.turns[i].key },
                            ) { i ->
                                when (val t = state.turns[i]) {
                                    is ChatTurn.UserText -> ChatBubble(t.message)
                                    is ChatTurn.AssistantText -> ChatBubble(t.message)
                                    is ChatTurn.ToolStatus -> ToolStatusPill(t.invocation)
                                    is ChatTurn.CalendarProposal -> CalendarProposalCard(
                                        invocation = t.invocation,
                                        state = t.state,
                                        onCreate = { viewModel.confirmCalendarProposal(t.invocation.id) },
                                        onCancel = { viewModel.cancelCalendarProposal(t.invocation.id) },
                                    )
                                }
                            }
                            if (state.isSending) {
                                item { TypingIndicator() }
                            }
                        }
                    }
                }
            }

            ChatComposer(
                isSending = state.isSending,
                onSend = viewModel::send,
            )
        }
    }

    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            title = { Text("Start a new chat?") },
            text = {
                Text(
                    "Your current conversation will be archived (we keep it for your " +
                        "records but the coach won't see it from here on).",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showResetDialog = false
                    viewModel.resetConversation()
                }) { Text("Start fresh") }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun ChatBubble(message: ChatMessageDto) {
    val isUser = message.role == "user"
    val alignment = if (isUser) Alignment.End else Alignment.Start
    val bubbleColor = if (isUser) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val textColor = if (isUser) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    val shape = if (isUser) {
        RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 4.dp)
    } else {
        RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomEnd = 16.dp, bottomStart = 4.dp)
    }
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = alignment,
    ) {
        Surface(
            shape = shape,
            color = bubbleColor,
            modifier = Modifier.widthIn(max = 320.dp),
        ) {
            Text(
                message.content,
                style = MaterialTheme.typography.bodyMedium,
                color = textColor,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }
    }
}

@Composable
private fun ToolStatusPill(invocation: ToolInvocationDto) {
    val name = invocation.name
    val isRead = name.startsWith("get_")
    val isError = invocation.status == "error"

    // Read tools: muted grey. Committed writes: soft green. Errors: muted
    // amber. Keeps the chat from feeling busy when the model fires off a
    // chain of reads.
    val (bg, fg, icon) = when {
        isError -> Triple(
            MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.onErrorContainer,
            Icons.Default.ErrorOutline,
        )
        invocation.committed -> Triple(
            Color(0xFFE6F4EA), // soft mint
            Color(0xFF1E5631),
            Icons.Default.Check,
        )
        isRead -> Triple(
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.onSurfaceVariant,
            Icons.Default.Search,
        )
        else -> Triple(
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.onSurfaceVariant,
            Icons.Default.Check,
        )
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start,
    ) {
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = bg,
            modifier = Modifier.widthIn(max = 320.dp),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = fg,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    invocation.summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = fg,
                )
            }
        }
    }
}

@Composable
private fun CalendarProposalCard(
    invocation: ToolInvocationDto,
    state: ProposalState,
    onCreate: () -> Unit,
    onCancel: () -> Unit,
) {
    val event = invocation.proposed_event ?: return
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start,
    ) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface,
            ),
            modifier = Modifier
                .widthIn(max = 320.dp)
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.25f),
                    shape = RoundedCornerShape(14.dp),
                ),
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.CalendarMonth,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "Proposed event",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    event.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    formatTimeRange(event),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!event.description.isNullOrBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        event.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                Spacer(Modifier.height(10.dp))
                when (state) {
                    ProposalState.Pending -> Row(horizontalArrangement = Arrangement.End) {
                        Spacer(Modifier.weight(1f))
                        OutlinedButton(onClick = onCancel) { Text("Cancel") }
                        Spacer(Modifier.width(8.dp))
                        Button(onClick = onCreate) { Text("Create") }
                    }
                    ProposalState.Creating -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 1.5.dp,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Creating…", style = MaterialTheme.typography.bodySmall)
                    }
                    ProposalState.Created -> Text(
                        "✓ Added to your calendar",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    ProposalState.Cancelled -> Text(
                        "Cancelled",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private fun formatTimeRange(fields: ParsedCalendarFieldsDto): String {
    // Backend hands UTC strings; render them in the device's local tz so
    // the user sees their own time. Failing to parse falls through to the
    // raw strings so we never crash the chat over a date format quirk.
    return try {
        val start = OffsetDateTime.parse(fields.start_time)
            .atZoneSameInstant(java.time.ZoneId.systemDefault())
        val end = OffsetDateTime.parse(fields.end_time)
            .atZoneSameInstant(java.time.ZoneId.systemDefault())
        val sameDay = start.toLocalDate() == end.toLocalDate()
        val dateFmt = java.time.format.DateTimeFormatter.ofPattern("EEE d MMM")
        val timeFmt = java.time.format.DateTimeFormatter.ofPattern("HH:mm")
        if (sameDay) {
            "${start.format(dateFmt)} · ${start.format(timeFmt)} – ${end.format(timeFmt)}"
        } else {
            "${start.format(dateFmt)} ${start.format(timeFmt)} → ${end.format(dateFmt)} ${end.format(timeFmt)}"
        }
    } catch (_: Exception) {
        "${fields.start_time} → ${fields.end_time}"
    }
}

@Composable
private fun TypingIndicator() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start,
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 1.5.dp,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "thinking…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun EmptyState() {
    Box(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    "Talk to your coach",
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "Ask about sleep, focus blocks, or weekly planning. The coach can " +
                        "look at your data, add checklist items for you, and draft " +
                        "calendar events you confirm before they land.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ChatComposer(
    isSending: Boolean,
    onSend: (String) -> Unit,
) {
    var input by remember { mutableStateOf("") }
    val canSend = !isSending && input.trim().isNotEmpty()
    Surface(
        tonalElevation = 3.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Message your coach…") },
                maxLines = 4,
                enabled = !isSending,
            )
            Spacer(Modifier.width(8.dp))
            IconButton(
                onClick = {
                    onSend(input)
                    input = ""
                },
                enabled = canSend,
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(
                        if (canSend) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceVariant
                    ),
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.Send,
                    contentDescription = "Send",
                    tint = if (canSend) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}
