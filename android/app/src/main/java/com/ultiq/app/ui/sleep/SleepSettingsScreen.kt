package com.ultiq.app.ui.sleep

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ultiq.app.audio.AudioInitStatus
import com.ultiq.app.ui.common.DurationStepperCard
import com.ultiq.app.ui.common.SectionHeaderWithSuffix
import com.ultiq.app.ui.common.StepperCard
import com.ultiq.app.ui.common.SwitchCard
import com.ultiq.app.ui.common.TimeSettingCard
import com.ultiq.app.ui.common.formatDuration
import com.ultiq.app.ui.common.targetDurationMinutes

/**
 * Stand-alone Sleep preferences screen, opened from the gear icon on the
 * Sleep tab. Keeps the Sleep tab itself focused on the session control,
 * stats, and alarms rather than getting cluttered with configuration cards.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SleepSettingsScreen(
    onBack: () -> Unit,
    viewModel: SleepViewModel = viewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val settings = uiState.settings
    val context = LocalContext.current

    // §10 — RECORD_AUDIO is a runtime permission; the toggle launches the
    // system prompt when the user flips it on without the permission already
    // granted. Denial silently leaves the toggle off — no nag, no error.
    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) viewModel.setAudioTrackingEnabled(true)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Sleep preferences") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
            )
        },
    ) { inner ->
        if (settings == null) return@Scaffold
        LazyColumn(
            modifier = Modifier.padding(inner).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                DurationStepperCard(
                    icon = Icons.Default.Bedtime,
                    title = "Optimal nightly sleep",
                    description = "Falling short adds to sleep debt; sleeping longer goes into extra rest",
                    valueMinutes = settings.sleepTargetMinutes,
                    stepMinutes = 15,
                    range = 300..720,
                    onValueChange = viewModel::setSleepTargetMinutes,
                    // 6h, 7h, 7.5h, 8h, 8.5h, 9h — the realistic adult range.
                    quickPicks = listOf(360, 420, 450, 480, 510, 540),
                )
            }
            item {
                val durationMins = targetDurationMinutes(settings.targetBedtime, settings.targetWakeTime)
                SectionHeaderWithSuffix(
                    title = "Sleep schedule",
                    suffix = "Duration: ${formatDuration(durationMins)}",
                )
            }
            item {
                TimeSettingCard(
                    icon = Icons.Default.Bedtime,
                    title = "Target bedtime",
                    description = "Used to compute sleep debt and schedule the bedtime reminder",
                    time = settings.targetBedtime,
                    onTimeChange = viewModel::setTargetBedtime,
                )
            }
            item {
                TimeSettingCard(
                    icon = Icons.Default.WbSunny,
                    title = "Target wake time",
                    description = "When you'd ideally wake up",
                    time = settings.targetWakeTime,
                    onTimeChange = viewModel::setTargetWakeTime,
                )
            }
            item {
                SwitchCard(
                    icon = Icons.Default.Bedtime,
                    title = "Lockout during sleep",
                    description = "Off by default — sleep is for sleeping, not friction",
                    checked = settings.lockoutForSleep,
                    onCheckedChange = viewModel::setLockoutForSleep,
                )
            }
            item {
                StepperCard(
                    icon = Icons.Default.Bedtime,
                    title = "Sleep phone-break duration",
                    description = "Quiet window after tapping 'Yes, I need my phone' during a sleep session",
                    value = settings.sleepLockoutGraceMinutes,
                    suffix = "min",
                    step = 1,
                    range = 1..10,
                    onValueChange = viewModel::setSleepLockoutGraceMinutes,
                )
            }
            item {
                SectionHeaderWithSuffix(
                    title = "Sleep sounds",
                    suffix = "",
                )
            }
            item {
                // §10 — Audio analysis is on-device; this never streams to
                // Bedrock or backs up raw audio. Toggling on requires
                // RECORD_AUDIO at first flip; denial leaves the toggle off.
                SwitchCard(
                    icon = Icons.Default.GraphicEq,
                    title = "Track snoring & coughing during sleep",
                    description = "Audio analysed on-device only — never uploaded or stored. " +
                        "Uses ~3-5% extra overnight battery.",
                    checked = settings.audioTrackingEnabled,
                    onCheckedChange = { wantOn ->
                        if (wantOn) {
                            val granted = ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.RECORD_AUDIO,
                            ) == PackageManager.PERMISSION_GRANTED
                            if (granted) {
                                viewModel.setAudioTrackingEnabled(true)
                            } else {
                                micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        } else {
                            viewModel.setAudioTrackingEnabled(false)
                        }
                    },
                )
            }
            item {
                // §10.x — Independent sleep-talk detection. Gated by
                // [audioTrackingEnabled] because the YAMNet pipeline must
                // be running for any class to be evaluated; toggling this
                // off (default) makes the classifier ignore the Speech
                // class entirely, so TV / partner / podcast won't surface
                // as false-positive events.
                SwitchCard(
                    icon = Icons.Default.RecordVoiceOver,
                    title = "Detect sleep-talk",
                    description = "Also listens for speech — off by default. " +
                        "Higher false-positive rate than snore/cough; consider " +
                        "leaving off if you sleep with the TV on.",
                    checked = settings.sleepTalkDetectionEnabled,
                    onCheckedChange = viewModel::setSleepTalkDetectionEnabled,
                    enabled = settings.audioTrackingEnabled,
                )
            }
            item {
                SectionHeaderWithSuffix(
                    title = "Audio recordings",
                    suffix = "Pro",
                )
            }
            item {
                // §10.x — Pro-tier master toggle. First flip-on shows the
                // consent dialog; subsequent flips are silent. The whole
                // section is hidden if audio tracking itself is off — no
                // point recording when there are no events to record.
                SleepAudioRecordingMasterToggle(
                    enabled = settings.audioTrackingEnabled,
                    checked = settings.sleepAudioRecordingEnabled,
                    consentSeen = settings.sleepAudioRecordingConsentSeen,
                    onConfirmEnable = {
                        viewModel.markSleepAudioRecordingConsentSeen()
                        viewModel.setSleepAudioRecordingEnabled(true)
                    },
                    onDisable = { viewModel.setSleepAudioRecordingEnabled(false) },
                )
            }
            if (settings.sleepAudioRecordingEnabled) {
                item {
                    SwitchCard(
                        icon = Icons.Default.FiberManualRecord,
                        title = "Record snore events",
                        description = "Save an audio clip for each detected snore.",
                        checked = settings.sleepAudioRecordSnore,
                        onCheckedChange = viewModel::setSleepAudioRecordSnore,
                    )
                }
                item {
                    SwitchCard(
                        icon = Icons.Default.FiberManualRecord,
                        title = "Record cough events",
                        description = "Save an audio clip for each detected cough.",
                        checked = settings.sleepAudioRecordCough,
                        onCheckedChange = viewModel::setSleepAudioRecordCough,
                    )
                }
                item {
                    SwitchCard(
                        icon = Icons.Default.FiberManualRecord,
                        title = "Record sleep-talk events",
                        description = "Save an audio clip for each detected sleep-talk.",
                        checked = settings.sleepAudioRecordSleepTalk,
                        onCheckedChange = viewModel::setSleepAudioRecordSleepTalk,
                        enabled = settings.sleepTalkDetectionEnabled,
                    )
                }
            }
            // §debug-card — Hidden from the user-facing Sleep Preferences
            // page. It's a developer diagnostic that can surface raw init
            // status like "Loading YAMNet model…". Kept in code (just not
            // rendered) so it's a one-line re-enable when debugging audio.
            // item { AudioInitStatusCard() }
        }
    }
}

/**
 * §10.x — Master toggle wrapper for the Pro-tier "Record events" feature.
 * Renders the SwitchCard and intercepts the on-flip to show a consent
 * dialog the first time per device. The dialog spells out:
 *   - what gets recorded (only audio around the detection window)
 *   - where it lives (encrypted S3, accessible to the user only)
 *   - retention (auto-deleted after 30 days)
 *   - reversibility (toggle off any time, individual delete in playback UI)
 *
 * This matches the project-wide "destructive / sensitive actions need a
 * confirmation dialog" rule from feedback_destructive_confirmation.
 */
