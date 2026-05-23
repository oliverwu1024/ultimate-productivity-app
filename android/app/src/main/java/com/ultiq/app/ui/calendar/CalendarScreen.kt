package com.ultiq.app.ui.calendar

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.material.icons.filled.EventAvailable
import com.ultiq.app.data.local.entity.CalendarEventEntity
import com.ultiq.app.ui.common.AiParsePromptDialog
import com.ultiq.app.ui.common.AiParseSurface
import com.ultiq.app.ui.common.MascotEmptyState
import com.ultiq.app.ui.common.SwipeToDeleteBox
import com.ultiq.app.ui.copy.WarmCopy
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarScreen(viewModel: CalendarViewModel = viewModel()) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    // §delete-consistency — the AddEventDialog's Delete button now goes
    // through a confirm dialog at this layer, matching the other tabs.
    var pendingDeleteId by remember { mutableStateOf<String?>(null) }
    var pendingDeleteTitle by remember { mutableStateOf("") }

    LaunchedEffect(uiState.error) {
        uiState.error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    // v2.11.8 — Re-sync from server whenever the Calendar tab regains focus
    // (foreground, or returning from another nav destination). The
    // ViewModel's init-time sync() only fires once per process; without
    // this, events added on web (or by another device) stayed invisible
    // until process death + relaunch. Cheap: one GET + a Room insertAll.
    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose { }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Calendar") }) },
        floatingActionButton = {
            // §9.5 — Small "AI" FAB stacked above the main "+" FAB. The small
            // size + accent tint distinguishes the AI quick-add affordance
            // without competing with the primary action.
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SmallFloatingActionButton(
                    onClick = { viewModel.showAiDialog() },
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                ) {
                    Icon(Icons.Default.AutoAwesome, "Quick add with AI")
                }
                FloatingActionButton(onClick = { viewModel.showAddDialog() }) {
                    Icon(Icons.Default.Add, "Add Event")
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            MonthHeader(
                yearMonth = uiState.currentMonth,
                isOnCurrentMonth = uiState.currentMonth == YearMonth.now(),
                onPrevious = { viewModel.changeMonth(-1) },
                onNext = { viewModel.changeMonth(1) },
                onToday = { viewModel.jumpToToday() },
            )

            DayOfWeekHeader()

            MonthGrid(
                yearMonth = uiState.currentMonth,
                selectedDate = uiState.selectedDate,
                today = LocalDate.now(),
                monthEvents = uiState.monthEvents,
                onDateSelected = { viewModel.selectDate(it) },
                onDateLongPressed = { date ->
                    viewModel.selectDate(date)
                    viewModel.showAddDialog()
                },
            )

            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))

            SelectedDayHeader(uiState.selectedDate)

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (uiState.selectedDayEvents.isEmpty()) {
                    item {
                        val (title, body) = WarmCopy.calendarEmpty()
                        MascotEmptyState(title = title, body = body)
                    }
                }
                items(uiState.selectedDayEvents, key = { "${it.id}_${it.startTime}" }) { event ->
                    // v2.12.0 — Swipe-to-delete matches the project-wide
                    // delete pattern used by Sleep / Alarms / Checklist
                    // (project memory: "every list screen uses
                    // SwipeToDeleteBox"). The Delete button inside
                    // AddEventDialog stays as the secondary path.
                    SwipeToDeleteBox(
                        confirmTitle = "Delete event?",
                        confirmBody = "'${event.title}' will be removed from your calendar.",
                        onDelete = { viewModel.deleteEvent(event.id) },
                        modifier = Modifier.animateItem(),
                    ) {
                        EventItem(
                            event = event,
                            viewDate = uiState.selectedDate,
                            onClick = { viewModel.showEditDialog(event) },
                            onSetDone = { done -> viewModel.setEventDone(event.id, done) },
                        )
                    }
                }
            }
        }
    }

    if (uiState.showAddDialog) {
        AddEventDialog(
            initialDate = uiState.selectedDate,
            editingEvent = uiState.editingEvent,
            onDismiss = { viewModel.hideDialog() },
            onSave = { dto ->
                val editing = uiState.editingEvent
                if (editing != null) {
                    viewModel.updateEvent(editing.id, dto)
                } else {
                    viewModel.createEvent(dto)
                }
            },
            onDelete = uiState.editingEvent?.let { event ->
                {
                    pendingDeleteId = event.id
                    pendingDeleteTitle = event.title
                    viewModel.hideDialog()
                }
            },
            prefilledNewEvent = uiState.aiPrefill,
        )
    }

    if (uiState.showAiDialog) {
        AiParsePromptDialog(
            surface = AiParseSurface.CALENDAR,
            loading = uiState.aiLoading,
            error = uiState.aiError,
            onSubmit = { text -> viewModel.submitAiParse(text) },
            onDismiss = { viewModel.dismissAiDialog() },
        )
    }

    pendingDeleteId?.let { id ->
        AlertDialog(
            onDismissRequest = { pendingDeleteId = null },
            title = { Text("Delete event?") },
            text = { Text("'${pendingDeleteTitle}' will be removed from your calendar.") },
            confirmButton = {
                Button(
                    onClick = {
                        pendingDeleteId = null
                        viewModel.deleteEvent(id)
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                    ),
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteId = null }) { Text("Cancel") }
            },
        )
    }
}

