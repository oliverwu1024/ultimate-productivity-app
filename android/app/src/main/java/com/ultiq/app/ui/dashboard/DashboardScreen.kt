package com.ultiq.app.ui.dashboard

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
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Nightlight
import androidx.compose.material.icons.filled.Notifications
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
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.res.painterResource
import com.ultiq.app.R
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Close
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ultiq.app.data.local.entity.CalendarEventEntity
import com.ultiq.app.ui.calendar.categoryColor
import com.ultiq.app.ui.copy.WarmCopy
import com.ultiq.app.ui.theme.AnimatedAppear
import com.ultiq.app.ui.common.formatDuration
import com.ultiq.app.util.Comparisons
import com.ultiq.app.util.UserPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
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
    onNavigateToChat: () -> Unit,
    onLogout: () -> Unit,
    viewModel: DashboardViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val userPrefs = remember { UserPreferences(context) }
    var showWebHint by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        userPrefs.settings.collectLatest { showWebHint = !it.webDashboardHintSeen }
    }
    val dismissWebHint = {
        showWebHint = false
        CoroutineScope(Dispatchers.IO).launch { userPrefs.setWebDashboardHintSeen(true) }
        Unit
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* result handled via NotificationManagerCompat checks */ }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // Re-check overlay + strict-lock permission state whenever the dashboard
    // comes back into the foreground, so the lock&overlay hint disappears
    // immediately after the user grants permission in system settings.
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                viewModel.refreshLockOverlayState()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Dashboard") },
                actions = {
                    TextButton(onClick = onNavigateToSettings) {
                        Icon(
                            Icons.Default.Settings,
                            contentDescription = null,
                            modifier = Modifier.size(ButtonDefaults.IconSize)
                        )
                        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                        Text("Settings")
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
            item { GreetingHeader(onCoachClick = onNavigateToChat) }

            if (uiState.showLockOverlayHint) {
                item {
                    com.ultiq.app.ui.common.ConfigureHintCard(
                        title = "Grant lock & overlay in Settings",
                        body = "Focus mode and sleep lockout need 'Display over " +
                            "other apps' and 'Strict lock' to work reliably. " +
                            "Without them, the lockout screen can be swiped " +
                            "away. Open Settings (top right) to grant.",
                        onDismiss = { viewModel.dismissLockOverlayHint() },
                    )
                }
            }

            if (uiState.showPrefsHint) {
                item {
                    com.ultiq.app.ui.common.ConfigureHintCard(
                        title = "Set up your sleep & focus preferences",
                        body = "Your wake-up alarms and sleep settings live on the " +
                            "Sleep tab; your focus settings live on the Focus tab. " +
                            "Open each tab and scroll down to configure — or tap " +
                            "this card's × to hide it.",
                        onDismiss = { viewModel.dismissPrefsHint() },
                    )
                }
            }

            if (showWebHint) {
                item {
                    WebDashboardHint(
                        onOpen = {
                            val intent = android.content.Intent(
                                android.content.Intent.ACTION_VIEW,
                                android.net.Uri.parse("https://app.ultiqapp.com"),
                            ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                            context.startActivity(intent)
                            dismissWebHint()
                        },
                        onDismiss = { dismissWebHint() },
                    )
                }
            }

            item {
                SyncIndicator(
                    isSyncing = uiState.isSyncing,
                    lastSyncTime = uiState.lastSyncTime,
                    onRefresh = { viewModel.refresh() }
                )
            }

            item {
                AnimatedAppear(delayMillis = 50) {
                    SleepCard(sleep = uiState.lastNightSleep, onClick = onNavigateToSleep)
                }
            }

            item {
                AnimatedAppear(delayMillis = 100) {
                    FocusCard(focus = uiState.todayFocus, onClick = onNavigateToSessions)
                }
            }

            item {
                AnimatedAppear(delayMillis = 130) {
                    ChecklistCard(
                        summary = uiState.todayChecklist,
                        onClick = onNavigateToChecklist,
                    )
                }
            }

            item {
                Text(
                    "Coming up",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            if (uiState.upcomingItems.isEmpty()) {
                item {
                    Text(
                        WarmCopy.upcomingEventsEmpty(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            items(
                uiState.upcomingItems,
                key = { item ->
                    when (item) {
                        is UpcomingItem.Calendar -> "evt-${item.event.id}-${item.event.startTime}"
                        is UpcomingItem.Checklist -> "list-${item.item.id}"
                    }
                },
            ) { item ->
                when (item) {
                    is UpcomingItem.Calendar -> UpcomingEventItem(
                        event = item.event,
                        onClick = onNavigateToCalendar,
                        modifier = Modifier.animateItem(),
                    )
                    is UpcomingItem.Checklist -> UpcomingChecklistItem(
                        item = item.item,
                        onClick = onNavigateToChecklist,
                        modifier = Modifier.animateItem(),
                    )
                }
            }
            if (uiState.upcomingItems.isNotEmpty()) {
                item {
                    TextButton(onClick = onNavigateToCalendar) {
                        Text("View all")
                    }
                }
            }

            item {
                QuickActionsRow(
                    onSleep = onNavigateToSleep,
                    onFocus = onNavigateToSessions,
                    onEvent = onNavigateToCalendar
                )
            }

            item {
                AnimatedAppear(delayMillis = 200) {
                    WeeklyHighlightsCard(
                        highlights = uiState.weeklyHighlights,
                        onClick = onNavigateToReports,
                    )
                }
            }

            // §9.8 — Anomaly alert card. Renders only when the daily scan
            // flagged something for this user AND the user hasn't dismissed
            // that specific insight locally. Placed above the weekly insight
            // because it's higher-priority (something needs attention) and
            // self-hiding (most days nothing shows up at all).
            uiState.anomalyAlert?.let { alert ->
                item {
                    AnimatedAppear(delayMillis = 210) {
                        AnomalyAlertCard(
                            reason = alert.reason,
                            onDismiss = { viewModel.dismissAnomalyAlert() },
                        )
                    }
                }
            }

            item {
                AnimatedAppear(delayMillis = 220) {
                    AiWeeklyInsightCard(
                        state = uiState.weeklyInsight,
                        onRefresh = { viewModel.loadWeeklyInsight(force = true) },
                    )
                }
            }

            // §9.6 — Coach entry promoted to the GreetingHeader (above the
            // fold) so the user doesn't have to scroll past two cards to
            // reach the chat. The old slim row card lived here.

            if (uiState.achievementsEarnedCount > 0) {
                item {
                    AnimatedAppear(delayMillis = 230) {
                        AchievementsCard(
                            earned = uiState.achievementsEarnedCount,
                            total = uiState.achievementsTotal,
                            recent = uiState.recentAchievements,
                        )
                    }
                }
            }

            item { Spacer(Modifier.height(8.dp)) }
        }
        }
    }
}

@Composable
private fun WebDashboardHint(
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpen() },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.Public,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Did you know?",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.7f),
                )
                Text(
                    "Ultiq is also on the web — full analytics at app.ultiqapp.com",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            }
            IconButton(onClick = onDismiss) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Dismiss",
                    tint = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            }
        }
    }
}

