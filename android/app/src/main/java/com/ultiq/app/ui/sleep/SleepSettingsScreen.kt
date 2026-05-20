package com.ultiq.app.ui.sleep

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bedtime
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
import androidx.compose.ui.unit.dp
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
        }
    }
}
