package com.app.productivity.ui.sleep

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.app.productivity.data.local.entity.SleepRecordEntity
import com.app.productivity.data.remote.dto.SleepStats
import com.app.productivity.ui.common.EmptyState
import com.app.productivity.ui.theme.AnimatedAppear
import com.app.productivity.ui.theme.QualityStar
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun SleepScreen(viewModel: SleepViewModel = viewModel()) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) viewModel.startSleepSession()
    }

    LaunchedEffect(uiState.error) {
        uiState.error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Sleep Tracker") }) },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        val hasStats = uiState.stats != null && uiState.stats!!.totalRecords > 0

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "session-control") {
                SessionControl(
                    isActive = uiState.isSessionActive,
                    sessionStartTime = uiState.sessionStartTime,
                    pickupEvents = uiState.pickupEvents,
                    onStartSleep = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            viewModel.startSleepSession()
                        }
                    },
                    onEndSleep = { viewModel.endSleepSession() },
                    onManualLog = { viewModel.showManualLog() },
                )
            }

            if (hasStats) {
                item(key = "stats") {
                    AnimatedAppear { StatsRow(uiState.stats!!) }
                }
                item(key = "chart") {
                    AnimatedAppear(delayMillis = 100) {
                        SleepChart(
                            records = uiState.records,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(200.dp)
                                .padding(horizontal = 16.dp),
                        )
                    }
                }
                item(key = "chart-legend") { ChartLegend() }
            }

            stickyHeader(key = "tabs") {
                val selectedTab = if (uiState.selectedTimeRange == TimeRange.WEEK) 0 else 1
                TabRow(
                    selectedTabIndex = selectedTab,
                    modifier = Modifier.background(MaterialTheme.colorScheme.background),
                ) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { viewModel.setTimeRange(TimeRange.WEEK) },
                        text = { Text("Week") },
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { viewModel.setTimeRange(TimeRange.MONTH) },
                        text = { Text("Month") },
                    )
                }
            }

            if (uiState.records.isEmpty()) {
                item(key = "empty") {
                    EmptyState(
                        icon = Icons.Default.Bedtime,
                        title = "No sleep records yet",
                        body = "Tap Start Sleep tonight and we'll track your rest automatically.",
                    )
                }
            } else {
                items(uiState.records, key = { it.id }) { record ->
                    SleepRecordItem(
                        record = record,
                        onDelete = { viewModel.deleteRecord(record.id) },
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

    // Pre-sleep target dialog
    if (uiState.showSetTargetDialog) {
        SetSessionTargetDialog(
            initialWakeTime = uiState.sessionTargetWakeTime,
            onDismiss = { viewModel.dismissSetTargetDialog() },
            onConfirm = { viewModel.confirmStartSleepSession(it) },
        )
    }

    // End sleep dialog
    if (uiState.showEndSleepDialog) {
        val durationMs = System.currentTimeMillis() - uiState.endedSessionStart
        EndSleepDialog(
            durationMinutes = durationMs / 60_000,
            pickupEvents = uiState.endedPickupEvents,
            onSave = { quality, notes -> viewModel.saveSessionRecord(quality, notes) },
            onDismiss = { viewModel.dismissEndSleepDialog() }
        )
    }

    // Manual log dialog
    if (uiState.showManualLogDialog) {
        AddSleepDialog(
            initialTargetBedtime = uiState.targetBedtime,
            initialTargetWakeTime = uiState.targetWakeTime,
            onDismiss = { viewModel.hideManualLog() },
            onSave = { viewModel.addManualRecord(it) }
        )
    }
}

@Composable
private fun SessionControl(
    isActive: Boolean,
    sessionStartTime: Long,
    pickupEvents: List<com.app.productivity.service.PickupEvent>,
    onStartSleep: () -> Unit,
    onEndSleep: () -> Unit,
    onManualLog: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isActive)
                MaterialTheme.colorScheme.primaryContainer
            else
                MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (isActive) {
                // Active session
                Icon(
                    Icons.Default.Bedtime,
                    contentDescription = null,
                    modifier = Modifier.size(40.dp),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )

                Text(
                    "Sleeping...",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )

                // Elapsed time (updates every second)
                var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
                LaunchedEffect(Unit) {
                    while (true) {
                        now = System.currentTimeMillis()
                        delay(1000)
                    }
                }
                val elapsed = (now - sessionStartTime) / 60_000
                val h = elapsed / 60
                val m = elapsed % 60
                Text(
                    "${h}h ${m}m elapsed",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )

                if (pickupEvents.isNotEmpty()) {
                    Text(
                        "${pickupEvents.size} phone pickup${if (pickupEvents.size != 1) "s" else ""}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                    )
                }

                val startTime = Instant.ofEpochMilli(sessionStartTime)
                    .atZone(ZoneId.systemDefault())
                    .format(DateTimeFormatter.ofPattern("hh:mm a"))
                Text(
                    "Started $startTime",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.5f)
                )

                Button(
                    onClick = onEndSleep,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.tertiary
                    )
                ) {
                    Icon(Icons.Default.WbSunny, null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("End Sleep")
                }
            } else {
                // No active session
                Icon(
                    Icons.Default.Bedtime,
                    contentDescription = null,
                    modifier = Modifier.size(40.dp),
                    tint = MaterialTheme.colorScheme.primary
                )

                Button(
                    onClick = onStartSleep,
                    modifier = Modifier.fillMaxWidth(0.7f)
                ) {
                    Text("Start Sleep")
                }

                TextButton(onClick = onManualLog) {
                    Text("Log past sleep", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun StatsRow(stats: SleepStats) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            val hours = (stats.avgDurationMinutes / 60).toInt()
            val mins = (stats.avgDurationMinutes % 60).toInt()
            StatCard("Avg Duration", "${hours}h ${mins}m")
        }
        item {
            StatCard("Avg Quality", String.format("%.1f / 5", stats.avgQuality))
        }
        item {
            val debtHours = (stats.sleepDebtMinutes / 60).toInt()
            val debtMins = (kotlin.math.abs(stats.sleepDebtMinutes) % 60).toInt()
            val sign = if (stats.sleepDebtMinutes >= 0) "-" else "+"
            StatCard(
                "Avg Debt / session",
                "${sign}${kotlin.math.abs(debtHours)}h ${debtMins}m",
                valueColor = if (stats.sleepDebtMinutes > 0) MaterialTheme.colorScheme.error else Color(0xFF4CAF50)
            )
        }
        item {
            StatCard("Avg Pickups", String.format("%.1f / session", stats.avgPhonePickups))
        }
    }
}

@Composable
private fun ChartLegend() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LegendDot(Color(0xFF3F51B5), "Night")
        LegendDot(Color(0xFF26C6DA), "Morning")
        LegendDot(Color(0xFFFFA726), "Afternoon")
    }
}

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .background(color, RoundedCornerShape(2.dp)),
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun StatCard(
    label: String,
    value: String,
    valueColor: Color = MaterialTheme.colorScheme.primary
) {
    Card(
        modifier = Modifier.width(140.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.height(4.dp))
            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = valueColor)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SleepRecordItem(
    record: SleepRecordEntity,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf(false) }
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) {
                pendingDelete = true
            }
            // Wait for the dialog before committing — never auto-dismiss the swipe.
            false
        }
    )

    if (pendingDelete) {
        val zone = ZoneId.systemDefault()
        val dateStr = Instant.ofEpochMilli(record.actualBedtime).atZone(zone)
            .format(DateTimeFormatter.ofPattern("EEE, MMM dd"))
        AlertDialog(
            onDismissRequest = { pendingDelete = false },
            title = { Text("Delete sleep record?") },
            text = { Text("This will permanently remove the record from $dateStr.") },
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
        enableDismissFromStartToEnd = false
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded },
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                val zone = ZoneId.systemDefault()
                val bedInstant = Instant.ofEpochMilli(record.actualBedtime).atZone(zone)
                val dateStr = bedInstant.format(DateTimeFormatter.ofPattern("EEE, MMM dd"))
                val durationMins = ((record.actualWakeTime - record.actualBedtime) / 60_000).toInt()
                val hours = durationMins / 60
                val mins = durationMins % 60

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(dateStr, style = MaterialTheme.typography.titleSmall)
                        Text("${hours}h ${mins}m", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Row {
                            repeat(record.qualityRating) {
                                Icon(Icons.Filled.Star, null, modifier = Modifier.size(16.dp), tint = QualityStar)
                            }
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Icon(Icons.Default.PhoneAndroid, null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(" ${record.phonePickups}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                AnimatedVisibility(visible = expanded) {
                    Column(modifier = Modifier.padding(top = 12.dp)) {
                        val wakeInstant = Instant.ofEpochMilli(record.actualWakeTime).atZone(zone)
                        val timeFormat = DateTimeFormatter.ofPattern("hh:mm a")

                        DetailRow("Target", "${record.targetBedtime} - ${record.targetWakeTime}")
                        DetailRow("Actual", "${bedInstant.format(timeFormat)} - ${wakeInstant.format(timeFormat)}")
                        if (record.totalPhoneMinutes != null) {
                            DetailRow("Phone Time", "${record.totalPhoneMinutes} min")
                        }
                        if (!record.notes.isNullOrBlank()) {
                            DetailRow("Notes", record.notes)
                        }
                        if (!record.isSynced) {
                            Text("Not synced", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text("$label: ", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun SetSessionTargetDialog(
    initialWakeTime: java.time.LocalTime,
    onDismiss: () -> Unit,
    onConfirm: (java.time.LocalTime) -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var wakeTime by remember { mutableStateOf(initialWakeTime) }
    val timeFormat = DateTimeFormatter.ofPattern("hh:mm a")
    val plannedDurationMins = run {
        val nowSecs = java.time.LocalTime.now().toSecondOfDay()
        val wakeSecs = wakeTime.toSecondOfDay()
        val raw = if (wakeSecs >= nowSecs) wakeSecs - nowSecs else 86400 + wakeSecs - nowSecs
        raw / 60
    }
    val durationLabel = run {
        val h = plannedDurationMins / 60
        val m = plannedDurationMins % 60
        if (h > 0) "${h}h ${m}m" else "${m}m"
    }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Set wake time") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Pick when you'd like to wake up. Sleep debt is measured against this target.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(
                    onClick = {
                        android.app.TimePickerDialog(context, { _, h, m ->
                            wakeTime = java.time.LocalTime.of(h, m)
                        }, wakeTime.hour, wakeTime.minute, false).show()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Wake at ${wakeTime.format(timeFormat)}")
                }
                Text(
                    "Target duration: $durationLabel",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(wakeTime) }) { Text("Start") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
