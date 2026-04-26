package com.app.productivity.ui.checklist

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
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import com.app.productivity.data.local.entity.ChecklistEntity
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
            ExtendedFloatingActionButton(
                onClick = viewModel::openAddDialog,
                icon = { Icon(Icons.Default.Add, null) },
                text = { Text("Add") },
            )
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
                            onToggle = { viewModel.toggleCompleted(item) },
                            onEdit = { viewModel.openEditDialog(item) },
                            onDelete = { viewModel.deleteItem(item) },
                        )
                    }
                }

                if (state.completedItems.isNotEmpty()) {
                    item {
                        CompletedSection(
                            items = state.completedItems,
                            onToggle = viewModel::toggleCompleted,
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
                onDismiss = viewModel::dismissDialog,
                onSave = viewModel::saveItem,
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
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            Icons.Default.Checklist,
            null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "No items for this day",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "Tap + to add something",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ChecklistRow(
    item: ChecklistEntity,
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
            Checkbox(checked = item.completed, onCheckedChange = { onToggle() })
            Column(modifier = Modifier.weight(1f).padding(horizontal = 4.dp)) {
                Text(
                    item.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    textDecoration = if (item.completed) TextDecoration.LineThrough else null,
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
                    ChecklistRow(
                        item = item,
                        onToggle = { onToggle(item) },
                        onEdit = {},
                        onDelete = { onDelete(item) },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChecklistEditDialog(
    editing: ChecklistEntity?,
    defaultDueDate: LocalDate,
    onDismiss: () -> Unit,
    onSave: (title: String, description: String?, dueDate: LocalDate, estimatedMinutes: Int?, priority: Int) -> Unit,
) {
    val context = LocalContext.current
    var title by remember { mutableStateOf(editing?.title ?: "") }
    var description by remember { mutableStateOf(editing?.description ?: "") }
    var dueDate by remember {
        mutableStateOf(
            editing?.dueDateEpochDay?.let(LocalDate::ofEpochDay) ?: defaultDueDate,
        )
    }
    var minutesText by remember {
        mutableStateOf(editing?.estimatedMinutes?.toString() ?: "")
    }
    var priority by remember { mutableStateOf(editing?.priority ?: 1) }

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
                    Text("Due: ${dueDate.format(dateFormatter)}")
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
                onSave(
                    title,
                    description.ifBlank { null },
                    dueDate,
                    minutesText.toIntOrNull(),
                    priority,
                )
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
