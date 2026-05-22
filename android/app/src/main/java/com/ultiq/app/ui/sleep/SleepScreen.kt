package com.ultiq.app.ui.sleep

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
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ultiq.app.data.local.entity.AlarmEntity
import com.ultiq.app.data.local.entity.SleepRecordEntity
import com.ultiq.app.data.remote.dto.SleepStats
import com.ultiq.app.ui.alarms.AlarmsViewModel
import com.ultiq.app.ui.common.AchievementCelebration
import com.ultiq.app.ui.common.ConfigureHintCard
import com.ultiq.app.ui.common.DurationStepperCard
import com.ultiq.app.ui.common.SectionHeader
import com.ultiq.app.ui.common.SectionHeaderWithSuffix
import com.ultiq.app.ui.common.StepperCard
import com.ultiq.app.ui.common.SwitchCard
import com.ultiq.app.ui.common.TimeSettingCard
import com.ultiq.app.ui.common.formatDuration
import com.ultiq.app.ui.common.targetDurationMinutes
import com.ultiq.app.ui.common.MascotEmptyState
import com.ultiq.app.ui.copy.WarmCopy
import com.ultiq.app.ui.theme.AnimatedAppear
import com.ultiq.app.ui.theme.QualityStar
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun SleepScreen(
    onCreateAlarm: () -> Unit = {},
    onEditAlarm: (String) -> Unit = {},
    onOpenSleepSettings: () -> Unit = {},
    viewModel: SleepViewModel = viewModel(),
    alarmsViewModel: AlarmsViewModel = viewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val alarms by alarmsViewModel.alarms.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    // Sub-tab state — local, no need to round-trip through the VM.
    // rememberSaveable so popping back from AlarmEditScreen returns to the
    // sub-tab the user came from (was resetting to Sleep tab — bug from v2.4).
    var subTab by rememberSaveable { mutableIntStateOf(0) } // 0 = Sleep, 1 = Alarms

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
        topBar = {
            TopAppBar(
                title = { Text("Sleep") },
                actions = {
                    TextButton(onClick = onOpenSleepSettings) {
                        Text("Preferences")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            TabRow(selectedTabIndex = subTab) {
                Tab(
                    selected = subTab == 0,
                    onClick = { subTab = 0 },
                    text = { Text("Sleep") },
                )
                Tab(
                    selected = subTab == 1,
                    onClick = { subTab = 1 },
                    text = { Text("Alarms") },
                )
            }

            when (subTab) {
                0 -> SleepSubTab(
                    uiState = uiState,
                    viewModel = viewModel,
                    permissionLauncher = permissionLauncher,
                )
                else -> AlarmsSubTab(
                    alarms = alarms,
                    onCreateAlarm = onCreateAlarm,
                    onEditAlarm = onEditAlarm,
                    alarmsViewModel = alarmsViewModel,
                    onDelete = { alarmsViewModel.delete(it) },
                )
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

    // Achievement earned celebration
    uiState.celebratedAchievement?.let { id ->
        AchievementCelebration(
            name = id.displayName,
            description = id.description,
            icon = id.icon,
            onDismiss = { viewModel.dismissAchievementCelebration() },
        )
    }

    // No-alarm prompt — fires when the user tries to start a sleep session
    // with zero enabled alarms in the next 24h.
    if (uiState.showNoAlarmDialog) {
        NoAlarmDialog(
            onSetAlarm = {
                viewModel.dismissNoAlarmDialog()
                onCreateAlarm()
            },
            onSleepAnyway = { viewModel.sleepWithoutAlarm() },
            onDismiss = { viewModel.dismissNoAlarmDialog() },
        )
    }
}

@Composable
private fun NoAlarmDialog(
    onSetAlarm: () -> Unit,
    onSleepAnyway: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("No alarm for tonight") },
        text = {
            Text(
                "There's no wake-up alarm scheduled in the next 24 hours. Want " +
                    "to set one with a dismiss mission so you actually get out " +
                    "of bed, or sleep without one tonight?",
            )
        },
        confirmButton = {
            Button(onClick = onSetAlarm) { Text("Set alarm") }
        },
        dismissButton = {
            TextButton(onClick = onSleepAnyway) { Text("Sleep without one") }
        },
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SleepSubTab(
    uiState: SleepUiState,
    viewModel: SleepViewModel,
    permissionLauncher: androidx.activity.compose.ManagedActivityResultLauncher<String, Boolean>,
) {
    val hasStats = uiState.stats != null && uiState.stats!!.totalRecords > 0
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (uiState.showSleepExplainer) {
            item(key = "explainer") {
                SleepExplainerCard(onDismiss = { viewModel.dismissSleepExplainer() })
            }
        }
        if (uiState.settings?.sleepPrefsHintSeen == false) {
            item(key = "sleep-prefs-hint") {
                ConfigureHintCard(
                    title = "Sleep preferences",
                    body = "Tap Preferences in the top right to set bedtime, " +
                        "wake time, sleep goal, and lockout. Wake-up alarms " +
                        "live in the Alarms sub-tab above.",
                    onDismiss = { viewModel.dismissSleepPrefsHint() },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
        }

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
                val (title, body) = WarmCopy.sleepEmpty()
                MascotEmptyState(title = title, body = body)
            }
        } else {
            // §audit-2 — group records into calendar-period sections so the
            // user sees "This week / Last week" or "This month / Last month"
            // depending on the selected sub-tab, instead of one flat list.
            val sections = groupRecordsByPeriod(uiState.records, uiState.selectedTimeRange)
            sections.forEach { (header, recs) ->
                item(key = "sec-$header") {
                    Text(
                        header,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 4.dp),
                    )
                }
                items(recs, key = { it.id }) { record ->
                    SleepRecordItem(
                        record = record,
                        onDelete = { viewModel.deleteRecord(record.id) },
                        modifier = Modifier
                            .padding(horizontal = 16.dp)
                            .animateItem(),
                    )
                }
            }
            item(key = "bottom-spacer") {
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

/// Partitions a window of sleep records into two named buckets:
/// - When viewing **Week**, buckets are "This week" (Mon..today) and
///   "Last week" (previous Mon..Sun). Older rows are dropped from the
///   list — the user can switch to Month for older history.
/// - When viewing **Month**, buckets are "This month" (1st..today) and
///   "Last month" (full previous calendar month).
/// Returns sections in display order, omitting empty ones so the user
/// doesn't see an empty "Last week" header on a brand-new account.
private fun groupRecordsByPeriod(
    records: List<SleepRecordEntity>,
    range: TimeRange,
): List<Pair<String, List<SleepRecordEntity>>> {
    val zone = ZoneId.systemDefault()
    val today = java.time.LocalDate.now(zone)
    fun dayOf(r: SleepRecordEntity): java.time.LocalDate =
        Instant.ofEpochMilli(r.actualBedtime).atZone(zone).toLocalDate()

    return when (range) {
        TimeRange.WEEK -> {
            val mondayOfThisWeek = today.minusDays(today.dayOfWeek.value.toLong() - 1)
            val mondayOfLastWeek = mondayOfThisWeek.minusWeeks(1)
            val sundayOfLastWeek = mondayOfThisWeek.minusDays(1)
            val thisWeek = records.filter { val d = dayOf(it); !d.isBefore(mondayOfThisWeek) && !d.isAfter(today) }
            val lastWeek = records.filter { val d = dayOf(it); !d.isBefore(mondayOfLastWeek) && !d.isAfter(sundayOfLastWeek) }
            listOfNotNull(
                "This week".takeIf { thisWeek.isNotEmpty() }?.let { it to thisWeek },
                "Last week".takeIf { lastWeek.isNotEmpty() }?.let { it to lastWeek },
            )
        }
        TimeRange.MONTH -> {
            val firstOfThisMonth = today.withDayOfMonth(1)
            val firstOfLastMonth = firstOfThisMonth.minusMonths(1)
            val lastOfLastMonth = firstOfThisMonth.minusDays(1)
            val thisMonth = records.filter { val d = dayOf(it); !d.isBefore(firstOfThisMonth) && !d.isAfter(today) }
            val lastMonth = records.filter { val d = dayOf(it); !d.isBefore(firstOfLastMonth) && !d.isAfter(lastOfLastMonth) }
            listOfNotNull(
                "This month".takeIf { thisMonth.isNotEmpty() }?.let { it to thisMonth },
                "Last month".takeIf { lastMonth.isNotEmpty() }?.let { it to lastMonth },
            )
        }
    }
}

@Composable
private fun AlarmsSubTab(
    alarms: List<AlarmEntity>,
    onCreateAlarm: () -> Unit,
    onEditAlarm: (String) -> Unit,
    alarmsViewModel: AlarmsViewModel,
    onDelete: (AlarmEntity) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(top = 8.dp, bottom = 16.dp),
    ) {
        sleepAlarmsSection(
            alarms = alarms,
            onCreate = onCreateAlarm,
            onEdit = onEditAlarm,
            onToggle = { alarm, enabled -> alarmsViewModel.setEnabled(alarm, enabled) },
            onDelete = onDelete,
            onTestAlarm = { kind -> alarmsViewModel.scheduleTestAlarm(kind) },
        )
    }
}

@Composable
private fun SleepExplainerCard(onDismiss: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Bedtime,
                    null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "How sleep tracking works",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.weight(1f),
                )
            }
            Text(
                "When you tap Start Sleep, Ultiq runs as a background service so it can detect phone pickups overnight while your screen is locked. We only check screen state — nothing else about what you do on the phone.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDismiss) {
                    Text("Got it")
                }
            }
        }
    }
}

@Composable
private fun SessionControl(
    isActive: Boolean,
    sessionStartTime: Long,
    pickupEvents: List<com.ultiq.app.service.PickupEvent>,
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
            val debtH = (stats.debtMinutes / 60).toInt()
            val debtM = (stats.debtMinutes % 60).toInt()
            StatCard(
                "Sleep debt",
                if (stats.debtMinutes > 0) "${debtH}h ${debtM}m" else "0h 0m",
                valueColor = if (stats.debtMinutes > 0) MaterialTheme.colorScheme.error else Color(0xFF4CAF50)
            )
        }
        item {
            val extraH = (stats.extraMinutes / 60).toInt()
            val extraM = (stats.extraMinutes % 60).toInt()
            StatCard(
                "Extra rest",
                if (stats.extraMinutes > 0) "${extraH}h ${extraM}m" else "0h 0m",
                valueColor = Color(0xFF4CAF50)
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
    val zone = ZoneId.systemDefault()
    val dateStr = Instant.ofEpochMilli(record.actualBedtime).atZone(zone)
        .format(DateTimeFormatter.ofPattern("EEE, MMM dd"))

    com.ultiq.app.ui.common.SwipeToDeleteBox(
        confirmTitle = "Delete sleep record?",
        confirmBody = "This will permanently remove the record from $dateStr.",
        onDelete = onDelete,
        modifier = modifier,
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

                        // §fix-target-time-format — server stores TIME as
                        // "HH:MM:SS" 24h strings; re-parse to LocalTime and
                        // format the same way as the Actual row below so
                        // the two rows don't disagree (one 24h, one 12h).
                        val targetBed = runCatching { LocalTime.parse(record.targetBedtime) }.getOrNull()
                        val targetWake = runCatching { LocalTime.parse(record.targetWakeTime) }.getOrNull()
                        val targetStr = if (targetBed != null && targetWake != null) {
                            "${targetBed.format(timeFormat)} - ${targetWake.format(timeFormat)}"
                        } else {
                            "${record.targetBedtime} - ${record.targetWakeTime}"
                        }
                        DetailRow("Target", targetStr)
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
