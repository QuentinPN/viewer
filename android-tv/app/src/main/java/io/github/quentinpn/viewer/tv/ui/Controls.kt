package io.github.quentinpn.viewer.tv.ui

import android.provider.Settings
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text

/** Fill of a control at rest, as the hero's buttons: DESIGN.md glass `rgb(120 120 128 / 0.36)`. */
internal val Glass = Color(0x5C787880)

/** Gap between a focused control and its accent ring, as on the hero's buttons. */
internal val RING_GAP = 3.dp

/** The system's "remove animations" setting: focused controls then keep their size, the ring stays. */
@Composable
internal fun reducedMotion(): Boolean {
    val context = LocalContext.current
    return remember {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
}

/**
 * A control in the home's style: glass at rest, white with black text when
 * [selected]; focused, white, slightly larger and ringed in the accent. A pill unless [shape]
 * says otherwise.
 */
@Composable
internal fun GlassButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    shape: Shape = CircleShape,
    height: Dp = 36.dp,
    /** False leaves it dimmed and out of the focus path. */
    enabled: Boolean = true,
    /** Dimmed like a disabled one and does nothing on OK, but stays on the focus path with its ring (a button that says why it is off). */
    dimmed: Boolean = false,
    content: @Composable RowScope.() -> Unit,
) {
    val still = reducedMotion()
    Surface(
        onClick = if (dimmed) ({}) else onClick,
        modifier = modifier.height(height),
        enabled = enabled,
        shape = ClickableSurfaceDefaults.shape(shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (dimmed) Glass.copy(alpha = 0.12f) else if (selected) Palette.text else Glass,
            contentColor = if (dimmed) Palette.textSecondary.copy(alpha = 0.7f) else if (selected) Color.Black else Palette.text,
            focusedContainerColor = Palette.text,
            focusedContentColor = Color.Black,
            pressedContainerColor = Palette.text,
            pressedContentColor = Color.Black,
            disabledContainerColor = Glass.copy(alpha = 0.12f),
            disabledContentColor = Palette.textSecondary.copy(alpha = 0.45f),
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = if (still) 1f else 1.06f),
        border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(2.dp, Palette.accent), inset = -RING_GAP, shape = shape)),
    ) {
        Row(
            Modifier.fillMaxHeight().padding(horizontal = 18.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}

/** A row's title, as the home's shelves, with its count dimmed beside it. */
@Composable
internal fun SectionHeader(title: String, count: Int?, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Bottom) {
        Text(title, style = MaterialTheme.typography.titleLarge, color = Palette.text)
        count?.let { Text(it.toString(), style = MaterialTheme.typography.titleMedium, color = Palette.textSecondary) }
    }
}

/**
 * The play row of a film or episode page: wider than its text column, over the backdrop, so a label never wraps
 * nor squeezes the rows under it; held within the screen's side margins, it scrolls sideways to a focused button
 * that would not fit.
 */
@Composable
internal fun Modifier.playRow(): Modifier {
    val screen = LocalConfiguration.current.screenWidthDp.dp
    return wrapContentWidth(Alignment.Start, unbounded = true)
        .widthIn(max = screen - PLAY_ROW_MARGINS)
        .horizontalScroll(rememberScrollState())
}

/** The pages' 48 dp side padding, on both sides. */
private val PLAY_ROW_MARGINS = 96.dp
