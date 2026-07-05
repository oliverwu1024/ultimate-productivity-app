package com.ultiq.app.ui.sleep

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.ultiq.app.data.local.entity.SleepRecordEntity
import com.ultiq.app.util.LocaleManager
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberAxisLabelComponent
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberBottom
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberStart
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberColumnCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.common.component.rememberLineComponent
import com.patrykandpatrick.vico.compose.common.fill
import com.patrykandpatrick.vico.core.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.core.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.core.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.core.cartesian.data.columnSeries
import com.patrykandpatrick.vico.core.cartesian.decoration.HorizontalLine
import com.patrykandpatrick.vico.core.cartesian.layer.ColumnCartesianLayer
import com.patrykandpatrick.vico.core.common.shape.CorneredShape
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val NightColor = Color(0xFF3F51B5)         // indigo: bedtime 19:00–04:59
private val MorningNapColor = Color(0xFF26C6DA)    // cyan: bedtime 05:00–11:59
private val AfternoonNapColor = Color(0xFFFFA726)  // amber: bedtime 12:00–18:59

private data class ChartData(
    val nightSeries: List<Number>,
    val morningSeries: List<Number>,
    val afternoonSeries: List<Number>,
    val dayLabels: List<String>,
    val targetHours: Double,
)

@Composable
fun SleepChart(
    records: List<SleepRecordEntity>,
    modifier: Modifier = Modifier,
    /**
     * §chart-target-fix (v2.14.1) — User's explicit nightly sleep target
     * (`UserSettings.sleepTargetMinutes`), passed in from SleepScreen.
     * Used to draw the target line. Before this change the chart averaged
     * the per-record `targetBedtime → targetWakeTime` window, which is the
     * SCHEDULE not the TARGET — so if you scheduled bedtime 00:00 → 06:00
     * but set "Optimal nightly sleep" to 8h, the line drew at 6h.
     * Null preserves the old behaviour so callers that don't pass a target
     * still render (no crash on first render before settings load).
     */
    sleepTargetMinutes: Int? = null,
) {
    val modelProducer = remember { CartesianChartModelProducer() }

    val chartData = remember(records, sleepTargetMinutes) {
        processRecordsForChart(records, sleepTargetMinutes)
    }

    LaunchedEffect(chartData) {
        modelProducer.runTransaction {
            columnSeries {
                series(chartData.nightSeries)
                series(chartData.morningSeries)
                series(chartData.afternoonSeries)
            }
        }
    }

    val targetLine = HorizontalLine(
        y = { chartData.targetHours },
        line = rememberLineComponent(
            fill = fill(Color(0xFF9E9E9E)),
            thickness = 1.dp,
        ),
    )

    // §chart-light-mode (v2.13.13) — Vico's default axis-label color comes
    // from its own theme (`vicoTheme.textColor`), which isn't wired to
    // MaterialTheme by default. The fallback color happens to be visible
    // on dark backgrounds but blends into light-mode surfaces — confirmed
    // on real device screenshot 2026-05-24 where the Mon/Tue/… X-axis
    // labels + Y-axis numbers were invisible in light mode. Passing a
    // theme-aware label component to both axes locks the color to the
    // current Material color scheme.
    val axisLabel = rememberAxisLabelComponent(
        color = MaterialTheme.colorScheme.onSurface,
    )
    CartesianChartHost(
        chart = rememberCartesianChart(
            rememberColumnCartesianLayer(
                columnProvider = ColumnCartesianLayer.ColumnProvider.series(
                    rememberLineComponent(
                        fill = fill(NightColor),
                        thickness = 16.dp,
                        shape = CorneredShape.rounded(allPercent = 25),
                    ),
                    rememberLineComponent(
                        fill = fill(MorningNapColor),
                        thickness = 16.dp,
                        shape = CorneredShape.rounded(allPercent = 25),
                    ),
                    rememberLineComponent(
                        fill = fill(AfternoonNapColor),
                        thickness = 16.dp,
                        shape = CorneredShape.rounded(allPercent = 25),
                    ),
                ),
                mergeMode = { ColumnCartesianLayer.MergeMode.Stacked },
            ),
            startAxis = VerticalAxis.rememberStart(label = axisLabel),
            bottomAxis = HorizontalAxis.rememberBottom(
                label = axisLabel,
                valueFormatter = { _, value, _ ->
                    chartData.dayLabels.getOrElse(value.toInt()) { "" }
                },
            ),
            decorations = listOf(targetLine),
        ),
        modelProducer = modelProducer,
        modifier = modifier,
    )
}

private enum class TimeOfDay { NIGHT, MORNING_NAP, AFTERNOON_NAP }

private fun classifyByBedtime(epochMs: Long, zone: ZoneId): TimeOfDay {
    val hour = Instant.ofEpochMilli(epochMs).atZone(zone).hour
    return when (hour) {
        in 5..11 -> TimeOfDay.MORNING_NAP
        in 12..18 -> TimeOfDay.AFTERNOON_NAP
        else -> TimeOfDay.NIGHT // 19–23 and 0–4
    }
}

private fun processRecordsForChart(
    records: List<SleepRecordEntity>,
    sleepTargetMinutes: Int?,
): ChartData {
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now()
    val days = (6 downTo 0).map { today.minusDays(it.toLong()) }
    val dayLabels = days.map { it.format(DateTimeFormatter.ofPattern("EEE", LocaleManager.currentLocale())) }

    // §sleep-day (v2.13.17) — Bars are sleep_day-bucketed. A Tue 02:00
    // bedtime stacks onto Monday's bar (Monday's night), not Tuesday's,
    // so the "this week" Dashboard card and the chart agree.
    val recordsByDay = records.groupBy { record ->
        com.ultiq.app.util.sleepDayFor(record.actualBedtime, zone)
    }

    val night = mutableListOf<Number>()
    val morning = mutableListOf<Number>()
    val afternoon = mutableListOf<Number>()

    for (day in days) {
        val dayRecords = recordsByDay[day]
        if (dayRecords.isNullOrEmpty()) {
            night.add(0); morning.add(0); afternoon.add(0)
            continue
        }

        var nightHrs = 0.0
        var morningHrs = 0.0
        var afternoonHrs = 0.0

        for (record in dayRecords) {
            val hours = (record.actualWakeTime - record.actualBedtime).toDouble() / 3_600_000
            // §last-night — an explicitly-marked nap is never charted as night
            // sleep, even if it was logged at a night-time bedtime.
            val tod = classifyByBedtime(record.actualBedtime, zone)
            when {
                record.isNap && tod == TimeOfDay.NIGHT -> afternoonHrs += hours
                tod == TimeOfDay.NIGHT -> nightHrs += hours
                tod == TimeOfDay.MORNING_NAP -> morningHrs += hours
                else -> afternoonHrs += hours
            }
        }

        night.add(nightHrs)
        morning.add(morningHrs)
        afternoon.add(afternoonHrs)
    }

    // §chart-target-fix (v2.14.1) — Use the user's explicit nightly sleep
    // target ("Optimal nightly sleep" in Sleep preferences). Falls back to
    // 8h when settings haven't loaded yet (first render race). The old
    // averaging-bedtime→wake-window code was always misleading: that pair
    // is the SCHEDULE for the bedtime reminder, not the SLEEP TARGET, and
    // they diverged whenever the user set a shorter schedule window than
    // their sleep goal (e.g. 00:00→06:00 schedule + 8h target → line at 6h).
    val targetHours = sleepTargetMinutes?.let { it.toDouble() / 60.0 } ?: 8.0

    return ChartData(night, morning, afternoon, dayLabels, targetHours)
}
