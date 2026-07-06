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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
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
import android.content.Context
import com.ultiq.app.R
import com.ultiq.app.util.LocaleManager
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle

// §13 (i18n) — a function (not a cached val) so it reads the CURRENT app locale
// on every call. A top-level val binds Locale.getDefault() once at class-load,
// which sticks to whatever language was active the first time the checklist
// opened (the Arabic-date-stuck bug).
private fun dateFormatter(): DateTimeFormatter =
    DateTimeFormatter.ofPattern("EEE, MMM d", com.ultiq.app.util.LocaleManager.currentLocale())

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChecklistScreen(
    onNavigateToWeeklyPlanner: () -> Unit = {},
    jumpToToday: Boolean = false,
    onJumpToTodayHandled: () -> Unit = {},
    viewModel: ChecklistViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(state.error) {
        state.error?.let { msg ->
            scope.launch {
                snackbarHostState.showSnackbar(msg)
                viewModel.clearError()
            }
        }
    }

    // Arriving via the dashboard "Today's plan" card forces today, even if the
    // checklist was last left on another date (one-shot savedStateHandle flag
    // set in AppNavigation).
    LaunchedEffect(jumpToToday) {
        if (jumpToToday) {
            viewModel.jumpToToday()
            onJumpToTodayHandled()
        }
    }

    val total = state.openItems.size + state.completedItems.size
    val done = state.completedItems.size
    val progress = if (total == 0) 0f else done.toFloat() / total.toFloat()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.checklist_title)) },
                actions = {
                    TextButton(onClick = viewModel::jumpToToday) { Text(stringResource(R.string.date_today)) }
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
                    Icon(Icons.Default.AutoAwesome, stringResource(R.string.ai_quick_add_cd))
                }
                ExtendedFloatingActionButton(
                    onClick = viewModel::openAddDialog,
                    icon = { Icon(Icons.Default.Add, null) },
                    text = { Text(stringResource(R.string.action_add)) },
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
                    onBringForward = viewModel::openBringForwardDialog,
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
                    item { SectionLabel(stringResource(R.string.checklist_section_open)) }
                    items(state.openItems, key = { it.id }) { item ->
                        // §delete-consistency — swipe row to delete with
                        // confirm dialog. Replaces the previous trash-icon
                        // + screen-level dialog plumbing.
                        //
                        // §v2.16.10 — Modifier.animateItem() smooths the
                        // exit + placement transition when the row moves
                        // to the Completed bucket on tap. Without it
                        // LazyColumn snaps remaining items to their new
                        // positions in a single frame, which read as a
                        // flicker when the screen had only one or two
                        // items (the whole open section structure swaps
                        // in one frame). All DB-layer flicker causes were
                        // already removed in v2.16.5-v2.16.9; this is the
                        // perceptual layer.
                        com.ultiq.app.ui.common.SwipeToDeleteBox(
                            confirmTitle = stringResource(R.string.checklist_delete_item_title),
                            confirmBody = stringResource(R.string.checklist_delete_item_body, item.title),
                            onDelete = { viewModel.deleteItem(item) },
                            modifier = Modifier.animateItem(),
                        ) {
                            ChecklistRow(
                                item = item,
                                isDoneNow = false,
                                onToggle = { viewModel.toggleCompleted(item) },
                                onEdit = { viewModel.openEditDialog(item) },
                            )
                        }
                    }
                }

                if (state.completedItems.isNotEmpty()) {
                    item {
                        CompletedSection(
                            items = state.completedItems,
                            onToggle = viewModel::toggleCompleted,
                            onEdit = viewModel::openEditDialog,
                            onDelete = viewModel::deleteItem,
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

        if (state.showBringForwardDialog) {
            BringForwardDialog(
                candidates = state.yesterdayOpenItems,
                onConfirm = viewModel::bringSelectedForward,
                onDismiss = viewModel::dismissBringForwardDialog,
            )
        }

        if (state.showWeeklyPrompt) {
            AlertDialog(
                onDismissRequest = viewModel::dismissWeeklyPrompt,
                title = { Text(stringResource(R.string.checklist_plan_week_q)) },
                text = {
                    Text(stringResource(R.string.checklist_plan_week_body))
                },
                confirmButton = {
                    Button(onClick = {
                        viewModel.dismissWeeklyPrompt()
                        onNavigateToWeeklyPlanner()
                    }) { Text(stringResource(R.string.checklist_plan_now)) }
                },
                dismissButton = {
                    TextButton(onClick = viewModel::dismissWeeklyPrompt) { Text(stringResource(R.string.action_skip)) }
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
    val label = pluralStringResource(R.plurals.checklist_carryover, count, count)
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
            TextButton(onClick = onBringForward) { Text(stringResource(R.string.checklist_bring_forward)) }
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_dismiss)) }
        }
    }
}

