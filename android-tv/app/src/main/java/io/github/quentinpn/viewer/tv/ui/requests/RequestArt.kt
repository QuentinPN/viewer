package io.github.quentinpn.viewer.tv.ui.requests

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import coil3.ImageLoader
import coil3.compose.AsyncImage
import io.github.quentinpn.viewer.tv.ui.Palette
import io.github.quentinpn.viewer.tv.request.StatusLabel
import io.github.quentinpn.viewer.tv.request.Tone
import io.github.quentinpn.viewer.tv.request.imageUrl
import okhttp3.HttpUrl

/**
 * Posters and backdrops of Request: the [loader] adds the Bearer to [origin] only, and a
 * path that is not a plain TMDB image gives no URL (ADR 0125). Image URLs never carry the token.
 */
internal class RequestArt(val loader: ImageLoader, private val origin: HttpUrl?) {
    fun url(size: String, path: String?): String? = origin?.let { imageUrl(it, size, path)?.toString() }
}

/** A TMDB image at [size] (`w185`, `w342`, `w500`, `w780`, `w1280`); nothing when there is none. */
@Composable
internal fun RequestImage(art: RequestArt, path: String?, size: String, modifier: Modifier = Modifier, contentScale: ContentScale = ContentScale.Crop) {
    val url = art.url(size, path) ?: return
    AsyncImage(model = url, imageLoader = art.loader, contentDescription = null, contentScale = contentScale, modifier = modifier)
}

internal fun Tone.color(): Color = when (this) {
    Tone.Success -> Palette.success
    Tone.Accent -> Palette.accent
    Tone.Warning -> Palette.warning
    Tone.Danger -> Palette.danger
    Tone.Muted -> Palette.textSecondary
}

/** A status in a small dark pill, its text in the status' colour. */
@Composable
internal fun StatusPill(label: StatusLabel, modifier: Modifier = Modifier) {
    Box(modifier.background(Color.Black.copy(alpha = 0.72f), RoundedCornerShape(10.dp)).padding(horizontal = 8.dp, vertical = 3.dp)) {
        Text(label.text, color = label.tone.color(), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/** A poster cell without an image: the title on the card's surface. */
@Composable
internal fun PosterFallback(title: String) {
    Box(Modifier.fillMaxSize().padding(8.dp), contentAlignment = Alignment.Center) {
        Text(title, color = Palette.textSecondary, fontSize = 13.sp, maxLines = 4)
    }
}
