package io.github.quentinpn.viewer.tv.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Text
import coil3.ImageLoader
import coil3.compose.AsyncImage
import io.github.quentinpn.viewer.tv.emby.BaseItemDto
import io.github.quentinpn.viewer.tv.emby.EmbyClient
import io.github.quentinpn.viewer.tv.emby.communityRatingLabel

/** The line under a grid card's title: the year, then the community rating when the server sent one. */
internal fun cardFacts(item: BaseItemDto): String? =
    listOfNotNull(item.productionYear?.toString(), communityRatingLabel(item.communityRating)).joinToString(" · ").ifEmpty { null }

/** The corner mark's text: a check once watched, else the episodes left, at most « 99+ ». */
internal fun badgeText(badge: PosterBadge): String = when (badge) {
    PosterBadge.Played -> "✓"
    is PosterBadge.Unplayed -> if (badge.count > 99) "99+" else badge.count.toString()
}

/**
 * A poster of the library grid (ADR 0099): as wide as its cell, 2:3, the corner mark top right,
 * then one line of title and one of facts in smaller type than the home's cards.
 */
@Composable
internal fun LibraryCard(item: BaseItemDto, client: EmbyClient, images: ImageLoader, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    val still = reducedMotion()
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Card(
            onClick = onClick,
            modifier = modifier.fillMaxWidth().aspectRatio(2f / 3f),
            shape = CardDefaults.shape(shape),
            scale = CardDefaults.scale(focusedScale = if (still) 1f else 1.06f),
            border = CardDefaults.border(focusedBorder = Border(BorderStroke(3.dp, Palette.accent), shape = shape)),
            colors = CardDefaults.colors(containerColor = Palette.surface),
        ) {
            Box(Modifier.fillMaxSize()) {
                val tag = item.imageTags["Primary"]
                if (tag != null) {
                    AsyncImage(
                        model = client.imageUrl(item.id, "Primary", tag, 300).toString(),
                        imageLoader = images,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize().clip(shape),
                    )
                } else {
                    Box(Modifier.fillMaxSize().padding(8.dp), contentAlignment = Alignment.Center) {
                        Text(item.name, color = Palette.textSecondary, fontSize = 13.sp, maxLines = 4, overflow = TextOverflow.Ellipsis)
                    }
                }
                Progress(item)
                badge(item)?.let { mark ->
                    Box(
                        Modifier.align(Alignment.TopEnd).padding(6.dp).defaultMinSize(minWidth = 22.dp, minHeight = 22.dp)
                            .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(11.dp)).padding(horizontal = 6.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(badgeText(mark), color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
        Text(item.name, color = Palette.text, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        cardFacts(item)?.let { Text(it, color = Palette.textSecondary, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
}
