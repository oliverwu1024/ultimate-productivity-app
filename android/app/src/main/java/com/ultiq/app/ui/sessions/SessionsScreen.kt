package com.ultiq.app.ui.sessions

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ultiq.app.R
import com.ultiq.app.data.local.entity.SessionEntity
import com.ultiq.app.ui.common.MascotEmptyState
import com.ultiq.app.ui.copy.WarmCopy
import com.ultiq.app.ui.sleep.TimeRange
import com.ultiq.app.ui.theme.AnimatedAppear
import com.ultiq.app.util.LocaleManager
import androidx.compose.material.icons.filled.SelfImprovement
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun SessionsScreen(
    onOpenFocusSettings: () -> Unit = {},
    viewModel: SessionsViewModel = viewModel(),
) {
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
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.sessions_title)) },
                actions = {
                    TextButton(onClick = onOpenFocusSettings) {
                        Text(stringResource(R.string.action_preferences))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (uiState.settings?.focusPrefsHintSeen == false) {
                item(key = "focus-prefs-hint") {
                    com.ultiq.app.ui.common.ConfigureHintCard(
                        title = stringResource(R.string.sessions_focus_prefs),
                        body = stringResource(R.string.sessions_focus_prefs_hint),
                        onDismiss = { viewModel.dismissFocusPrefsHint() },
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
            }

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
                                stringResource(R.string.sessions_usage_banner),
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(onClick = { viewModel.openUsageSettings() }) {
                                Text(stringResource(R.string.sessions_enable))
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
                    val context = LocalContext.current
                    val (title, body) = WarmCopy.sessionsEmpty(context)
                    MascotEmptyState(title = title, body = body)
                }
            } else if (uiState.recentSessions.isNotEmpty()) {
                // §focus-period — Week/Month toggle mirroring the Sleep tab.
                // Sticks to the top while the timer + controls scroll away.
                stickyHeader(key = "session-tabs") {
                    val selectedTab = if (uiState.selectedTimeRange == TimeRange.WEEK) 0 else 1
                    TabRow(
                        selectedTabIndex = selectedTab,
                        modifier = Modifier.background(MaterialTheme.colorScheme.background),
                    ) {
                        Tab(
                            selected = selectedTab == 0,
                            onClick = { viewModel.setTimeRange(TimeRange.WEEK) },
                            text = { Text(stringResource(R.string.range_week)) },
                        )
                        Tab(
                            selected = selectedTab == 1,
                            onClick = { viewModel.setTimeRange(TimeRange.MONTH) },
                            text = { Text(stringResource(R.string.range_month)) },
                        )
                    }
                }

                // §focus-period — group completed sessions into calendar
                // buckets ("This week / Last week" or "This month / Last
                // month") for the selected range. Sessions outside the window
                // are dropped from view — switch range to see older ones,
                // same as the Sleep tab.
                val sections = groupSessionsByPeriod(
                    uiState.recentSessions,
                    uiState.selectedTimeRange,
                )
                if (sections.isEmpty()) {
                    item(key = "empty-range") {
                        Text(
                            if (uiState.selectedTimeRange == TimeRange.WEEK)
                                stringResource(R.string.sessions_none_week)
                            else stringResource(R.string.sessions_none_month),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 24.dp, vertical = 24.dp),
                        )
                    }
                } else {
                    sections.forEach { (headerRes, sessions) ->
                        item(key = "sec-$headerRes") {
                            Text(
                                stringResource(headerRes),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 20.dp, top = 16.dp, bottom = 4.dp),
                            )
                        }
                        items(sessions, key = { it.id }) { session ->
                            SessionItem(
                                session = session,
                                checklistTitle = session.checklistItemId
                                    ?.let { uiState.checklistTitleById[it] },
                                onDelete = { viewModel.deletePastSession(session.id) },
                                // §v2.16.18 — Pickup timeline detail. The
                                // expansion calls fetchRecordDetails(id) which
                                // starts a Flow subscription, so the timeline
                                // appears immediately from local Room and
                                // refreshes when the network upload lands.
                                details = uiState.recordDetails[session.id],
                                onExpand = { viewModel.fetchRecordDetails(session.id) },
                                modifier = Modifier
                                    .padding(horizontal = 16.dp)
                                    .animateItem(),
                            )
                        }
                    }
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
            title = { Text(stringResource(R.string.sessions_mark_done_title)) },
            text = { Text("'${prompt.title}'") },
            confirmButton = {
                Button(onClick = { viewModel.confirmChecklistCompletion() }) { Text(stringResource(R.string.action_yes)) }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissChecklistCompletion() }) { Text(stringResource(R.string.action_no)) }
            },
        )
    }

    // §9.7 — Debrief prompt sits beneath the checklist dialog. Skippable;
    // submit hits Haiku and reveals the auto-assigned tag.
    val debrief = uiState.debriefPrompt
    if (debrief != null && uiState.completionPrompt == null) {
        DebriefPromptDialog(
            prompt = debrief,
            onTextChange = viewModel::updateDebriefText,
            onSubmit = viewModel::submitDebrief,
            onDismiss = viewModel::dismissDebrief,
        )
    }

    // v2.13.3 — Removed AchievementCelebration dialog. Achievements still
    // record silently; the user reviews them in Settings → Achievements.
}

