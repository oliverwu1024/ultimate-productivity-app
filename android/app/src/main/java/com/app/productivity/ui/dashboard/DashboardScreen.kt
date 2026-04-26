package com.app.productivity.ui.dashboard

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Nightlight
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.app.productivity.data.local.entity.CalendarEventEntity
import com.app.productivity.ui.calendar.categoryColor
import com.app.productivity.ui.theme.AnimatedAppear
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    onNavigateToSleep: () -> Unit,
    onNavigateToSessions: () -> Unit,
    onNavigateToCalendar: () -> Unit,
    onNavigateToChecklist: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToReports: () -> Unit,
    onLogout: () -> Unit,
    viewModel: DashboardViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* result handled via NotificationManagerCompat checks */ }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Dashboard") },
                actions = {
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Default.Settings, "Settings")
                    }
                    IconButton(onClick = onLogout) {
                        Icon(Icons.AutoMirrored.Filled.Logout, "Logout")
                    }
                }
            )
        }
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = uiState.isSyncing,
            onRefresh = { viewModel.refresh() },
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 1. Greeting
            item { GreetingHeader() }

            // 2. Sync indicator
            item {
                SyncIndicator(
                    isSyncing = uiState.isSyncing,
                    lastSyncTime = uiState.lastSyncTime,
                    onRefresh = { viewModel.refresh() }
                )
            }

            // 3. Last Night's Sleep
            item {
                AnimatedAppear(delayMillis = 50) {
                    SleepCard(sleep = uiState.lastNightSleep, onClick = onNavigateToSleep)
                }
            }

            // 4. Today's Focus
            item {
                AnimatedAppear(delayMillis = 100) {
                    FocusCard(focus = uiState.todayFocus, onClick = onNavigateToSessions)
                }
            }

            // 4b. Today's Checklist
            item {
                AnimatedAppear(delayMillis = 130) {
                    ChecklistCard(
                        summary = uiState.todayChecklist,
                        onClick = onNavigateToChecklist,
                    )
                }
            }

            // 5. Upcoming Events
            item {
                Text(
                    "Upcoming Events",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            if (uiState.upcomingEvents.isEmpty()) {
                item {
                    Text(
                        "No upcoming events",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            items(uiState.upcomingEvents, key = { "${it.id}_${it.startTime}" }) { event ->
                UpcomingEventItem(
                    event = event,
                    onClick = onNavigateToCalendar,
                    modifier = Modifier.animateItem(),
                )
            }
            if (uiState.upcomingEvents.isNotEmpty()) {
                item {
                    TextButton(onClick = onNavigateToCalendar) {
                        Text("View all")
                    }
                }
            }

            // 6. Quick Actions
            item {
                QuickActionsRow(
                    onSleep = onNavigateToSleep,
                    onFocus = onNavigateToSessions,
                    onEvent = onNavigateToCalendar
                )
            }

            // 7. Weekly Highlights
            item {
                AnimatedAppear(delayMillis = 200) {
                    WeeklyHighlightsCard(
                        highlights = uiState.weeklyHighlights,
                        onClick = onNavigateToReports,
                    )
                }
            }

            item { Spacer(Modifier.height(8.dp)) }
        }
        }
    }
}

// ── Greeting ────────────────────────────────────────────────────────────

@Composable
private fun GreetingHeader() {
    val hour = LocalTime.now().hour
    val greeting = when (hour) {
        in 5..11 -> "Good morning"
        in 12..16 -> "Good afternoon"
        in 17..20 -> "Good evening"
        else -> "Good night"
    }
    Text(
        greeting,
        style = MaterialTheme.typography.headlineMedium,
        fontWeight = FontWeight.Bold
    )
}

// ── Sync indicator ──────────────────────────────────────────────────────

@Composable
private fun SyncIndicator(isSyncing: Boolean, lastSyncTime: Long, onRefresh: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        val text = if (lastSyncTime == 0L) "Not synced yet" else {
            val agoMs = System.currentTimeMillis() - lastSyncTime
            val agoMins = (agoMs / 60_000).toInt()
            when {
                agoMins < 1 -> "Just synced"
                agoMins < 60 -> "Synced ${agoMins}m ago"
                else -> "Synced ${agoMins / 60}h ago"
            }
        }
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (isSyncing) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        } else {
            IconButton(onClick = onRefresh, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.Refresh, "Refresh", modifier = Modifier.size(20.dp))
            }
        }
    }
}

// ── Sleep card ──────────────────────────────────────────────────────────

