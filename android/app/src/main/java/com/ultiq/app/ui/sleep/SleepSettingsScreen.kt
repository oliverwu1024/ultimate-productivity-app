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
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
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
        }
    }
}
