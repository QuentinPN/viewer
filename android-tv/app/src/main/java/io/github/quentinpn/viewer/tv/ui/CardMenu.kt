package io.github.quentinpn.viewer.tv.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.ImageLoader
import coil3.compose.AsyncImage
import io.github.quentinpn.viewer.tv.emby.BaseItemDto
import io.github.quentinpn.viewer.tv.emby.EmbyClient
import io.github.quentinpn.viewer.tv.emby.EmbyError
import io.github.quentinpn.viewer.tv.emby.EmbyException
import io.github.quentinpn.viewer.tv.emby.resumeTicks
import io.github.quentinpn.viewer.tv.emby.videoPlaylists
import kotlinx.coroutines.launch

// A card's menu (ADR 0098, after ADR 0097): a panel laid over the card, as on Emby for
// Android TV, with play, « Ajouter à une playlist », the watched mark and the favourite.

/** Where a card's menu is opened: [resumeShelf] adds « Retirer de Reprendre ». */
internal data class MenuContext(val resumeShelf: Boolean = false)

/** The small icon on the right of a menu row. */
internal enum class MenuIcon { Play, PlaylistAdd, Check, Heart, Trash }

/** One row of a card's menu. */
internal sealed interface MenuAction {
    val label: String
    val icon: MenuIcon

    /** Plays [itemId] from [startTicks]: « Reprendre » from a resume point, else « Lire ». */
    data class Play(val itemId: String, val startTicks: Long) : MenuAction {
        override val label get() = if (startTicks > 0) "Reprendre" else "Lire"
        override val icon get() = MenuIcon.Play
    }

    /** A series: its first episode not watched yet, as the hero plays it. */
    data object PlayNextEpisode : MenuAction {
        override val label = "Lire le prochain épisode"
        override val icon = MenuIcon.Play
    }

    data object AddToPlaylist : MenuAction {
        override val label = "Ajouter à une playlist"
        override val icon = MenuIcon.PlaylistAdd
    }

    /** A write on the user's data (ADR 0097). */
    data class Write(val action: ItemAction) : MenuAction {
        override val label get() = action.label
        override val icon get() = when (action) {
            ItemAction.MarkPlayed, ItemAction.MarkUnplayed -> MenuIcon.Check
            ItemAction.AddFavorite, ItemAction.RemoveFavorite -> MenuIcon.Heart
            ItemAction.RemoveFromResume -> MenuIcon.Trash
        }
    }
}

/**
 * The menu of [item], in its order: play, « Ajouter à une playlist », the watched mark, the
 * favourite, then « Retirer de Reprendre » on that shelf only. A season has no play row (no
 * episode of its own to pick here); no « Tout lire » nor « Aléatoire », since the player has no
 * queue (ADR 0088). None for a playlist or anything else without user data.
 */
internal fun menuActions(item: BaseItemDto, context: MenuContext = MenuContext()): List<MenuAction> {
    val play = when (item.type) {
        "Movie", "Episode" -> MenuAction.Play(item.id, resumeTicks(item))
        "Series" -> MenuAction.PlayNextEpisode
        "Season" -> null
        else -> return emptyList()
    }
    return listOfNotNull(
        play,
        MenuAction.AddToPlaylist,
        MenuAction.Write(playedAction(item)),
        MenuAction.Write(favoriteAction(item)),
        MenuAction.Write(ItemAction.RemoveFromResume).takeIf { context.resumeShelf },
    )
}

/** What « Lire le prochain épisode » plays from the answer of `firstUnplayedEpisode`: from its resume point; null when none is left. */
internal fun nextEpisodePlay(episodes: List<BaseItemDto>): MenuAction.Play? =
    episodes.firstOrNull { it.type == "Episode" && it.id.isNotEmpty() }?.let { MenuAction.Play(it.id, resumeTicks(it)) }

/** The panel's pages; Back goes to [previous], and closes the menu from [Actions]. */
internal enum class MenuPage {
    Actions,
    Playlists,
    NewPlaylist,
    ;

    val previous: MenuPage?
        get() = when (this) {
            Actions -> null
            Playlists -> Actions
            NewPlaylist -> Playlists
        }
}

