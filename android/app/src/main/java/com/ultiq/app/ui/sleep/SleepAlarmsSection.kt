package com.ultiq.app.ui.sleep

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Calculate
import androidx.compose.material.icons.filled.Camera
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ultiq.app.BuildConfig
import com.ultiq.app.R
import com.ultiq.app.alarm.AlarmPermissionState
import com.ultiq.app.alarm.openAppNotificationSettings
import com.ultiq.app.alarm.openExactAlarmSettings
import com.ultiq.app.alarm.openFullScreenIntentSettings
import com.ultiq.app.alarm.rememberAlarmPermissionState
import com.ultiq.app.alarm.rememberBatteryOptimized
import com.ultiq.app.data.local.entity.AlarmEntity
import com.ultiq.app.util.LocaleManager
import com.ultiq.app.util.OemBatteryGuidance

/**
 * Embedded "Wake-up alarms" section for the Sleep tab. Adds the alarm list,
 * Add button, OEM guidance (when relevant), and debug shortcuts as items in
 * the surrounding [LazyListScope], keeping the parent LazyColumn flat and
 * pageable rather than nesting a second scrollable list.
 */
fun LazyListScope.sleepAlarmsSection(
    alarms: List<AlarmEntity>,
    onCreate: () -> Unit,
    onEdit: (String) -> Unit,
    onToggle: (AlarmEntity, Boolean) -> Unit,
    /// Fires after the user confirms in the swipe-to-delete dialog —
    /// no further confirmation needed at the parent (was double-dialoging
    /// when `onRequestDelete` opened a second dialog on top of the swipe
    /// one in v2.10.3).
    onDelete: (AlarmEntity) -> Unit,
    onTestAlarm: (String) -> Unit,
) {
    item(key = "alarms-header") {
        Text(
            text = stringResource(R.string.alarms_header).uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
        )
    }

    // Permissions banner — shows when any of POST_NOTIFICATIONS / SCHEDULE_EXACT_ALARM
    // / USE_FULL_SCREEN_INTENT is missing. The third one is what makes the
    // alarm only appear as a notification rather than over the lock screen.
    item(key = "alarms-permissions") {
        val state = rememberAlarmPermissionState()
        if (!state.allGranted) {
            AlarmPermissionsCard(
                state = state,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
    }

    if (alarms.isEmpty()) {
        item(key = "alarms-empty") {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        stringResource(R.string.alarms_empty),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Button(
                        onClick = onCreate,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null)
                        Text("  " + stringResource(R.string.sleep_add_alarm_cd))
                    }
                }
            }
        }
    } else {
        items(alarms, key = { "alarm-${it.id}" }) { alarm ->
            // §delete-consistency — swipe row to delete, mirrors Sleep
            // + Checklist. Tap still edits.
            com.ultiq.app.ui.common.SwipeToDeleteBox(
                confirmTitle = stringResource(R.string.alarm_delete_title),
                confirmBody = stringResource(R.string.alarm_delete_body),
                onDelete = { onDelete(alarm) },
                modifier = Modifier.padding(horizontal = 16.dp),
            ) {
                AlarmRow(
                    alarm = alarm,
                    onToggle = { onToggle(alarm, it) },
                    onClick = { onEdit(alarm.id) },
                )
            }
        }
        // v2.13.19 — Bottom "Add alarm" OutlinedButton removed; the floating
        // + on the Scaffold covers the action and stays in reach regardless
        // of scroll position.
    }

    // OEM battery-killer guidance — only for manufacturers known to over-kill
    // background apps (Xiaomi, Samsung, …), only while we're still subject to
    // battery optimisation, and only until the user dismisses it. Some OEMs
    // (e.g. HTC) don't report the exemption back through the framework flag,
    // so the ✕ is the reliable escape hatch there.
    item(key = "alarms-oem") {
        val context = LocalContext.current
        val oemUrl = OemBatteryGuidance.urlFor()
        var dismissed by remember { mutableStateOf(OemBatteryGuidance.isDismissed(context)) }
        if (oemUrl != null && rememberBatteryOptimized() && !dismissed) {
            OemBatteryGuidanceCard(
                manufacturer = OemBatteryGuidance.displayName(),
                url = oemUrl,
                onDismiss = {
                    OemBatteryGuidance.setDismissed(context)
                    dismissed = true
                },
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
    }

    // Hidden in release builds — useful for dev testing of the alarm pipeline
    // but not something an end user should ever see in production.
    if (BuildConfig.DEBUG) {
        item(key = "alarms-debug") {
            DebugTestAlarmCard(
                onTest = onTestAlarm,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
    }
}

@Composable
internal fun DeleteAlarmDialog(
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.alarm_delete_title)) },
        text = {
            Text(stringResource(R.string.alarm_delete_body))
        },
        confirmButton = { Button(onClick = onConfirm) { Text(stringResource(R.string.action_delete)) } },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun AlarmRow(
    alarm: AlarmEntity,
    onToggle: (Boolean) -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (alarm.enabled) MaterialTheme.colorScheme.surfaceVariant
            else MaterialTheme.colorScheme.surface,
        ),
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = java.time.LocalTime.of(alarm.triggerHour, alarm.triggerMinute)
                            .format(java.time.format.DateTimeFormatter.ofPattern("h:mm a")),
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.SemiBold,
                        color = if (alarm.enabled) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    val label = alarm.label?.takeIf { it.isNotBlank() } ?: stringResource(R.string.alarm_default_label)
                    Text(label, style = MaterialTheme.typography.bodyMedium)
                }
                Switch(checked = alarm.enabled, onCheckedChange = onToggle)
            }
            DayOfWeekChips(mask = alarm.daysOfWeekMask, dim = !alarm.enabled)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MissionBadge(kind = alarm.missionKind)
                if (alarm.vibration) {
                    BadgeChip(icon = Icons.Default.Vibration, label = stringResource(R.string.alarm_vibrate))
                }
            }
        }
    }
}

