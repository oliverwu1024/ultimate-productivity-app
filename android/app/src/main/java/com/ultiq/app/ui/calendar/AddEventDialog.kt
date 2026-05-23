package com.ultiq.app.ui.calendar

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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTimePickerState
import java.time.ZoneOffset
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
// v2.13.1 — Reminder offset palette (minutes-before-start). Multi-select:
// the picker lets the user attach any combination of these to an event
// ("1 day before AND 1 hour before AND 5 min before"). Keep the values
// aligned with AlarmScheduler.KNOWN_REMINDER_OFFSETS — that constant is
// what cancellation walks to clear every PendingIntent for an event.
private val reminderOffsetOptions: List<Pair<Int, String>> = listOf(
    5 to "5 min",
    15 to "15 min",
    30 to "30 min",
    60 to "1 hr",
    120 to "2 hr",
    240 to "4 hr",
    1440 to "1 day",
    2880 to "2 days",
    10080 to "1 week",
)
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
    onDelete: (() -> Unit)?,
    /// §9.5 — When non-null AND editingEvent is null, the form opens with
    /// these values instead of blank defaults. Used by the AI quick-add flow
    /// to pre-fill a freshly-parsed event for the user to confirm.
    prefilledNewEvent: CreateCalendarEventDto? = null,
    /// v2.12.2 — Existing events in the visible month, used for the inline
    /// conflict-warning notice below the time pickers. Empty list disables
    /// the check; the editingEvent itself is excluded inside the dialog
    /// so editing your own event isn't flagged as conflicting with itself.
    existingEvents: List<CalendarEventEntity> = emptyList(),
) {
    // v2.12.1 — Block swipe-down dismiss so a stray finger drag inside the
    // form (common when scrolling the chips / time fields) doesn't kill the
    // user's in-progress entry. The Hidden value transition is rejected;
    // dismissal goes through onDismissRequest below, which routes to the
    // discard-confirm dialog when the form has any content.
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { newValue -> newValue != androidx.compose.material3.SheetValue.Hidden },
    )
    val zone = ZoneId.systemDefault()
    var showDiscardConfirm by remember { mutableStateOf(false) }

    // Initial start/end resolves in priority order:
    //  1. editingEvent (user opened the dialog from an existing row)
    //  2. prefilledNewEvent (user came in via the AI quick-add flow)
    //  3. blank defaults (now → now+1h on the chosen day)
    val initStart: java.time.LocalDateTime? = when {
        editingEvent != null ->
            Instant.ofEpochMilli(editingEvent.startTime).atZone(zone).toLocalDateTime()
        prefilledNewEvent != null ->
            runCatching {
                Instant.parse(prefilledNewEvent.start_time).atZone(zone).toLocalDateTime()
            }.getOrNull()
        else -> null
    }
    val initEnd: java.time.LocalDateTime? = when {
        editingEvent != null ->
            Instant.ofEpochMilli(editingEvent.endTime).atZone(zone).toLocalDateTime()
        prefilledNewEvent != null ->
            runCatching {
                Instant.parse(prefilledNewEvent.end_time).atZone(zone).toLocalDateTime()
            }.getOrNull()
        else -> null
    }

    val defaultNow = java.time.LocalDateTime.of(initialDate, LocalTime.now().withSecond(0).withNano(0))
    val defaultEnd = defaultNow.plusHours(1)

    var title by remember {
        mutableStateOf(editingEvent?.title ?: prefilledNewEvent?.title ?: "")
    }
    var description by remember {
        mutableStateOf(editingEvent?.description ?: prefilledNewEvent?.description ?: "")
    }
    var startDate by remember { mutableStateOf(initStart?.toLocalDate() ?: defaultNow.toLocalDate()) }
    var startTime by remember { mutableStateOf(initStart?.toLocalTime() ?: defaultNow.toLocalTime()) }
    var endDate by remember { mutableStateOf(initEnd?.toLocalDate() ?: defaultEnd.toLocalDate()) }
    var endTime by remember { mutableStateOf(initEnd?.toLocalTime() ?: defaultEnd.toLocalTime()) }
    // v2.12.0 — All-day events. Schema has no is_all_day column so we
    // detect / store the flag purely by timestamp pattern: start = 00:00:00
    // and end = 23:59:* (any second value, since some clients send 23:59:00
    // and others 23:59:59). When toggled on, time pickers hide + save
    // forces midnight-to-end-of-day; when toggled off, restore to the
    // last explicit time the user picked (or default to now / now+1h).
    var isAllDay by remember {
        mutableStateOf(
            initStart != null && initEnd != null &&
                initStart.toLocalTime() == LocalTime.MIDNIGHT &&
                initEnd.toLocalTime().hour == 23 && initEnd.toLocalTime().minute == 59
        )
    }
    var savedStartTime by remember { mutableStateOf(startTime) }
    var savedEndTime by remember { mutableStateOf(endTime) }

    // v2.11.9 — Google-Calendar-style delta-shift: when the user changes
    // start date or time, end shifts by the same delta so the original
    // duration is preserved. Before this, picking a start of next week
    // left end at today (which then failed validation), and picking a
    // later same-day start time would silently invert the range.
    fun shiftStartTo(newStartDate: LocalDate, newStartTime: LocalTime) {
        val oldStart = LocalDateTime.of(startDate, startTime)
        val oldEnd = LocalDateTime.of(endDate, endTime)
        val delta = java.time.Duration.between(oldStart, oldEnd)
            .let { if (it.isNegative || it.isZero) java.time.Duration.ofHours(1) else it }
        val newEnd = LocalDateTime.of(newStartDate, newStartTime).plus(delta)
        startDate = newStartDate
        startTime = newStartTime
        endDate = newEnd.toLocalDate()
        endTime = newEnd.toLocalTime()
    }
    // §9.5 — Coerce prefill values against the known chip lists. Backend
    // validates the AI output too, but if the model ever drifts off-schema
    // (or a future client sends a bad prefill) we'd silently render a chip
    // group with nothing selected. Snap to the chip set or fall back.
    var category by remember {
        mutableStateOf(
            editingEvent?.category
                ?: prefilledNewEvent?.category?.takeIf { it in categories }
                ?: "study"
        )
    }
    var priority by remember {
        mutableStateOf(
            editingEvent?.priority
                ?: prefilledNewEvent?.priority?.takeIf { it in priorities }
                ?: "medium"
        )
    }
    var selectedColor by remember {
        mutableStateOf(editingEvent?.color ?: prefilledNewEvent?.color ?: "#4A90D9")
    }
    var isRecurring by remember { mutableStateOf(editingEvent?.isRecurring ?: false) }
    // v2.13.1 — Per-event reminder offsets, list-valued. Null = "use client
    // default" (single 15-min reminder); empty list = explicit opt-out;
    // non-empty = the user's exact picks. Multi-select FilterChip group
    // below tracks this through Default / None / per-offset chips.
    var reminderMinutes by remember {
        mutableStateOf<List<Int>?>(editingEvent?.reminderMinutes)
    }
    var frequency by remember { mutableStateOf(parseFrequency(editingEvent?.recurrenceRule)) }
    var weeklyDays by remember { mutableStateOf(parseWeeklyDays(editingEvent?.recurrenceRule)) }
    var monthlyDay by remember { mutableStateOf(parseMonthlyDay(editingEvent?.recurrenceRule)) }
    var validationError by remember { mutableStateOf<String?>(null) }

    // v2.12.2 — Picker visibility flags drive the four M3 picker dialogs
    // (replaces the legacy android.app.DatePickerDialog / TimePickerDialog
    // that didn't match the rest of the M3 UI and broke in dark mode).
    var showStartDatePicker by remember { mutableStateOf(false) }
    var showEndDatePicker by remember { mutableStateOf(false) }
    var showStartTimePicker by remember { mutableStateOf(false) }
    var showEndTimePicker by remember { mutableStateOf(false) }

    val timeFormat = DateTimeFormatter.ofPattern("hh:mm a")
    val dateFormat = DateTimeFormatter.ofPattern("MMM dd, yyyy")

    // v2.12.1 — Dirty = user has put any non-trivial effort into the form
    // (typed a title, typed a description, or — when editing — touched any
    // chip / toggle). For a fresh open with default values, swipe-out
    // doesn't trigger the discard confirm. The check is intentionally
    // generous (`title.isNotBlank()` alone covers most accidental swipes)
    // because false-positive confirm prompts are cheaper than data loss.
    val isDirty = title.isNotBlank() ||
        description.isNotBlank() ||
        (editingEvent != null && (
            title != (editingEvent.title) ||
            description != (editingEvent.description ?: "") ||
            category != editingEvent.category ||
            priority != editingEvent.priority ||
            selectedColor != editingEvent.color ||
            isRecurring != editingEvent.isRecurring ||
            reminderMinutes != editingEvent.reminderMinutes
        ))

    if (showDiscardConfirm) {
        AlertDialog(
            onDismissRequest = { showDiscardConfirm = false },
            title = { Text(if (editingEvent != null) "Discard changes?" else "Discard event?") },
            text = { Text("You'll lose what you've typed.") },
            confirmButton = {
                TextButton(onClick = {
                    showDiscardConfirm = false
                    onDismiss()
                }) { Text("Discard") }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardConfirm = false }) {
                    Text("Keep editing")
                }
            },
        )
    }

    ModalBottomSheet(
        onDismissRequest = {
            if (isDirty) showDiscardConfirm = true else onDismiss()
        },
        sheetState = sheetState,
    ) {
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

            // v2.12.0 — All-day toggle. When on, hides the time pickers and
            // saves with start = 00:00 / end = 23:59 on the picked dates.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("All day", style = MaterialTheme.typography.labelLarge)
                Switch(
                    checked = isAllDay,
                    onCheckedChange = { newValue ->
                        if (newValue) {
                            savedStartTime = startTime
                            savedEndTime = endTime
                            startTime = LocalTime.MIDNIGHT
                            endTime = LocalTime.of(23, 59)
                        } else {
                            startTime = savedStartTime
                            endTime = savedEndTime
                        }
                        isAllDay = newValue
                    },
                )
            }

            // Start date (+ time when not all-day)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ClickableField(
                    label = "Start Date",
                    value = startDate.format(dateFormat),
                    onClick = { showStartDatePicker = true },
                    modifier = Modifier.weight(1f)
                )
                if (!isAllDay) {
                    ClickableField(
                        label = "Start Time",
                        value = startTime.format(timeFormat),
                        onClick = { showStartTimePicker = true },
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // End date (+ time when not all-day)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ClickableField(
                    label = "End Date",
                    value = endDate.format(dateFormat),
                    onClick = { showEndDatePicker = true },
                    modifier = Modifier.weight(1f)
                )
                if (!isAllDay) {
                    ClickableField(
                        label = "End Time",
                        value = endTime.format(timeFormat),
                        onClick = { showEndTimePicker = true },
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // v2.12.2 — Inline conflict warning. Computed from the events
            // the parent passed in; the editingEvent itself is excluded so
            // an edit doesn't flag itself. Two events conflict if their
            // intervals overlap on the time axis (half-open: start < other.end
            // AND end > other.start). All-day events use 00:00→23:59 so
            // they conflict with any timed event on the same day. Listed up
            // to 3 conflicts; "+ N more" suffix if there are more.
            val proposedStartMs = remember(startDate, startTime) {
                LocalDateTime.of(startDate, startTime).atZone(zone).toInstant().toEpochMilli()
            }
            val proposedEndMs = remember(endDate, endTime) {
                LocalDateTime.of(endDate, endTime).atZone(zone).toInstant().toEpochMilli()
            }
            val conflicts = remember(existingEvents, proposedStartMs, proposedEndMs, editingEvent?.id) {
                if (proposedStartMs >= proposedEndMs) emptyList()
                else existingEvents.filter { ev ->
                    ev.id != editingEvent?.id &&
                        proposedStartMs < ev.endTime &&
                        proposedEndMs > ev.startTime
                }
            }
            if (conflicts.isNotEmpty()) {
                val timeFmt = DateTimeFormatter.ofPattern("h:mm a")
                val shown = conflicts.take(3)
                val extra = conflicts.size - shown.size
                val lines = shown.joinToString(separator = "\n") { c ->
                    val s = Instant.ofEpochMilli(c.startTime).atZone(zone).format(timeFmt)
                    val e = Instant.ofEpochMilli(c.endTime).atZone(zone).format(timeFmt)
                    "• ${c.title} ($s–$e)"
                } + if (extra > 0) "\n+ $extra more" else ""
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.6f),
                            RoundedCornerShape(8.dp),
                        )
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        "Conflicts with " + (if (conflicts.size == 1) "1 event" else "${conflicts.size} events") + ":",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                    Text(
                        lines,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                }
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

            // v2.13.1 — Multi-select reminder picker. Three modes:
            //   • Default chip → reminderMinutes = null   (single 15-min
            //     reminder via AlarmScheduler.EVENT_LEAD_MINUTES fallback).
            //   • None chip → reminderMinutes = emptyList() (explicit opt-out).
            //   • One or more offset chips → reminderMinutes = listOf(...)
            //     in chip-row order. Selecting any specific offset auto-
            //     clears Default/None; selecting Default/None clears the
            //     explicit list.
            Text("Reminders", style = MaterialTheme.typography.labelLarge)
            val currentList = reminderMinutes
            val isDefault = currentList == null
            val isNone = currentList?.isEmpty() == true
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(
                    selected = isDefault,
                    onClick = { reminderMinutes = null },
                    label = { Text("Default") }
                )
                FilterChip(
                    selected = isNone,
                    onClick = { reminderMinutes = emptyList() },
                    label = { Text("None") }
                )
                reminderOffsetOptions.forEach { (mins, label) ->
                    val checked = currentList?.contains(mins) == true
                    FilterChip(
                        selected = checked,
                        onClick = {
                            val base = currentList?.toMutableList() ?: mutableListOf()
                            if (checked) {
                                base.remove(mins)
                            } else {
                                base.add(mins)
                                base.sort()
                            }
                            reminderMinutes = base
                        },
                        label = { Text(label) }
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
                                    color = selectedColor,
                                    reminder_minutes = reminderMinutes,
                                )
                            )
                        }
                    }
                }) { Text("Save") }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }

    // v2.12.2 — Material 3 date/time pickers replace the legacy
    // android.app.DatePickerDialog / TimePickerDialog. Rendered outside
    // the ModalBottomSheet (Compose Dialogs handle their own overlay
    // z-order). DatePicker selectedDateMillis is UTC-midnight per its
    // contract — round-trip through ZoneOffset.UTC to keep the date
    // intact regardless of the user's local zone.
    if (showStartDatePicker) {
        M3DatePickerDialog(
            initial = startDate,
            onDismiss = { showStartDatePicker = false },
            onConfirm = { picked ->
                shiftStartTo(picked, startTime)
                showStartDatePicker = false
            },
        )
    }
    if (showEndDatePicker) {
        M3DatePickerDialog(
            initial = endDate,
            onDismiss = { showEndDatePicker = false },
            onConfirm = { picked ->
                endDate = picked
                showEndDatePicker = false
            },
        )
    }
    if (showStartTimePicker) {
        M3TimePickerDialog(
            initialHour = startTime.hour,
            initialMinute = startTime.minute,
            onDismiss = { showStartTimePicker = false },
            onConfirm = { h, m ->
                shiftStartTo(startDate, LocalTime.of(h, m))
                showStartTimePicker = false
            },
        )
    }
    if (showEndTimePicker) {
        M3TimePickerDialog(
            initialHour = endTime.hour,
            initialMinute = endTime.minute,
            onDismiss = { showEndTimePicker = false },
            onConfirm = { h, m ->
                endTime = LocalTime.of(h, m)
                showEndTimePicker = false
            },
        )
    }
}

// ── Helpers ─────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun M3DatePickerDialog(
    initial: LocalDate,
    onDismiss: () -> Unit,
    onConfirm: (LocalDate) -> Unit,
) {
    val state = rememberDatePickerState(
        initialSelectedDateMillis = initial.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                state.selectedDateMillis?.let { ms ->
                    val date = Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate()
                    onConfirm(date)
                } ?: onDismiss()
            }) { Text("OK") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    ) {
        DatePicker(state = state, showModeToggle = false)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun M3TimePickerDialog(
    initialHour: Int,
    initialMinute: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int, Int) -> Unit,
) {
    val state = rememberTimePickerState(
        initialHour = initialHour,
        initialMinute = initialMinute,
        is24Hour = false,
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select time") },
        text = { TimePicker(state = state) },
        confirmButton = {
            TextButton(onClick = { onConfirm(state.hour, state.minute) }) { Text("OK") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}


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
