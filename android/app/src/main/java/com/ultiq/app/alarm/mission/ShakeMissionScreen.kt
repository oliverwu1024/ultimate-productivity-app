package com.ultiq.app.alarm.mission

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * Shake dismiss mission (§8.8). Counts threshold-exceeding accelerometer
 * samples (≥ 150 ms apart so a vibrating surface can't cheat). Calls
 * [onComplete] once [targetShakes] have been registered.
 */
@Composable
fun ShakeMissionScreen(
    intensity: ShakeIntensity,
    targetShakes: Int,
    onComplete: () -> Unit,
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current

    var count by remember { mutableIntStateOf(0) }
    var lastShakeAt by remember { mutableLongStateOf(0L) }

    DisposableEffect(intensity, targetShakes) {
        val detector = ShakeDetector(
            context = context,
            thresholdMs2 = intensity.thresholdMs2,
        ) {
            // Detector callback runs on a sensor thread; mutate state we
            // own so Compose re-composes on the main thread.
            count = (count + 1).coerceAtMost(targetShakes)
            lastShakeAt = System.currentTimeMillis()
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        }
        detector.start()
        onDispose { detector.stop() }
    }

    LaunchedEffect(count) {
        if (count >= targetShakes) {
            // Tiny breath so the user sees the bar fill before the activity
            // tears down.
            delay(150)
            onComplete()
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(PaddingValues(horizontal = 32.dp, vertical = 48.dp)),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "Shake to dismiss · $count / $targetShakes",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
            )

            ShakeIcon(lastShakeAt = lastShakeAt)

            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                val progressFraction = if (targetShakes <= 0) 0f
                else (count.toFloat() / targetShakes.toFloat()).coerceIn(0f, 1f)
                val animatedFraction by animateFloatAsState(
                    targetValue = progressFraction,
                    animationSpec = tween(durationMillis = 200),
                    label = "shake-progress",
                )
                LinearProgressIndicator(
                    progress = { animatedFraction },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(16.dp)
                        .clip(RoundedCornerShape(8.dp)),
                )
                Text(
                    text = intensityHint(intensity),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ShakeIcon(lastShakeAt: Long) {
    // §M7: pulse-on-shake. Each shake pushes the target to 1.25×; a 120ms
    // LaunchedEffect resets it to 1.0× so the spring animation has something
    // to recompose to. The previous implementation read System.currentTimeMillis()
    // inside the targetValue computation, which never re-evaluated after the
    // first composition — the icon stayed at 1.25× forever.
    var pulseScale by remember { mutableFloatStateOf(1f) }
    LaunchedEffect(lastShakeAt) {
        if (lastShakeAt == 0L) return@LaunchedEffect
        pulseScale = 1.25f
        kotlinx.coroutines.delay(120)
        pulseScale = 1f
    }
    val scale by animateFloatAsState(
        targetValue = pulseScale,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessHigh,
        ),
        label = "shake-pulse",
    )

    Box(
        modifier = Modifier
            .size(180.dp)
            .scale(scale),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Default.Vibration,
            contentDescription = null,
            modifier = Modifier.size(160.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
    }
}

private fun intensityHint(intensity: ShakeIntensity): String = when (intensity) {
    ShakeIntensity.LOW -> "Shake firmly to dismiss"
    ShakeIntensity.MEDIUM -> "Shake hard to dismiss"
    ShakeIntensity.HIGH -> "Shake vigorously to dismiss"
}