/// §focus-period — Partition completed focus sessions into two named
/// calendar buckets for the recent-sessions list, mirroring the Sleep tab's
/// groupRecordsByPeriod:
///   - Week  → "This week" (Mon..today) + "Last week" (prev Mon..Sun)
///   - Month → "This month" (1st..today) + "Last month" (full prev month)
/// Sessions outside the selected window are omitted; empty buckets are
/// dropped so a brand-new account never sees an empty "Last week" header.
/// Bucketing is by the session's start date in the device's local zone.
private fun groupSessionsByPeriod(
    sessions: List<SessionEntity>,
    range: TimeRange,
): List<Pair<Int, List<SessionEntity>>> {
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    fun dayOf(s: SessionEntity): LocalDate =
        Instant.ofEpochMilli(s.startedAt).atZone(zone).toLocalDate()

    return when (range) {
        TimeRange.WEEK -> {
            val mondayOfThisWeek = today.minusDays(today.dayOfWeek.value.toLong() - 1)
            val mondayOfLastWeek = mondayOfThisWeek.minusWeeks(1)
            val sundayOfLastWeek = mondayOfThisWeek.minusDays(1)
            val thisWeek = sessions.filter { val d = dayOf(it); !d.isBefore(mondayOfThisWeek) && !d.isAfter(today) }
            val lastWeek = sessions.filter { val d = dayOf(it); !d.isBefore(mondayOfLastWeek) && !d.isAfter(sundayOfLastWeek) }
            listOfNotNull(
                R.string.dashboard_this_week.takeIf { thisWeek.isNotEmpty() }?.let { it to thisWeek },
                R.string.dashboard_last_week.takeIf { lastWeek.isNotEmpty() }?.let { it to lastWeek },
            )
        }
        TimeRange.MONTH -> {
            val firstOfThisMonth = today.withDayOfMonth(1)
            val firstOfLastMonth = firstOfThisMonth.minusMonths(1)
            val lastOfLastMonth = firstOfThisMonth.minusDays(1)
            val thisMonth = sessions.filter { val d = dayOf(it); !d.isBefore(firstOfThisMonth) && !d.isAfter(today) }
            val lastMonth = sessions.filter { val d = dayOf(it); !d.isBefore(firstOfLastMonth) && !d.isAfter(lastOfLastMonth) }
            listOfNotNull(
                R.string.period_this_month.takeIf { thisMonth.isNotEmpty() }?.let { it to thisMonth },
                R.string.period_last_month.takeIf { lastMonth.isNotEmpty() }?.let { it to lastMonth },
            )
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
            StatCard(stringResource(R.string.sessions_focus_today), text)
        }
        item { StatCard(stringResource(R.string.reports_sessions), "${s.sessionsCompleted}") }
        item {
            StatCard(
                stringResource(R.string.sessions_streak),
                if (s.currentStreak > 0) pluralStringResource(R.plurals.sessions_streak_days, s.currentStreak, s.currentStreak) else "-"
            )
        }
        item { StatCard(stringResource(R.string.sessions_pickups), "${s.phonePickupsToday}") }
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
                    text = if (uiState.isOvertime) stringResource(R.string.lockout_overtime) else stringResource(R.string.sessions_focus_phase),
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
                        text = pluralStringResource(R.plurals.sessions_distractions, uiState.phonePickups, uiState.phonePickups),
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
                primaryLabel = stringResource(R.string.sessions_pause),
                primaryIcon = { Icon(Icons.Default.Pause, null, Modifier.size(18.dp)) }
            )
            TimerState.PAUSED -> ActiveControls(
                onPause = { viewModel.resumeTimer() },
                onCancel = { viewModel.cancelSession() },
                onComplete = { viewModel.completeSession() },
                primaryLabel = stringResource(R.string.sessions_resume),
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
        label = { Text(stringResource(R.string.sessions_tag_hint)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )

    DurationPicker(
        label = stringResource(R.string.sessions_work),
        value = uiState.workDuration,
        onDecrease = { viewModel.updateWorkDuration(uiState.workDuration - 5) },
        onIncrease = { viewModel.updateWorkDuration(uiState.workDuration + 5) },
        onSetValue = { viewModel.updateWorkDuration(it) },
        quickPicks = listOf(15, 25, 30, 45, 60, 90, 120, 150, 180, 210, 240),
    )

    Button(
        onClick = { viewModel.startSession() },
        modifier = Modifier.fillMaxWidth()
    ) {
        Icon(Icons.Default.PlayArrow, null, Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.sessions_start))
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
        ?: if (items.isEmpty()) stringResource(R.string.sessions_no_checklist_items)
        else stringResource(R.string.sessions_pick_checklist, items.size)

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
            label = { Text(stringResource(R.string.sessions_from_checklist)) },
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
                text = { Text(stringResource(R.string.sessions_custom_tag)) },
                onClick = { onSelect(null); onDismiss() },
            )
            items.forEach { item ->
                val priorityLabel = when (item.priority) {
                    2 -> "● " + stringResource(R.string.priority_high)
                    1 -> "● " + stringResource(R.string.priority_medium)
                    else -> "● " + stringResource(R.string.priority_low)
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

@OptIn(
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
    ExperimentalMaterial3Api::class,
)
@Composable
private fun DurationPicker(
    label: String,
    value: Int,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
    onSetValue: (Int) -> Unit = {},
    quickPicks: List<Int> = emptyList(),
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (quickPicks.isNotEmpty()) {
            androidx.compose.foundation.layout.FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
            ) {
                quickPicks.forEach { pick ->
                    androidx.compose.material3.FilterChip(
                        selected = value == pick,
                        onClick = { onSetValue(pick) },
                        label = { Text("${pick}m") },
                    )
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onDecrease, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.Remove, stringResource(R.string.action_decrease), modifier = Modifier.size(16.dp))
            }
            Text(
                if (value == 0) stringResource(R.string.sessions_off) else "${value}m",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.width(48.dp),
                textAlign = TextAlign.Center
            )
            IconButton(onClick = onIncrease, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.Add, stringResource(R.string.action_increase), modifier = Modifier.size(16.dp))
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
            Text(stringResource(R.string.action_cancel))
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
        Text(stringResource(R.string.sessions_complete))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SessionItem(
    session: SessionEntity,
    checklistTitle: String?,
    onDelete: () -> Unit,
    // §v2.16.18 — Optional so callers that don't care about pickup
    // timeline (none today, but defensive) still compile.
    details: SessionRecordDetails? = null,
    onExpand: () -> Unit = {},
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
            title = { Text(stringResource(R.string.sessions_delete_title)) },
            text = { Text(stringResource(R.string.sessions_delete_body, session.tag)) },
            confirmButton = {
                Button(
                    onClick = {
                        pendingDelete = false
                        onDelete()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = false }) { Text(stringResource(R.string.action_cancel)) }
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
                Icon(Icons.Default.Delete, stringResource(R.string.action_delete), tint = MaterialTheme.colorScheme.onError)
            }
        },
        enableDismissFromStartToEnd = false,
    ) {
        SessionItemContent(session, checklistTitle, details, onExpand)
    }
}

@Composable
private fun SessionItemContent(
    session: SessionEntity,
    checklistTitle: String?,
    details: SessionRecordDetails?,
    onExpand: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    // §tz-anchor — render this past session in the zone it was started in
    // (recordedTz), not the device's current zone. null/invalid → device tz.
    val zone = session.recordedTz?.let { runCatching { ZoneId.of(it) }.getOrNull() }
        ?: ZoneId.systemDefault()
    val startInstant = Instant.ofEpochMilli(session.startedAt).atZone(zone)
    val dateStr = startInstant.format(DateTimeFormatter.ofPattern("EEE, MMM dd", LocaleManager.currentLocale()))
    val h = session.durationMinutes / 60
    val m = session.durationMinutes % 60
    val durationStr = if (h > 0) "${h}h ${m}m" else "${m}m"

    // §v2.16.18 — Fire the lazy-fetch + Flow subscription the first
    // time the user expands the card. ViewModel short-circuits on
    // already-loaded so taps after the first are free.
    LaunchedEffect(expanded) {
        if (expanded) onExpand()
    }

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
                    // §9.7 / 2026-06-06 — Surface the user's post-session
                    // debrief ("what did you work on?") right under the
                    // tag so the list itself is glanceable without
                    // expanding. Nullable: skipped debriefs render
                    // nothing. Tag chip sits inline as a small label
                    // ("deep_work", "admin", etc.) when Haiku classified.
                    if (!session.debrief.isNullOrBlank()) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(top = 2.dp),
                        ) {
                            Text(
                                session.debrief!!,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            if (!session.debriefTag.isNullOrBlank()) {
                                Spacer(modifier = Modifier.size(6.dp))
                                Box(
                                    modifier = Modifier
                                        .background(
                                            color = MaterialTheme.colorScheme.secondaryContainer,
                                            shape = RoundedCornerShape(50),
                                        )
                                        .padding(horizontal = 6.dp, vertical = 1.dp),
                                ) {
                                    Text(
                                        session.debriefTag!!.replace('_', ' '),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    )
                                }
                            }
                        }
                    }
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
                    val timeFormat = DateTimeFormatter.ofPattern("hh:mm a", LocaleManager.currentLocale())
                    val timeStr = if (session.endedAt != null) {
                        val endInstant = Instant.ofEpochMilli(session.endedAt).atZone(zone)
                        "${startInstant.format(timeFormat)} – ${endInstant.format(timeFormat)}"
                    } else {
                        startInstant.format(timeFormat)
                    }
                    SessionDetailRow(stringResource(R.string.sessions_detail_time), timeStr)
                    val planStr = if (session.breakDuration > 0) {
                        stringResource(R.string.sessions_plan_work_break, session.workDuration, session.breakDuration)
                    } else {
                        stringResource(R.string.sessions_plan_planned, session.workDuration)
                    }
                    SessionDetailRow(stringResource(R.string.sessions_detail_plan), planStr)
                    if (!checklistTitle.isNullOrBlank()) {
                        SessionDetailRow(stringResource(R.string.sessions_detail_task), checklistTitle)
                    }
                    if (!session.isSynced) {
                        Text(
                            stringResource(R.string.sessions_not_synced),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }

                    // §v2.16.18 — Per-pickup timeline, mirror of the
                    // Sleep tab's pickup section. Rendered when (a) the
                    // session reports any pickups AND (b) the Flow has
                    // delivered the detail rows. Older sessions logged
                    // before v2.16.18 have a phonePickups count but no
                    // timeline data — they degrade gracefully to count-
                    // only (the "Pickups" label is skipped entirely so
                    // the card doesn't show an empty placeholder).
                    val pickups = details?.pickups ?: emptyList()
                    if (session.phonePickups > 0 && pickups.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant
                                .copy(alpha = 0.5f),
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            stringResource(R.string.sessions_phone_pickups),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        val pickupTimeFormat =
                            DateTimeFormatter.ofPattern("hh:mm a", LocaleManager.currentLocale())
                        pickups.forEachIndexed { index, pickup ->
                            val pickupTime = Instant.ofEpochMilli(pickup.pickedUpAt)
                                .atZone(zone)
                                .format(pickupTimeFormat)
                            val durationLabel = formatPickupDuration(pickup.durationSeconds)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 2.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        Icons.Default.PhoneAndroid,
                                        null,
                                        modifier = Modifier.size(12.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        stringResource(R.string.sessions_pickup_row, index + 1, pickupTime),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                }
                                Text(
                                    durationLabel,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun formatPickupDuration(durationSeconds: Int): String {
    if (durationSeconds < 60) return "${durationSeconds}s"
    val m = durationSeconds / 60
    val s = durationSeconds % 60
    return if (s == 0) "${m}m" else "${m}m ${s}s"
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

/// §9.7 — One-line "what did you work on?" prompt that shows after a
/// focus session ends. On submit, Haiku assigns a bucket (deep_work /
/// meetings / admin / other) and the dialog flips to show the tag for
/// a beat before the user closes it.
@Composable
private fun DebriefPromptDialog(
    prompt: SessionDebriefPrompt,
    onTextChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.sessions_debrief_title)) },
        text = {
            Column {
                when (val s = prompt.submitState) {
                    is DebriefSubmitState.Idle,
                    is DebriefSubmitState.Error -> {
                        OutlinedTextField(
                            value = prompt.text,
                            onValueChange = onTextChange,
                            placeholder = { Text(stringResource(R.string.sessions_debrief_hint)) },
                            singleLine = false,
                            maxLines = 3,
                            supportingText = { Text(stringResource(R.string.sessions_debrief_count, prompt.text.length)) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        if (s is DebriefSubmitState.Error) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                s.message,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                    DebriefSubmitState.Submitting -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(stringResource(R.string.sessions_tagging), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    is DebriefSubmitState.Tagged -> {
                        Text(
                            prompt.text,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            stringResource(R.string.sessions_tagged_as, s.tag.replace('_', ' ')),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        },
        confirmButton = {
            when (prompt.submitState) {
                is DebriefSubmitState.Tagged -> {
                    Button(onClick = onDismiss) { Text(stringResource(R.string.sessions_done)) }
                }
                DebriefSubmitState.Submitting -> {
                    Button(onClick = {}, enabled = false) { Text(stringResource(R.string.action_save)) }
                }
                else -> {
                    Button(
                        onClick = onSubmit,
                        enabled = prompt.text.trim().isNotEmpty(),
                    ) { Text(stringResource(R.string.action_save)) }
                }
            }
        },
        dismissButton = {
            if (prompt.submitState !is DebriefSubmitState.Tagged &&
                prompt.submitState !is DebriefSubmitState.Submitting) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_skip)) }
            }
        },
    )
}
