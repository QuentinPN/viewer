package io.github.quentinpn.viewer.tv.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import kotlinx.coroutines.launch

/**
 * Distance to scroll so an item at [offset] of [size] shows in a container of [containerSize]:
 * nothing when it already shows, else just enough to bring its nearer edge in; one larger
 * than the container shows from its start.
 */
internal fun scrollNeeded(offset: Float, size: Float, containerSize: Float): Float {
    val end = offset + size
    return when {
        offset >= 0f && end <= containerSize -> 0f
        offset < 0f || size > containerSize -> offset
        else -> end - containerSize
    }
}

@OptIn(ExperimentalFoundationApi::class)
private val asNeeded = object : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float =
        scrollNeeded(offset, size, containerSize)
}

/**
 * A detail page's scrolling column: it moves only when the focus leaves the screen, where the
 * TV default would bring each focused row up to a fixed line, pushing the page's title off the
 * top. The rows inside keep the default.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ScrollingPage(
    scroll: ScrollState,
    modifier: Modifier = Modifier,
    /** Inside the scroll, as a padding that scrolls away with the content. */
    inner: Modifier = Modifier,
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    content: @Composable ColumnScope.() -> Unit,
) {
    val default = LocalBringIntoViewSpec.current
    CompositionLocalProvider(LocalBringIntoViewSpec provides asNeeded) {
        Column(modifier.verticalScroll(scroll).then(inner), verticalArrangement = verticalArrangement) {
            CompositionLocalProvider(LocalBringIntoViewSpec provides default) { content() }
        }
    }
}

/**
 * Back on the part holding the page's title, which takes no focus, the page returns to its
 * top; one frame later, so the focused element's own bring-into-view does not cancel it.
 */
@Composable
internal fun Modifier.topOnFocus(scroll: ScrollState): Modifier {
    val scope = rememberCoroutineScope()
    return onFocusChanged {
        if (it.hasFocus) {
            scope.launch {
                withFrameNanos {}
                scroll.animateScrollTo(0)
            }
        }
    }
}
