package com.ultiq.app.ui.reports

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.TrendingFlat
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProgressIndicatorDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ultiq.app.data.achievements.AchievementId
import com.ultiq.app.ui.theme.CategoryColors
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WeeklyReportScreen(
    onBack: () -> Unit,
    viewModel: WeeklyReportViewModel = viewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Weekly report") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                WeekSelector(
                    start = uiState.weekStart,
                    onPrevious = { viewModel.previousWeek() },
                    onNext = { viewModel.nextWeek() },
                )
            }
            item { SectionHeader("Sleep") }
            item { SleepSection(uiState) }

            item { SectionHeader("Focus") }
            item { FocusSection(uiState) }

            item { SectionHeader("Calendar") }
            item { CalendarSection(uiState) }

            item { SectionHeader("Streaks") }
            item { StreakSection(uiState) }

            item { SectionHeader("Achievements") }
            item { AchievementsSection(uiState) }
        }
    }
}

// ── Week selector ───────────────────────────────────────────────────────

@Composable
private fun WeekSelector(start: LocalDate, onPrevious: () -> Unit, onNext: () -> Unit) {
    val end = start.plusDays(6)
    val fmt = DateTimeFormatter.ofPattern("MMM d")
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        IconButton(onClick = onPrevious) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Previous week")
        }
        Text(
            "${start.format(fmt)} – ${end.format(fmt)}",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        IconButton(onClick = onNext) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Next week")
        }
    }
}

// ── Section header ──────────────────────────────────────────────────────

@Composable
private fun SectionHeader(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
    )
}

// ── Sleep section ───────────────────────────────────────────────────────

@Composable
private fun SleepSection(state: WeeklyReportUiState) {
    ReportCard {
        val bars = state.sleepByDay.map { day ->
            BarData(
                label = day.date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()).take(1),
                value = (day.durationMinutes ?: 0).toFloat() / 60f,
                color = sleepBarColor(day.qualityRating),
            )
        }
        BarChart(
            bars = bars,
            maxValue = 10f,
            yUnit = "h",
            modifier = Modifier
                .fillMaxWidth()
                .height(160.dp),
        )

        Spacer(Modifier.height(12.dp))
        StatGrid(
            pairs = listOf(
                "Avg duration" to formatDuration(state.avgSleepDurationMinutes.toInt()),
                "Avg quality" to if (state.avgSleepQuality > 0) "%.1f / 5".format(state.avgSleepQuality) else "-",
                "Best night" to (state.bestNight?.let { "${it.date.dayOfWeek.name.take(3)} (${it.qualityRating}★)" } ?: "-"),
                "Worst night" to (state.worstNight?.let { "${it.date.dayOfWeek.name.take(3)} (${it.qualityRating}★)" } ?: "-"),
            ),
        )

        Spacer(Modifier.height(8.dp))
        PickupsRow(
            label = "Phone pickups / night",
            current = state.avgPhonePickupsPerNight,
            trend = state.phonePickupsTrend,
        )
    }
}

// ── Focus section ───────────────────────────────────────────────────────

