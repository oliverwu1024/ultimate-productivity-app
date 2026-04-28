package com.app.productivity.ui.sleep

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.app.productivity.data.local.entity.SleepRecordEntity
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
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
) {
    val modelProducer = remember { CartesianChartModelProducer() }

    val chartData = remember(records) { processRecordsForChart(records) }

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
            startAxis = VerticalAxis.rememberStart(),
            bottomAxis = HorizontalAxis.rememberBottom(
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

private fun processRecordsForChart(records: List<SleepRecordEntity>): ChartData {
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now()
    val days = (6 downTo 0).map { today.minusDays(it.toLong()) }
    val dayLabels = days.map { it.format(DateTimeFormatter.ofPattern("EEE")) }

    val recordsByDay = records.groupBy { record ->
        Instant.ofEpochMilli(record.actualBedtime).atZone(zone).toLocalDate()
    }

    val night = mutableListOf<Number>()
    val morning = mutableListOf<Number>()
    val afternoon = mutableListOf<Number>()
    var totalTargetHours = 0.0
    var targetCount = 0

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
            when (classifyByBedtime(record.actualBedtime, zone)) {
                TimeOfDay.NIGHT -> nightHrs += hours
                TimeOfDay.MORNING_NAP -> morningHrs += hours
                TimeOfDay.AFTERNOON_NAP -> afternoonHrs += hours
            }
        }

        night.add(nightHrs)
        morning.add(morningHrs)
        afternoon.add(afternoonHrs)

        // Use the first record's target as a representative for this day.
        val ref = dayRecords.first()
        val bedParts = ref.targetBedtime.split(":")
        val bedSecs = bedParts[0].toInt() * 3600 + bedParts[1].toInt() * 60
        val wakeParts = ref.targetWakeTime.split(":")
        val wakeSecs = wakeParts[0].toInt() * 3600 + wakeParts[1].toInt() * 60
        val targetSecs = if (wakeSecs >= bedSecs) wakeSecs - bedSecs else 86400 + wakeSecs - bedSecs
        totalTargetHours += targetSecs.toDouble() / 3600
        targetCount++
    }

    val targetHours = if (targetCount > 0) totalTargetHours / targetCount else 8.0

    return ChartData(night, morning, afternoon, dayLabels, targetHours)
}
