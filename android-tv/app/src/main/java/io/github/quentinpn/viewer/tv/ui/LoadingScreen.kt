package io.github.quentinpn.viewer.tv.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import io.github.quentinpn.viewer.tv.brand.rememberLogo

/** The home's first load: the app's logo over a spinning ring, until the shelves are read. */
@Composable
internal fun LoadingScreen() {
    Column(
        Modifier.fillMaxSize().background(Palette.background).semantics { contentDescription = "Chargement" },
        verticalArrangement = Arrangement.spacedBy(32.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(painterResource(rememberLogo()), contentDescription = null, modifier = Modifier.size(120.dp))
        Spinner()
        Text("Chargement…", color = Palette.textSecondary)
    }
}

/** A ring with an accent arc turning once a second; still when the system turns animations off. */
@Composable
internal fun Spinner(modifier: Modifier = Modifier) {
    val turn by rememberInfiniteTransition(label = "spinner").animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(1000, easing = LinearEasing), RepeatMode.Restart),
        label = "turn",
    )
    Canvas(modifier.size(40.dp).rotate(turn)) {
        val stroke = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round)
        drawArc(Palette.surfaceRaised, 0f, 360f, useCenter = false, style = stroke)
        drawArc(Palette.accent, -90f, 90f, useCenter = false, style = stroke)
    }
}