@Composable
private fun SleepCard(sleep: SleepSummary?, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Last Night's Sleep", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            if (sleep != null) {
                Text(sleep.duration, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                // Quality stars
                Row {
                    (1..5).forEach { star ->
                        Icon(
                            imageVector = if (star <= sleep.quality) Icons.Filled.Star else Icons.Outlined.StarBorder,
                            contentDescription = null,
                            tint = if (star <= sleep.quality) com.app.productivity.ui.theme.QualityStar else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    val vsColor = if (sleep.vsTarget.startsWith("+")) Color(0xFF4CAF50) else MaterialTheme.colorScheme.error
                    Text("vs target: ${sleep.vsTarget}", style = MaterialTheme.typography.bodySmall, color = vsColor)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.PhoneAndroid, null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(" ${sleep.phonePickups}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else {
                Text("No sleep data yet", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

// ── Focus card ──────────────────────────────────────────────────────────

@Composable
private fun FocusCard(focus: FocusSummary?, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Today's Focus", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            val f = focus ?: FocusSummary(0, 0, 0, 0)
            val h = f.totalMinutesToday / 60
            val m = f.totalMinutesToday % 60
            val timeStr = if (h > 0) "${h}h ${m}m" else "${m}m"
            Text(timeStr, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("${f.sessionsToday} session${if (f.sessionsToday != 1) "s" else ""}", style = MaterialTheme.typography.bodySmall)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.LocalFireDepartment, null, modifier = Modifier.size(16.dp), tint = Color(0xFFFF9800))
                    Text(
                        " ${f.currentStreak} day${if (f.currentStreak != 1) "s" else ""}",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFFF9800)
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.PhoneAndroid, null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(" ${f.phonePickupsToday}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

// ── Today's checklist card ──────────────────────────────────────────────

@Composable
private fun ChecklistCard(summary: TodayChecklistSummary?, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    Icons.Filled.Checklist,
                    null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    "Today's checklist",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                if (summary != null && summary.totalCount > 0) {
                    Text(
                        "${summary.completedCount} of ${summary.totalCount} done",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (summary == null || summary.totalCount == 0) {
                Text(
                    "Nothing planned for today — tap to add some",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                val progress = summary.completedCount.toFloat() / summary.totalCount.toFloat()
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth(),
                )

                summary.openItems.take(3).forEach { item ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        val priorityColor = when (item.priority) {
                            2 -> MaterialTheme.colorScheme.error
                            1 -> MaterialTheme.colorScheme.tertiary
                            else -> MaterialTheme.colorScheme.outline
                        }
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(color = priorityColor, shape = CircleShape),
                        )
                        Text(
                            item.title,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (summary.openItems.size > 3) {
                    Text(
                        "+${summary.openItems.size - 3} more",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

// ── Upcoming event item ─────────────────────────────────────────────────

@Composable
private fun UpcomingEventItem(event: CalendarEventEntity, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val zone = ZoneId.systemDefault()
    val fmt = DateTimeFormatter.ofPattern("EEE h:mm a")
    val timeStr = Instant.ofEpochMilli(event.startTime).atZone(zone).format(fmt)
    val dotColor = categoryColor(event.category)

    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Category color dot
            androidx.compose.foundation.Canvas(modifier = Modifier.size(10.dp)) {
                drawCircle(color = dotColor)
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    event.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    timeStr,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

// ── Quick Actions ───────────────────────────────────────────────────────

@Composable
private fun QuickActionsRow(
    onSleep: () -> Unit,
    onFocus: () -> Unit,
    onEvent: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        QuickActionButton("Log Sleep", Icons.Default.Nightlight, onSleep, Modifier.weight(1f))
        QuickActionButton("Start Focus", Icons.Default.PlayArrow, onFocus, Modifier.weight(1f))
        QuickActionButton("Add Event", Icons.Default.Add, onEvent, Modifier.weight(1f))
    }
}

@Composable
private fun QuickActionButton(label: String, icon: ImageVector, onClick: () -> Unit, modifier: Modifier = Modifier) {
    FilledTonalButton(
        onClick = onClick,
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 12.dp)
    ) {
        Icon(icon, null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(4.dp))
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}

// ── Weekly Highlights ───────────────────────────────────────────────────

@Composable
private fun WeeklyHighlightsCard(highlights: WeeklyHighlights?, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Weekly Highlights", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            val h = highlights
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                HighlightStat("Avg Sleep", h?.avgSleepDuration ?: "-")
                HighlightStat("Sleep Quality", if (h != null && h.avgSleepQuality > 0) String.format("%.1f/5", h.avgSleepQuality) else "-")
                HighlightStat("Focus", if (h != null) String.format("%.1fh", h.totalFocusHours) else "-")
                HighlightStat("Events", if (h != null) "${h.eventsCompleted}/${h.eventsTotal}" else "-")
            }
        }
    }
}

@Composable
private fun HighlightStat(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
