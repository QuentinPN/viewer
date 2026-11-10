package io.github.quentinpn.viewer.tv.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.ImageLoader
import coil3.compose.AsyncImage
import io.github.quentinpn.viewer.tv.emby.BaseItemDto
import io.github.quentinpn.viewer.tv.emby.EmbyClient
import kotlinx.coroutines.launch
import java.util.Locale

// The « Distribution » row of the film, series and episode pages, and the navigation to a
// person page (ADR 0068).

/** People in the row: a screen and a half of cards, as the similar titles. */
internal const val CAST_LIMIT = 12

/** One card of the row, and what its person page shows before its own read; public as [SeriesTarget], a page parameter. */
data class CastMember(val id: String, val name: String, val role: String?, val imageTag: String?)

/**
 * The people of the row, in the server's billing order: actors of a film or series, guest
 * stars of an episode (its series' cast is on the series page, as the former « Invités » line).
 * A person without a usable id has no page to open and no photo: left out, as duplicates.
 */
internal fun castMembers(item: BaseItemDto, limit: Int = CAST_LIMIT): List<CastMember> {
    val type = if (item.type == "Episode") "GuestStar" else "Actor"
    return item.people.asSequence()
        .filter { it.type == type }
        .mapNotNull { person ->
            val id = person.id?.takeIf(::isPersonId) ?: return@mapNotNull null
            val name = person.name.trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            CastMember(id, name, person.role?.trim()?.takeIf { it.isNotEmpty() }, person.primaryImageTag?.takeIf { it.isNotBlank() })
        }
        .distinctBy { it.id }
        .take(limit)
        .toList()
}

/** The ids `EmbyClient.segment` accepts in a path: checked here so a card never fails on OK. */
private fun isPersonId(id: String) = id.all { it.isLetterOrDigit() && it.code < 128 || it == '-' }

/** "DV" for Denis Villeneuve, "Z" for Zendaya: first and last words' first letters, "?" without any. */
internal fun initials(name: String): String {
    val letters = name.split(Regex("\\s+")).mapNotNull { word -> word.firstOrNull { it.isLetterOrDigit() } }
    val picked = when (letters.size) {
        0 -> return "?"
        1 -> listOf(letters[0])
        else -> listOf(letters.first(), letters.last())
    }
    return picked.joinToString("").uppercase(Locale.FRANCE)
}

/**
 * [page] remembering what to focus when it shows again: the person card left for a person
 * page, or on a person page the title opened from it. Null brings back its usual focus.
 */
internal fun remembering(page: DetailPage, focus: String?): DetailPage = when (page) {
    is DetailPage.Movie -> page.copy(person = focus, collection = null, genre = null)
    is DetailPage.Series -> page.copy(person = focus, genre = null)
    is DetailPage.Episode -> page.copy(person = focus)
    is DetailPage.Person -> page.copy(opened = focus)
    // The grid keeps the card opened from it in its memory
    is DetailPage.Grid -> page
}

/**
 * [page] over [pages], the page below remembering [focus]. A similar title or an episode
 * passes none, so a person card focused earlier does not take the focus back later.
 */
internal fun openOver(pages: List<DetailPage>, page: DetailPage, focus: String? = null): List<DetailPage> {
    val below = pages.lastOrNull()?.let { replaceTop(pages, remembering(it, focus)) } ?: pages
    return pushPage(below, page)
}

/** Back from an episode: its series page, on the [target] season and card, pages below kept (ADR 0060). */
internal fun backFromEpisode(pages: List<DetailPage>, target: SeriesTarget): List<DetailPage> {
    val below = pages.dropLast(1)
    return if (below.lastOrNull() is DetailPage.Series) replaceTop(below, DetailPage.Series(target)) else below + DetailPage.Series(target)
}

/**
 * What keys a page's composition: a fresh one per page, but one episode page across its
 * previous / next moves, which keep the button used focused (ADR 0060).
 */
