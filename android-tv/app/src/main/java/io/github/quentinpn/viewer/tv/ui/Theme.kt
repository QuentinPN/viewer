package io.github.quentinpn.viewer.tv.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

// DESIGN.md tokens: black canvas, grey surfaces, white primary action, accent for focus only
object Palette {
    val background = Color.Black
    val surface = Color(0xFF1C1C1E)
    val surfaceRaised = Color(0xFF2C2C2E)
    val text = Color(0xFFF5F5F7)
    val textSecondary = Color(0xB3EBEBF5)
    val accent = Color(0xFF8FD3F5)
    val error = Color(0xFFFF8A80)
    // Status colours of the requests (DESIGN.md): done, in progress or waiting, refused
    val success = Color(0xFF64C69A)
    val warning = Color(0xFFF5C451)
    val danger = Color(0xFFFF6961)
}

@Composable
fun ViewerTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color.White,
            onPrimary = Color.Black,
            background = Palette.background,
            onBackground = Palette.text,
            surface = Palette.surface,
            onSurface = Palette.text,
            surfaceVariant = Palette.surfaceRaised,
            onSurfaceVariant = Palette.textSecondary,
            border = Palette.accent,
            error = Palette.error,
        ),
        content = content,
    )
}