/** Longest playlist name sent; the TV keyboard makes a longer one unlikely anyway. */
internal const val PLAYLIST_NAME_MAX = 100

/** The name typed for a new playlist, trimmed and cut at [PLAYLIST_NAME_MAX]; null when blank. */
internal fun playlistName(input: String): String? = input.trim().take(PLAYLIST_NAME_MAX).trim().takeIf { it.isNotEmpty() }

/** The name a new playlist's field starts with: the series of an episode, else the item's own name. */
internal fun defaultPlaylistName(item: BaseItemDto): String = menuTitle(item).take(PLAYLIST_NAME_MAX)

/** Shown once an item is in [playlist]. */
internal fun addedTo(playlist: String): String = "Ajouté à $playlist"

/** The French message of a failed playlist call; the panel stays open. */
internal fun playlistFailed(error: EmbyError): String = "Ajout impossible. ${error.message}"

/** The panel's header title: an episode under its series. */
internal fun menuTitle(item: BaseItemDto): String = if (item.type == "Episode") item.seriesName ?: item.name else item.name

/** The line under it: « S1 · É3 — name » for an episode, else the year. */
internal fun menuSubtitle(item: BaseItemDto): String? = when (item.type) {
    "Episode" -> listOfNotNull(
        item.parentIndexNumber?.let { s -> item.indexNumber?.let { e -> "S$s · É$e" } },
        item.name.takeIf { item.seriesName != null },
    ).joinToString(" — ").takeIf { it.isNotEmpty() }
    else -> item.productionYear?.toString()
}

/**
 * Where the panel goes: centred over its card ([anchor]), top on the card's top, then moved
 * just enough to stay [margin] inside the window, so the first card of a row gets it at the
 * left edge and a low card gets it higher up.
 */
internal fun menuOffset(anchor: IntRect, window: IntSize, popup: IntSize, margin: Int): IntOffset {
    val x = (anchor.left + (anchor.width - popup.width) / 2).coerceIn(margin, maxOf(margin, window.width - popup.width - margin))
    val y = anchor.top.coerceIn(margin, maxOf(margin, window.height - popup.height - margin))
    return IntOffset(x, y)
}

private class CardMenuPosition(private val margin: Int) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize) =
        menuOffset(anchorBounds, windowSize, popupContentSize, margin)
}

/** The panel's dark glass and its rows' shape. */
private val PanelColor = Color(0xEB1C1C1E)
private val RowShape = RoundedCornerShape(50)

/**
 * A card's menu (ADR 0098), opened by a long press on OK on a card it is placed in (a `Box`
 * around the card: the popup is anchored on it). Each row runs at once or keeps the panel
 * open while the server answers, with its message on failure. « Ajouter à une playlist »
 * turns the panel into the user's playlists, then into a name field for a new one; Back goes
 * up one page and closes it from the first.
 *
 * [onPlay] starts playback as the card does; [perform] sends a write and updates the host's
 * view, throwing [EmbyException] on failure; [onPlaylistChanged] follows an item added to a
 * playlist, so the host reads its playlists again.
 */