@Composable
private fun SleepAudioRecordingMasterToggle(
    enabled: Boolean,
    checked: Boolean,
    consentSeen: Boolean,
    onConfirmEnable: () -> Unit,
    onDisable: () -> Unit,
) {
    var showConsentDialog by remember { mutableStateOf(false) }

    SwitchCard(
        icon = Icons.Default.FiberManualRecord,
        title = "Record events",
        description = "Stores ~10 s audio per detected event so you can play it back. " +
            "Clips auto-delete after 30 days. Pro only.",
        checked = checked,
        onCheckedChange = { wantOn ->
            if (wantOn) {
                if (consentSeen) onConfirmEnable() else showConsentDialog = true
            } else {
                onDisable()
            }
        },
        enabled = enabled,
    )

    if (showConsentDialog) {
        AlertDialog(
            onDismissRequest = { showConsentDialog = false },
            title = { Text("Record sleep audio?") },
            text = {
                Column {
                    Text(
                        "When you turn this on, Ultiq will save a ~10-second audio clip " +
                            "for each snore, cough, or sleep-talk event we detect.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "Clips are stored on Ultiq's servers (encrypted) and auto-delete " +
                            "after 30 days. Only you can play them back.",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                    Text(
                        "Playback requires an internet connection — clips stream from " +
                            "Ultiq's servers and aren't stored on this phone.",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                    Text(
                        "You can turn this off any time, or delete individual clips from " +
                            "the playback list.",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showConsentDialog = false
                    onConfirmEnable()
                }) { Text("Turn on") }
            },
            dismissButton = {
                TextButton(onClick = { showConsentDialog = false }) { Text("Cancel") }
            },
        )
    }
}

/** §10.x (v2.11.4) — Diagnostic readout of the most-recent audio-init
 *  attempt. Surfaces the same information that adb logcat would, but
 *  on the phone itself so users can read it without a USB cable. Updates
 *  live as the next sleep session goes through the init path. */
@Suppress("unused") // §debug-card — call site commented out above; kept for re-enable.
@Composable
private fun AudioInitStatusCard() {
    val status by AudioInitStatus.current.collectAsState()
    Card(
        modifier = Modifier.fillMaxSize(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "Last audio attempt",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                status,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}
