package com.app.productivity.ui.sessions

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

@Composable
fun TimerCircle(
    timeRemaining: Int,
    totalTime: Int,
    isWorkPhase: Boolean,
    modifier: Modifier = Modifier
) {
    val progress = if (totalTime > 0) timeRemaining.toFloat() / totalTime else 1f
    val animatedProgress by animateFloatAsState(targetValue = progress, label = "timer")

    val foregroundColor = if (isWorkPhase) Color(0xFF4CAF50) else Color(0xFFFF9800)
    val backgroundColor = Color.LightGray.copy(alpha = 0.3f)

    Canvas(modifier = modifier) {
        val strokeWidth = 12.dp.toPx()
        val diameter = size.minDimension - strokeWidth
        val topLeft = androidx.compose.ui.geometry.Offset(
            (size.width - diameter) / 2f,
            (size.height - diameter) / 2f
        )
        val arcSize = androidx.compose.ui.geometry.Size(diameter, diameter)

        // Background circle
        drawArc(
            color = backgroundColor,
            startAngle = 0f,
            sweepAngle = 360f,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
        )

        // Foreground arc
        drawArc(
            color = foregroundColor,
            startAngle = -90f,
            sweepAngle = 360f * animatedProgress,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
        )
    }
}
