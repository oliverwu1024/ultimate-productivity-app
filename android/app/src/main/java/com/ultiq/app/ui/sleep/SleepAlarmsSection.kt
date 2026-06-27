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
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ultiq.app.BuildConfig
import com.ultiq.app.alarm.AlarmPermissionState
import com.ultiq.app.alarm.openAppNotificationSettings
import com.ultiq.app.alarm.openExactAlarmSettings
import com.ultiq.app.alarm.openFullScreenIntentSettings
import com.ultiq.app.alarm.rememberAlarmPermissionState
import com.ultiq.app.data.local.entity.AlarmEntity
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
            text = "WAKE-UP ALARMS",
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
                        "No alarms yet. Set a wake-up time and a dismiss mission " +
                            "so you actually get out of bed.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Button(
                        onClick = onCreate,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null)
                        Text("  Add alarm")
                    }
                }
            }
        }
    } else {
        items(alarms, key = { "alarm-${it.id}" }) { alarm ->
            // §delete-consistency — swipe row to delete, mirrors Sleep
            // + Checklist. Tap still edits.
            com.ultiq.app.ui.common.SwipeToDeleteBox(
                confirmTitle = "Delete alarm?",
                confirmBody = "This alarm will be removed from this device and your account. This can't be undone.",
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

    val oemUrl = OemBatteryGuidance.urlFor()
    if (oemUrl != null) {
        item(key = "alarms-oem") {
            OemBatteryGuidanceCard(
                manufacturer = OemBatteryGuidance.displayName(),
                url = oemUrl,
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
        title = { Text("Delete alarm?") },
        text = {
            Text("This alarm will be removed from this device and your account. This can't be undone.")
        },
        confirmButton = { Button(onClick = onConfirm) { Text("Delete") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
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
                    val label = alarm.label?.takeIf { it.isNotBlank() } ?: "Alarm"
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
                    BadgeChip(icon = Icons.Default.Vibration, label = "Vibrate")
                }
            }
        }
    }
}

@Composable
private fun DayOfWeekChips(mask: Int, dim: Boolean) {
    val labels = listOf("S", "M", "T", "W", "T", "F", "S") // bit 0 = Sun
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
                text = "One-shot",
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
        "math" -> Icons.Default.Calculate to "Math"
        "shake" -> Icons.Default.Vibration to "Shake"
        "photo" -> Icons.Default.Camera to "Photo"
        else -> Icons.Default.Alarm to "Tap to dismiss"
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
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "Make alarms reliable on this $manufacturer",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            Text(
                "$manufacturer phones aggressively kill background apps, which can " +
                    "stop alarms from firing. Follow the dontkillmyapp.com guide to " +
                    "exclude Ultiq from battery optimisation.",
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
            ) { Text("Open setup guide") }
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
                "Alarms need a few permissions",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Text(
                "Without these, alarms either won't fire on schedule or won't " +
                    "show on screen when they do.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            if (!state.hasNotifications) {
                Button(
                    onClick = { openAppNotificationSettings(context) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Allow notifications") }
            }
            if (!state.hasExactAlarm) {
                Button(
                    onClick = { openExactAlarmSettings(context) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Allow exact alarms") }
            }
            if (!state.hasFullScreenIntent) {
                Button(
                    onClick = { openFullScreenIntentSettings(context) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Allow full-screen alarms (over lock)") }
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
                "Debug — schedule a one-shot 1–2 min from now",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { onTest("none") },
                    modifier = Modifier.weight(1f),
                ) { Text("Tap") }
                OutlinedButton(
                    onClick = { onTest("math") },
                    modifier = Modifier.weight(1f),
                ) { Text("Math") }
                OutlinedButton(
                    onClick = { onTest("shake") },
                    modifier = Modifier.weight(1f),
                ) { Text("Shake") }
            }
        }
    }
}
