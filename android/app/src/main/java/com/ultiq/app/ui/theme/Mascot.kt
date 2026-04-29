package com.ultiq.app.ui.theme

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ultiq.app.R

/**
 * The Ultiq mascot — sleeping book on a pillow.
 *
 * Reuses the launcher foreground vector so the in-app character matches the
 * app icon. Wrap on the indigo brand surface to ground the character.
 */
@Composable
fun MascotSleepingBook(
    modifier: Modifier = Modifier,
    size: Dp = 96.dp,
    onIndigoSurface: Boolean = true,
) {
    if (onIndigoSurface) {
        Box(
            modifier = modifier
                .size(size)
                .background(Color(0xFF2A1B6E), RoundedCornerShape(percent = 22)),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painter = painterResource(R.drawable.ic_launcher_foreground),
                contentDescription = null,
                modifier = Modifier.size(size),
            )
        }
    } else {
        Image(
            painter = painterResource(R.drawable.ic_launcher_foreground),
            contentDescription = null,
            modifier = modifier.size(size),
        )
    }
}
