package com.ultiq.app.ui.sessions

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBoxScope
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ultiq.app.data.local.entity.SessionEntity
import com.ultiq.app.ui.common.AchievementCelebration
import com.ultiq.app.ui.common.MascotEmptyState
import com.ultiq.app.ui.copy.WarmCopy
import com.ultiq.app.ui.theme.AnimatedAppear
import androidx.compose.material.icons.filled.SelfImprovement
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionsScreen(viewModel: SessionsViewModel = viewModel()) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.checkUsagePermission()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(uiState.error) {
        uiState.error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Focus") }) },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (!uiState.hasUsagePermission && uiState.timerState == TimerState.IDLE) {
                item(key = "usage-banner") {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                "Enable Usage Access to track distractions",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(onClick = { viewModel.openUsageSettings() }) {
                                Text("Enable")
                            }
                        }
                    }
                }
            }

            item(key = "today-stats") {
                AnimatedAppear { TodayStatsBar(uiState.todayStats) }
            }

            item(key = "timer") { TimerSection(uiState) }

            item(key = "controls") {
                Box(modifier = Modifier.padding(top = 8.dp)) {
                    TimerControls(uiState, viewModel)
                }
            }

            if (uiState.recentSessions.isEmpty() && uiState.timerState == TimerState.IDLE) {
                item(key = "empty") {
                    val (title, body) = WarmCopy.sessionsEmpty()
                    MascotEmptyState(title = title, body = body)
                }
            } else if (uiState.recentSessions.isNotEmpty()) {
                item(key = "recent-header") {
                    Text(
                        "Recent sessions",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
                items(uiState.recentSessions, key = { it.id }) { session ->
                    SessionItem(
                        session = session,
                        checklistTitle = session.checklistItemId
                            ?.let { uiState.checklistTitleById[it] },
                        onDelete = { viewModel.deletePastSession(session.id) },
                        modifier = Modifier
                            .padding(horizontal = 16.dp)
                            .animateItem(),
                    )
                }
                item(key = "bottom-spacer") {
                    Spacer(modifier = Modifier.height(16.dp))
                }
            }
        }
    }

    val prompt = uiState.completionPrompt
    if (prompt != null) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissChecklistCompletion() },
            title = { Text("Mark as done?") },
            text = { Text("'${prompt.title}'") },
            confirmButton = {
                Button(onClick = { viewModel.confirmChecklistCompletion() }) { Text("Yes") }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissChecklistCompletion() }) { Text("No") }
            },
        )
    }

    uiState.celebratedAchievement?.let { id ->
        AchievementCelebration(
            name = id.displayName,
            description = id.description,
            icon = id.icon,
            onDismiss = { viewModel.dismissAchievementCelebration() },
        )
    }
}

@Composable
private fun TodayStatsBar(stats: TodayStats?) {
    val s = stats ?: TodayStats(0, 0, 0, 0)
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            val h = s.totalFocusMinutes / 60
            val m = s.totalFocusMinutes % 60
            val text = if (h > 0) "${h}h ${m}m" else "${m}m"
            StatCard("Focus Today", text)
        }
        item { StatCard("Sessions", "${s.sessionsCompleted}") }
        item {
            StatCard(
                "Streak",
                if (s.currentStreak > 0) "${s.currentStreak} day${if (s.currentStreak != 1) "s" else ""}" else "-"
            )
        }
        item { StatCard("Pickups", "${s.phonePickupsToday}") }
    }
}

