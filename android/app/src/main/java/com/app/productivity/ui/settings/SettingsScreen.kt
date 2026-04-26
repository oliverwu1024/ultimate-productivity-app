package com.app.productivity.ui.settings

import android.app.TimePickerDialog
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.SettingsBrightness
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.app.productivity.ui.theme.ThemeMode
import java.time.LocalTime
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onNavigateToReminders: () -> Unit,
    onNavigateToReports: () -> Unit,
    onLogout: () -> Unit,
    viewModel: SettingsViewModel = viewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                }
            )
        }
    ) { padding ->
        val user = uiState.user
        if (user == null) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) { Text("Loading...") }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { SectionHeader("Appearance") }
            item { ThemeCard(current = uiState.themeMode, onSelect = viewModel::setThemeMode) }

            item {
                val durationMins = targetDurationMinutes(user.targetBedtime, user.targetWakeTime)
                SectionHeaderWithSuffix(
                    title = "Sleep targets",
                    suffix = "Duration: ${formatDuration(durationMins)}",
                )
            }
            item {
                TimeSettingCard(
                    icon = Icons.Default.Bedtime,
                    title = "Target bedtime",
                    description = "Used to compute sleep debt and schedule the bedtime reminder",
                    time = user.targetBedtime,
                    onTimeChange = viewModel::setTargetBedtime,
                )
            }
            item {
                TimeSettingCard(
                    icon = Icons.Default.WbSunny,
                    title = "Target wake time",
                    description = "When you'd ideally wake up",
                    time = user.targetWakeTime,
                    onTimeChange = viewModel::setTargetWakeTime,
                )
            }

            item { SectionHeader("Focus defaults") }
            item {
                StepperCard(
                    icon = Icons.Default.Timer,
                    title = "Work duration",
                    description = "Length of one focus block",
                    value = user.defaultWorkDuration,
                    suffix = "min",
                    step = 5,
                    range = 5..120,
                    onValueChange = viewModel::setDefaultWorkDuration,
                )
            }
            item {
                StepperCard(
                    icon = Icons.Default.Timer,
                    title = "Break duration",
                    description = "Length of breaks between work blocks",
                    value = user.defaultBreakDuration,
                    suffix = "min",
                    step = 1,
                    range = 1..60,
                    onValueChange = viewModel::setDefaultBreakDuration,
                )
            }

            item { SectionHeader("Focus mode") }
            item {
                SwitchCard(
                    icon = Icons.Default.Lock,
                    title = "Lockout during focus sessions",
                    description = "Pop a confirmation screen each time you unlock during a focus session",
                    checked = user.lockoutForFocus,
                    onCheckedChange = viewModel::setLockoutForFocus,
                )
            }
            item {
                SwitchCard(
                    icon = Icons.Default.Bedtime,
                    title = "Lockout during sleep",
                    description = "Off by default — sleep is for sleeping, not friction",
                    checked = user.lockoutForSleep,
                    onCheckedChange = viewModel::setLockoutForSleep,
                )
            }
            item {
                SwitchCard(
                    icon = Icons.Default.Visibility,
                    title = "Show unlock count",
                    description = "Display how many times you've unlocked during the active session",
                    checked = user.showPickupCountOnLockout,
                    onCheckedChange = viewModel::setShowPickupCountOnLockout,
                )
            }
            item {
                SwitchCard(
                    icon = Icons.Default.Stop,
                    title = "Allow ending session from lockout",
                    description = "Show an 'End session early' link on the lockout screen",
                    checked = user.allowEndSessionFromLockout,
                    onCheckedChange = viewModel::setAllowEndSessionFromLockout,
                )
            }

            item { SectionHeader("Notifications") }
            item {
                LinkRow(
                    icon = Icons.Default.Notifications,
                    title = "Reminders",
                    description = "Bedtime, focus, morning summary",
                    onClick = onNavigateToReminders,
                )
            }

            item { SectionHeader("Insights") }
            item {
                LinkRow(
                    icon = Icons.Default.Insights,
                    title = "Weekly report",
                    description = "Sleep, focus, and achievements",
                    onClick = onNavigateToReports,
                )
            }

            item { SectionHeader("Account") }
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Icon(Icons.Default.Email, null, tint = MaterialTheme.colorScheme.primary)
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    uiState.email ?: "Signed in",
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Medium,
                                )
                                Text(
                                    "Signed in",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        OutlinedButton(onClick = onLogout) {
                            Icon(Icons.AutoMirrored.Filled.Logout, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Log out")
                        }
                    }
                }
            }

            item { SectionHeader("About") }
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(Icons.Default.Info, null, tint = MaterialTheme.colorScheme.primary)
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "Productivity",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Medium,
                            )
                            Text(
                                "Version ${uiState.versionName} (${uiState.versionCode})",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            item { Spacer(Modifier.height(8.dp)) }
        }
    }
}