@Composable
internal fun CardMenu(
    item: BaseItemDto,
    actions: List<MenuAction>,
    client: EmbyClient,
    userId: String,
    images: ImageLoader,
    onDismiss: () -> Unit,
    onPlay: (itemId: String, startTicks: Long) -> Unit,
    onSessionExpired: () -> Unit,
    onPlaylistChanged: () -> Unit,
    perform: suspend (ItemAction) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val margin = with(LocalDensity.current) { 24.dp.roundToPx() }
    val first = remember { FocusRequester() }
    val playlistRow = remember { FocusRequester() }
    var page by remember { mutableStateOf(MenuPage.Actions) }
    // Back from the playlists: the focus goes back on « Ajouter à une playlist »
    var cameBack by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var playlists by remember { mutableStateOf<Load<List<BaseItemDto>>>(Load.Loading) }
    var name by remember { mutableStateOf(defaultPlaylistName(item)) }

    fun go(to: MenuPage, back: Boolean = false) {
        message = null
        cameBack = back
        page = to
    }

    /** Runs [block] once at a time; a failure shows its message and leaves the panel as it is. */
    fun run(block: suspend () -> Unit) {
        // Pressed again while the server answers: nothing more is sent
        if (busy) return
        busy = true
        message = null
        scope.launch {
            try {
                block()
            } catch (e: EmbyException) {
                if (e.error == EmbyError.SessionExpired) onSessionExpired()
                message = if (page == MenuPage.Actions) actionFailed(e.error) else playlistFailed(e.error)
            } finally {
                busy = false
            }
        }
    }

    fun added(playlist: String) {
        Toast.makeText(context, addedTo(playlist), Toast.LENGTH_SHORT).show()
        onPlaylistChanged()
        onDismiss()
    }

    Popup(
        popupPositionProvider = remember(margin) { CardMenuPosition(margin) },
        // Back: up one page, and closes the menu from the first
        onDismissRequest = { page.previous?.let { go(it, back = true) } ?: onDismiss() },
        properties = PopupProperties(focusable = true),
    ) {
        Column(
            Modifier.width(296.dp).background(PanelColor, RoundedCornerShape(16.dp)).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            MenuHeader(item, client, images)
            when (page) {
                MenuPage.Actions -> actions.forEachIndexed { index, action ->
                    val focus = when {
                        action == MenuAction.AddToPlaylist -> Modifier.focusRequester(playlistRow)
                        else -> Modifier
                    }.then(if (index == 0) Modifier.focusRequester(first) else Modifier)
                    MenuRow(action.label, action.icon, busy, focus) {
                        when (action) {
                            is MenuAction.Play -> {
                                if (busy) return@MenuRow
                                onDismiss()
                                onPlay(action.itemId, action.startTicks)
                            }
                            MenuAction.PlayNextEpisode -> run {
                                val next = nextEpisodePlay(client.firstUnplayedEpisode(userId, item.id).items)
                                if (next == null) {
                                    message = "Aucun épisode à voir dans cette série."
                                } else {
                                    onDismiss()
                                    onPlay(next.itemId, next.startTicks)
                                }
                            }
                            MenuAction.AddToPlaylist -> if (!busy) go(MenuPage.Playlists)
                            is MenuAction.Write -> run {
                                perform(action.action)
                                onDismiss()
                            }
                        }
                    }
                }
                MenuPage.Playlists -> {
                    MenuRow("Nouvelle playlist…", MenuIcon.PlaylistAdd, busy, Modifier.focusRequester(first)) {
                        if (!busy) go(MenuPage.NewPlaylist)
                    }
                    when (val list = playlists) {
                        Load.Loading -> Note("Chargement…")
                        is Load.Failed -> Note(list.message, Palette.error)
                        is Load.Ready -> if (list.value.isEmpty()) {
                            Note("Aucune playlist pour l'instant.")
                        } else {
                            LazyColumn(Modifier.heightIn(max = 280.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                items(list.value, key = { it.id }) { playlist ->
                                    MenuRow(playlist.name, null, busy, Modifier) {
                                        run {
                                            client.addToPlaylist(userId, playlist.id, item.id)
                                            added(playlist.name)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                MenuPage.NewPlaylist -> {
                    NameField(name, { name = it }, Modifier.focusRequester(first))
                    MenuRow("Créer", MenuIcon.PlaylistAdd, busy, Modifier) {
                        val chosen = playlistName(name)
                        if (chosen == null) {
                            message = "Saisissez un nom."
                        } else {
                            run {
                                val created = client.createPlaylist(userId, chosen, item.id)
                                added(created.name?.takeIf { it.isNotBlank() } ?: chosen)
                            }
                        }
                    }
                }
            }
            message?.let {
                Text(it, color = Palette.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
            }
        }
    }

    // The user's playlists, read each time their page opens: one may have been made elsewhere
    LaunchedEffect(page) {
        if (page != MenuPage.Playlists) return@LaunchedEffect
        if (playlists !is Load.Ready) playlists = Load.Loading
        playlists = try {
            Load.Ready(videoPlaylists(client.playlists(userId).items))
        } catch (e: EmbyException) {
            if (e.error == EmbyError.SessionExpired) onSessionExpired()
            Load.Failed(playlistFailed(e.error))
        }
    }
    // The first row of each page takes the focus; « Ajouter à une playlist » on the way back
    LaunchedEffect(page) {
        withFrameNanos {}
        runCatching { (if (page == MenuPage.Actions && cameBack) playlistRow else first).requestFocus() }
    }
}

@Composable
private fun MenuHeader(item: BaseItemDto, client: EmbyClient, images: ImageLoader) {
    Row(
        Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // An episode's own image is a 16:9 still; everything else has a poster
        val wide = item.type == "Episode"
        item.imageTags["Primary"]?.let { tag ->
            AsyncImage(
                model = client.imageUrl(item.id, "Primary", tag, if (wide) 240 else 120).toString(),
                imageLoader = images,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(if (wide) 96.dp else 48.dp, if (wide) 54.dp else 72.dp).clip(RoundedCornerShape(6.dp)),
            )
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(menuTitle(item), color = Palette.text, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            menuSubtitle(item)?.let {
                Text(it, color = Palette.textSecondary, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/**
 * A full-width row: its label on the left, its icon on the right; a white pill with dark text
 * when focused, plain text otherwise. Dimmed while the server answers, still focusable so the
 * focus does not jump.
 */
@Composable
private fun MenuRow(label: String, icon: MenuIcon?, busy: Boolean, modifier: Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val ink = if (focused) Color.Black else Palette.text
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused }.alpha(if (busy) 0.5f else 1f),
        shape = ClickableSurfaceDefaults.shape(RowShape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            contentColor = Palette.text,
            focusedContainerColor = Palette.text,
            focusedContentColor = Color.Black,
            pressedContainerColor = Palette.text,
            pressedContentColor = Color.Black,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, color = ink, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            icon?.let { Icon(it.vector(), contentDescription = null, tint = ink, modifier = Modifier.size(18.dp)) }
        }
    }
}

/** A line of the playlists page that is not a row: loading, none, or the reason they did not load. */
@Composable
private fun Note(text: String, color: Color = Palette.textSecondary) {
    Text(text, color = color, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
}

/**
 * The new playlist's name, in a pill like the search field; OK opens the TV keyboard, whose
 * « OK » key goes down to « Créer ».
 */
@Composable
private fun NameField(value: String, onChange: (String) -> Unit, modifier: Modifier) {
    var focused by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val ink = if (focused) Color.Black else Palette.text
    Box(
        Modifier.fillMaxWidth().padding(bottom = 4.dp)
            .background(if (focused) Palette.text else Glass, CircleShape)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        BasicTextField(
            value = value,
            onValueChange = { onChange(it.take(PLAYLIST_NAME_MAX)) },
            singleLine = true,
            textStyle = TextStyle(color = ink, fontSize = 15.sp),
            cursorBrush = SolidColor(ink),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { focusManager.moveFocus(FocusDirection.Down) }),
            modifier = modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused },
        )
    }
}

private fun MenuIcon.vector(): ImageVector = when (this) {
    MenuIcon.Play -> PlayIcon
    MenuIcon.PlaylistAdd -> PlaylistAddIcon
    MenuIcon.Check -> CheckIcon
    MenuIcon.Heart -> HeartIcon
    MenuIcon.Trash -> TrashIcon
}

/** Stroked 24-unit icons of the menu rows, tinted by the row. */
private fun menuIcon(name: String, vararg paths: String): ImageVector =
    ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
        for (d in paths) {
            addPath(
                pathData = addPathNodes(d),
                stroke = SolidColor(Color.White),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
    }.build()

/** Three lines of a list and a plus. */
private val PlaylistAddIcon = menuIcon("playlist-add", "M3 6h12", "M3 12h12", "M3 18h8", "M18 13v8", "M14 17h8")

private val CheckIcon = menuIcon("check", "M4 12.5l5 5L20 6.5")

private val HeartIcon = menuIcon(
    "heart",
    "M12 20.5s-7.5-4.6-7.5-10.2A4.3 4.3 0 0 1 12 7.6a4.3 4.3 0 0 1 7.5 2.7c0 5.6-7.5 10.2-7.5 10.2z",
)

private val TrashIcon = menuIcon("trash", "M4 7h16", "M9 7V4.5h6V7", "M6 7l1 13h10l1-13", "M10 11v6", "M14 11v6")