@Composable
private fun StatCard(label: String, value: String) {
    Card(
        modifier = Modifier.width(120.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
private fun TimerSection(uiState: SessionsUiState) {
    val displayTime = when {
        uiState.timerState == TimerState.IDLE -> uiState.workDuration * 60
        uiState.isOvertime -> uiState.overtimeSeconds
        else -> uiState.timeRemainingSeconds
    }
    val displayTotal = if (uiState.timerState == TimerState.IDLE) {
        uiState.workDuration * 60
    } else {
        uiState.totalTimeSeconds
    }

    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(220.dp)) {
        TimerCircle(
            timeRemaining = if (uiState.isOvertime) 0 else displayTime,
            totalTime = displayTotal,
            isWorkPhase = !uiState.isOvertime,
            modifier = Modifier.fillMaxSize()
        )
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = if (uiState.isOvertime) "+${formatTime(displayTime)}" else formatTime(displayTime),
                style = MaterialTheme.typography.displayMedium,
                fontWeight = FontWeight.Bold,
                color = if (uiState.isOvertime) Color(0xFFFF9800) else MaterialTheme.colorScheme.onSurface,
            )
            if (uiState.timerState != TimerState.IDLE) {
                Text(
                    text = if (uiState.isOvertime) "OVERTIME" else "FOCUS",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    color = if (uiState.isOvertime) Color(0xFFFF9800) else Color(0xFF4CAF50),
                )
                if (uiState.tag.isNotBlank()) {
                    Text(
                        text = uiState.tag,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (uiState.phonePickups > 0) {
                    Text(
                        text = "${uiState.phonePickups} distraction${if (uiState.phonePickups != 1) "s" else ""}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error.copy(alpha = 0.8f)
                    )
                }
            }
        }
    }
}

@Composable
private fun TimerControls(uiState: SessionsUiState, viewModel: SessionsViewModel) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        when (uiState.timerState) {
            TimerState.IDLE -> IdleControls(uiState, viewModel)
            TimerState.RUNNING -> ActiveControls(
                onPause = { viewModel.pauseTimer() },
                onCancel = { viewModel.cancelSession() },
                onComplete = { viewModel.completeSession() },
                primaryLabel = "Pause",
                primaryIcon = { Icon(Icons.Default.Pause, null, Modifier.size(18.dp)) }
            )
            TimerState.PAUSED -> ActiveControls(
                onPause = { viewModel.resumeTimer() },
                onCancel = { viewModel.cancelSession() },
                onComplete = { viewModel.completeSession() },
                primaryLabel = "Resume",
                primaryIcon = { Icon(Icons.Default.PlayArrow, null, Modifier.size(18.dp)) }
            )
            TimerState.FINISHED -> {}
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun IdleControls(uiState: SessionsUiState, viewModel: SessionsViewModel) {
    ChecklistDropdown(
        items = uiState.openChecklistItems,
        selectedId = uiState.selectedChecklistItemId,
        onSelect = viewModel::selectChecklistItem,
    )
    Spacer(Modifier.height(8.dp))

    OutlinedTextField(
        value = uiState.tag,
        onValueChange = { viewModel.updateTag(it) },
        label = { Text("Tag (e.g. LeetCode, Study)") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        DurationPicker(
            label = "Work",
            value = uiState.workDuration,
            onDecrease = { viewModel.updateWorkDuration(uiState.workDuration - 5) },
            onIncrease = { viewModel.updateWorkDuration(uiState.workDuration + 5) }
        )
    }

    Button(
        onClick = { viewModel.startSession() },
        modifier = Modifier.fillMaxWidth()
    ) {
        Icon(Icons.Default.PlayArrow, null, Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text("Start Focus")
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChecklistDropdown(
    items: List<com.ultiq.app.data.local.entity.ChecklistEntity>,
    selectedId: String?,
    onSelect: (com.ultiq.app.data.local.entity.ChecklistEntity?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = items.firstOrNull { it.id == selectedId }
    val text = selected?.title
        ?: if (items.isEmpty()) "No items for today — add some in Checklist"
        else "Pick from today's checklist (${items.size})"

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { if (items.isNotEmpty()) expanded = it },
        modifier = Modifier.fillMaxWidth(),
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = {},
            readOnly = true,
            enabled = items.isNotEmpty(),
            label = { Text("From checklist (optional)") },
            trailingIcon = {
                if (items.isNotEmpty()) {
                    ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
                }
            },
            modifier = Modifier
                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth(),
        )
        if (items.isNotEmpty()) {
            ChecklistMenu(
                scope = this,
                expanded = expanded,
                onDismiss = { expanded = false },
                items = items,
                onSelect = onSelect,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChecklistMenu(
    scope: ExposedDropdownMenuBoxScope,
    expanded: Boolean,
    onDismiss: () -> Unit,
    items: List<com.ultiq.app.data.local.entity.ChecklistEntity>,
    onSelect: (com.ultiq.app.data.local.entity.ChecklistEntity?) -> Unit,
) {
    with(scope) {
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = onDismiss,
        ) {
            DropdownMenuItem(
                text = { Text("(type a custom tag)") },
                onClick = { onSelect(null); onDismiss() },
            )
            items.forEach { item ->
                val priorityLabel = when (item.priority) {
                    2 -> "● High"
                    1 -> "● Med"
                    else -> "● Low"
                }
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(item.title, fontWeight = FontWeight.Medium)
                            Text(
                                priorityLabel,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    onClick = { onSelect(item); onDismiss() },
                )
            }
        }
    }
}

@Composable
private fun DurationPicker(
    label: String,
    value: Int,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onDecrease, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.Remove, "Decrease", modifier = Modifier.size(16.dp))
            }
            Text(
                if (value == 0) "off" else "${value}m",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.width(48.dp),
                textAlign = TextAlign.Center
            )
            IconButton(onClick = onIncrease, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.Add, "Increase", modifier = Modifier.size(16.dp))
            }
        }
    }
}

@Composable
private fun ActiveControls(
    onPause: () -> Unit,
    onCancel: () -> Unit,
    onComplete: () -> Unit,
    primaryLabel: String,
    primaryIcon: @Composable () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        FilledTonalButton(
            onClick = onPause,
            modifier = Modifier.weight(1f)
        ) {
            primaryIcon()
            Spacer(Modifier.width(8.dp))
            Text(primaryLabel)
        }
        OutlinedButton(
            onClick = onCancel,
            modifier = Modifier.weight(1f),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = MaterialTheme.colorScheme.error
            )
        ) {
            Icon(Icons.Default.Close, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Cancel")
        }
    }
    Button(
        onClick = onComplete,
        modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.buttonColors(
            containerColor = Color(0xFF4CAF50)
        )
    ) {
        Icon(Icons.Default.Check, null, Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text("Complete Session")
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SessionItem(
    session: SessionEntity,
    checklistTitle: String?,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var pendingDelete by remember { mutableStateOf(false) }
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) {
                pendingDelete = true
            }
            // Never let the swipe auto-confirm — we wait for the dialog.
            false
        }
    )

    if (pendingDelete) {
        AlertDialog(
            onDismissRequest = { pendingDelete = false },
            title = { Text("Delete session?") },
            text = { Text("This will permanently remove '${session.tag}'.") },
            confirmButton = {
                Button(
                    onClick = {
                        pendingDelete = false
                        onDelete()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = false }) { Text("Cancel") }
            },
        )
    }

    SwipeToDismissBox(
        state = dismissState,
        modifier = modifier,
        backgroundContent = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.error, RoundedCornerShape(12.dp))
                    .padding(horizontal = 20.dp),
                contentAlignment = Alignment.CenterEnd
            ) {
                Icon(Icons.Default.Delete, "Delete", tint = MaterialTheme.colorScheme.onError)
            }
        },
        enableDismissFromStartToEnd = false,
    ) {
        SessionItemContent(session, checklistTitle)
    }
}