/** Picker for choosing which of yesterday's open items to carry forward.
 *  Opt-in: rows start unticked, so the user selects only what they want to
 *  move to today; everything left unticked stays on yesterday. */
@Composable
private fun BringForwardDialog(
    candidates: List<ChecklistEntity>,
    onConfirm: (Set<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var selectedIds by remember { mutableStateOf(emptySet<String>()) }
    val allSelected = candidates.isNotEmpty() && selectedIds.size == candidates.size

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.checklist_bring_forward)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.checklist_bring_forward_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = {
                            selectedIds =
                                if (allSelected) emptySet()
                                else candidates.map { it.id }.toSet()
                        },
                    ) { Text(if (allSelected) stringResource(R.string.action_clear) else stringResource(R.string.action_all)) }
                }
                LazyColumn(
                    modifier = Modifier.heightIn(max = 320.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(candidates, key = { it.id }) { item ->
                        val checked = item.id in selectedIds
                        val toggle = {
                            selectedIds =
                                if (checked) selectedIds - item.id
                                else selectedIds + item.id
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(onClick = toggle),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = checked, onCheckedChange = { toggle() })
                            Column(modifier = Modifier.weight(1f).padding(start = 4.dp)) {
                                Text(item.title, style = MaterialTheme.typography.bodyLarge)
                                scheduleLabel(context, item)?.let { label ->
                                    Text(
                                        label,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(selectedIds) },
                enabled = selectedIds.isNotEmpty(),
            ) {
                Text(
                    if (selectedIds.isEmpty()) stringResource(R.string.checklist_bring_forward)
                    else stringResource(R.string.checklist_bring_forward_count, selectedIds.size),
                )
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
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
                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.checklist_prev_day_cd))
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                val label = when (date) {
                    LocalDate.now() -> stringResource(R.string.date_today)
                    LocalDate.now().plusDays(1) -> stringResource(R.string.date_tomorrow)
                    LocalDate.now().minusDays(1) -> stringResource(R.string.date_yesterday)
                    else -> date.format(dateFormatter())
                }
                Text(label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                if (label != date.format(dateFormatter())) {
                    Text(
                        date.format(dateFormatter()),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            IconButton(onClick = onNext) {
                Icon(Icons.AutoMirrored.Filled.ArrowForward, stringResource(R.string.checklist_next_day_cd))
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
                if (total == 0) stringResource(R.string.checklist_nothing_today)
                else stringResource(R.string.dashboard_checklist_done, done, total),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (total > 0) {
                Text(
                    stringResource(R.string.checklist_percent, (progress * 100).toInt()),
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
        text.uppercase(LocaleManager.currentLocale()),
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp, top = 8.dp),
    )
}

@Composable
private fun EmptyState() {
    val context = LocalContext.current
    val (title, body) = WarmCopy.checklistEmpty(context)
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
) {
    val context = LocalContext.current
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
                                pluralStringResource(R.plurals.estimate_minutes, mins, mins),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                scheduleLabel(context, item)?.let { label ->
                    Text(
                        label,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun PriorityChip(priority: Int) {
    val (label, color) = when (priority) {
        2 -> stringResource(R.string.priority_high) to MaterialTheme.colorScheme.error
        1 -> stringResource(R.string.priority_medium) to MaterialTheme.colorScheme.tertiary
        else -> stringResource(R.string.priority_low) to MaterialTheme.colorScheme.outline
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
                    stringResource(R.string.checklist_completed_count, items.size).uppercase(LocaleManager.currentLocale()),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (expanded) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items.forEach { item ->
                    // §024 — Everything in `items` is in the completed
                    // bucket by virtue of being partitioned that way in
                    // the ViewModel (recurring → tick exists in
                    // checklist_completions for the day; non-recurring →
                    // completed = true). So `isDoneNow` is unconditionally
                    // true; no per-row re-check needed.
                    com.ultiq.app.ui.common.SwipeToDeleteBox(
                        confirmTitle = "Delete this item?",
                        confirmBody = "'${item.title}' will be removed.",
                        onDelete = { onDelete(item) },
                    ) {
                        ChecklistRow(
                            item = item,
                            isDoneNow = true,
                            onToggle = { onToggle(item) },
                            onEdit = { onEdit(item) },
                        )
                    }
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
        title = { Text(if (editing == null) stringResource(R.string.checklist_new_item) else stringResource(R.string.checklist_edit_item)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text(stringResource(R.string.field_title)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text(stringResource(R.string.field_description_optional)) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                )

                Text(stringResource(R.string.checklist_schedule), style = MaterialTheme.typography.labelMedium)
                val modeLabels = listOf(
                    ScheduleMode.ONE_OFF to stringResource(R.string.date_today),
                    ScheduleMode.RECURRING to stringResource(R.string.checklist_schedule_repeat),
                    ScheduleMode.UNTIL_DUE to stringResource(R.string.checklist_schedule_bydue),
                )
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    modeLabels.forEachIndexed { i, (value, label) ->
                        SegmentedButton(
                            selected = mode == value,
                            onClick = { mode = value },
                            shape = SegmentedButtonDefaults.itemShape(i, modeLabels.size),
                            label = {
                                Text(
                                    label,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.labelMedium,
                                )
                            },
                        )
                    }
                }
                // §UX: tiny helper text under each mode so the option label
                // ("Today" / "Repeat" / "By due") isn't load-bearing on its own.
                Text(
                    when (mode) {
                        ScheduleMode.ONE_OFF -> stringResource(R.string.checklist_schedule_help_oneoff)
                        ScheduleMode.RECURRING -> stringResource(R.string.checklist_schedule_help_recurring)
                        ScheduleMode.UNTIL_DUE -> stringResource(R.string.checklist_schedule_help_untildue)
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
                        ScheduleMode.ONE_OFF -> stringResource(R.string.checklist_prefix_on)
                        ScheduleMode.RECURRING -> stringResource(R.string.checklist_prefix_starts)
                        ScheduleMode.UNTIL_DUE -> stringResource(R.string.checklist_prefix_due)
                    }
                    Text(stringResource(R.string.checklist_date_line, prefix, dueDate.format(dateFormatter())))
                }

                OutlinedTextField(
                    value = minutesText,
                    onValueChange = { v -> minutesText = v.filter(Char::isDigit).take(4) },
                    label = { Text(stringResource(R.string.checklist_estimate_optional)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Text(stringResource(R.string.field_priority), style = MaterialTheme.typography.labelMedium)
                val labels = listOf(
                    stringResource(R.string.priority_low) to 0,
                    stringResource(R.string.priority_medium) to 1,
                    stringResource(R.string.priority_high) to 2,
                )
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    labels.forEachIndexed { i, (label, value) ->
                        SegmentedButton(
                            selected = priority == value,
                            onClick = { priority = value },
                            shape = SegmentedButtonDefaults.itemShape(i, labels.size),
                            label = {
                                Text(
                                    label,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.labelMedium,
                                )
                            },
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
            }) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
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
            title = { Text(stringResource(R.string.checklist_include_today_q)) },
            text = {
                Text(
                    stringResource(
                        R.string.checklist_include_today_body,
                        today.dayOfWeek.getDisplayName(TextStyle.FULL, LocaleManager.currentLocale()),
                    ),
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
                }) { Text(stringResource(R.string.checklist_include_today_yes)) }
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
                }) { Text(stringResource(R.string.checklist_include_today_no)) }
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
    // §13.1 — narrow day letters resolve from the app locale (Sun-first order).
    val locale = LocaleManager.currentLocale()
    val labels = (0..6).map { bit ->
        val dow = if (bit == 0) DayOfWeek.SUNDAY else DayOfWeek.of(bit)
        dow.getDisplayName(TextStyle.NARROW, locale)
    }
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
        ) { Text(stringResource(R.string.recur_every_day), maxLines = 1, softWrap = false) }
        TextButton(
            onClick = { onChange(0b0111110) },
            contentPadding = tightPadding,
            modifier = Modifier.weight(1f),
        ) { Text(stringResource(R.string.recur_weekdays), maxLines = 1, softWrap = false) }
        TextButton(
            onClick = { onChange(0b1000001) },
            contentPadding = tightPadding,
            modifier = Modifier.weight(1f),
        ) { Text(stringResource(R.string.recur_weekends), maxLines = 1, softWrap = false) }
    }
}

/** Human-readable summary of an item's schedule, shown under each row.
 *  §13.1 — takes a [Context] so labels + day names resolve in the app locale. */
internal fun scheduleLabel(context: Context, item: ChecklistEntity): String? {
    if (item.recurrenceDaysMask != 0) {
        if (item.recurrenceDaysMask == 0b1111111) return context.getString(R.string.recur_every_day)
        if (item.recurrenceDaysMask == 0b0111110) return context.getString(R.string.recur_weekdays)
        if (item.recurrenceDaysMask == 0b1000001) return context.getString(R.string.recur_weekends)
        val locale = LocaleManager.currentLocale()
        val picked = (0..6).filter { (item.recurrenceDaysMask shr it) and 1 == 1 }
            .joinToString(", ") { bit ->
                val dow = if (bit == 0) DayOfWeek.SUNDAY else DayOfWeek.of(bit)
                dow.getDisplayName(TextStyle.SHORT, locale)
            }
        return context.getString(R.string.checklist_repeats, picked)
    }
    if (item.showUntilDue) {
        return context.getString(
            R.string.checklist_due_date,
            LocalDate.ofEpochDay(item.dueDateEpochDay).format(dateFormatter()),
        )
    }
    return null
}
