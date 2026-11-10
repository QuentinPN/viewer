package io.github.quentinpn.viewer.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.ImageLoader
import io.github.quentinpn.viewer.tv.emby.BaseItemDto
import io.github.quentinpn.viewer.tv.emby.EmbyClient
import io.github.quentinpn.viewer.tv.emby.EmbyError
import io.github.quentinpn.viewer.tv.emby.EmbyException
import io.github.quentinpn.viewer.tv.emby.personTitles

/** Grid items before the first poster: the person's header and the section title. */
private const val HEADER_ITEMS = 2

/**
 * A person (ADR 0068): photo, name, a short overview when the server has one, then their
 * films and series in the user's library, newest first, as the library grid's posters. OK
 * on a poster opens its page; Back from it focuses that poster again ([page] `opened`).
 */
@Composable
internal fun PersonScreen(
    client: EmbyClient,
    userId: String,
    page: DetailPage.Person,
    images: ImageLoader,
    onOpen: (BaseItemDto) -> Unit,
    onSessionExpired: () -> Unit,
) {
    val member = page.member
    var titles by remember { mutableStateOf<Load<List<BaseItemDto>>>(Load.Loading) }
    var overview by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }

    LaunchedEffect(attempt) {
        titles = Load.Loading
        titles = try {
            Load.Ready(personTitles(client.personItems(userId, member.id).items))
        } catch (e: EmbyException) {
            if (e.error == EmbyError.SessionExpired) onSessionExpired()
            Load.Failed(e.error.message)
        }
    }
    LaunchedEffect(Unit) {
        // Optional: without it the page shows the name and photo only
        overview = try {
            client.item(userId, member.id).overview?.trim()?.takeIf { it.isNotEmpty() }
        } catch (e: EmbyException) {
            if (e.error == EmbyError.SessionExpired) onSessionExpired()
            null
        }
    }

    val grid = rememberLazyGridState()
    val focus = remember { FocusRequester() }
    Box(Modifier.fillMaxSize().background(Palette.background)) {
        val loaded = (titles as? Load.Ready)?.value
        // The title opened last, else the newest one
        val target = loaded?.let { list -> page.opened?.takeIf { id -> list.any { it.id == id } } ?: list.firstOrNull()?.id }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(150.dp),
            state = grid,
            contentPadding = PaddingValues(start = 48.dp, end = 48.dp, top = 48.dp, bottom = 48.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) { Header(member, overview, client, images) }
            item(span = { GridItemSpan(maxLineSpan) }) {
                SectionHeader("Dans la bibliothèque", loaded?.size?.takeIf { it > 0 }, Modifier.padding(top = 16.dp))
            }
            when (val t = titles) {
                Load.Loading -> item(span = { GridItemSpan(maxLineSpan) }) { Text("Chargement…", color = Palette.textSecondary) }
                is Load.Failed -> item(span = { GridItemSpan(maxLineSpan) }) { Retry(t.message) { attempt++ } }
                is Load.Ready -> if (t.value.isEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Text("Aucun titre de cette personne dans la bibliothèque.", color = Palette.textSecondary)
                    }
                } else {
                    items(t.value, key = { it.id }) { item ->
                        val modifier = if (item.id == target) Modifier.focusRequester(focus) else Modifier
                        ItemCard(item, landscape = false, client, images, modifier, fill = true) { onOpen(item) }
                    }
                }
            }
        }
        LaunchedEffect(target) {
            val list = loaded ?: return@LaunchedEffect
            val index = list.indexOfFirst { it.id == target }
            if (index < 0) return@LaunchedEffect
            // An item off screen cannot take focus: brought in first, then focused once placed
            if (index > 0) grid.scrollToItem(index + HEADER_ITEMS)
            withFrameNanos {}
            runCatching { focus.requestFocus() }
        }
    }
}

@Composable
private fun Header(member: CastMember, overview: String?, client: EmbyClient, images: ImageLoader) {
    Row(horizontalArrangement = Arrangement.spacedBy(32.dp), verticalAlignment = Alignment.CenterVertically) {
        PersonPhoto(member, client, images, 160.dp)
        Column(Modifier.fillMaxWidth(0.7f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(member.name, style = MaterialTheme.typography.displaySmall, color = Palette.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            overview?.let {
                Text(it, color = Palette.textSecondary, maxLines = 4, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