@Composable
private fun FocusSection(state: WeeklyReportUiState) {
    ReportCard {
        val bars = state.focusByDay.map { day ->
            BarData(
                label = day.date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()).take(1),
                value = day.totalMinutes.toFloat() / 60f,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        val maxHours = (bars.maxOfOrNull { it.value } ?: 0f).coerceAtLeast(4f)
        BarChart(
            bars = bars,
            maxValue = maxHours,
            yUnit = "h",
            modifier = Modifier
                .fillMaxWidth()
                .height(160.dp),
        )

        Spacer(Modifier.height(12.dp))
        StatGrid(
            pairs = listOf(
                "Total focus" to formatDuration(state.totalFocusMinutes),
                "Sessions" to "${state.sessionsCompleted}",
                "Pickups total" to "${state.totalSessionPickups}",
                "Avg pickups/session" to "%.1f".format(state.avgPickupsPerSession),
            ),
        )

        if (state.topTags.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text(
                "Top tags",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(4.dp))
            val totalMinutes = state.topTags.sumOf { it.totalMinutes }.coerceAtLeast(1)
            state.topTags.forEach { tag ->
                TagBar(
                    tag = tag.tag,
                    minutes = tag.totalMinutes,
                    total = totalMinutes,
                    sessionCount = tag.sessionCount,
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        Text(
            "Best distraction-free streak: ${state.bestDistractionFreeStreak} session${if (state.bestDistractionFreeStreak != 1) "s" else ""}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun TagBar(tag: String, minutes: Int, total: Int, sessionCount: Int) {
    val fraction = minutes.toFloat() / total
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(tag, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(
                "${formatDuration(minutes)} · $sessionCount session${if (sessionCount != 1) "s" else ""}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        LinearProgressIndicator(
            progress = { fraction.coerceIn(0f, 1f) },
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surface,
            strokeCap = ProgressIndicatorDefaults.LinearStrokeCap,
        )
    }
}

// ── Calendar section ────────────────────────────────────────────────────

@Composable
private fun CalendarSection(state: WeeklyReportUiState) {
    ReportCard {
        StatGrid(
            pairs = listOf(
                "Events" to "${state.eventsCompleted}/${state.eventsTotal}",
                "Busiest day" to (state.busiestDay?.let {
                    "${it.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())} ($${state.busiestDayCount})"
                        .replace("$", "")
                } ?: "-"),
            ),
        )
        if (state.categoryBreakdown.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text(
                "By category",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(4.dp))
            val total = state.categoryBreakdown.sumOf { it.count }.coerceAtLeast(1)
            state.categoryBreakdown.forEach { cat ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(CategoryColors.forCategory(cat.category), CircleShape),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        cat.category.replaceFirstChar { it.uppercase() },
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.width(96.dp),
                    )
                    LinearProgressIndicator(
                        progress = { cat.count.toFloat() / total },
                        modifier = Modifier
                            .weight(1f)
                            .height(6.dp),
                        color = CategoryColors.forCategory(cat.category),
                        trackColor = MaterialTheme.colorScheme.surface,
                        strokeCap = ProgressIndicatorDefaults.LinearStrokeCap,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("${cat.count}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

// ── Streaks section ─────────────────────────────────────────────────────

@Composable
private fun StreakSection(state: WeeklyReportUiState) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        StreakCard(
            label = "Sleep target",
            value = state.sleepTargetStreak,
            unit = "night${if (state.sleepTargetStreak != 1) "s" else ""}",
            modifier = Modifier.weight(1f),
        )
        StreakCard(
            label = "Focus",
            value = state.focusStreak,
            unit = "day${if (state.focusStreak != 1) "s" else ""}",
            modifier = Modifier.weight(1f),
        )
        StreakCard(
            label = "Zero pickups",
            value = state.bestDistractionFreeStreak,
            unit = "session${if (state.bestDistractionFreeStreak != 1) "s" else ""}",
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun StreakCard(label: String, value: Int, unit: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(Icons.Default.LocalFireDepartment, null, tint = Color(0xFFFF9800))
            Spacer(Modifier.height(4.dp))
            Text(
                "$value",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(unit, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ── Achievements section ────────────────────────────────────────────────

@Composable
private fun AchievementsSection(state: WeeklyReportUiState) {
    val earnedIds = state.achievements.map { it.id }.toSet()
    val earnedMap = state.achievements.associateBy { it.id }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AchievementId.entries.forEach { id ->
            val earned = id in earnedIds
            val at = earnedMap[id]?.earnedAt
            AchievementRow(id = id, earned = earned, earnedAt = at)
        }
    }
}

@Composable
private fun AchievementRow(id: AchievementId, earned: Boolean, earnedAt: Long?) {
    val bg = if (earned) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (earned) MaterialTheme.colorScheme.onPrimaryContainer
        else MaterialTheme.colorScheme.onSurfaceVariant
    Card(colors = CardDefaults.cardColors(containerColor = bg)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .background(
                        if (earned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                        CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    id.icon, null,
                    modifier = Modifier.size(24.dp),
                    tint = if (earned) MaterialTheme.colorScheme.onPrimary else fg.copy(alpha = 0.5f),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    id.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = fg,
                )
                Text(
                    id.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = fg.copy(alpha = 0.8f),
                )
                if (earned && earnedAt != null) {
                    val dateStr = java.time.Instant.ofEpochMilli(earnedAt)
                        .atZone(java.time.ZoneId.systemDefault())
                        .format(DateTimeFormatter.ofPattern("MMM d, yyyy"))
                    Text(
                        "Earned $dateStr",
                        style = MaterialTheme.typography.labelSmall,
                        color = fg.copy(alpha = 0.7f),
                    )
                }
            }
            if (earned) {
                Icon(Icons.Default.EmojiEvents, null, tint = fg)
            }
        }
    }
}

// ── Shared helpers ──────────────────────────────────────────────────────

@Composable
private fun ReportCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(16.dp)) { content() }
    }
}

@Composable
private fun StatGrid(pairs: List<Pair<String, String>>) {
    val rows = pairs.chunked(2)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        rows.forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                row.forEach { (label, value) ->
                    Column(modifier = Modifier.weight(1f)) {
                        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            value,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun PickupsRow(label: String, current: Double, trend: Double) {
    val (icon, color) = when {
        trend < -0.5 -> Icons.Default.ArrowDownward to Color(0xFF2ECC71)
        trend > 0.5 -> Icons.Default.ArrowUpward to MaterialTheme.colorScheme.error
        else -> Icons.AutoMirrored.Filled.TrendingFlat to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "$label: %.1f".format(current),
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.width(8.dp))
        Icon(icon, null, tint = color, modifier = Modifier.size(16.dp))
        if (trend != 0.0) {
            Text(
                " %+.1f vs last week".format(trend),
                style = MaterialTheme.typography.bodySmall,
                color = color,
            )
        }
    }
}

private fun sleepBarColor(quality: Int?): Color = when (quality) {
    null, 0 -> Color(0xFFBDBDBD)
    1, 2 -> Color(0xFFE57373)
    3 -> Color(0xFFFFD54F)
    else -> Color(0xFF66BB6A)
}

private fun formatDuration(minutes: Int): String {
    if (minutes <= 0) return "-"
    val h = minutes / 60
    val m = minutes % 60
    return if (h > 0) "${h}h ${m}m" else "${m}m"
}

// ── Bar chart ───────────────────────────────────────────────────────────

private data class BarData(val label: String, val value: Float, val color: Color)

@Composable
private fun BarChart(
    bars: List<BarData>,
    maxValue: Float,
    yUnit: String,
    modifier: Modifier = Modifier,
) {
    val axisColor = MaterialTheme.colorScheme.onSurfaceVariant
    Column(modifier = modifier) {
        Row(modifier = Modifier.weight(1f)) {
            Column(
                verticalArrangement = Arrangement.SpaceBetween,
                horizontalAlignment = Alignment.End,
            ) {
                Text("${maxValue.toInt()}$yUnit", style = MaterialTheme.typography.labelSmall, color = axisColor)
                Text("${(maxValue / 2f).toInt()}$yUnit", style = MaterialTheme.typography.labelSmall, color = axisColor)
                Text("0", style = MaterialTheme.typography.labelSmall, color = axisColor)
            }
            Spacer(Modifier.width(4.dp))
            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxSize(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.Bottom,
            ) {
                bars.forEach { bar ->
                    val heightFrac = (bar.value / maxValue).coerceIn(0f, 1f)
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxSize(),
                        verticalArrangement = Arrangement.Bottom,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        if (heightFrac > 0f) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(0.6f)
                                    .fillMaxHeight(heightFrac)
                                    .background(
                                        bar.color,
                                        RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp),
                                    ),
                            )
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 24.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            bars.forEach { bar ->
                Text(
                    bar.label,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 10.sp,
                )
            }
        }
    }
}