// ── Month header ────────────────────────────────────────────────────────

@Composable
private fun MonthHeader(
    yearMonth: YearMonth,
    isOnCurrentMonth: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onToday: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onPrevious) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Previous month")
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                yearMonth.format(DateTimeFormatter.ofPattern("MMMM yyyy")),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            // v2.12.0 — "Today" affordance only visible when off the
            // current month. Hidden on-month avoids visual noise and
            // implies the user is already where this would take them.
            if (!isOnCurrentMonth) {
                Spacer(modifier = Modifier.width(8.dp))
                TextButton(onClick = onToday, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
                    Text("Today", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        IconButton(onClick = onNext) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Next month")
        }
    }
}

// ── Day of week header ──────────────────────────────────────────────────

@Composable
private fun DayOfWeekHeader() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
    ) {
        java.time.DayOfWeek.entries.forEach { day ->
            Text(
                text = day.getDisplayName(TextStyle.SHORT, Locale.getDefault()),
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ── Month grid ──────────────────────────────────────────────────────────

@Composable
private fun MonthGrid(
    yearMonth: YearMonth,
    selectedDate: LocalDate,
    today: LocalDate,
    monthEvents: Map<LocalDate, List<CalendarEventEntity>>,
    onDateSelected: (LocalDate) -> Unit,
    onDateLongPressed: (LocalDate) -> Unit,
) {
    val firstDay = yearMonth.atDay(1)
    val offset = firstDay.dayOfWeek.value - 1 // Mon = 0
    val daysInMonth = yearMonth.lengthOfMonth()

    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
        for (week in 0 until 6) {
            val weekStart = week * 7 - offset + 1
            if (weekStart > daysInMonth) break

            Row(modifier = Modifier.fillMaxWidth()) {
                for (col in 0 until 7) {
                    val dayNum = weekStart + col
                    if (dayNum in 1..daysInMonth) {
                        val date = yearMonth.atDay(dayNum)
                        val events = monthEvents[date] ?: emptyList()
                        // v2.11.9 — Split rendering between dots (single-day
                        // events on this date) and bars (multi-day events
                        // spanning this date). The bar visually continues
                        // across consecutive cells because each cell renders
                        // a full-width stripe in the same colour, giving
                        // the Google-Calendar "ribbon" look without needing
                        // a custom multi-cell overlay layer.
                        val zone = ZoneId.systemDefault()
                        val (multiDay, singleDay) = events.partition { ev ->
                            val sd = Instant.ofEpochMilli(ev.startTime).atZone(zone).toLocalDate()
                            val ed = Instant.ofEpochMilli(ev.endTime).atZone(zone).toLocalDate()
                            sd != ed
                        }
                        val dots = singleDay
                            .map { parseHexColor(it.color) }
                            .distinct()
                            .take(3)
                        val bars = multiDay
                            .map { parseHexColor(it.color) }
                            .take(3)

                        DayCell(
                            day = dayNum,
                            isSelected = date == selectedDate,
                            isToday = date == today,
                            dots = dots,
                            bars = bars,
                            onClick = { onDateSelected(date) },
                            onLongPress = { onDateLongPressed(date) },
                            modifier = Modifier.weight(1f)
                        )
                    } else {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DayCell(
    day: Int,
    isSelected: Boolean,
    isToday: Boolean,
    dots: List<Color>,
    bars: List<Color>,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier
) {
    val bgColor = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent
    val textColor = when {
        isSelected -> MaterialTheme.colorScheme.onPrimary
        else -> MaterialTheme.colorScheme.onSurface
    }

    // v2.12.0 — Restructured: the circular tap target lives in its own Box
    // with aspectRatio(1f); multi-day "ribbons" sit BELOW the circle, full
    // cell width, outside any clip. The previous render put the bars
    // *inside* the CircleShape clip, which cut each bar to an arc-shaped
    // stub through the date number and left visible padding gaps between
    // consecutive cells — the result looked like a stray short line
    // crossing the number instead of a continuous bar across the week.
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .padding(2.dp)
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(CircleShape)
                .background(bgColor)
                .then(
                    if (isToday && !isSelected)
                        Modifier.border(1.5.dp, MaterialTheme.colorScheme.primary, CircleShape)
                    else Modifier
                )
                .combinedClickable(onClick = onClick, onLongClick = onLongPress),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "$day",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
                    color = textColor
                )
                if (dots.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        dots.forEach { color ->
                            Box(
                                modifier = Modifier
                                    .size(4.dp)
                                    .background(color, CircleShape)
                            )
                        }
                    }
                }
            }
        }
        // Multi-day ribbons: full cell width, no clip, stacked vertically
        // when multiple multi-day events overlap (capped at 3 in the
        // caller). Reserved-height placeholder keeps row heights aligned
        // even when only some cells in the row have bars.
        Column(
            modifier = Modifier.fillMaxWidth().height(12.dp).padding(top = 1.dp),
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            bars.forEach { color ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .background(color, RoundedCornerShape(1.dp))
                )
            }
        }
    }
}

// ── Selected day section ────────────────────────────────────────────────

@Composable
private fun SelectedDayHeader(date: LocalDate) {
    Text(
        text = date.format(DateTimeFormatter.ofPattern("EEEE, MMMM d")),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
    )
}

@Composable
private fun EventItem(
    event: CalendarEventEntity,
    viewDate: LocalDate,
    onClick: () -> Unit,
    onSetDone: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Past = the slot has actually finished. Mark-done only makes sense for
    // those — future events stay clean / no checkbox.
    val isPast = event.endTime < System.currentTimeMillis()
    val isDone = event.isDone
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Colored left border
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .fillMaxHeight()
                    .background(
                        parseHexColor(event.color),
                        RoundedCornerShape(topStart = 12.dp, bottomStart = 12.dp)
                    )
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(12.dp)
            ) {
                Text(
                    event.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textDecoration = if (isDone) TextDecoration.LineThrough else null,
                    color = if (isDone) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    formatTimeRange(event.startTime, event.endTime, viewDate),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CategoryChip(event.category)
                    PriorityIndicator(event.priority)
                }
            }
            if (isPast) {
                Checkbox(
                    checked = isDone,
                    onCheckedChange = onSetDone,
                    modifier = Modifier.padding(end = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun CategoryChip(category: String) {
    val color = categoryColor(category)
    Box(
        modifier = Modifier
            .background(color.copy(alpha = 0.15f), RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(
            category.replaceFirstChar { it.uppercase() },
            style = MaterialTheme.typography.labelSmall,
            color = color
        )
    }
}

@Composable
private fun PriorityIndicator(priority: String) {
    val color = com.ultiq.app.ui.theme.PriorityColors.forPriority(priority)
    val label = when (priority) {
        "high" -> "High"
        "low" -> "Low"
        else -> "Med"
    }
    Text(label, style = MaterialTheme.typography.labelSmall, color = color)
}

// ── Helpers ─────────────────────────────────────────────────────────────

internal fun categoryColor(category: String): Color =
    com.ultiq.app.ui.theme.CategoryColors.forCategory(category)

internal fun parseHexColor(hex: String): Color {
    return try {
        Color(android.graphics.Color.parseColor(hex))
    } catch (_: Exception) {
        Color(0xFF95A5A6)
    }
}

/// v2.11.9 — Multi-day-aware time formatter. Single-day events render as
/// "9:00 AM – 10:00 AM" exactly as before. Multi-day events adapt to which
/// day is being viewed:
///   • Start day:  "9:00 AM → ends Wed 6:00 PM"
///   • Middle day: "All day · started Mon 9:00 AM"
///   • End day:    "Ends 6:00 PM · started Mon 9:00 AM"
/// so a user looking at "today" mid-event sees the right context without
/// needing to tap into the event.
private fun formatTimeRange(startMillis: Long, endMillis: Long, viewDate: LocalDate): String {
    val zone = ZoneId.systemDefault()
    val timeFmt = DateTimeFormatter.ofPattern("h:mm a")
    val dayFmt = DateTimeFormatter.ofPattern("EEE")
    val startDt = Instant.ofEpochMilli(startMillis).atZone(zone)
    val endDt = Instant.ofEpochMilli(endMillis).atZone(zone)
    val startDate = startDt.toLocalDate()
    val endDate = endDt.toLocalDate()
    val startStr = startDt.format(timeFmt)
    val endStr = endDt.format(timeFmt)
    if (startDate == endDate) return "$startStr – $endStr"
    return when (viewDate) {
        startDate -> "$startStr → ends ${endDate.format(dayFmt)} $endStr"
        endDate -> "Ends $endStr · started ${startDate.format(dayFmt)} $startStr"
        else -> "All day · started ${startDate.format(dayFmt)} $startStr"
    }
}
