package com.app.productivity.ui.sessions

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
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import com.app.productivity.data.local.entity.SessionEntity
import com.app.productivity.ui.common.EmptyState
import com.app.productivity.ui.theme.AnimatedAppear
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
        topBar = { TopAppBar(title = { Text("Focus Timer") }) },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Usage permission banner
            if (!uiState.hasUsagePermission && uiState.timerState == TimerState.IDLE) {
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

            // 1. Today's stats bar
            AnimatedAppear { TodayStatsBar(uiState.todayStats) }

            Spacer(modifier = Modifier.height(16.dp))

            // 2. Timer circle with time display
            TimerSection(uiState)

            Spacer(modifier = Modifier.height(16.dp))

            // 3. Controls
            TimerControls(uiState, viewModel)

            Spacer(modifier = Modifier.height(16.dp))

            // 4. Recent sessions
            if (uiState.recentSessions.isNotEmpty()) {
                Text(
                    "Recent Sessions",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentPadding = PaddingValues(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (uiState.recentSessions.isEmpty() && uiState.timerState == TimerState.IDLE) {
                    item {
                        EmptyState(
                            icon = Icons.Default.SelfImprovement,
                            title = "Ready to focus?",
                            body = "Tag a task, pick durations, and start your first focus block.",
                        )
                    }
                }
                items(uiState.recentSessions, key = { it.id }) { session ->
                    SessionItem(session, modifier = Modifier.animateItem())
                }
            }
        }
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
    val displayTime = if (uiState.timerState == TimerState.IDLE) {
        uiState.workDuration * 60
    } else {
        uiState.timeRemainingSeconds
    }
    val displayTotal = if (uiState.timerState == TimerState.IDLE) {
        uiState.workDuration * 60
    } else {
        uiState.totalTimeSeconds
    }
    val isWork = uiState.currentPhase == Phase.WORK

    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(220.dp)) {
        TimerCircle(
            timeRemaining = displayTime,
            totalTime = displayTotal,
            isWorkPhase = isWork,
            modifier = Modifier.fillMaxSize()
        )
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = formatTime(displayTime),
                style = MaterialTheme.typography.displayMedium,
                fontWeight = FontWeight.Bold
            )
            if (uiState.timerState != TimerState.IDLE) {
                Text(
                    text = if (isWork) "WORK" else "BREAK",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    color = if (isWork) Color(0xFF4CAF50) else Color(0xFFFF9800)
                )
                if (uiState.tag.isNotBlank()) {
                    Text(
                        text = uiState.tag,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (uiState.completedPomodoros > 0) {
                    Text(
                        text = "${uiState.completedPomodoros} pomodoro${if (uiState.completedPomodoros != 1) "s" else ""}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
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
            TimerState.BREAK -> ActiveControls(
                onPause = { viewModel.skipBreak() },
                onCancel = { viewModel.cancelSession() },
                onComplete = { viewModel.completeSession() },
                primaryLabel = "Skip Break",
                primaryIcon = { Icon(Icons.Default.SkipNext, null, Modifier.size(18.dp)) }
            )
            TimerState.FINISHED -> {}
        }
    }
}

@Composable
private fun IdleControls(uiState: SessionsUiState, viewModel: SessionsViewModel) {
    OutlinedTextField(
        value = uiState.tag,
        onValueChange = { viewModel.updateTag(it) },
        label = { Text("Tag (e.g. LeetCode, Study)") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        DurationPicker(
            label = "Work",
            value = uiState.workDuration,
            onDecrease = { viewModel.updateWorkDuration(uiState.workDuration - 5) },
            onIncrease = { viewModel.updateWorkDuration(uiState.workDuration + 5) }
        )
        DurationPicker(
            label = "Break",
            value = uiState.breakDuration,
            onDecrease = { viewModel.updateBreakDuration(uiState.breakDuration - 1) },
            onIncrease = { viewModel.updateBreakDuration(uiState.breakDuration + 1) }
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
                "${value}m",
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

@Composable
private fun SessionItem(session: SessionEntity, modifier: Modifier = Modifier) {
    val zone = ZoneId.systemDefault()
    val dateStr = Instant.ofEpochMilli(session.startedAt)
        .atZone(zone)
        .format(DateTimeFormatter.ofPattern("EEE, MMM dd"))
    val h = session.durationMinutes / 60
    val m = session.durationMinutes % 60
    val durationStr = if (h > 0) "${h}h ${m}m" else "${m}m"

    Card(
        shape = RoundedCornerShape(12.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
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
    }
}

private fun formatTime(seconds: Int): String {
    val m = seconds / 60
    val s = seconds % 60
    return "%02d:%02d".format(m, s)
}
