package com.app.productivity.ui.theme

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier

/**
 * Fade + slight slide-up on first composition.
 *
 * WHY: Dashboard/stats cards feel heavier when they appear instantly. A short
 * fade reduces perceived load. Wrapping once in a helper keeps screens clean.
 */
@Composable
fun AnimatedAppear(
    modifier: Modifier = Modifier,
    delayMillis: Int = 0,
    content: @Composable () -> Unit,
) {
    val state = remember {
        MutableTransitionState(initialState = false).apply { targetState = true }
    }
    AnimatedVisibility(
        visibleState = state,
        enter = fadeIn(tween(durationMillis = 250, delayMillis = delayMillis)) +
            slideInVertically(tween(durationMillis = 250, delayMillis = delayMillis)) { it / 8 },
        modifier = modifier,
    ) {
        content()
    }
}