@Composable
private fun GreetingHeader(onCoachClick: () -> Unit) {
    val now = LocalTime.now()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                WarmCopy.greeting(now),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                WarmCopy.greetingSubtitle(now),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(12.dp))
        CoachMascotEntry(onClick = onCoachClick)
    }
}

/// Coach entry mascot, parked next to the morning/evening greeting so it's
/// always above the fold. Idle "breathing" animation gives the avatar a
/// pulse — subtle enough not to be distracting but enough to read as a
/// living, tappable affordance rather than dead decoration. Tapping it
/// opens the Coach chat screen.
@Composable
private fun CoachMascotEntry(onClick: () -> Unit) {
    val transition = rememberInfiniteTransition(label = "coach-breath")
    val scale by transition.animateFloat(
        initialValue = 0.97f,
        targetValue = 1.03f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2200, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "coach-breath-scale",
    )

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .scale(scale)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .border(
                    width = 2.dp,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
                    shape = CircleShape,
                )
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painter = painterResource(R.drawable.ic_coach_mascot),
                contentDescription = "Talk to your coach",
                modifier = Modifier.size(44.dp),
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "Coach",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

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

@Composable
private fun SleepCard(sleep: SleepSummary?, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                WarmCopy.sleepHeader(sleep?.quality, sleep?.vsTargetMinutes),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(8.dp))
            if (sleep != null) {
                Text(sleep.duration, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)

                if (sleep.rankPhrase != null) {
                    Text(
                        "✦ ${sleep.rankPhrase}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Medium,
                    )
                }

                Spacer(Modifier.height(4.dp))
                Row {
                    (1..5).forEach { star ->
                        Icon(
                            imageVector = if (star <= sleep.quality) Icons.Filled.Star else Icons.Outlined.StarBorder,
                            contentDescription = null,
                            tint = if (star <= sleep.quality) com.ultiq.app.ui.theme.QualityStar else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    // §sleep-card-copy — labels now reference "optimal
                    // sleep" (matching the term used in Sleep prefs),
                    // not the engineering-y "target". Same direction
                    // colours as before.
                    val vsColor = if (sleep.vsTargetMinutes >= 0) Color(0xFF4CAF50) else MaterialTheme.colorScheme.error
                    Text(
                        "${sleep.vsTarget} vs optimal sleep",
                        style = MaterialTheme.typography.bodySmall,
                        color = vsColor,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.PhoneAndroid, null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(" ${sleep.phonePickups}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                // §sleep-card-copy — absolute reference instead of the
                // ambiguous delta line. Matches the focus card pattern.
                sleep.lastWeekDailyAvgMinutes?.let { avgMins ->
                    val ah = avgMins / 60
                    val am = avgMins % 60
                    val avgStr = if (ah > 0) "${ah}h ${am}m" else "${am}m"
                    Text(
                        "Last week's daily average was $avgStr",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                val (title, body) = WarmCopy.sleepEmpty()
                Text(title, style = MaterialTheme.typography.bodyMedium)
                Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun FocusCard(focus: FocusSummary?, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                WarmCopy.focusHeader(focus?.totalMinutesToday),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(8.dp))
            val f = focus ?: FocusSummary(0, 0, 0, 0, 0)
            val h = f.totalMinutesToday / 60
            val m = f.totalMinutesToday % 60
            val timeStr = if (h > 0) "${h}h ${m}m" else "${m}m"
            Row(verticalAlignment = Alignment.Bottom) {
                Text(timeStr, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(6.dp))
                Text(
                    "today",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }

            // §audit-2 round-2 — read as an absolute reference point rather
            // than a delta. User found the "-1h 11m vs last week (daily
            // avg)" string ambiguous about which direction was up.
            f.lastWeekDailyAvgMinutes?.let { avgMins ->
                val ah = avgMins / 60
                val am = avgMins % 60
                val avgStr = if (ah > 0) "${ah}h ${am}m" else "${am}m"
                Text(
                    "Last week's daily average was $avgStr",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("${f.sessionsToday} session${if (f.sessionsToday != 1) "s" else ""} today", style = MaterialTheme.typography.bodySmall)

                val streakLine = WarmCopy.streakLine(f.currentStreak, f.longestStreak)
                if (streakLine != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val streakColor = if (f.currentStreak >= f.longestStreak && f.currentStreak >= 2) {
                            Color(0xFFE85B5B)
                        } else {
                            Color(0xFFFF9800)
                        }
                        AnimatedStreakNumber(value = f.currentStreak, color = streakColor)
                        Spacer(Modifier.width(4.dp))
                        Icon(Icons.Default.LocalFireDepartment, null, modifier = Modifier.size(16.dp), tint = streakColor)
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.PhoneAndroid, null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(" ${f.phonePickupsToday}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            if (f.currentStreak >= f.longestStreak && f.currentStreak >= 2) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "Longest streak yet — keep it going",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

@Composable
private fun AnimatedStreakNumber(value: Int, color: Color) {
    val animated by animateIntAsState(
        targetValue = value,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "streak",
    )
    Text(
        "$animated",
        style = MaterialTheme.typography.bodySmall,
        color = color,
        fontWeight = FontWeight.SemiBold,
    )
}

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
                    "Today's plan",
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
                val (_, body) = WarmCopy.checklistEmpty()
                Text(
                    body,
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

/// Same row treatment as [UpcomingEventItem] but for an open checklist
/// item due today. Tapping routes the user to the Checklist tab; no
/// clock-time line because checklist items are day-scoped.
@Composable
private fun UpcomingChecklistItem(
    item: com.ultiq.app.data.local.entity.ChecklistEntity,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(8.dp),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.Checklist,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    item.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "Today",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

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

@Composable
private fun WeeklyHighlightsCard(highlights: WeeklyHighlights?, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "This week",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(12.dp))
            val h = highlights
            // §value-color (v2.13.12) — Warning yellow + good green for the
            // top-row trend signal on Avg sleep + Avg quality. Moved here
            // from the Last-week row underneath (was v2.13.7-v2.13.11)
            // because the trend reads stronger on the big headline number
            // than on the small comparison beneath. `#FFC107` is the
            // standard Android warning amber — clearly yellow on dark
            // surface, not the orange-leaning `#FFA000` we had before.
            val warningYellow = Color(0xFFFFC107)
            val good = Color(0xFF4CAF50)
            val avgSleepColor = h?.avgSleepDeltaMinutes?.let { d ->
                when {
                    d > 0 -> good
                    d < 0 -> warningYellow
                    else -> Color.Unspecified
                }
            } ?: Color.Unspecified
            val avgQualityColor = h?.avgQualityDelta?.let { d ->
                when {
                    d > 0.05 -> good
                    d < -0.05 -> warningYellow
                    else -> Color.Unspecified
                }
            } ?: Color.Unspecified
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                HighlightStat(
                    label = "Avg sleep",
                    value = h?.avgSleepDuration ?: "-",
                    subtitle = null,
                    modifier = Modifier.weight(1f),
                    valueColor = avgSleepColor,
                )
                HighlightStat(
                    label = "Avg quality",
                    value = if (h != null && h.avgSleepQuality > 0) String.format("%.1f/5", h.avgSleepQuality) else "-",
                    subtitle = null,
                    modifier = Modifier.weight(1f),
                    valueColor = avgQualityColor,
                )
                HighlightStat(
                    label = "Total focus",
                    value = if (h != null && h.totalFocusHours > 0.0) {
                        String.format("%.1fh", h.totalFocusHours)
                    } else "-",
                    subtitle = null,
                    modifier = Modifier.weight(1f),
                )
                HighlightStat(
                    label = "Calendar events",
                    value = if (h != null && h.eventsTotal > 0) "${h.eventsTotal}" else "-",
                    subtitle = null,
                    modifier = Modifier.weight(1f),
                )
            }

            // §last-week-per-column (v2.13.11) — Three "Last week" mini-stacks,
            // one per comparable column (Avg sleep / Avg quality / Total
            // focus). Each stack is a small label on top of the prior-week
            // value, column-aligned with the stat tile above via weight(1f).
            // Calendar events column has no comparison by design — fourth
            // slot stays empty so the row keeps the same 4-column grid.
            //
            // §value-color (v2.13.12) — Color signal moved off this row up
            // to the headline "This week" values. The last-week numbers
            // here are now always neutral — they're the reference baseline,
            // not the verdict.
            val anyLastWeek = h?.lastWeekAvgSleepMinutes != null ||
                h?.lastWeekQuality != null ||
                h?.lastWeekFocusHours != null
            if (h != null && anyLastWeek) {
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    LastWeekColumn(
                        value = h.lastWeekAvgSleepMinutes?.let(::formatDuration),
                        modifier = Modifier.weight(1f),
                    )
                    LastWeekColumn(
                        value = h.lastWeekQuality?.let { String.format("%.1f/5", it) },
                        modifier = Modifier.weight(1f),
                    )
                    LastWeekColumn(
                        value = h.lastWeekFocusHours?.let { String.format("%.1fh", it) },
                        modifier = Modifier.weight(1f),
                    )
                    // Calendar events: empty slot to preserve grid alignment.
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
            // §empty-state — hide debt/extra/net tiles when no sleep records
            // this week. Showing "Sleep debt 0m / Extra rest 0m" implies
            // "on target" when actually we just have no data — misleading.
            // avgSleepDuration == "-" is the canonical no-records signal
            // (set in loadWeeklyHighlights when sleepThis is empty).
            if (h != null && h.avgSleepDuration != "-") {
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    SleepBalanceTile(
                        label = "Sleep debt",
                        minutes = h.sleepDebtMinutes,
                        positiveIsGood = false,
                        modifier = Modifier.weight(1f),
                    )
                    SleepBalanceTile(
                        label = "Extra rest",
                        minutes = h.sleepExtraMinutes,
                        positiveIsGood = true,
                        modifier = Modifier.weight(1f),
                    )
                }
                // §net-sleep (v2.13.7) — Extra rest minus sleep debt, signed.
                // Surfaces the bottom line at a glance so the user doesn't
                // have to subtract the two tiles mentally. Hidden when both
                // are zero — nothing meaningful to show before the first
                // night of the week is logged.
                val net = h.sleepExtraMinutes - h.sleepDebtMinutes
                if (h.sleepDebtMinutes > 0 || h.sleepExtraMinutes > 0) {
                    Spacer(Modifier.height(8.dp))
                    val absNet = kotlin.math.abs(net)
                    val absH = absNet / 60
                    val absM = absNet % 60
                    val magnitude = when {
                        absNet == 0 -> "on target"
                        absH == 0 -> "${absM}m"
                        absM == 0 -> "${absH}h"
                        else -> "${absH}h ${absM}m"
                    }
                    val (label, color) = when {
                        net > 0 -> "Net: +$magnitude vs goal" to Color(0xFF4CAF50)
                        net < 0 -> "Net: −$magnitude vs goal" to MaterialTheme.colorScheme.error
                        else -> "Net: on target" to MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelMedium,
                        color = color,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }
    }
}

@Composable
private fun SleepBalanceTile(
    label: String,
    minutes: Int,
    positiveIsGood: Boolean,
    modifier: Modifier = Modifier,
) {
    val active = minutes > 0
    val accent = when {
        !active -> MaterialTheme.colorScheme.onSurfaceVariant
        positiveIsGood -> Color(0xFF4CAF50)
        else -> MaterialTheme.colorScheme.error
    }
    Column(
        modifier = modifier
            .background(
                MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
                RoundedCornerShape(12.dp),
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(2.dp))
        val h = minutes / 60
        val m = minutes % 60
        val display = when {
            !active -> "0m"
            h > 0 -> "${h}h ${m}m"
            else -> "${m}m"
        }
        Text(display, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = accent)
    }
}

/// §last-week-uniform (v2.13.7) — Stat tile with value + label + an
/// optional "Last week: …" subtitle. `subtitleColor` is computed by the
/// caller: neutral for Total focus, green/amber for Avg sleep + Avg
/// quality based on direction. Calendar events passes null subtitle.
///
/// §column-squeeze (v2.13.8) — `modifier` must carry `Modifier.weight(1f)`
/// from the caller so all four tiles share equal width in the parent Row.
/// Without the weight, wider subtitles ("Last week: 8h 32m") stretched
/// their column and bled into the next tile (observed on 2026-05-24).
/// Every text in the tile center-aligns + wraps inside its allotted
/// width instead of pushing siblings around.
///
/// §value-color (v2.13.12) — `valueColor` overrides the default primary
/// for the headline value. Avg sleep + Avg quality use this to render
/// the THIS WEEK number in green (this week > last week) or warning
/// yellow (this week < last week). Total focus + Calendar events leave
/// it `Color.Unspecified` to stay primary. Moves the trend signal onto
/// the prominent headline number rather than the small comparison
/// value beneath, per real-device feedback 2026-05-24.
@Composable
private fun HighlightStat(
    label: String,
    value: String,
    subtitle: String?,
    modifier: Modifier = Modifier,
    subtitleColor: Color = Color.Unspecified,
    valueColor: Color = Color.Unspecified,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = if (valueColor == Color.Unspecified) {
                MaterialTheme.colorScheme.primary
            } else valueColor,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 2,
        )
        if (subtitle != null) {
            val resolved = if (subtitleColor == Color.Unspecified) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else subtitleColor
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = resolved,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                maxLines = 2,
            )
        }
    }
}

/// §last-week-per-column (v2.13.11) — One column's worth of "Last week"
/// comparison: small "Last week" label on top of the prior-week value.
/// Column-aligned with the stat tile above via `Modifier.weight(1f)`
/// from the caller. When `value` is null (no prior-week data for this
/// stat), renders an em dash so the column still aligns with its
/// siblings.
///
/// §value-color (v2.13.12) — Always neutral grey now. The trend signal
/// (green / yellow) moved up to the THIS WEEK headline value where it
/// reads stronger; the last-week reference numbers stay calm.
@Composable
private fun LastWeekColumn(
    value: String?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "Last week",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
        Text(
            text = value ?: "—",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
    }
}

@Composable
private fun AchievementsCard(earned: Int, total: Int, recent: List<AchievementBadge>) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.EmojiEvents,
                    null,
                    tint = Color(0xFFFFC83D),
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "Achievements",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "$earned / $total",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                recent.take(4).forEach { badge ->
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            badge.icon,
                            contentDescription = badge.name,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            val newest = recent.firstOrNull()
            if (newest != null) {
                Text(
                    "Latest: ${newest.name}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/// §9.8 — Anomaly alert card. Surfaced when the daily Haiku scan flagged
/// a pattern worth interrupting the user about. Distinct visual treatment
/// from the weekly insight (tertiary container, leading icon) so it reads
/// as "needs attention" not "here's a summary". One dismiss button —
/// dismissed alerts hide for the rest of the 24h cache window via local
/// DataStore, no server round-trip.
@Composable
private fun AnomalyAlertCard(
    reason: String,
    onDismiss: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Notifications,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onTertiaryContainer,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "Heads up",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onDismiss) {
                    Text(
                        "Dismiss",
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                reason,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
        }
    }
}

/// §9.4 — Weekly AI summary card. Multi-paragraph plain prose; split on
/// blank lines to get the paragraph breaks the model emits per its prompt.
@Composable
private fun AiWeeklyInsightCard(
    state: WeeklyInsightState,
    onRefresh: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Past 7 days — AI summary",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = onRefresh,
                    enabled = state !is WeeklyInsightState.Loading,
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                }
            }
            Spacer(Modifier.height(8.dp))
            when (state) {
                WeeklyInsightState.Idle,
                WeeklyInsightState.Loading -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            "Generating your week…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                is WeeklyInsightState.Loaded -> {
                    state.content.split("\n\n").forEach { paragraph ->
                        val trimmed = paragraph.trim()
                        if (trimmed.isNotEmpty()) {
                            Text(
                                trimmed,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Spacer(Modifier.height(10.dp))
                        }
                    }
                    // §insight-timestamps (v2.13.15) — Explicit "Generated /
                    // Next refresh" timestamps so users understand they're
                    // looking at the same cached summary until the next
                    // 24h-cache rollover. Replaces the older generic line
                    // ("Refreshes every 24 hours — your next summary's on
                    // its way.") which didn't tell the user WHEN. Parses
                    // failsafe — corrupt server timestamp falls back to
                    // the older generic line so the card never silently
                    // drops its footer.
                    val zone = java.time.ZoneId.systemDefault()
                    val fmt = java.time.format.DateTimeFormatter
                        .ofPattern("MMM d 'at' h:mm a")
                    val gen = runCatching {
                        java.time.OffsetDateTime.parse(state.generatedAt)
                            .atZoneSameInstant(zone)
                            .format(fmt)
                    }.getOrNull()
                    val exp = runCatching {
                        java.time.OffsetDateTime.parse(state.expiresAt)
                            .atZoneSameInstant(zone)
                            .format(fmt)
                    }.getOrNull()
                    if (gen != null && exp != null) {
                        Text(
                            "Generated $gen",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            "Next refresh $exp",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else if (state.cached) {
                        Text(
                            "Refreshes every 24 hours — your next summary's on its way.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                is WeeklyInsightState.Error -> {
                    Text(
                        state.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}
