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

private data class ChartData(
    val greenSeries: List<Number>,
    val yellowSeries: List<Number>,
    val redSeries: List<Number>,
    val dayLabels: List<String>,
    val targetHours: Double
)

@Composable
fun SleepChart(
    records: List<SleepRecordEntity>,
    modifier: Modifier = Modifier
) {
    val modelProducer = remember { CartesianChartModelProducer() }

    val chartData = remember(records) { processRecordsForChart(records) }

    LaunchedEffect(chartData) {
        modelProducer.runTransaction {
            columnSeries {
                series(chartData.greenSeries)
                series(chartData.yellowSeries)
                series(chartData.redSeries)
            }
        }
    }

    val targetLine = HorizontalLine(
        y = { chartData.targetHours },
        line = rememberLineComponent(
            fill = fill(Color(0xFF9E9E9E)),
            thickness = 1.dp
        )
    )

    CartesianChartHost(
        chart = rememberCartesianChart(
            rememberColumnCartesianLayer(
                columnProvider = ColumnCartesianLayer.ColumnProvider.series(
                    rememberLineComponent(
                        fill = fill(Color(0xFF4CAF50)),
                        thickness = 16.dp,
                        shape = CorneredShape.rounded(allPercent = 25)
                    ),
                    rememberLineComponent(
                        fill = fill(Color(0xFFFFC107)),
                        thickness = 16.dp,
                        shape = CorneredShape.rounded(allPercent = 25)
                    ),
                    rememberLineComponent(
                        fill = fill(Color(0xFFF44336)),
                        thickness = 16.dp,
                        shape = CorneredShape.rounded(allPercent = 25)
                    )
                ),
                mergeMode = { ColumnCartesianLayer.MergeMode.Stacked }
            ),
            startAxis = VerticalAxis.rememberStart(),
            bottomAxis = HorizontalAxis.rememberBottom(
                valueFormatter = { _, value, _ ->
                    chartData.dayLabels.getOrElse(value.toInt()) { "" }
                }
            ),
            decorations = listOf(targetLine)
        ),
        modelProducer = modelProducer,
        modifier = modifier
    )
}

private fun processRecordsForChart(records: List<SleepRecordEntity>): ChartData {
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now()
    val days = (6 downTo 0).map { today.minusDays(it.toLong()) }
    val dayLabels = days.map { it.format(DateTimeFormatter.ofPattern("EEE")) }

    val recordsByDay = records.groupBy { record ->
        Instant.ofEpochMilli(record.actualBedtime).atZone(zone).toLocalDate()
    }

    val green = mutableListOf<Number>()
    val yellow = mutableListOf<Number>()
    val red = mutableListOf<Number>()
    var totalTargetHours = 0.0
    var targetCount = 0

    for (day in days) {
        val dayRecords = recordsByDay[day]
        if (dayRecords != null && dayRecords.isNotEmpty()) {
            val record = dayRecords.first()
            val hours = (record.actualWakeTime - record.actualBedtime).toDouble() / 3_600_000

            when {
                record.qualityRating >= 4 -> { green.add(hours); yellow.add(0); red.add(0) }
                record.qualityRating == 3 -> { green.add(0); yellow.add(hours); red.add(0) }
                else -> { green.add(0); yellow.add(0); red.add(hours) }
            }

            val bedParts = record.targetBedtime.split(":")
            val bedSecs = bedParts[0].toInt() * 3600 + bedParts[1].toInt() * 60
            val wakeParts = record.targetWakeTime.split(":")
            val wakeSecs = wakeParts[0].toInt() * 3600 + wakeParts[1].toInt() * 60
            val targetSecs = if (wakeSecs >= bedSecs) wakeSecs - bedSecs else 86400 + wakeSecs - bedSecs
            totalTargetHours += targetSecs.toDouble() / 3600
            targetCount++
        } else {
            green.add(0); yellow.add(0); red.add(0)
        }
    }

    val targetHours = if (targetCount > 0) totalTargetHours / targetCount else 8.0

    return ChartData(green, yellow, red, dayLabels, targetHours)
}
