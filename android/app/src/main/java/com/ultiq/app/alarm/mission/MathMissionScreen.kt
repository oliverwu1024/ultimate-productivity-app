package com.ultiq.app.alarm.mission

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.random.Random

private const val MAX_INPUT_DIGITS = 5

/**
 * Math dismiss mission UI (§8.7). User must answer [targetCount] problems
 * **in a row** without error. A wrong answer flashes the input red, drops
 * the entered digits, and generates a new problem — but does NOT decrement
 * the streak (so the user can keep trying without being punished further).
 */
@Composable
fun MathMissionScreen(
    difficulty: MathDifficulty,
    targetCount: Int,
    onComplete: () -> Unit,
    random: Random = Random.Default,
) {
    var problem by remember { mutableStateOf(MathProblemGenerator.generate(difficulty, random)) }
    var input by remember { mutableStateOf("") }
    var correct by remember { mutableIntStateOf(0) }
    var wrongFlash by remember { mutableStateOf(false) }

    val flashColor by animateColorAsState(
        targetValue = if (wrongFlash) MaterialTheme.colorScheme.error
        else MaterialTheme.colorScheme.surfaceVariant,
        animationSpec = tween(durationMillis = 250),
        label = "wrong-flash",
    )

    LaunchedEffect(wrongFlash) {
        if (wrongFlash) {
            delay(400)
            wrongFlash = false
        }
    }

    fun submit() {
        val typed = input.toIntOrNull() ?: return
        if (typed == problem.answer) {
            correct += 1
            if (correct >= targetCount) {
                onComplete()
                return
            }
            problem = MathProblemGenerator.generate(difficulty, random)
            input = ""
        } else {
            wrongFlash = true
            problem = MathProblemGenerator.generate(difficulty, random)
            input = ""
        }
    }

    fun pressDigit(d: Int) {
        if (input.length >= MAX_INPUT_DIGITS) return
        if (input == "0") input = d.toString() else input += d.toString()
    }

    fun pressBackspace() {
        if (input.isNotEmpty()) input = input.dropLast(1)
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(PaddingValues(horizontal = 24.dp, vertical = 32.dp)),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = "Solve to dismiss · $correct / $targetCount",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
            )

            Spacer(Modifier.height(8.dp))

            Text(
                text = problem.display,
                fontSize = 56.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(8.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(72.dp)
                    .background(flashColor, RoundedCornerShape(16.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (input.isEmpty()) "—" else input,
                    fontSize = 36.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (wrongFlash) MaterialTheme.colorScheme.onError
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(8.dp))

            Keypad(
                onDigit = ::pressDigit,
                onBackspace = ::pressBackspace,
                onSubmit = ::submit,
                submitEnabled = input.isNotEmpty(),
            )
        }
    }
}

@Composable
private fun Keypad(
    onDigit: (Int) -> Unit,
    onBackspace: () -> Unit,
    onSubmit: () -> Unit,
    submitEnabled: Boolean,
) {
    val rows = listOf(
        listOf(KeyKind.Digit(1), KeyKind.Digit(2), KeyKind.Digit(3)),
        listOf(KeyKind.Digit(4), KeyKind.Digit(5), KeyKind.Digit(6)),
        listOf(KeyKind.Digit(7), KeyKind.Digit(8), KeyKind.Digit(9)),
        listOf(KeyKind.Backspace, KeyKind.Digit(0), KeyKind.Submit),
    )
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        rows.forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                row.forEach { kind ->
                    KeyButton(
                        kind = kind,
                        onDigit = onDigit,
                        onBackspace = onBackspace,
                        onSubmit = onSubmit,
                        submitEnabled = submitEnabled,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

private sealed interface KeyKind {
    data class Digit(val value: Int) : KeyKind
    data object Backspace : KeyKind
    data object Submit : KeyKind
}

@Composable
private fun KeyButton(
    kind: KeyKind,
    onDigit: (Int) -> Unit,
    onBackspace: () -> Unit,
    onSubmit: () -> Unit,
    submitEnabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val containerColor = when (kind) {
        is KeyKind.Submit -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val contentColor = when (kind) {
        is KeyKind.Submit -> MaterialTheme.colorScheme.onPrimary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val label = when (kind) {
        is KeyKind.Digit -> kind.value.toString()
        KeyKind.Backspace -> "⌫"
        KeyKind.Submit -> "✓"
    }
    val onClick: () -> Unit = when (kind) {
        is KeyKind.Digit -> { -> onDigit(kind.value) }
        KeyKind.Backspace -> onBackspace
        KeyKind.Submit -> onSubmit
    }
    val enabled = kind !is KeyKind.Submit || submitEnabled

    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(60.dp),
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = containerColor,
            contentColor = contentColor,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        ),
    ) {
        Text(label, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
    }
}
