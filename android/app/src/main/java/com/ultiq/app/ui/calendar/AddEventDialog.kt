package com.ultiq.app.ui.calendar

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.ultiq.app.data.local.entity.CalendarEventEntity
import com.ultiq.app.data.remote.dto.CreateCalendarEventDto
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val categories = listOf("study", "project", "exercise", "personal", "other")
private val priorities = listOf("high", "medium", "low")
private val colorOptions = listOf(
    "#4A90D9", "#E67E22", "#2ECC71", "#9B59B6", "#95A5A6",
    "#E74C3C", "#F1C40F", "#1ABC9C"
)
private val weekdays = listOf("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN")

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AddEventDialog(
    initialDate: LocalDate,
    editingEvent: CalendarEventEntity?,
    onDismiss: () -> Unit,
    onSave: (CreateCalendarEventDto) -> Unit,
    onDelete: (() -> Unit)?
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val context = LocalContext.current
    val zone = ZoneId.systemDefault()

    // Init state from editing event or defaults
    val initStart = editingEvent?.let { Instant.ofEpochMilli(it.startTime).atZone(zone).toLocalDateTime() }
    val initEnd = editingEvent?.let { Instant.ofEpochMilli(it.endTime).atZone(zone).toLocalDateTime() }

    // Default new events to "now → now + 1h" on the chosen day. If now+1h
    // rolls past midnight, the end date follows so the form shows the
    // correct next-day end (user can still adjust).
    val defaultNow = java.time.LocalDateTime.of(initialDate, LocalTime.now().withSecond(0).withNano(0))
    val defaultEnd = defaultNow.plusHours(1)

    var title by remember { mutableStateOf(editingEvent?.title ?: "") }
    var description by remember { mutableStateOf(editingEvent?.description ?: "") }
    var startDate by remember { mutableStateOf(initStart?.toLocalDate() ?: defaultNow.toLocalDate()) }
    var startTime by remember { mutableStateOf(initStart?.toLocalTime() ?: defaultNow.toLocalTime()) }
    var endDate by remember { mutableStateOf(initEnd?.toLocalDate() ?: defaultEnd.toLocalDate()) }
    var endTime by remember { mutableStateOf(initEnd?.toLocalTime() ?: defaultEnd.toLocalTime()) }
    var category by remember { mutableStateOf(editingEvent?.category ?: "study") }
    var priority by remember { mutableStateOf(editingEvent?.priority ?: "medium") }
    var selectedColor by remember { mutableStateOf(editingEvent?.color ?: "#4A90D9") }
    var isRecurring by remember { mutableStateOf(editingEvent?.isRecurring ?: false) }
    var frequency by remember { mutableStateOf(parseFrequency(editingEvent?.recurrenceRule)) }
    var weeklyDays by remember { mutableStateOf(parseWeeklyDays(editingEvent?.recurrenceRule)) }
    var monthlyDay by remember { mutableStateOf(parseMonthlyDay(editingEvent?.recurrenceRule)) }
    var validationError by remember { mutableStateOf<String?>(null) }

    val timeFormat = DateTimeFormatter.ofPattern("hh:mm a")
    val dateFormat = DateTimeFormatter.ofPattern("MMM dd, yyyy")

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                if (editingEvent != null) "Edit Event" else "New Event",
                style = MaterialTheme.typography.headlineSmall
            )

            // Title
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("Title") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            // Description
            OutlinedTextField(
                value = description,
                onValueChange = { description = it },
                label = { Text("Description (optional)") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
                maxLines = 4
            )

            // Start date + time
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ClickableField(
                    label = "Start Date",
                    value = startDate.format(dateFormat),
                    onClick = {
                        DatePickerDialog(context, { _, y, m, d ->
                            startDate = LocalDate.of(y, m + 1, d)
                        }, startDate.year, startDate.monthValue - 1, startDate.dayOfMonth).show()
                    },
                    modifier = Modifier.weight(1f)
                )
                ClickableField(
                    label = "Start Time",
                    value = startTime.format(timeFormat),
                    onClick = {
                        TimePickerDialog(context, { _, h, m ->
                            startTime = LocalTime.of(h, m)
                        }, startTime.hour, startTime.minute, false).show()
                    },
                    modifier = Modifier.weight(1f)
                )
            }

            // End date + time
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ClickableField(
                    label = "End Date",
                    value = endDate.format(dateFormat),
                    onClick = {
                        DatePickerDialog(context, { _, y, m, d ->
                            endDate = LocalDate.of(y, m + 1, d)
                        }, endDate.year, endDate.monthValue - 1, endDate.dayOfMonth).show()
                    },
                    modifier = Modifier.weight(1f)
                )
                ClickableField(
                    label = "End Time",
                    value = endTime.format(timeFormat),
                    onClick = {
                        TimePickerDialog(context, { _, h, m ->
                            endTime = LocalTime.of(h, m)
                        }, endTime.hour, endTime.minute, false).show()
                    },
                    modifier = Modifier.weight(1f)
                )
            }

            // Category
            Text("Category", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                categories.forEach { cat ->
                    FilterChip(
                        selected = category == cat,
                        onClick = { category = cat },
                        label = { Text(cat.replaceFirstChar { it.uppercase() }) }
                    )
                }
            }

            // Priority
            Text("Priority", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                priorities.forEach { p ->
                    FilterChip(
                        selected = priority == p,
                        onClick = { priority = p },
                        label = { Text(p.replaceFirstChar { it.uppercase() }) }
                    )
                }
            }

            // Color
            Text("Color", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                colorOptions.forEach { hex ->
                    val color = parseHexColor(hex)
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(color)
                            .then(
                                if (selectedColor == hex)
                                    Modifier.border(2.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                                else Modifier
                            )
                            .clickable { selectedColor = hex },
                        contentAlignment = Alignment.Center
                    ) {
                        if (selectedColor == hex) {
                            Icon(
                                Icons.Default.Check, null,
                                modifier = Modifier.size(16.dp),
                                tint = Color.White
                            )
                        }
                    }
                }
            }

            // Recurring toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Recurring", style = MaterialTheme.typography.labelLarge)
                Switch(checked = isRecurring, onCheckedChange = { isRecurring = it })
            }

            // Recurrence rule config
            if (isRecurring) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("DAILY", "WEEKLY", "MONTHLY").forEach { f ->
                        FilterChip(
                            selected = frequency == f,
                            onClick = { frequency = f },
                            label = { Text(f.lowercase().replaceFirstChar { it.uppercase() }) }
                        )
                    }
                }

                if (frequency == "WEEKLY") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        weekdays.forEach { day ->
                            FilterChip(
                                selected = day in weeklyDays,
                                onClick = {
                                    weeklyDays = if (day in weeklyDays) weeklyDays - day else weeklyDays + day
                                },
                                label = { Text(day) }
                            )
                        }
                    }
                }

                if (frequency == "MONTHLY") {
                    OutlinedTextField(
                        value = monthlyDay,
                        onValueChange = { monthlyDay = it.filter { c -> c.isDigit() }.take(2) },
                        label = { Text("Day of month (1-31)") },
                        singleLine = true,
                        modifier = Modifier.width(160.dp)
                    )
                }
            }

            // Validation error
            if (validationError != null) {
                Text(
                    validationError!!,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }

            // Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (onDelete != null) {
                    OutlinedButton(
                        onClick = onDelete,
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Icon(Icons.Default.Delete, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Delete")
                    }
                    Spacer(Modifier.weight(1f))
                }
                OutlinedButton(onClick = onDismiss) { Text("Cancel") }
                Button(onClick = {
                    val startDt = LocalDateTime.of(startDate, startTime)
                    val endDt = LocalDateTime.of(endDate, endTime)

                    when {
                        title.isBlank() -> validationError = "Title is required"
                        !startDt.isBefore(endDt) -> validationError = "Start must be before end"
                        isRecurring && frequency == "WEEKLY" && weeklyDays.isEmpty() ->
                            validationError = "Select at least one day"
                        isRecurring && frequency == "MONTHLY" && (monthlyDay.toIntOrNull() ?: 0) !in 1..31 ->
                            validationError = "Enter a valid day (1-31)"
                        else -> {
                            val rule = if (isRecurring) {
                                when (frequency) {
                                    "DAILY" -> "DAILY"
                                    "WEEKLY" -> "WEEKLY:${weeklyDays.joinToString(",")}"
                                    "MONTHLY" -> "MONTHLY:$monthlyDay"
                                    else -> null
                                }
                            } else null

                            onSave(
                                CreateCalendarEventDto(
                                    title = title.trim(),
                                    description = description.ifBlank { null },
                                    start_time = startDt.atZone(zone).toInstant().toString(),
                                    end_time = endDt.atZone(zone).toInstant().toString(),
                                    category = category,
                                    priority = priority,
                                    is_recurring = isRecurring,
                                    recurrence_rule = rule,
                                    color = selectedColor
                                )
                            )
                        }
                    }
                }) { Text("Save") }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

// ── Helpers ─────────────────────────────────────────────────────────────

@Composable
private fun ClickableField(
    label: String,
    value: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed = interactionSource.collectIsPressedAsState()
    if (isPressed.value) onClick()

    OutlinedTextField(
        value = value,
        onValueChange = {},
        label = { Text(label) },
        readOnly = true,
        modifier = modifier,
        interactionSource = interactionSource
    )
}

@Composable
private fun MutableInteractionSource.collectIsPressedAsState(): State<Boolean> {
    val isPressed = remember { mutableStateOf(false) }
    LaunchedEffect(this) {
        interactions.collect { interaction ->
            when (interaction) {
                is PressInteraction.Press -> isPressed.value = true
                is PressInteraction.Release -> isPressed.value = false
                is PressInteraction.Cancel -> isPressed.value = false
            }
        }
    }
    return isPressed
}

private fun parseFrequency(rule: String?): String = when {
    rule == null -> "DAILY"
    rule == "DAILY" -> "DAILY"
    rule.startsWith("WEEKLY:") -> "WEEKLY"
    rule.startsWith("MONTHLY:") -> "MONTHLY"
    else -> "DAILY"
}

private fun parseWeeklyDays(rule: String?): Set<String> {
    if (rule == null || !rule.startsWith("WEEKLY:")) return emptySet()
    return rule.removePrefix("WEEKLY:").split(",").map { it.trim() }.toSet()
}

private fun parseMonthlyDay(rule: String?): String {
    if (rule == null || !rule.startsWith("MONTHLY:")) return "1"
    return rule.removePrefix("MONTHLY:").trim()
}
