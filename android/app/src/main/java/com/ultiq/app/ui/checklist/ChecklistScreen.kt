package com.ultiq.app.ui.checklist

import android.app.DatePickerDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.FilterChip
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ultiq.app.data.local.entity.ChecklistEntity
import com.ultiq.app.ui.common.AiParsePromptDialog
import com.ultiq.app.ui.common.AiParseSurface
import com.ultiq.app.ui.common.MascotEmptyState
import com.ultiq.app.ui.copy.WarmCopy
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val dateFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE, MMM d")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChecklistScreen(
    onNavigateToWeeklyPlanner: () -> Unit = {},
    viewModel: ChecklistViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    // §delete-consistency: every destructive action in the app gets a
    // confirm dialog (Sleep + Alarms already had one — this brings
    // Checklist into line). Lives screen-local so a backgrounded screen
    // forgets the pending row.
    var pendingDelete by remember { mutableStateOf<ChecklistEntity?>(null) }

    LaunchedEffect(state.error) {
        state.error?.let { msg ->
            scope.launch {
                snackbarHostState.showSnackbar(msg)
                viewModel.clearError()
            }
        }
    }

    val total = state.openItems.size + state.completedItems.size
    val done = state.completedItems.size
    val progress = if (total == 0) 0f else done.toFloat() / total.toFloat()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Checklist") },
                actions = {
                    TextButton(onClick = viewModel::jumpToToday) { Text("Today") }
                },
            )
        },
        floatingActionButton = {
            // §9.5 — Small "AI" FAB above the main Add FAB.
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SmallFloatingActionButton(
                    onClick = viewModel::showAiDialog,
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                ) {
                    Icon(Icons.Default.AutoAwesome, "Quick add with AI")
                }
                ExtendedFloatingActionButton(
                    onClick = viewModel::openAddDialog,
                    icon = { Icon(Icons.Default.Add, null) },
                    text = { Text("Add") },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            DateSelector(
                date = state.selectedDate,
                onPrev = viewModel::selectPreviousDay,
                onNext = viewModel::selectNextDay,
            )

            ProgressBar(done = done, total = total, progress = progress)

            if (state.yesterdayOpenItems.isNotEmpty() && state.selectedDate == LocalDate.now()) {
                CarryOverBanner(
                    count = state.yesterdayOpenItems.size,
                    onBringForward = viewModel::bringYesterdayForward,
                    onDismiss = viewModel::dismissYesterdayBanner,
                )
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (state.openItems.isEmpty() && state.completedItems.isEmpty()) {
                    item { EmptyState() }
                }

                if (state.openItems.isNotEmpty()) {
                    item { SectionLabel("Open") }
                    items(state.openItems, key = { it.id }) { item ->
                        ChecklistRow(
                            item = item,
                            isDoneNow = false,
                            onToggle = { viewModel.toggleCompleted(item) },
                            onEdit = { viewModel.openEditDialog(item) },
                            onDelete = { pendingDelete = item },
                        )
                    }
                }

                if (state.completedItems.isNotEmpty()) {
                    item {
                        CompletedSection(
                            items = state.completedItems,
                            onToggle = viewModel::toggleCompleted,
                            onEdit = viewModel::openEditDialog,
                            onDelete = { pendingDelete = it },
                            todayEpochDay = state.selectedDate.toEpochDay(),
                        )
                    }
                }

                item { Spacer(Modifier.height(80.dp)) }
            }
        }

        if (state.showAddDialog) {
            ChecklistEditDialog(
                editing = state.editingItem,
                defaultDueDate = state.selectedDate,
                prefill = state.aiPrefill,
                onDismiss = viewModel::dismissDialog,
                onSave = viewModel::saveItem,
            )
        }

        if (state.showAiDialog) {
            AiParsePromptDialog(
                surface = AiParseSurface.CHECKLIST,
                loading = state.aiLoading,
                error = state.aiError,
                onSubmit = { text -> viewModel.submitAiParse(text) },
                onDismiss = viewModel::dismissAiDialog,
            )
        }

        if (state.showWeeklyPrompt) {
            AlertDialog(
                onDismissRequest = viewModel::dismissWeeklyPrompt,
                title = { Text("Plan your week?") },
                text = {
                    Text(
                        "Sketch what you want to get done each day. " +
                            "You can always add more or skip and fill in as you go.",
                    )
                },
                confirmButton = {
                    Button(onClick = {
                        viewModel.dismissWeeklyPrompt()
                        onNavigateToWeeklyPlanner()
                    }) { Text("Plan now") }
                },
                dismissButton = {
                    TextButton(onClick = viewModel::dismissWeeklyPrompt) { Text("Skip") }
                },
            )
        }

        pendingDelete?.let { target ->
            AlertDialog(
                onDismissRequest = { pendingDelete = null },
                title = { Text("Delete this item?") },
                text = { Text("'${target.title}' will be removed.") },
                confirmButton = {
                    TextButton(onClick = {
                        viewModel.deleteItem(target)
                        pendingDelete = null
                    }) { Text("Delete") }
                },
                dismissButton = {
                    TextButton(onClick = { pendingDelete = null }) { Text("Cancel") }
                },
            )
        }
    }
}

