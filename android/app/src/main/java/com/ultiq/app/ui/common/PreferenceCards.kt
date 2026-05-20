package com.ultiq.app.ui.common

import android.app.TimePickerDialog
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * Reusable preference cards. Originally private to SettingsScreen, lifted
 * here when sleep/focus prefs moved to their respective tabs.
 */

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(start = 4.dp, top = 8.dp),
    )
}

@Composable
fun SectionHeaderWithSuffix(title: String, suffix: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
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
fun TimeSettingCard(
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

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun StepperCard(
    icon: ImageVector,
    title: String,
    description: String,
    value: Int,
    suffix: String,
    step: Int,
    range: IntRange,
    onValueChange: (Int) -> Unit,
    /** Common values shown as tap-to-select FilterChips above the stepper. */
    quickPicks: List<Int> = emptyList(),
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
            if (quickPicks.isNotEmpty()) {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    quickPicks.forEach { pick ->
                        FilterChip(
                            selected = value == pick,
                            onClick = { onValueChange(pick.coerceIn(range)) },
                            label = { Text("$pick") },
                        )
                    }
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

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun DurationStepperCard(
    icon: ImageVector,
    title: String,
    description: String,
    valueMinutes: Int,
    stepMinutes: Int,
    range: IntRange,
    onValueChange: (Int) -> Unit,
    /** Common durations (in minutes) shown as tap-to-select chips above the
     *  stepper. Chips render with [compactDurationLabel] so "480" → "8h",
     *  "450" → "7.5h", "510" → "8.5h", etc. */
    quickPicks: List<Int> = emptyList(),
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
            if (quickPicks.isNotEmpty()) {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    quickPicks.forEach { pick ->
                        FilterChip(
                            selected = valueMinutes == pick,
                            onClick = { onValueChange(pick.coerceIn(range)) },
                            label = { Text(compactDurationLabel(pick)) },
                        )
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                IconButton(
                    onClick = { onValueChange((valueMinutes - stepMinutes).coerceAtLeast(range.first)) },
                    enabled = valueMinutes > range.first,
                ) { Icon(Icons.Default.Remove, "Decrease") }
                Text(
                    formatDuration(valueMinutes),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier
                        .padding(horizontal = 16.dp)
                        .width(96.dp),
                    textAlign = TextAlign.Center,
                )
                IconButton(
                    onClick = { onValueChange((valueMinutes + stepMinutes).coerceAtMost(range.last)) },
                    enabled = valueMinutes < range.last,
                ) { Icon(Icons.Default.Add, "Increase") }
            }
        }
    }
}

@Composable
fun SwitchCard(
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

fun targetDurationMinutes(bedtime: LocalTime, wakeTime: LocalTime): Int {
    val bed = bedtime.hour * 60 + bedtime.minute
    val wake = wakeTime.hour * 60 + wakeTime.minute
    return if (wake >= bed) wake - bed else 24 * 60 - bed + wake
}

fun formatDuration(minutes: Int): String {
    val h = minutes / 60
    val m = minutes % 60
    return if (m == 0) "${h}h" else "${h}h ${m}m"
}

/** Compact form for chip labels: 480 → "8h", 450 → "7.5h", 510 → "8.5h". */
fun compactDurationLabel(minutes: Int): String {
    val h = minutes / 60
    val m = minutes % 60
    return when (m) {
        0 -> "${h}h"
        30 -> "${h}.5h"
        else -> "${h}h${m}m"
    }
}

/**
 * One-shot "configure these settings" nudge shown on Sleep / Focus / Dashboard
 * the first time the user lands on each tab after settings moved out of the
 * Settings screen. Tap × to dismiss; the parent should persist the seen-flag
 * so the card doesn't reappear on next launch.
 */
@Composable
fun ConfigureHintCard(
    title: String,
    body: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    androidx.compose.material3.Card(
        colors = androidx.compose.material3.CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Text(
                    body,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            androidx.compose.material3.IconButton(onClick = onDismiss) {
                androidx.compose.material3.Icon(
                    androidx.compose.material.icons.Icons.Default.Close,
                    contentDescription = "Dismiss hint",
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
    }
}