@Composable
private fun SessionItemContent(
    session: SessionEntity,
    checklistTitle: String?,
) {
    var expanded by remember { mutableStateOf(false) }
    val zone = ZoneId.systemDefault()
    val startInstant = Instant.ofEpochMilli(session.startedAt).atZone(zone)
    val dateStr = startInstant.format(DateTimeFormatter.ofPattern("EEE, MMM dd"))
    val h = session.durationMinutes / 60
    val m = session.durationMinutes % 60
    val durationStr = if (h > 0) "${h}h ${m}m" else "${m}m"

    Card(
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        session.tag,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        "$durationStr  ·  $dateStr",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (session.phonePickups > 0) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.PhoneAndroid,
                            null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            " ${session.phonePickups}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            AnimatedVisibility(visible = expanded) {
                Column(modifier = Modifier.padding(top = 12.dp)) {
                    val timeFormat = DateTimeFormatter.ofPattern("hh:mm a")
                    val timeStr = if (session.endedAt != null) {
                        val endInstant = Instant.ofEpochMilli(session.endedAt).atZone(zone)
                        "${startInstant.format(timeFormat)} – ${endInstant.format(timeFormat)}"
                    } else {
                        startInstant.format(timeFormat)
                    }
                    SessionDetailRow("Time", timeStr)
                    val planStr = if (session.breakDuration > 0) {
                        "${session.workDuration}m work / ${session.breakDuration}m break"
                    } else {
                        "${session.workDuration}m planned"
                    }
                    SessionDetailRow("Plan", planStr)
                    if (!checklistTitle.isNullOrBlank()) {
                        SessionDetailRow("Linked task", checklistTitle)
                    }
                    if (!session.isSynced) {
                        Text(
                            "Not synced",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SessionDetailRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(
            "$label: ",
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

private fun formatTime(seconds: Int): String {
    val m = seconds / 60
    val s = seconds % 60
    return "%02d:%02d".format(m, s)
}
