package io.github.quentinpn.viewer.tv.ui.requests

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Text
import io.github.quentinpn.viewer.tv.ui.Palette
import io.github.quentinpn.viewer.tv.ui.reducedMotion
import io.github.quentinpn.viewer.tv.request.MediaSummary
import io.github.quentinpn.viewer.tv.request.MediaType
import io.github.quentinpn.viewer.tv.request.mediaStatusLabel

/** « 2021 · Série » under a title: its year, then film or series. */
internal fun mediaMeta(media: MediaSummary): String =
    listOfNotNull(media.year?.toString(), if (media.type == MediaType.Movie) "Film" else "Série").joinToString(" · ")

/**
 * A poster, 2:3, with its status at the bottom left. Focused it lifts and takes the accent ring,
 * as the library's cards. [showTitle] adds the title and meta under it (the grids); the rows show
 * the focused card's under the row instead. [onFocused] is told when it takes the focus.
 */
@Composable
internal fun MediaCardTv(
    media: MediaSummary,
    art: RequestArt,
    modifier: Modifier = Modifier,
    showTitle: Boolean = false,
    onFocused: () -> Unit = {},
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(10.dp)
    val still = reducedMotion()
    // 10 dp under the poster: the focused one's scale and ring then stay clear of its caption
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Card(
            onClick = onClick,
            modifier = modifier.fillMaxWidth().aspectRatio(2f / 3f).onFocusChanged { if (it.isFocused) onFocused() },
            shape = CardDefaults.shape(shape),
            scale = CardDefaults.scale(focusedScale = if (still) 1f else 1.06f),
            border = CardDefaults.border(focusedBorder = Border(BorderStroke(3.dp, Palette.accent), shape = shape)),
            colors = CardDefaults.colors(containerColor = Palette.surface),
        ) {
            Box(Modifier.fillMaxSize()) {
                if (media.posterPath != null) {
                    RequestImage(art, media.posterPath, "w342", Modifier.fillMaxSize().clip(shape))
                } else {
                    PosterFallback(media.title)
                }
                media.status?.let { StatusPill(mediaStatusLabel(it, media.progress), Modifier.align(Alignment.BottomStart).padding(6.dp)) }
            }
        }
        if (showTitle) {
            Text(media.title, color = Palette.text, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(mediaMeta(media), color = Palette.textSecondary, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