@Composable
private fun DayOfWeekChips(mask: Int, dim: Boolean) {
    // §13.1 — localized narrow day letters, Sun-first (bit 0 = Sun).
    val locale = LocaleManager.currentLocale()
    val labels = (0..6).map { bit ->
        val dow = if (bit == 0) java.time.DayOfWeek.SUNDAY else java.time.DayOfWeek.of(bit)
        dow.getDisplayName(java.time.format.TextStyle.NARROW, locale)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        labels.forEachIndexed { bit, letter ->
            val on = (mask shr bit) and 1 == 1
            Box(
                modifier = Modifier.size(28.dp),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .background(
                            color = when {
                                on && !dim -> MaterialTheme.colorScheme.primary
                                on && dim -> MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
                                else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                            },
                            shape = CircleShape,
                        ),
                )
                Text(
                    text = letter,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (on) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
        if (mask == 0) {
            Text(
                text = stringResource(R.string.alarm_one_shot),
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(start = 6.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun MissionBadge(kind: String) {
    val (icon, label) = when (kind) {
        "math" -> Icons.Default.Calculate to stringResource(R.string.mission_math)
        "shake" -> Icons.Default.Vibration to stringResource(R.string.mission_shake)
        "photo" -> Icons.Default.Camera to stringResource(R.string.mission_photo)
        else -> Icons.Default.Alarm to stringResource(R.string.mission_tap_dismiss)
    }
    BadgeChip(icon = icon, label = label)
}

@Composable
private fun BadgeChip(icon: ImageVector, label: String) {
    Row(
        modifier = Modifier
            .background(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = RoundedCornerShape(20.dp),
            )
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.size(16.dp),
        )
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}

@Composable
private fun OemBatteryGuidanceCard(
    manufacturer: String,
    url: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.oem_title, manufacturer),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(24.dp),
                ) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = stringResource(R.string.action_dismiss),
                        tint = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                }
            }
            Text(
                stringResource(R.string.oem_body, manufacturer),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            Button(
                onClick = {
                    val intent = android.content.Intent(
                        android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse(url),
                    ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.oem_open_guide)) }
        }
    }
}

@Composable
private fun AlarmPermissionsCard(
    state: AlarmPermissionState,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.alarm_perms_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Text(
                stringResource(R.string.alarm_perms_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            if (!state.hasNotifications) {
                Button(
                    onClick = { openAppNotificationSettings(context) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.alarm_allow_notifications)) }
            }
            if (!state.hasExactAlarm) {
                Button(
                    onClick = { openExactAlarmSettings(context) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.alarm_allow_exact)) }
            }
            if (!state.hasFullScreenIntent) {
                Button(
                    onClick = { openFullScreenIntentSettings(context) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.alarm_allow_fullscreen)) }
            }
        }
    }
}

@Composable
private fun DebugTestAlarmCard(
    onTest: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.alarm_debug_title),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { onTest("none") },
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(R.string.mission_tap)) }
                OutlinedButton(
                    onClick = { onTest("math") },
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(R.string.mission_math)) }
                OutlinedButton(
                    onClick = { onTest("shake") },
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(R.string.mission_shake)) }
            }
        }
    }
}
