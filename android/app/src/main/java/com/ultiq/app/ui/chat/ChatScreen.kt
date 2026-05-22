package com.ultiq.app.ui.chat

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Alarm
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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ultiq.app.data.remote.dto.ChatMessageDto
import com.ultiq.app.data.remote.dto.ParsedCalendarFieldsDto
import com.ultiq.app.data.remote.dto.ProposedAlarmFieldsDto
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
                                AnimatedTurn {
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
                                        is ChatTurn.AlarmProposal -> AlarmProposalCard(
                                            invocation = t.invocation,
                                            state = t.state,
                                            onCreate = { viewModel.confirmAlarmProposal(t.invocation.id) },
                                            onCancel = { viewModel.cancelAlarmProposal(t.invocation.id) },
                                        )
                                    }
                                }
                            }
                            if (state.isSending) {
                                item { AnimatedTurn { TypingIndicator() } }
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

/// Wraps each LazyColumn item with a fade + slide-in + placement spring so
/// new turns settle in instead of snapping. `animateItem()` (Compose 1.7+)
/// handles entrance, placement, and removal at the item level.
@Composable
private fun LazyItemScope.AnimatedTurn(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier.animateItem(
            fadeInSpec = tween(durationMillis = 240),
            placementSpec = spring(
                stiffness = Spring.StiffnessMediumLow,
                dampingRatio = Spring.DampingRatioMediumBouncy,
            ),
            fadeOutSpec = tween(durationMillis = 120),
        ),
    ) { content() }
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
    // Assistant turns render inline Markdown (**bold**, *italic*, `code`).
    // User turns stay plain — they typed it, no point parsing their input.
    val styled = remember(message.content, isUser) {
        if (isUser) AnnotatedString(message.content) else parseInlineMarkdown(message.content)
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
                styled,
                style = MaterialTheme.typography.bodyMedium,
                color = textColor,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }
    }
}

/// Tiny inline-Markdown parser. Handles `**bold**`, `*italic*`, and
/// `` `code` `` inside a single paragraph of text. Everything else
/// passes through as plain text. Deliberately doesn't handle tables,
/// headers, links, or images — the system prompt forbids those and we
/// don't want to bring in a full Markdown library for a chat bubble.
private fun parseInlineMarkdown(text: String): AnnotatedString = buildAnnotatedString {
    var i = 0
    val n = text.length
    while (i < n) {
        when {
            i + 1 < n && text[i] == '*' && text[i + 1] == '*' -> {
                // **bold**
                val end = text.indexOf("**", startIndex = i + 2)
                if (end > i + 1) {
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) {
                        append(text.substring(i + 2, end))
                    }
                    i = end + 2
                } else {
                    append(text[i]); i++
                }
            }
            text[i] == '*' -> {
                // *italic*
                val end = text.indexOf('*', startIndex = i + 1)
                if (end > i) {
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                        append(text.substring(i + 1, end))
                    }
                    i = end + 1
                } else {
                    append(text[i]); i++
                }
            }
            text[i] == '`' -> {
                // `code`
                val end = text.indexOf('`', startIndex = i + 1)
                if (end > i) {
                    withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) {
                        append(text.substring(i + 1, end))
                    }
                    i = end + 1
                } else {
                    append(text[i]); i++
                }
            }
            else -> {
                append(text[i]); i++
            }
        }
    }
}

@Composable
private fun ToolStatusPill(invocation: ToolInvocationDto) {
    val name = invocation.name
    val isRead = name.startsWith("get_")
    val isError = invocation.status == "error"

    val (bg, fg, icon) = when {
        isError -> Triple(
            MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.onErrorContainer,
            Icons.Default.ErrorOutline,
        )
        invocation.committed -> Triple(
            Color(0xFFE6F4EA),
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

/// Inline confirm card for a proposed alarm. Visually distinct from the
/// calendar card (alarm icon + alarm-style time formatting) so the user
/// can tell which kind of side-effect they're about to confirm.
@Composable
private fun AlarmProposalCard(
    invocation: ToolInvocationDto,
    state: ProposalState,
    onCreate: () -> Unit,
    onCancel: () -> Unit,
) {
    val alarm = invocation.proposed_alarm ?: return
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
                        Icons.Default.Alarm,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "Proposed alarm",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    alarm.trigger_time_local,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    formatAlarmSubline(alarm),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!alarm.label.isNullOrBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        alarm.label,
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
                        "✓ Alarm set",
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

private fun formatAlarmSubline(alarm: ProposedAlarmFieldsDto): String {
    // Bit 0 = Sun … bit 6 = Sat. Decode into a human-friendly recurrence
    // string ("Mon-Fri", "Daily", "Sat/Sun", or "One-shot").
    val mask = alarm.days_of_week.toInt() and 0x7F
    val labels = listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
    val days = (0..6).filter { (mask shr it) and 1 == 1 }
    val cadence = when {
        days.isEmpty() -> "One-shot"
        days.size == 7 -> "Daily"
        days == listOf(1, 2, 3, 4, 5) -> "Mon-Fri"
        days == listOf(0, 6) -> "Weekends"
        else -> days.joinToString("/") { labels[it] }
    }
    val mission = when (alarm.mission_kind) {
        "math" -> "math mission"
        "shake" -> "shake mission"
        "photo" -> "photo mission"
        else -> "no mission"
    }
    return "$cadence · $mission"
}

private fun formatTimeRange(fields: ParsedCalendarFieldsDto): String {
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
