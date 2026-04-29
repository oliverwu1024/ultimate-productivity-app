package com.ultiq.app.ui.onboarding

import android.app.TimePickerDialog
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.SelfImprovement
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ultiq.app.ui.theme.MascotSleepingBook
import java.time.LocalTime
import java.time.format.DateTimeFormatter

private const val STEP_COUNT = 4

@Composable
fun OnboardingScreen(
    onFinish: () -> Unit,
    viewModel: OnboardingViewModel = viewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        bottomBar = {
            BottomBar(
                step = uiState.step,
                onBack = { viewModel.back() },
                onNext = {
                    if (uiState.step == STEP_COUNT - 1) {
                        viewModel.finish()
                        onFinish()
                    } else {
                        viewModel.next()
                    }
                },
            )
        }
    ) { padding ->
        Column(modifier = Modifier
            .fillMaxSize()
            .padding(padding)) {
            ProgressDots(current = uiState.step, total = STEP_COUNT)

            AnimatedContent(
                targetState = uiState.step,
                transitionSpec = {
                    val dir = if (targetState > initialState)
                        AnimatedContentTransitionScope.SlideDirection.Left
                    else
                        AnimatedContentTransitionScope.SlideDirection.Right
                    slideIntoContainer(dir, tween(300)) togetherWith
                        slideOutOfContainer(dir, tween(300))
                },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                label = "onboarding-step",
            ) { step ->
                when (step) {
                    0 -> WelcomeStep()
                    1 -> SleepTargetsStep(
                        bedtime = uiState.targetBedtime,
                        wakeTime = uiState.targetWakeTime,
                        onBedtimeChange = viewModel::setTargetBedtime,
                        onWakeChange = viewModel::setTargetWakeTime,
                    )
                    2 -> FocusPrefsStep(
                        work = uiState.workDuration,
                        breakMins = uiState.breakDuration,
                        onWorkChange = viewModel::setWorkDuration,
                        onBreakChange = viewModel::setBreakDuration,
                    )
                    else -> AllSetStep()
                }
            }
        }
    }
}

// ── Steps ───────────────────────────────────────────────────────────────

@Composable
private fun WelcomeStep() {
    StepContainer {
        MascotSleepingBook(size = 128.dp)
        Spacer(Modifier.height(24.dp))
        Text("Hi, I'm Ultiq", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(
            "Your daily companion for sleep, focus, and the in-between. Let's get you set up — takes about a minute.",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SleepTargetsStep(
    bedtime: LocalTime,
    wakeTime: LocalTime,
    onBedtimeChange: (LocalTime) -> Unit,
    onWakeChange: (LocalTime) -> Unit,
) {
    StepContainer {
        BigIcon(Icons.Default.Bedtime)
        Text("When do you sleep best?", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(
            "Pick the times you'd like to wind down and wake up. We'll quietly track how close you get.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        TimeRow(label = "Target bedtime", time = bedtime, onChange = onBedtimeChange)
        Spacer(Modifier.height(12.dp))
        TimeRow(label = "Target wake time", time = wakeTime, onChange = onWakeChange)
    }
}

@Composable
private fun FocusPrefsStep(
    work: Int,
    breakMins: Int,
    onWorkChange: (Int) -> Unit,
    onBreakChange: (Int) -> Unit,
) {
    StepContainer {
        BigIcon(Icons.Default.Timer)
        Text("How do you focus?", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(
            "Pick your default work + break lengths. Tweak them anytime — these are just starting points.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        StepperRow(label = "Work", value = work, suffix = "min", step = 5, range = 5..120, onChange = onWorkChange)
        Spacer(Modifier.height(12.dp))
        StepperRow(label = "Rest", value = breakMins, suffix = "min", step = 1, range = 1..60, onChange = onBreakChange)
    }
}

@Composable
private fun AllSetStep() {
    StepContainer {
        MascotSleepingBook(size = 128.dp)
        Spacer(Modifier.height(24.dp))
        Text("Pillow's ready", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(
            "You're set up. Targets, reminders, and lockout settings are all in Settings whenever you want to adjust.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ── Helpers ─────────────────────────────────────────────────────────────

@Composable
private fun StepContainer(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        content = content,
    )
}

@Composable
private fun BigIcon(icon: ImageVector) {
    Box(
        modifier = Modifier
            .size(96.dp)
            .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, modifier = Modifier.size(56.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
    }
    Spacer(Modifier.height(24.dp))
}

@Composable
private fun TimeRow(label: String, time: LocalTime, onChange: (LocalTime) -> Unit) {
    val context = LocalContext.current
    val fmt = DateTimeFormatter.ofPattern("h:mm a")
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.titleMedium)
        TextButton(onClick = {
            TimePickerDialog(
                context,
                { _, h, m -> onChange(LocalTime.of(h, m)) },
                time.hour, time.minute, false,
            ).show()
        }) {
            Text(time.format(fmt), style = MaterialTheme.typography.titleLarge)
        }
    }
}

@Composable
private fun StepperRow(
    label: String,
    value: Int,
    suffix: String,
    step: Int,
    range: IntRange,
    onChange: (Int) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.titleMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = { onChange((value - step).coerceAtLeast(range.first)) },
                enabled = value > range.first,
            ) { Icon(Icons.Default.Remove, "Decrease") }
            Text(
                "$value $suffix",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.width(96.dp),
                textAlign = TextAlign.Center,
            )
            IconButton(
                onClick = { onChange((value + step).coerceAtMost(range.last)) },
                enabled = value < range.last,
            ) { Icon(Icons.Default.Add, "Increase") }
        }
    }
}

@Composable
private fun ProgressDots(current: Int, total: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        repeat(total) { i ->
            val color = if (i == current) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surfaceVariant
            Box(
                modifier = Modifier
                    .padding(horizontal = 4.dp)
                    .size(8.dp)
                    .background(color, CircleShape)
            )
        }
    }
}

@Composable
private fun BottomBar(step: Int, onBack: () -> Unit, onNext: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(PaddingValues(horizontal = 24.dp, vertical = 16.dp)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        if (step > 0) {
            TextButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, null, Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("Back")
            }
        } else {
            Spacer(Modifier.width(80.dp))
        }
        Button(onClick = onNext) {
            Text(if (step == STEP_COUNT - 1) "Let's go" else "Continue")
        }
    }
}
