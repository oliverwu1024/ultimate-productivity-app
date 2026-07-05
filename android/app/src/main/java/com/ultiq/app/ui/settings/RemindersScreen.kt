package com.ultiq.app.ui.settings

import android.app.TimePickerDialog
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ultiq.app.R
import com.ultiq.app.util.LocaleManager
import com.ultiq.app.util.ReminderSettings
import java.time.LocalTime
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemindersScreen(
    onBack: () -> Unit,
    viewModel: RemindersViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshPermissionState()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.reminders_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back))
                    }
                }
            )
        }
    ) { padding ->
        val settings = uiState.settings
        if (settings == null) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) { Text(stringResource(R.string.common_loading)) }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (!uiState.notificationsEnabled) {
                item {
                    PermissionCard(
                        title = stringResource(R.string.reminders_notifs_off_title),
                        body = stringResource(R.string.reminders_notifs_off_body),
                        actionLabel = stringResource(R.string.reminders_open_settings),
                        onAction = {
                            val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                                putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                            }
                            context.startActivity(intent)
                        }
                    )
                }
            }
            if (!uiState.canScheduleExact) {
                item {
                    PermissionCard(
                        title = stringResource(R.string.reminders_exact_off_title),
                        body = stringResource(R.string.reminders_exact_off_body),
                        actionLabel = stringResource(R.string.reminders_allow_exact),
                        onAction = {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                                context.startActivity(intent)
                            }
                        }
                    )
                }
            }

            item {
                BedtimeReminderRow(
                    enabled = settings.bedtimeEnabled,
                    targetBedtime = uiState.targetBedtime,
                    onToggle = { viewModel.setBedtimeEnabled(it) },
                )
            }

            item {
                ReminderRow(
                    icon = Icons.Default.Timer,
                    title = stringResource(R.string.reminders_focus_title),
                    description = stringResource(R.string.reminders_focus_desc),
                    enabled = settings.focusEnabled,
                    time = settings.focusTime,
                    onToggle = { viewModel.setFocusEnabled(it) },
                    onTimeChange = { viewModel.setFocusTime(it) }
                )
            }

            item {
                ReminderRow(
                    icon = Icons.Default.WbSunny,
                    title = stringResource(R.string.reminders_morning_title),
                    description = stringResource(R.string.reminders_morning_desc),
                    enabled = settings.morningSummaryEnabled,
                    time = settings.morningSummaryTime,
                    onToggle = { viewModel.setMorningSummaryEnabled(it) },
                    onTimeChange = { viewModel.setMorningSummaryTime(it) }
                )
            }

            item {
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.reminders_calendar_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/// §fix-bedtime-unified — toggle-only row for the bedtime reminder.
/// The actual time lives in Sleep settings (UserPreferences.targetBedtime)
/// and is only displayed here as static text so users can verify what
/// they'd be reminded about.
@Composable
private fun BedtimeReminderRow(
    enabled: Boolean,
    targetBedtime: LocalTime,
    onToggle: (Boolean) -> Unit,
) {
    val timeFormat = DateTimeFormatter.ofPattern("h:mm a", LocaleManager.currentLocale())
    val leadMinutes = ReminderSettings.BEDTIME_LEAD_MINUTES
    val triggerTime = targetBedtime.minusMinutes(leadMinutes.toLong()).format(timeFormat)
    val bedtimeText = targetBedtime.format(timeFormat)

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(Icons.Default.Bedtime, null, tint = MaterialTheme.colorScheme.primary)
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.reminders_bedtime), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                    Text(
                        stringResource(R.string.reminders_bedtime_desc, triggerTime, leadMinutes, bedtimeText),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = enabled, onCheckedChange = onToggle)
            }
            Text(
                stringResource(R.string.reminders_bedtime_time_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ReminderRow(
    icon: ImageVector,
    title: String,
    description: String,
    enabled: Boolean,
    time: LocalTime,
    onToggle: (Boolean) -> Unit,
    onTimeChange: (LocalTime) -> Unit,
) {
    val context = LocalContext.current
    val timeFormat = DateTimeFormatter.ofPattern("h:mm a", LocaleManager.currentLocale())

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
                Column(modifier = Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                    Text(
                        description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = enabled, onCheckedChange = onToggle)
            }
            TextButton(
                onClick = {
                    TimePickerDialog(
                        context,
                        { _, h, m -> onTimeChange(LocalTime.of(h, m)) },
                        time.hour,
                        time.minute,
                        false,
                    ).show()
                },
                enabled = enabled,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Notifications, null, modifier = Modifier.padding(end = 8.dp))
                Text(stringResource(R.string.reminders_time_label, time.format(timeFormat)))
            }
        }
    }
}

@Composable
private fun PermissionCard(
    title: String,
    body: String,
    actionLabel: String,
    onAction: () -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onErrorContainer)
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
            TextButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}