internal fun pageKey(page: DetailPage): Any = if (page is DetailPage.Episode) DetailPage.Episode::class else page

/** A round photo with the initials under it: they show while it loads, and stay when there is none. */
@Composable
internal fun PersonPhoto(member: CastMember, client: EmbyClient, images: ImageLoader, size: Dp) {
    Box(Modifier.size(size).clip(CircleShape).background(Palette.surfaceRaised), contentAlignment = Alignment.Center) {
        Text(
            initials(member.name),
            color = Palette.textSecondary,
            style = if (size >= 120.dp) MaterialTheme.typography.displaySmall else MaterialTheme.typography.headlineSmall,
        )
        member.imageTag?.let { tag ->
            AsyncImage(
                // Portrait photos: the top keeps the face in the circle
                model = client.imageUrl(member.id, "Primary", tag, 320).toString(),
                imageLoader = images,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                alignment = Alignment.TopCenter,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/**
 * « Distribution »: a card per person, OK opening their page; nothing at all without people.
 * [focus] is the card a person page was opened from: it takes the focus again once shown.
 */
@Composable
internal fun CastRow(
    people: List<CastMember>,
    client: EmbyClient,
    images: ImageLoader,
    focus: String?,
    onOpen: (CastMember) -> Unit,
) {
    if (people.isEmpty()) return
    val start = people.indexOfFirst { it.id == focus }
    // Starts scrolled to that card: an item off screen cannot take focus
    val row = remember { LazyListState(firstVisibleItemIndex = start.coerceAtLeast(0)) }
    val requester = remember { FocusRequester() }
    // Back from a person page, the rows above load after the focus and push the card off screen:
    // it is brought into view again at each move until the focus leaves it (seen on a series page)
    val bring = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()
    var settling by remember { mutableStateOf(start >= 0) }
    var held by remember { mutableStateOf(false) }
    val still = reducedMotion()
    Column(Modifier.padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Distribution", style = MaterialTheme.typography.titleLarge, color = Palette.text, modifier = Modifier.padding(horizontal = 48.dp))
        // Entered from above or below: the card focused last, else the first one, not the nearest one
        LazyRow(state = row, modifier = Modifier.focusRestorer(requester), contentPadding = PaddingValues(horizontal = 48.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            itemsIndexed(people, key = { _, it -> it.id }) { index, member ->
                Column(Modifier.width(132.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // The ring and scale of the glass controls (ADR 0067), the size kept with reduced motion
                    Surface(
                        onClick = { onOpen(member) },
                        modifier = (if (index == start.coerceAtLeast(0)) Modifier.focusRequester(requester) else Modifier)
                            .then(
                                if (index != start) {
                                    Modifier
                                } else {
                                    Modifier.bringIntoViewRequester(bring)
                                        .onFocusChanged { if (it.isFocused) held = true else if (held) settling = false }
                                        .onGloballyPositioned { if (settling && held) scope.launch { bring.bringIntoView() } }
                                },
                            )
                            .size(112.dp),
                        shape = ClickableSurfaceDefaults.shape(CircleShape),
                        colors = ClickableSurfaceDefaults.colors(containerColor = Palette.surface, focusedContainerColor = Palette.surface),
                        scale = ClickableSurfaceDefaults.scale(focusedScale = if (still) 1f else 1.06f),
                        border = ClickableSurfaceDefaults.border(
                            focusedBorder = Border(BorderStroke(3.dp, Palette.accent), inset = -RING_GAP, shape = CircleShape),
                        ),
                    ) {
                        PersonPhoto(member, client, images, 112.dp)
                    }
                    Text(member.name, color = Palette.text, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                    member.role?.let {
                        Text(it, color = Palette.textSecondary, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                    }
                }
            }
        }
    }
    if (start >= 0) {
        // Back from that person's page; one frame later, once laid out
        LaunchedEffect(Unit) {
            withFrameNanos {}
            runCatching { requester.requestFocus() }
        }
    }
}
