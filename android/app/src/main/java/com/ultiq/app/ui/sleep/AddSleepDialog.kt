package com.ultiq.app.ui.sleep

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ultiq.app.R
import com.ultiq.app.data.remote.dto.CreateSleepRecordDto
import com.ultiq.app.util.LocaleManager
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddSleepDialog(
    initialTargetBedtime: LocalTime = LocalTime.of(22, 0),
    initialTargetWakeTime: LocalTime = LocalTime.of(6, 0),
    onDismiss: () -> Unit,
    onSave: (CreateSleepRecordDto) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val context = LocalContext.current

    var targetBedtime by remember { mutableStateOf(initialTargetBedtime) }
    var targetWakeTime by remember { mutableStateOf(initialTargetWakeTime) }
    var actualBedDate by remember { mutableStateOf(LocalDate.now().minusDays(1)) }
    var actualBedTime by remember { mutableStateOf(LocalTime.of(22, 30)) }
    var actualWakeDate by remember { mutableStateOf(LocalDate.now()) }
    var actualWakeTime by remember { mutableStateOf(LocalTime.of(6, 30)) }
    var qualityRating by remember { mutableIntStateOf(0) }
    var phonePickups by remember { mutableIntStateOf(0) }
    var totalPhoneMinutes by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    var validationError by remember { mutableStateOf<String?>(null) }
    // §last-night — null = follow the heuristic as the user edits times; once
    // they tap the toggle it sticks (final value = override ?: heuristic).
    var napOverride by remember { mutableStateOf<Boolean?>(null) }
    val napLikely = looksLikeNap(
        LocalDateTime.of(actualBedDate, actualBedTime).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
        LocalDateTime.of(actualWakeDate, actualWakeTime).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
    )
    val isNap = napOverride ?: napLikely

    val timeFormat = DateTimeFormatter.ofPattern("hh:mm a", LocaleManager.currentLocale())
    val dateFormat = DateTimeFormatter.ofPattern("MMM dd, yyyy", LocaleManager.currentLocale())

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(stringResource(R.string.dashboard_quick_sleep), style = MaterialTheme.typography.headlineSmall)

            // Target bedtime
            ClickableField(
                label = stringResource(R.string.add_sleep_target_bedtime),
                value = targetBedtime.format(timeFormat),
                onClick = {
                    TimePickerDialog(context, { _, h, m ->
                        targetBedtime = LocalTime.of(h, m)
                    }, targetBedtime.hour, targetBedtime.minute, false).show()
                }
            )

            // Target wake time
            ClickableField(
                label = stringResource(R.string.add_sleep_target_wake),
                value = targetWakeTime.format(timeFormat),
                onClick = {
                    TimePickerDialog(context, { _, h, m ->
                        targetWakeTime = LocalTime.of(h, m)
                    }, targetWakeTime.hour, targetWakeTime.minute, false).show()
                }
            )

            // Actual bedtime
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ClickableField(
                    label = stringResource(R.string.add_sleep_bed_date),
                    value = actualBedDate.format(dateFormat),
                    onClick = {
                        DatePickerDialog(context, { _, y, m, d ->
                            actualBedDate = LocalDate.of(y, m + 1, d)
                        }, actualBedDate.year, actualBedDate.monthValue - 1, actualBedDate.dayOfMonth).show()
                    },
                    modifier = Modifier.weight(1f)
                )
                ClickableField(
                    label = stringResource(R.string.add_sleep_bed_time),
                    value = actualBedTime.format(timeFormat),
                    onClick = {
                        TimePickerDialog(context, { _, h, m ->
                            actualBedTime = LocalTime.of(h, m)
                        }, actualBedTime.hour, actualBedTime.minute, false).show()
                    },
                    modifier = Modifier.weight(1f)
                )
            }

            // Actual wake time
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ClickableField(
                    label = stringResource(R.string.add_sleep_wake_date),
                    value = actualWakeDate.format(dateFormat),
                    onClick = {
                        DatePickerDialog(context, { _, y, m, d ->
                            actualWakeDate = LocalDate.of(y, m + 1, d)
                        }, actualWakeDate.year, actualWakeDate.monthValue - 1, actualWakeDate.dayOfMonth).show()
                    },
                    modifier = Modifier.weight(1f)
                )
                ClickableField(
                    label = stringResource(R.string.add_sleep_wake_time),
                    value = actualWakeTime.format(timeFormat),
                    onClick = {
                        TimePickerDialog(context, { _, h, m ->
                            actualWakeTime = LocalTime.of(h, m)
                        }, actualWakeTime.hour, actualWakeTime.minute, false).show()
                    },
                    modifier = Modifier.weight(1f)
                )
            }

            // Quality rating
            Text(stringResource(R.string.add_sleep_quality), style = MaterialTheme.typography.labelLarge)
            Row {
                (1..5).forEach { star ->
                    IconButton(onClick = { qualityRating = star }) {
                        Icon(
                            imageVector = if (star <= qualityRating) Icons.Filled.Star else Icons.Outlined.StarBorder,
                            contentDescription = stringResource(R.string.star_rating_cd, star),
                            tint = if (star <= qualityRating) com.ultiq.app.ui.theme.QualityStar else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                }
            }

            // §last-night — surfaced for any manual entry; defaults to the
            // heuristic (daytime + short) but the user can flip it.
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.nap_question), style = MaterialTheme.typography.labelLarge)
                    Text(
                        stringResource(R.string.nap_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = isNap, onCheckedChange = { napOverride = it })
            }

            // Phone pickups stepper
            Text(stringResource(R.string.add_sleep_phone_pickups), style = MaterialTheme.typography.labelLarge)
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { if (phonePickups > 0) phonePickups-- }) {
                    Icon(Icons.Default.Remove, stringResource(R.string.action_decrease))
                }
                Text(
                    "$phonePickups",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.width(40.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
                IconButton(onClick = { phonePickups++ }) {
                    Icon(Icons.Default.Add, stringResource(R.string.action_increase))
                }
            }

            // Total phone minutes
            OutlinedTextField(
                value = totalPhoneMinutes,
                onValueChange = { totalPhoneMinutes = it.filter { c -> c.isDigit() } },
                label = { Text(stringResource(R.string.add_sleep_phone_minutes)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            // Notes
            OutlinedTextField(
                value = notes,
                onValueChange = { notes = it },
                label = { Text(stringResource(R.string.notes_optional)) },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
                maxLines = 4
            )

            // Validation error
            if (validationError != null) {
                Text(
                    validationError!!,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }

            // Buttons
            val errBedtime = stringResource(R.string.add_sleep_err_bedtime)
            val errQuality = stringResource(R.string.add_sleep_err_quality)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)
            ) {
                OutlinedButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
                Button(onClick = {
                    val actualBed = LocalDateTime.of(actualBedDate, actualBedTime)
                    val actualWake = LocalDateTime.of(actualWakeDate, actualWakeTime)

                    when {
                        actualBed >= actualWake -> {
                            validationError = errBedtime
                        }
                        qualityRating < 1 -> {
                            validationError = errQuality
                        }
                        else -> {
                            val zone = ZoneId.systemDefault()
                            val dto = CreateSleepRecordDto(
                                // §v2.16.15 — Client-side UUID for the
                                // backend's idempotent upsert path.
                                id = java.util.UUID.randomUUID().toString(),
                                target_bedtime = String.format("%02d:%02d:00", targetBedtime.hour, targetBedtime.minute),
                                target_wake_time = String.format("%02d:%02d:00", targetWakeTime.hour, targetWakeTime.minute),
                                actual_bedtime = actualBed.atZone(zone).toInstant().toString(),
                                actual_wake_time = actualWake.atZone(zone).toInstant().toString(),
                                quality_rating = qualityRating,
                                phone_pickups = phonePickups,
                                total_phone_minutes = totalPhoneMinutes.toIntOrNull(),
                                notes = notes.ifBlank { null },
                                is_nap = isNap
                            )
                            onSave(dto)
                        }
                    }
                }) { Text(stringResource(R.string.action_save)) }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun ClickableField(
    label: String,
    value: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedTextField(
        value = value,
        onValueChange = {},
        label = { Text(label) },
        readOnly = true,
        modifier = modifier,
        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
            .also { source ->
                val isPressed = source.collectIsPressedAsState()
                if (isPressed.value) onClick()
            }
    )
}

@Composable
private fun androidx.compose.foundation.interaction.InteractionSource.collectIsPressedAsState(): androidx.compose.runtime.State<Boolean> {
    val isPressed = remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(this) {
        interactions.collect { interaction ->
            when (interaction) {
                is androidx.compose.foundation.interaction.PressInteraction.Press -> isPressed.value = true
                is androidx.compose.foundation.interaction.PressInteraction.Release -> isPressed.value = false
                is androidx.compose.foundation.interaction.PressInteraction.Cancel -> isPressed.value = false
            }
        }
    }
    return isPressed
}
