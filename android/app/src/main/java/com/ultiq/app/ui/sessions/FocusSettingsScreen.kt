package com.ultiq.app.ui.sessions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Visibility
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
import com.ultiq.app.ui.common.StepperCard
import com.ultiq.app.ui.common.SwitchCard

/**
 * Stand-alone Focus preferences screen, opened from the gear icon on the
 * Focus tab. Keeps the Focus tab itself focused on the timer + session
 * history rather than getting cluttered with configuration cards.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FocusSettingsScreen(
    onBack: () -> Unit,
    viewModel: SessionsViewModel = viewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val settings = uiState.settings

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Focus preferences") },
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
                StepperCard(
                    icon = Icons.Default.Timer,
                    title = "Default work duration",
                    description = "Length of one focus block",
                    value = settings.defaultWorkDuration,
                    suffix = "min",
                    step = 5,
                    range = 5..240,
                    onValueChange = viewModel::setDefaultWorkDuration,
                    // Pomodoro (15/25/30), deep work (45/60/90), extended focus
                    // blocks (120/150/180/210/240) for marathon coding / study
                    // sessions. Wraps to a second row on typical phone widths.
                    quickPicks = listOf(15, 25, 30, 45, 60, 90, 120, 150, 180, 210, 240),
                )
            }
            item {
                SwitchCard(
                    icon = Icons.Default.Lock,
                    title = "Lockout during focus sessions",
                    description = "Pop a confirmation screen each time you unlock during a focus session",
                    checked = settings.lockoutForFocus,
                    onCheckedChange = viewModel::setLockoutForFocus,
                )
            }
            item {
                SwitchCard(
                    icon = Icons.Default.Visibility,
                    title = "Show unlock count",
                    description = "Display how many times you've unlocked during the active session",
                    checked = settings.showPickupCountOnLockout,
                    onCheckedChange = viewModel::setShowPickupCountOnLockout,
                )
            }
            item {
                SwitchCard(
                    icon = Icons.Default.Stop,
                    title = "Allow ending session from lockout",
                    description = "Show an 'End session early' link on the lockout screen",
                    checked = settings.allowEndSessionFromLockout,
                    onCheckedChange = viewModel::setAllowEndSessionFromLockout,
                )
            }
            item {
                StepperCard(
                    icon = Icons.Default.Timer,
                    title = "Focus phone-break duration",
                    description = "Quiet window after tapping 'Yes, I need my phone' during a focus session",
                    value = settings.focusLockoutGraceMinutes,
                    suffix = "min",
                    step = 1,
                    range = 1..10,
                    onValueChange = viewModel::setFocusLockoutGraceMinutes,
                )
            }
        }
    }
}