private fun targetDurationMinutes(bedtime: LocalTime, wakeTime: LocalTime): Int {
    val bed = bedtime.hour * 60 + bedtime.minute
    val wake = wakeTime.hour * 60 + wakeTime.minute
    return if (wake >= bed) wake - bed else 24 * 60 - bed + wake
}

private fun formatDuration(minutes: Int): String {
    val h = minutes / 60
    val m = minutes % 60
    return if (m == 0) "${h}h" else "${h}h ${m}m"
}

@Composable
private fun SectionHeaderWithSuffix(title: String, suffix: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, end = 4.dp, top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            title.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            suffix,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp, top = 8.dp),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ThemeCard(current: ThemeMode, onSelect: (ThemeMode) -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Theme", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
            val options = listOf(
                ThemeMode.LIGHT to ("Light" to Icons.Default.LightMode),
                ThemeMode.SYSTEM to ("System" to Icons.Default.SettingsBrightness),
                ThemeMode.DARK to ("Dark" to Icons.Default.DarkMode),
            )
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                options.forEachIndexed { index, (mode, labelAndIcon) ->
                    SegmentedButton(
                        selected = current == mode,
                        onClick = { onSelect(mode) },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                        icon = { Icon(labelAndIcon.second, null, modifier = Modifier.size(18.dp)) },
                        label = { Text(labelAndIcon.first) },
                    )
                }
            }
        }
    }
}

@Composable
private fun TimeSettingCard(
    icon: ImageVector,
    title: String,
    description: String,
    time: LocalTime,
    onTimeChange: (LocalTime) -> Unit,
) {
    val context = LocalContext.current
    val fmt = DateTimeFormatter.ofPattern("h:mm a")

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
                Column(modifier = Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                    Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            TextButton(
                onClick = {
                    TimePickerDialog(
                        context,
                        { _, h, m -> onTimeChange(LocalTime.of(h, m)) },
                        time.hour, time.minute, false,
                    ).show()
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(time.format(fmt))
            }
        }
    }
}

@Composable
private fun StepperCard(
    icon: ImageVector,
    title: String,
    description: String,
    value: Int,
    suffix: String,
    step: Int,
    range: IntRange,
    onValueChange: (Int) -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
                Column(modifier = Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                    Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                IconButton(
                    onClick = { onValueChange((value - step).coerceAtLeast(range.first)) },
                    enabled = value > range.first,
                ) { Icon(Icons.Default.Remove, "Decrease") }
                Text(
                    "$value $suffix",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier
                        .padding(horizontal = 16.dp)
                        .width(96.dp),
                    textAlign = TextAlign.Center,
                )
                IconButton(
                    onClick = { onValueChange((value + step).coerceAtMost(range.last)) },
                    enabled = value < range.last,
                ) { Icon(Icons.Default.Add, "Increase") }
            }
        }
    }
}

@Composable
private fun SwitchCard(
    icon: ImageVector,
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
    }
}

@Composable
private fun LinkRow(
    icon: ImageVector,
    title: String,
    description: String,
    onClick: () -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onClick) {
                Icon(Icons.Default.ChevronRight, "Open")
            }
        }
        HorizontalDivider(thickness = 0.dp)
    }
}
