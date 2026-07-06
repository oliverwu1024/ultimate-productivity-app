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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ultiq.app.R
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
import com.ultiq.app.util.LocaleManager
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
    resetSubTab: Boolean = false,
    onResetSubTabHandled: () -> Unit = {},
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

    // Re-tapping the Sleep item in the bottom nav (while already on this
    // screen) snaps back to the Sleep sub-tab. One-shot flag set in
    // AppNavigation on bottom-nav reselection.
    LaunchedEffect(resetSubTab) {
        if (resetSubTab) {
            subTab = 0
            onResetSubTabHandled()
        }
    }

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
                title = { Text(stringResource(R.string.sleep_title)) },
                actions = {
                    TextButton(onClick = onOpenSleepSettings) {
                        Text(stringResource(R.string.action_preferences))
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
                    Icon(Icons.Default.Add, contentDescription = stringResource(R.string.sleep_add_alarm_cd))
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
                    text = { Text(stringResource(R.string.sleep_title)) },
                )
                Tab(
                    selected = subTab == 1,
                    onClick = { subTab = 1 },
                    text = { Text(stringResource(R.string.sleep_subtab_alarms)) },
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
            onRequestAiRating = { isNap -> viewModel.requestAiSleepRating(isNap) },
            onSave = { quality, notes, isNap -> viewModel.saveSessionRecord(quality, notes, isNap) },
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
        stringResource(R.string.sleep_audio_sync_failed_title)
    } else {
        stringResource(R.string.sleep_audio_syncing_title)
    }
    val body = if (failedThisSession) {
        pluralStringResource(R.plurals.sleep_audio_sync_failed_body, count, count)
    } else {
        pluralStringResource(R.plurals.sleep_audio_syncing_body, count, count)
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
                        text = stringResource(R.string.sleep_retry_now),
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
                    text = stringResource(R.string.sleep_mic_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = stringResource(R.string.sleep_mic_body),
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
                    Text(stringResource(R.string.sleep_grant_permission))
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
        title = { Text(stringResource(R.string.sleep_no_alarm_title)) },
        text = {
            Text(stringResource(R.string.sleep_no_alarm_body))
        },
        confirmButton = {
            Button(onClick = onSetAlarm) { Text(stringResource(R.string.sleep_set_alarm)) }
        },
        dismissButton = {
            TextButton(onClick = onSleepAnyway) { Text(stringResource(R.string.sleep_sleep_without)) }
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
                    title = stringResource(R.string.sleep_prefs_title),
                    body = stringResource(R.string.sleep_prefs_hint_body),
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

        // §10 — On-device sound summary for the latest sleep session. Shows
        // the counts whenever events were captured; shows "Quiet night" when
        // monitoring was on but nothing fired. Stays hidden when monitoring
        // was off (we never listened, so we can't claim the night was quiet)
        // or when there's no session yet.
        val soundsMonitored = uiState.settings?.audioTrackingEnabled == true ||
            uiState.settings?.sleepTalkDetectionEnabled == true
        val hasTonightSounds = uiState.tonightSnoreCount > 0 ||
            uiState.tonightCoughCount > 0 ||
            uiState.tonightSleepTalkCount > 0
        if (uiState.tonightSleepRecordId != null && (hasTonightSounds || soundsMonitored)) {
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
                    text = { Text(stringResource(R.string.range_week)) },
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { viewModel.setTimeRange(TimeRange.MONTH) },
                    text = { Text(stringResource(R.string.range_month)) },
                )
            }
        }

        if (uiState.records.isEmpty()) {
            item(key = "empty") {
                val context = LocalContext.current
                val (title, body) = WarmCopy.sleepEmpty(context)
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
                        stringResource(header),
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
): List<Pair<Int, List<SleepRecordEntity>>> {
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
                R.string.dashboard_this_week.takeIf { thisWeek.isNotEmpty() }?.let { it to thisWeek },
                R.string.dashboard_last_week.takeIf { lastWeek.isNotEmpty() }?.let { it to lastWeek },
            )
        }
        TimeRange.MONTH -> {
            val firstOfThisMonth = today.withDayOfMonth(1)
            val firstOfLastMonth = firstOfThisMonth.minusMonths(1)
            val lastOfLastMonth = firstOfThisMonth.minusDays(1)
            val thisMonth = records.filter { val d = dayOf(it); !d.isBefore(firstOfThisMonth) && !d.isAfter(today) }
            val lastMonth = records.filter { val d = dayOf(it); !d.isBefore(firstOfLastMonth) && !d.isAfter(lastOfLastMonth) }
            listOfNotNull(
                R.string.period_this_month.takeIf { thisMonth.isNotEmpty() }?.let { it to thisMonth },
                R.string.period_last_month.takeIf { lastMonth.isNotEmpty() }?.let { it to lastMonth },
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
                    stringResource(R.string.sleep_explainer_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.weight(1f),
                )
            }
            Text(
                stringResource(R.string.sleep_explainer_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.sleep_got_it))
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
                    stringResource(R.string.sleep_sleeping),
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
                    stringResource(R.string.sleep_elapsed, h, m),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )

                if (pickupEvents.isNotEmpty()) {
                    Text(
                        pluralStringResource(R.plurals.sleep_phone_pickups, pickupEvents.size, pickupEvents.size),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                    )
                }

                val startTime = Instant.ofEpochMilli(sessionStartTime)
                    .atZone(ZoneId.systemDefault())
                    .format(DateTimeFormatter.ofPattern("hh:mm a", LocaleManager.currentLocale()))
                Text(
                    stringResource(R.string.sleep_started_at, startTime),
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
                    Text(stringResource(R.string.sleep_end))
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
                    Text(stringResource(R.string.sleep_start))
                }

                TextButton(onClick = onManualLog) {
                    Text(stringResource(R.string.sleep_log_past), style = MaterialTheme.typography.bodySmall)
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
            StatCard(stringResource(R.string.sleep_stat_avg_duration), "${hours}h ${mins}m")
        }
        item {
            StatCard(
                stringResource(R.string.sleep_stat_avg_quality),
                stringResource(R.string.sleep_quality_value, String.format(LocaleManager.currentLocale(), "%.1f", stats.avgQuality)),
            )
        }
        item {
            val debtH = (stats.debtMinutes / 60).toInt()
            val debtM = (stats.debtMinutes % 60).toInt()
            StatCard(
                stringResource(R.string.dashboard_sleep_debt),
                if (stats.debtMinutes > 0) "${debtH}h ${debtM}m" else "0h 0m",
                valueColor = if (stats.debtMinutes > 0) MaterialTheme.colorScheme.error else Color(0xFF4CAF50)
            )
        }
        item {
            val extraH = (stats.extraMinutes / 60).toInt()
            val extraM = (stats.extraMinutes % 60).toInt()
            StatCard(
                stringResource(R.string.dashboard_extra_rest),
                if (stats.extraMinutes > 0) "${extraH}h ${extraM}m" else "0h 0m",
                valueColor = Color(0xFF4CAF50)
            )
        }
        item {
            StatCard(
                stringResource(R.string.sleep_stat_avg_pickups),
                stringResource(R.string.sleep_per_session, String.format(LocaleManager.currentLocale(), "%.1f", stats.avgPhonePickups)),
            )
        }
        // §last-night — naps are excluded from the averages above; surface the
        // count so they're visible without skewing the numbers.
        if (stats.napCount > 0) {
            item {
                StatCard(stringResource(R.string.sleep_stat_naps), stats.napCount.toString())
            }
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
        LegendDot(Color(0xFF3F51B5), stringResource(R.string.sleep_legend_night))
        LegendDot(Color(0xFF26C6DA), stringResource(R.string.sleep_legend_morning))
        LegendDot(Color(0xFFFFA726), stringResource(R.string.sleep_legend_afternoon))
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
        .format(DateTimeFormatter.ofPattern("EEE, MMM dd", LocaleManager.currentLocale()))

    com.ultiq.app.ui.common.SwipeToDeleteBox(
        confirmTitle = stringResource(R.string.sleep_delete_record_title),
        confirmBody = stringResource(R.string.sleep_delete_record_body, dateStr),
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
                // §tz-anchor — render this past night in the zone it was logged
                // in (recordedTz), not the device's current zone, so an "11pm
                // Sydney" sleep stays 11pm after a move. null/invalid → device tz.
                val zone = record.recordedTz?.let { runCatching { ZoneId.of(it) }.getOrNull() }
                    ?: ZoneId.systemDefault()
                val bedInstant = Instant.ofEpochMilli(record.actualBedtime).atZone(zone)
                val dateStr = bedInstant.format(DateTimeFormatter.ofPattern("EEE, MMM dd", LocaleManager.currentLocale()))
                val durationMins = ((record.actualWakeTime - record.actualBedtime) / 60_000).toInt()
                val hours = durationMins / 60
                val mins = durationMins % 60

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(dateStr, style = MaterialTheme.typography.titleSmall)
                            if (record.isNap) {
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    stringResource(R.string.sleep_nap_badge),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
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
                        val timeFormat = DateTimeFormatter.ofPattern("hh:mm a", LocaleManager.currentLocale())

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
                            DetailRow(stringResource(R.string.detail_notes), record.notes)
                        }
                        DetailRow(stringResource(R.string.detail_target), targetStr)
                        DetailRow(stringResource(R.string.detail_actual), "${bedInstant.format(timeFormat)} - ${wakeInstant.format(timeFormat)}")
                        if (record.totalPhoneMinutes != null) {
                            DetailRow(
                                stringResource(R.string.detail_phone_time),
                                pluralStringResource(R.plurals.estimate_minutes, record.totalPhoneMinutes, record.totalPhoneMinutes),
                            )
                        }
                        if (!record.isSynced) {
                            Text(stringResource(R.string.sleep_not_synced), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 4.dp))
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
        Text(stringResource(R.string.detail_label_prefix, label), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
            stringResource(R.string.sleep_detail_loading),
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
            stringResource(R.string.sleep_phone_pickups_header),
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
                label = stringResource(R.string.sleep_pickup_indexed, index + 1, time),
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
            title = stringResource(R.string.sleep_group_snoring),
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
            title = stringResource(R.string.sleep_group_coughing),
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
            title = stringResource(R.string.sleep_group_sleeptalk),
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
private const val MAX_SANE_AUDIO_EVENT_MS = 24L * 60L * 60L * 1000L

// §abnormal-seconds — guard a corrupt endedAt. A sentinel classifier
// timestamp can produce a ~9.2e15 ms span that renders as ~9.2e12 "s"; treat
// any span outside a sane window as 0 so one bad row can't print garbage
// (also heals rows already written by pre-fix builds).
private fun sleepEventDurationSec(startedAt: Long, endedAt: Long): Long {
    val ms = endedAt - startedAt
    return if (ms in 0..MAX_SANE_AUDIO_EVENT_MS) ms / 1000L else 0L
}

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
    val totalSecs = events.sumOf { sleepEventDurationSec(it.startedAt, it.endedAt) }
    val anyClips = events.any { it.hasClip }

    Text(
        pluralStringResource(R.plurals.sleep_group_episodes, events.size, events.size, title, totalSecs) +
            if (anyClips) stringResource(R.string.sleep_tap_to_play) else "",
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    var playingId by remember { mutableStateOf<String?>(null) }

    events.forEachIndexed { index, e ->
        val time = Instant.ofEpochMilli(e.startedAt).atZone(zone).format(timeFormat)
        val durSec = sleepEventDurationSec(e.startedAt, e.endedAt).coerceAtLeast(1L)
        if (e.hasClip) {
            EventPlaybackRow(
                event = e,
                index = index,
                label = stringResource(R.string.sleep_pickup_indexed, index + 1, time),
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
                label = stringResource(R.string.sleep_pickup_indexed, index + 1, time),
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
    val errLoadClip = stringResource(R.string.sleep_load_clip_error)
    val errOffline = stringResource(R.string.sleep_offline_clip)
    val errPlayback = stringResource(R.string.sleep_playback_failed)

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
                contentDescription = if (isExpanded) stringResource(R.string.sleep_stop_cd) else stringResource(R.string.sleep_play_cd),
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
                    stringResource(R.string.sleep_clip_duration, (ms + 500) / 1000),
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
                    loadError = if (isOnline(context)) errLoadClip else errOffline
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
                        loadError = if (isOnline(context)) errPlayback else errOffline
                        true
                    }
                    player.prepareAsync()
                } catch (e: Throwable) {
                    loadError = if (isOnline(context)) errPlayback else errOffline
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
                    stringResource(R.string.sleep_loading),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                loadError != null -> Text(
                    loadError!!,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
                else -> Text(
                    stringResource(R.string.sleep_playing),
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
                    stringResource(R.string.sleep_confidence, String.format(LocaleManager.currentLocale(), "%.0f", event.peakConfidence * 100)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = { confirmingDelete = true }) {
                    Text(stringResource(R.string.sleep_delete_clip), color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }

    if (confirmingDelete) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text(stringResource(R.string.sleep_delete_recording_title)) },
            text = { Text(stringResource(R.string.sleep_delete_recording_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmingDelete = false
                    onConfirmDelete()
                }) { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmingDelete = false }) { Text(stringResource(R.string.action_cancel)) }
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
    val timeFormat = DateTimeFormatter.ofPattern("hh:mm a", LocaleManager.currentLocale())
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
        title = { Text(stringResource(R.string.sleep_set_wake_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    stringResource(R.string.sleep_set_wake_body),
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
                    Text(stringResource(R.string.sleep_wake_at, wakeTime.format(timeFormat)))
                }
                Text(
                    stringResource(R.string.sleep_target_duration, durationLabel),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(wakeTime) }) { Text(stringResource(R.string.action_start)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

/**
 * §10 — Renders the snore / cough / sleep-talk counts captured by on-device
 * YAMNet during the user's most recent sleep session, or a "Quiet night"
 * state when monitoring was on but nothing fired. SleepScreen decides whether
 * to show it at all (hidden when monitoring was off or there's no session).
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
        stringResource(R.string.sleep_last_night)
    } else {
        date.format(java.time.format.DateTimeFormatter.ofPattern("EEE, MMM d", LocaleManager.currentLocale()))
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
                    stringResource(R.string.sleep_sounds_header, label),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            Spacer(Modifier.height(12.dp))
            if (snoreCount > 0 || coughCount > 0 || sleepTalkCount > 0) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    if (snoreCount > 0) {
                        SoundCountTile(stringResource(R.string.sleep_sound_snoring), snoreCount, Modifier.weight(1f))
                    }
                    if (coughCount > 0) {
                        SoundCountTile(stringResource(R.string.sleep_sound_cough), coughCount, Modifier.weight(1f))
                    }
                    if (sleepTalkCount > 0) {
                        SoundCountTile(stringResource(R.string.sleep_sound_sleeptalk), sleepTalkCount, Modifier.weight(1f))
                    }
                }
            } else {
                // Monitoring was on but nothing fired — reassure rather than
                // hide, mirroring the Dashboard card's "Quiet night" line.
                Text(
                    stringResource(R.string.dashboard_quiet_night),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    stringResource(R.string.sleep_quiet_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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
            pluralStringResource(R.plurals.sleep_label_episodes, count, label),
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