@Composable
private fun CarryOverBanner(
    count: Int,
    onBringForward: () -> Unit,
    onDismiss: () -> Unit,
) {
    val label = if (count == 1) "1 unfinished from yesterday" else "$count unfinished from yesterday"
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onBringForward) { Text("Bring forward") }
            TextButton(onClick = onDismiss) { Text("Dismiss") }
        }
    }
}

@Composable
private fun DateSelector(date: LocalDate, onPrev: () -> Unit, onNext: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            IconButton(onClick = onPrev) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Previous day")
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                val label = when (date) {
                    LocalDate.now() -> "Today"
                    LocalDate.now().plusDays(1) -> "Tomorrow"
                    LocalDate.now().minusDays(1) -> "Yesterday"
                    else -> date.format(dateFormatter)
                }
                Text(label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                if (label != date.format(dateFormatter)) {
                    Text(
                        date.format(dateFormatter),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            IconButton(onClick = onNext) {
                Icon(Icons.AutoMirrored.Filled.ArrowForward, "Next day")
            }
        }
    }
}

@Composable
private fun ProgressBar(done: Int, total: Int, progress: Float) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                if (total == 0) "Nothing for today" else "$done of $total done",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (total > 0) {
                Text(
                    "${(progress * 100).toInt()}%",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp, top = 8.dp),
    )
}

@Composable
private fun EmptyState() {
    val (title, body) = WarmCopy.checklistEmpty()
    MascotEmptyState(title = title, body = body)
}

