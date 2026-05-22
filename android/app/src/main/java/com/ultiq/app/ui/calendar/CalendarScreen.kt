package com.ultiq.app.ui.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.material.icons.filled.EventAvailable
import com.ultiq.app.data.local.entity.CalendarEventEntity
import com.ultiq.app.ui.common.AiParsePromptDialog
import com.ultiq.app.ui.common.AiParseSurface
import com.ultiq.app.ui.common.MascotEmptyState
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

    LaunchedEffect(uiState.error) {
        uiState.error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
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
                onPrevious = { viewModel.changeMonth(-1) },
                onNext = { viewModel.changeMonth(1) }
            )

            DayOfWeekHeader()

            MonthGrid(
                yearMonth = uiState.currentMonth,
                selectedDate = uiState.selectedDate,
                today = LocalDate.now(),
                monthEvents = uiState.monthEvents,
                onDateSelected = { viewModel.selectDate(it) }
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
                    EventItem(
                        event = event,
                        onClick = { viewModel.showEditDialog(event) },
                        onSetDone = { done -> viewModel.setEventDone(event.id, done) },
                        modifier = Modifier.animateItem(),
                    )
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
                { viewModel.deleteEvent(event.id) }
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
}

// ── Month header ────────────────────────────────────────────────────────

@Composable
private fun MonthHeader(
    yearMonth: YearMonth,
    onPrevious: () -> Unit,
    onNext: () -> Unit
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
        Text(
            yearMonth.format(DateTimeFormatter.ofPattern("MMMM yyyy")),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )
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
    onDateSelected: (LocalDate) -> Unit
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
                        val dots = events
                            .map { categoryColor(it.category) }
                            .distinct()
                            .take(3)

                        DayCell(
                            day = dayNum,
                            isSelected = date == selectedDate,
                            isToday = date == today,
                            dots = dots,
                            onClick = { onDateSelected(date) },
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

@Composable
private fun DayCell(
    day: Int,
    isSelected: Boolean,
    isToday: Boolean,
    dots: List<Color>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val bgColor = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent
    val textColor = when {
        isSelected -> MaterialTheme.colorScheme.onPrimary
        else -> MaterialTheme.colorScheme.onSurface
    }

    Column(
        modifier = modifier
            .aspectRatio(1f)
            .padding(2.dp)
            .clip(CircleShape)
            .background(bgColor)
            .then(
                if (isToday && !isSelected)
                    Modifier.border(1.5.dp, MaterialTheme.colorScheme.primary, CircleShape)
                else Modifier
            )
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
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
                    formatTimeRange(event.startTime, event.endTime),
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

private fun formatTimeRange(startMillis: Long, endMillis: Long): String {
    val zone = ZoneId.systemDefault()
    val fmt = DateTimeFormatter.ofPattern("h:mm a")
    val start = Instant.ofEpochMilli(startMillis).atZone(zone).format(fmt)
    val end = Instant.ofEpochMilli(endMillis).atZone(zone).format(fmt)
    return "$start – $end"
}
