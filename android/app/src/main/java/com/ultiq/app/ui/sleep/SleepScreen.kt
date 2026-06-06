package com.ultiq.app.ui.sleep

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.MicOff
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
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ultiq.app.data.local.entity.AlarmEntity
import com.ultiq.app.data.local.entity.SleepRecordEntity
import com.ultiq.app.data.remote.dto.SleepStats
import com.ultiq.app.ui.alarms.AlarmsViewModel
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
import kotlinx.coroutines.launch
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
        // v2.13.19 — Floating + on the Alarms sub-tab so adding an alarm
        // doesn't require scrolling past the existing list + OEM card +
        // debug card to reach the bottom button. Hidden on the Sleep
        // sub-tab where the primary action is Start Sleep (its own big
        // card) and a stray FAB would be misleading.
        floatingActionButton = {
            if (subTab == 1) {
                FloatingActionButton(onClick = onCreateAlarm) {
                    Icon(Icons.Default.Add, contentDescription = "Add alarm")
                }
            }
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
            audioEvents = uiState.endedAudioEvents,
            aiRatingLoading = uiState.aiRatingLoading,
            aiRatingResult = uiState.aiRatingResult,
            aiRatingError = uiState.aiRatingError,
            onRequestAiRating = { viewModel.requestAiSleepRating() },
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

    // v2.13.3 — Removed AchievementCelebration dialog. Achievements still
    // record silently; the user reviews them in Settings → Achievements.

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
private fun UnsyncedAudioBanner(
    count: Int,
    failedThisSession: Boolean,
    onRetryNow: () -> Unit,
) {
    // §10.x-fix — Tertiary container (warmer than error, more attention
    // than surface) keeps the banner informational. If the user already
    // saw the in-session upload fail (lastUploadFailed=true) we lead with
    // a stronger title; otherwise it's the steady-state "still syncing"
    // message that the WorkManager will resolve in the background.
    val title = if (failedThisSession) {
        "Last night's sounds didn't sync"
    } else {
        "Syncing last night's sounds…"
    }
    val body = if (failedThisSession) {
        "$count event${if (count == 1) "" else "s"} are stored locally but " +
            "haven't reached the server yet. Tap Retry to upload now, or " +
            "they'll retry automatically when you next have signal."
    } else {
        "$count event${if (count == 1) "" else "s"} waiting to upload. " +
            "They'll send automatically when your connection is available."
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Default.GraphicEq,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.size(28.dp),
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
                Spacer(modifier = Modifier.height(8.dp))
                TextButton(
                    onClick = onRetryNow,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                ) {
                    Text(
                        text = "Retry now",
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

@Composable
private fun MicPermissionBanner(
    onGrant: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Default.MicOff,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.size(32.dp),
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Snore tracking needs mic permission",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "Your account has snore + cough detection enabled, " +
                        "but this device hasn't been granted microphone access. " +
                        "Audio analysis stays on-device — never uploaded.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = onGrant,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.onErrorContainer,
                        contentColor = MaterialTheme.colorScheme.errorContainer,
                    ),
                ) {
                    Text("Grant permission")
                }
            }
        }
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

    // §mic-permission-banner (v2.13.5) — On a fresh install, the user's
    // `audio_tracking_enabled` preference round-trips from the server (so
    // the toggle in Sleep Preferences reads ON), but the OS-level
    // RECORD_AUDIO grant does not — it's per-install. Without this banner,
    // sleep sessions silently skip the snore/cough pipeline and the user
    // never realises until they check the Audio diagnostic card. Re-check
    // on lifecycle resume so a system-settings grant flips the banner off
    // without needing a tab swap.
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var hasMicPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO,
            ) == PackageManager.PERMISSION_GRANTED,
        )
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasMicPermission = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.RECORD_AUDIO,
                ) == PackageManager.PERMISSION_GRANTED
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> hasMicPermission = granted }

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

        if (uiState.settings?.audioTrackingEnabled == true && !hasMicPermission) {
            item(key = "mic-permission-banner") {
                MicPermissionBanner(
                    onGrant = {
                        micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    },
                )
            }
        }

        // §10.x-fix — Surface unsynced audio events so the user knows to
        // keep the app open while the WorkManager retry runs. Banner
        // disappears the moment the count hits zero. The stronger
        // lastUploadFailed flag drives a more urgent tone right after a
        // failed session-end; once enough time passes for the worker to
        // start trying, the steady-state banner takes over.
        if (uiState.unsyncedAudioEventCount > 0) {
            item(key = "unsynced-audio-banner") {
                UnsyncedAudioBanner(
                    count = uiState.unsyncedAudioEventCount,
                    failedThisSession = uiState.lastUploadFailed,
                    onRetryNow = { viewModel.retryUnsyncedNow() },
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

        // §10 — Show the on-device snore + cough counts for the latest sleep
        // session. Card hides when neither type fired during the night, so
        // users who don't snore / never enable the toggle never see it.
        if (uiState.tonightSnoreCount > 0 || uiState.tonightCoughCount > 0 || uiState.tonightSleepTalkCount > 0) {
            item(key = "tonight-sounds") {
                AnimatedAppear {
                    TonightSoundsCard(
                        snoreCount = uiState.tonightSnoreCount,
                        coughCount = uiState.tonightCoughCount,
                        sleepTalkCount = uiState.tonightSleepTalkCount,
                        bedtimeMs = uiState.tonightSleepBedtimeMs,
                    )
                }
            }
        }

        if (hasStats) {
            item(key = "stats") {
                AnimatedAppear { StatsRow(uiState.stats!!) }
            }
            item(key = "chart") {
                AnimatedAppear(delayMillis = 100) {
                    SleepChart(
                        records = uiState.records,
                        sleepTargetMinutes = uiState.settings?.sleepTargetMinutes,
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
                        details = uiState.recordDetails[record.id],
                        onExpand = { viewModel.fetchRecordDetails(record.id) },
                        onDelete = { viewModel.deleteRecord(record.id) },
                        onFetchClipUrl = { eventId -> viewModel.fetchClipPlaybackUrl(eventId) },
                        onDeleteClip = { eventId, sleepRecId, onDone ->
                            viewModel.deleteClip(eventId, sleepRecId, onDone)
                        },
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
    // §sleep-day (v2.13.17) — Group by sleep_day so a Tue 02:00 bedtime
    // appears under Monday alongside other Monday-night sleeps, instead
    // of jumping ahead into Tuesday. Display fields (bedtime/wake/quality)
    // still show the raw clock times underneath the group label.
    fun dayOf(r: SleepRecordEntity): java.time.LocalDate =
        com.ultiq.app.util.sleepDayFor(r.actualBedtime, zone)

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
        // v2.13.19 — Extra bottom inset (88dp) clears the floating + so the
        // last alarm row / OEM card / debug card aren't tucked under the FAB
        // when scrolled to the bottom.
        contentPadding = PaddingValues(top = 8.dp, bottom = 88.dp),
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
    details: SleepRecordDetails?,
    onExpand: () -> Unit,
    onDelete: () -> Unit,
    onFetchClipUrl: suspend (eventId: String) -> String?,
    onDeleteClip: (eventId: String, sleepRecordId: String, onDone: () -> Unit) -> Unit,
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
                .clickable {
                    val nextExpanded = !expanded
                    expanded = nextExpanded
                    // §10 — Lazy-fetch pickup + snore/cough detail on first
                    // expansion. The ViewModel caches by record id so
                    // collapsing + re-expanding doesn't refire the request.
                    if (nextExpanded) onExpand()
                },
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
                        // §2026-06-06 — Notes (when present) leads the
                        // expanded section. It's the only user-written field
                        // here, so users glancing at a past record see their
                        // own words first, then the derived stats.
                        if (!record.notes.isNullOrBlank()) {
                            DetailRow("Notes", record.notes)
                        }
                        DetailRow("Target", targetStr)
                        DetailRow("Actual", "${bedInstant.format(timeFormat)} - ${wakeInstant.format(timeFormat)}")
                        if (record.totalPhoneMinutes != null) {
                            DetailRow("Phone Time", "${record.totalPhoneMinutes} min")
                        }
                        if (!record.isSynced) {
                            Text("Not synced", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 4.dp))
                        }
                        // §10 — Lazy-loaded detail: pickup timeline + snore /
                        // cough event list. The ViewModel fetches pickups from
                        // backend and audio events from Room when the user
                        // first expands the card.
                        RecordDetailSection(
                            details = details,
                            sleepRecordId = record.id,
                            onFetchClipUrl = onFetchClipUrl,
                            onDeleteClip = onDeleteClip,
                            zone = zone,
                            timeFormat = timeFormat,
                        )
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

/// §10 — Lazy-loaded detail section beneath the existing target/actual rows.
/// Renders pickup timeline + snore + cough + sleep_talk event lists for the
/// record. Hidden before [details] is loaded; "Loading…" placeholder while
/// in flight; quiet if loaded but empty.
@Composable
private fun RecordDetailSection(
    details: SleepRecordDetails?,
    sleepRecordId: String,
    onFetchClipUrl: suspend (eventId: String) -> String?,
    onDeleteClip: (eventId: String, sleepRecordId: String, onDone: () -> Unit) -> Unit,
    zone: ZoneId,
    timeFormat: DateTimeFormatter,
) {
    if (details == null) return
    if (details.loading) {
        Text(
            "Loading details…",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
        return
    }
    if (!details.loaded) return

    val pickups = details.pickups
    val snores = details.audioEvents.filter { it.eventType == "snore" }
    val coughs = details.audioEvents.filter { it.eventType == "cough" }
    val sleepTalks = details.audioEvents.filter { it.eventType == "sleep_talk" }
    if (pickups.isEmpty() && snores.isEmpty() && coughs.isEmpty() && sleepTalks.isEmpty()) return

    HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))

    if (pickups.isNotEmpty()) {
        Text(
            "Phone pickups",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        pickups.forEachIndexed { index, p ->
            val time = Instant.ofEpochMilli(p.pickedUpAt).atZone(zone).format(timeFormat)
            val durMins = p.durationSeconds / 60
            val durSecs = p.durationSeconds % 60
            val durText = if (durMins > 0) "${durMins}m ${durSecs}s" else "${durSecs}s"
            DetailEventRow(
                icon = Icons.Default.PhoneAndroid,
                label = "#${index + 1} at $time",
                trailing = durText,
            )
        }
    }

    // §10.x — One AudioEventGroup per type. Each owns its own playback
    // state so playing a snore doesn't kick off a cough; auto-advance
    // within the group works like the web dashboard.
    if (snores.isNotEmpty()) {
        if (pickups.isNotEmpty()) Spacer(Modifier.height(8.dp))
        SleepAudioEventGroup(
            title = "Snoring",
            events = snores,
            sleepRecordId = sleepRecordId,
            onFetchClipUrl = onFetchClipUrl,
            onDeleteClip = onDeleteClip,
            zone = zone,
            timeFormat = timeFormat,
        )
    }

    if (coughs.isNotEmpty()) {
        if (snores.isNotEmpty() || pickups.isNotEmpty()) Spacer(Modifier.height(8.dp))
        SleepAudioEventGroup(
            title = "Coughing",
            events = coughs,
            sleepRecordId = sleepRecordId,
            onFetchClipUrl = onFetchClipUrl,
            onDeleteClip = onDeleteClip,
            zone = zone,
            timeFormat = timeFormat,
        )
    }

    if (sleepTalks.isNotEmpty()) {
        if (snores.isNotEmpty() || coughs.isNotEmpty() || pickups.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
        }
        SleepAudioEventGroup(
            title = "Sleep-talk",
            events = sleepTalks,
            sleepRecordId = sleepRecordId,
            onFetchClipUrl = onFetchClipUrl,
            onDeleteClip = onDeleteClip,
            zone = zone,
            timeFormat = timeFormat,
        )
    }
}

/**
 * §10.x — Renders one event-type's full list with inline playback for
 * rows that have a clip. Owns:
 *   - `playingId`: which event is currently expanded with a player
 *   - `mediaPlayer`: single MediaPlayer instance reused across rows
 *
 * Auto-advance: when the active clip's MediaPlayer fires onCompletion, we
 * walk to the next event in the list with `hasClip = true` and switch.
 */
@Composable
private fun SleepAudioEventGroup(
    title: String,
    events: List<com.ultiq.app.data.local.entity.SleepAudioEventEntity>,
    sleepRecordId: String,
    onFetchClipUrl: suspend (eventId: String) -> String?,
    onDeleteClip: (eventId: String, sleepRecordId: String, onDone: () -> Unit) -> Unit,
    zone: ZoneId,
    timeFormat: DateTimeFormatter,
) {
    val totalSecs = events.sumOf { (it.endedAt - it.startedAt) / 1000L }
    val anyClips = events.any { it.hasClip }

    Text(
        "$title · ${events.size} episode${if (events.size != 1) "s" else ""} (${totalSecs}s)" +
            if (anyClips) " · tap ▶ to play" else "",
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    var playingId by remember { mutableStateOf<String?>(null) }

    events.forEachIndexed { index, e ->
        val time = Instant.ofEpochMilli(e.startedAt).atZone(zone).format(timeFormat)
        val durSec = ((e.endedAt - e.startedAt) / 1000L).coerceAtLeast(1L)
        if (e.hasClip) {
            EventPlaybackRow(
                event = e,
                index = index,
                label = "#${index + 1} at $time",
                durationText = "${durSec}s",
                isExpanded = playingId == e.id,
                onToggle = { playingId = if (playingId == e.id) null else e.id },
                onCompleted = {
                    // Walk to the next event with a clip (or stop).
                    val next = events.drop(index + 1).firstOrNull { it.hasClip }
                    playingId = next?.id
                },
                onFetchUrl = { onFetchClipUrl(e.id) },
                onConfirmDelete = {
                    onDeleteClip(e.id, sleepRecordId) {
                        if (playingId == e.id) playingId = null
                    }
                },
            )
        } else {
            DetailEventRow(
                icon = Icons.Default.GraphicEq,
                label = "#${index + 1} at $time",
                trailing = "${durSec}s",
            )
        }
    }
}

/**
 * §10.x — Compact row that expands inline to a MediaPlayer-backed audio
 * player on tap. State machine:
 *   collapsed   → tap row → fetch presigned URL → play
 *   playing     → tap row again → stop + collapse
 *   playing end → auto-advance via [onCompleted]
 *
 * Delete uses an AlertDialog confirm step per the project-wide swipe+confirm
 * pattern (feedback_destructive_confirmation): a Pro-tier clip is a real
 * artifact the user might tap by accident.
 */
@Composable
private fun EventPlaybackRow(
    event: com.ultiq.app.data.local.entity.SleepAudioEventEntity,
    index: Int,
    label: String,
    durationText: String,
    isExpanded: Boolean,
    onToggle: () -> Unit,
    onCompleted: () -> Unit,
    onFetchUrl: suspend () -> String?,
    onConfirmDelete: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var confirmingDelete by remember { mutableStateOf(false) }
    var loadingUrl by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf<String?>(null) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggle() }
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (isExpanded) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = if (isExpanded) "Stop" else "Play",
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(
                "  $label",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            event.clipDurationMs?.let { ms ->
                Text(
                    "${(ms + 500) / 1000}s clip · ",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                durationText,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (isExpanded) {
        // Single-player-per-expanded-row. Released on dispose so a row
        // collapsing doesn't leave a MediaPlayer instance dangling.
        val player = remember { android.media.MediaPlayer() }
        val playerScope = rememberCoroutineScope()
        DisposableEffect(event.id) {
            loadingUrl = true
            loadError = null
            val job = playerScope.launch {
                val url = try { onFetchUrl() } catch (_: Throwable) { null }
                loadingUrl = false
                if (url == null) {
                    // The clip URL fetch + the playback itself both require
                    // network — clips stream from S3 via a presigned URL and
                    // aren't stored locally (see SleepAudioClipCapture.SUBDIR
                    // deletion on upload). Detect the offline case explicitly
                    // so the user gets a useful message instead of the
                    // catch-all "Couldn't load clip".
                    loadError = if (isOnline(context)) {
                        "Couldn't load clip"
                    } else {
                        "No connection — connect to play this clip"
                    }
                    return@launch
                }
                try {
                    player.reset()
                    player.setAudioAttributes(
                        android.media.AudioAttributes.Builder()
                            .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build(),
                    )
                    player.setDataSource(url)
                    player.setOnPreparedListener { it.start() }
                    player.setOnCompletionListener { onCompleted() }
                    player.setOnErrorListener { _, _, _ ->
                        // MediaPlayer streams from S3, so a mid-playback
                        // connectivity loss surfaces here too.
                        loadError = if (isOnline(context)) {
                            "Playback failed"
                        } else {
                            "No connection — connect to play this clip"
                        }
                        true
                    }
                    player.prepareAsync()
                } catch (e: Throwable) {
                    loadError = if (isOnline(context)) {
                        "Playback failed"
                    } else {
                        "No connection — connect to play this clip"
                    }
                }
            }
            onDispose {
                job.cancel()
                try { player.stop() } catch (_: Throwable) {}
                try { player.release() } catch (_: Throwable) {}
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, top = 4.dp, bottom = 8.dp),
        ) {
            when {
                loadingUrl -> Text(
                    "Loading…",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                loadError != null -> Text(
                    loadError!!,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
                else -> Text(
                    "Playing… (tap row again to stop)",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Row(
                modifier = Modifier.padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Confidence ${"%.0f".format(event.peakConfidence * 100)}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = { confirmingDelete = true }) {
                    Text("Delete clip", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }

    if (confirmingDelete) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text("Delete this recording?") },
            text = { Text("The detection event stays — only the audio clip is removed.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmingDelete = false
                    onConfirmDelete()
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmingDelete = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun DetailEventRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    trailing: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                icon,
                null,
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "  $label",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Text(
            trailing,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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

/**
 * §10 — Renders the snore + cough counts captured by on-device YAMNet
 * during the user's most recent sleep session. Hidden by SleepScreen when
 * neither count is > 0, so users who never snore / never enable tracking
 * never see this surface.
 */
@Composable
private fun TonightSoundsCard(
    snoreCount: Int,
    coughCount: Int,
    sleepTalkCount: Int,
    bedtimeMs: Long,
) {
    val date = java.time.Instant.ofEpochMilli(bedtimeMs)
        .atZone(java.time.ZoneId.systemDefault())
        .toLocalDate()
    val today = java.time.LocalDate.now()
    // Most-recent sleep is conceptually "last night" regardless of clock
    // time — same-day test sessions still belong to the user's most recent
    // sleep period. Older records show the date so the user can tell which
    // night they're looking at.
    val label = if (date >= today.minusDays(1)) {
        "Last night"
    } else {
        date.format(java.time.format.DateTimeFormatter.ofPattern("EEE, MMM d"))
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.GraphicEq,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "Sleep sounds — $label",
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (snoreCount > 0) {
                    SoundCountTile("Snoring", snoreCount, Modifier.weight(1f))
                }
                if (coughCount > 0) {
                    SoundCountTile("Cough", coughCount, Modifier.weight(1f))
                }
                if (sleepTalkCount > 0) {
                    SoundCountTile("Sleep-talk", sleepTalkCount, Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun SoundCountTile(label: String, count: Int, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            count.toString(),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            "$label ${if (count == 1) "episode" else "episodes"}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Returns true if the device currently has any usable network. Used by
 *  the clip playback row to swap the generic "Couldn't load" message
 *  for a concrete "No connection" hint when the failure is offline-
 *  related — clip URLs and the audio bytes themselves both come from
 *  the network, since clips aren't cached on device. */
private fun isOnline(context: android.content.Context): Boolean {
    val cm = context.getSystemService(android.content.Context.CONNECTIVITY_SERVICE)
        as? android.net.ConnectivityManager ?: return true
    return cm.activeNetwork != null
}