@Composable
private fun ChecklistRow(
    item: ChecklistEntity,
    /// `true` when this row represents a "done-for-the-selected-day" state.
    /// Non-recurring rows pass `item.completed`; recurring rows pass the
    /// computed `lastCompletedEpochDay == day` check from the parent so
    /// the checkbox tick reflects the actual displayed state.
    isDoneNow: Boolean,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onEdit),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = isDoneNow, onCheckedChange = { onToggle() })
            Column(modifier = Modifier.weight(1f).padding(horizontal = 4.dp)) {
                Text(
                    item.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    textDecoration = if (isDoneNow) TextDecoration.LineThrough else null,
                )
                if (!item.description.isNullOrBlank()) {
                    Text(
                        item.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 4.dp),
                ) {
                    PriorityChip(priority = item.priority)
                    item.estimatedMinutes?.let { mins ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.Schedule,
                                null,
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.width(2.dp))
                            Text(
                                "$mins min",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                scheduleLabel(item)?.let { label ->
                    Text(
                        label,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Default.Delete,
                    "Delete",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun PriorityChip(priority: Int) {
    val (label, color) = when (priority) {
        2 -> "High" to MaterialTheme.colorScheme.error
        1 -> "Med" to MaterialTheme.colorScheme.tertiary
        else -> "Low" to MaterialTheme.colorScheme.outline
    }
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = color.copy(alpha = 0.18f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .background(color = color, shape = CircleShape),
            )
            Spacer(Modifier.width(6.dp))
            Text(label, style = MaterialTheme.typography.labelSmall, color = color)
        }
    }
}

@Composable
private fun CompletedSection(
    items: List<ChecklistEntity>,
    onToggle: (ChecklistEntity) -> Unit,
    onEdit: (ChecklistEntity) -> Unit,
    onDelete: (ChecklistEntity) -> Unit,
    todayEpochDay: Long,
) {
    var expanded by remember { mutableStateOf(false) }
    Column(modifier = Modifier.padding(top = 16.dp)) {
        Surface(
            color = Color.Transparent,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded },
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    "Completed (${items.size})".uppercase(),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (expanded) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items.forEach { item ->
                    // §3 — recurring rows report "done-for-today" via the
                    // stamped epoch day; non-recurring rows use the boolean.
                    val doneNow = if (item.recurrenceDaysMask != 0) {
                        item.lastCompletedEpochDay == todayEpochDay
                    } else {
                        item.completed
                    }
                    ChecklistRow(
                        item = item,
                        isDoneNow = doneNow,
                        onToggle = { onToggle(item) },
                        onEdit = { onEdit(item) },
                        onDelete = { onDelete(item) },
                    )
                }
            }
        }
    }
}

private enum class ScheduleMode { ONE_OFF, RECURRING, UNTIL_DUE }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChecklistEditDialog(
    editing: ChecklistEntity?,
    defaultDueDate: LocalDate,
    /// §9.5 — AI-parsed values used as initial state when the user came in
    /// via the AI quick-add flow. Only consulted when `editing` is null.
    prefill: AiChecklistPrefill?,
    onDismiss: () -> Unit,
    /// `alsoCreateTodayOneOff` is true when the user opted in to the
    /// "include today as well" prompt for a recurring task whose mask
    /// doesn't already cover today's weekday. The VM creates a separate
    /// one-off row for today in that case.
    onSave: (title: String, description: String?, dueDate: LocalDate, estimatedMinutes: Int?, priority: Int, recurrenceDaysMask: Int, showUntilDue: Boolean, alsoCreateTodayOneOff: Boolean) -> Unit,
) {
    val context = LocalContext.current
    var title by remember {
        mutableStateOf(editing?.title ?: prefill?.title ?: "")
    }
    var description by remember {
        mutableStateOf(editing?.description ?: prefill?.description ?: "")
    }
    var dueDate by remember {
        mutableStateOf(
            editing?.dueDateEpochDay?.let(LocalDate::ofEpochDay)
                ?: prefill?.dueDate
                ?: defaultDueDate,
        )
    }
    var minutesText by remember {
        mutableStateOf(
            editing?.estimatedMinutes?.toString()
                ?: prefill?.estimatedMinutes?.toString()
                ?: "",
        )
    }
    var priority by remember {
        mutableStateOf(editing?.priority ?: prefill?.priority ?: 1)
    }

    val initialMode = remember(editing) {
        when {
            editing == null -> ScheduleMode.ONE_OFF
            editing.recurrenceDaysMask != 0 -> ScheduleMode.RECURRING
            editing.showUntilDue -> ScheduleMode.UNTIL_DUE
            else -> ScheduleMode.ONE_OFF
        }
    }
    var mode by remember { mutableStateOf(initialMode) }
    var recurrenceMask by remember { mutableStateOf(editing?.recurrenceDaysMask ?: 0) }
    /// §4 — when the user clicks Save on a new recurring task whose mask
    /// doesn't cover today's weekday, we pause the save and ask whether to
    /// also drop a one-off in today's list.
    var showIncludeTodayPrompt by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (editing == null) "New item" else "Edit item") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Title") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Description (optional)") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                )

                Text("Schedule", style = MaterialTheme.typography.labelMedium)
                val modeLabels = listOf(
                    ScheduleMode.ONE_OFF to "Today",
                    ScheduleMode.RECURRING to "Repeat",
                    ScheduleMode.UNTIL_DUE to "By due",
                )
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    modeLabels.forEachIndexed { i, (value, label) ->
                        SegmentedButton(
                            selected = mode == value,
                            onClick = { mode = value },
                            shape = SegmentedButtonDefaults.itemShape(i, modeLabels.size),
                            label = { Text(label) },
                        )
                    }
                }
                // §UX: tiny helper text under each mode so the option label
                // ("Today" / "Repeat" / "By due") isn't load-bearing on its own.
                Text(
                    when (mode) {
                        ScheduleMode.ONE_OFF -> "Happens on the picked day only."
                        ScheduleMode.RECURRING -> "Pick which days of the week it repeats on."
                        ScheduleMode.UNTIL_DUE -> "Shows every day until the due date. Marking done removes it everywhere."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                if (mode == ScheduleMode.RECURRING) {
                    DayOfWeekChips(mask = recurrenceMask, onChange = { recurrenceMask = it })
                }

                OutlinedButton(
                    onClick = {
                        DatePickerDialog(
                            context,
                            { _, y, m, d -> dueDate = LocalDate.of(y, m + 1, d) },
                            dueDate.year,
                            dueDate.monthValue - 1,
                            dueDate.dayOfMonth,
                        ).show()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    val prefix = when (mode) {
                        ScheduleMode.ONE_OFF -> "On"
                        ScheduleMode.RECURRING -> "Starts"
                        ScheduleMode.UNTIL_DUE -> "Due"
                    }
                    Text("$prefix: ${dueDate.format(dateFormatter)}")
                }

                OutlinedTextField(
                    value = minutesText,
                    onValueChange = { v -> minutesText = v.filter(Char::isDigit).take(4) },
                    label = { Text("Estimated minutes (optional)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Text("Priority", style = MaterialTheme.typography.labelMedium)
                val labels = listOf("Low" to 0, "Med" to 1, "High" to 2)
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    labels.forEachIndexed { i, (label, value) ->
                        SegmentedButton(
                            selected = priority == value,
                            onClick = { priority = value },
                            shape = SegmentedButtonDefaults.itemShape(i, labels.size),
                            label = { Text(label) },
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                val mask = if (mode == ScheduleMode.RECURRING) {
                    // Empty mask in recurring mode = treat as everyday so we never
                    // store a row that's recurring-but-invisible.
                    if (recurrenceMask == 0) 0b1111111 else recurrenceMask
                } else 0
                val showUntilDue = mode == ScheduleMode.UNTIL_DUE

                // §4 — only prompt when we're adding a new recurring task
                // whose weekday mask doesn't already cover today. We use
                // the system's "today" rather than the selected day, since
                // the prompt is about the user's *current* daily list.
                val today = LocalDate.now()
                val todayBit = 1 shl (today.dayOfWeek.value % 7)
                val needsTodayPrompt = editing == null &&
                    mode == ScheduleMode.RECURRING &&
                    (mask and todayBit) == 0

                if (needsTodayPrompt) {
                    showIncludeTodayPrompt = true
                } else {
                    onSave(
                        title,
                        description.ifBlank { null },
                        dueDate,
                        minutesText.toIntOrNull(),
                        priority,
                        mask,
                        showUntilDue,
                        false,
                    )
                }
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )

    if (showIncludeTodayPrompt) {
        val today = LocalDate.now()
        val mask = if (recurrenceMask == 0) 0b1111111 else recurrenceMask
        val showUntilDue = mode == ScheduleMode.UNTIL_DUE
        val params = remember(title, description, dueDate, minutesText, priority, mask, showUntilDue) {
            SaveCommit(
                title = title,
                description = description.ifBlank { null },
                dueDate = dueDate,
                minutes = minutesText.toIntOrNull(),
                priority = priority,
                mask = mask,
                showUntilDue = showUntilDue,
            )
        }
        AlertDialog(
            onDismissRequest = { showIncludeTodayPrompt = false },
            title = { Text("Include today?") },
            text = {
                Text(
                    "Today is ${today.dayOfWeek.name.lowercase().replaceFirstChar { it.uppercase() }}, " +
                        "which isn't in this task's repeat schedule. Add it to today's list as well?",
                )
            },
            confirmButton = {
                Button(onClick = {
                    showIncludeTodayPrompt = false
                    onSave(
                        params.title,
                        params.description,
                        params.dueDate,
                        params.minutes,
                        params.priority,
                        params.mask,
                        params.showUntilDue,
                        true,
                    )
                }) { Text("Yes, add today") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showIncludeTodayPrompt = false
                    onSave(
                        params.title,
                        params.description,
                        params.dueDate,
                        params.minutes,
                        params.priority,
                        params.mask,
                        params.showUntilDue,
                        false,
                    )
                }) { Text("No, future only") }
            },
        )
    }
}

private data class SaveCommit(
    val title: String,
    val description: String?,
    val dueDate: LocalDate,
    val minutes: Int?,
    val priority: Int,
    val mask: Int,
    val showUntilDue: Boolean,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DayOfWeekChips(mask: Int, onChange: (Int) -> Unit) {
    // §UX: app weeks elsewhere start Sunday (matches the alarm RepeatPicker
    // and the system locale most users come in on); bit 0 = Sun … bit 6 = Sat.
    val labels = listOf("S", "M", "T", "W", "T", "F", "S")
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        labels.forEachIndexed { bit, letter ->
            val on = (mask shr bit) and 1 == 1
            FilterChip(
                selected = on,
                onClick = {
                    val newMask = if (on) mask and (1 shl bit).inv() else mask or (1 shl bit)
                    onChange(newMask)
                },
                label = { Text(letter) },
                modifier = Modifier.weight(1f),
            )
        }
    }
    // §UX: AlertDialog is narrow and TextButton's default 12dp horizontal
    // padding pushed "Weekends" past the right edge so the "s" wrapped onto
    // a second line. Tight contentPadding + softWrap=false + equal weights
    // keeps all three labels single-line.
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        val tightPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp)
        TextButton(
            onClick = { onChange(0b1111111) },
            contentPadding = tightPadding,
            modifier = Modifier.weight(1f),
        ) { Text("Every day", maxLines = 1, softWrap = false) }
        TextButton(
            onClick = { onChange(0b0111110) },
            contentPadding = tightPadding,
            modifier = Modifier.weight(1f),
        ) { Text("Weekdays", maxLines = 1, softWrap = false) }
        TextButton(
            onClick = { onChange(0b1000001) },
            contentPadding = tightPadding,
            modifier = Modifier.weight(1f),
        ) { Text("Weekends", maxLines = 1, softWrap = false) }
    }
}

/** Human-readable summary of an item's schedule, shown under each row. */
internal fun scheduleLabel(item: ChecklistEntity): String? {
    if (item.recurrenceDaysMask != 0) {
        if (item.recurrenceDaysMask == 0b1111111) return "Every day"
        if (item.recurrenceDaysMask == 0b0111110) return "Weekdays"
        if (item.recurrenceDaysMask == 0b1000001) return "Weekends"
        val names = listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
        val picked = (0..6).filter { (item.recurrenceDaysMask shr it) and 1 == 1 }
            .joinToString(", ") { names[it] }
        return "Repeats: $picked"
    }
    if (item.showUntilDue) {
        return "Due ${LocalDate.ofEpochDay(item.dueDateEpochDay).format(dateFormatter)}"
    }
    return null
}
