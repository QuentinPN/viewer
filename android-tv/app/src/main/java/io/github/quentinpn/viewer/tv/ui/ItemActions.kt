package io.github.quentinpn.viewer.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import io.github.quentinpn.viewer.tv.emby.BaseItemDto
import io.github.quentinpn.viewer.tv.emby.EmbyClient
import io.github.quentinpn.viewer.tv.emby.EmbyError
import io.github.quentinpn.viewer.tv.emby.EmbyException
import io.github.quentinpn.viewer.tv.emby.UserItemData
import kotlinx.coroutines.launch

// Write actions on the user's data (ADR 0097): watched mark, favourite, « Reprendre ».

/** One write a card's menu or a detail page offers. */
internal enum class ItemAction(val label: String) {
    RemoveFromResume("Retirer de Reprendre"),
    MarkPlayed("Marquer comme vu"),
    MarkUnplayed("Marquer comme non vu"),
    AddFavorite("Ajouter aux favoris"),
    RemoveFavorite("Retirer des favoris"),
}

/** Types whose watched mark and favourite the server keeps. */
private val ACTION_TYPES = setOf("Movie", "Episode", "Series", "Season")

/** The watched-mark action [item] offers: the opposite of its current mark. */
internal fun playedAction(item: BaseItemDto): ItemAction =
    if (item.userData?.played == true) ItemAction.MarkUnplayed else ItemAction.MarkPlayed

/** The favourite action [item] offers: the opposite of its current state. */
internal fun favoriteAction(item: BaseItemDto): ItemAction =
    if (item.userData?.isFavorite == true) ItemAction.RemoveFavorite else ItemAction.AddFavorite

/**
 * The menu of a home card, in its order: « Retirer de Reprendre » on that shelf only, then the
 * watched mark and the favourite. None for a playlist or anything else without user data.
 */
internal fun cardActions(item: BaseItemDto, resumeShelf: Boolean): List<ItemAction> {
    if (item.type !in ACTION_TYPES) return emptyList()
    return listOfNotNull(ItemAction.RemoveFromResume.takeIf { resumeShelf }, playedAction(item), favoriteAction(item))
}

/** Sends [action] for [itemId]; the server answers with the item's new user data. */
internal suspend fun EmbyClient.perform(userId: String, itemId: String, action: ItemAction): UserItemData = when (action) {
    ItemAction.RemoveFromResume -> hideFromResume(userId, itemId, hide = true)
    ItemAction.MarkPlayed -> setPlayed(userId, itemId, played = true)
    ItemAction.MarkUnplayed -> setPlayed(userId, itemId, played = false)
    ItemAction.AddFavorite -> setFavorite(userId, itemId, favorite = true)
    ItemAction.RemoveFavorite -> setFavorite(userId, itemId, favorite = false)
}

/** The short French message of a failed action; the previous state stays. */
internal fun actionFailed(error: EmbyError): String = "Action impossible. ${error.message}"

/**
 * The home's shelves once [action] has succeeded for [itemId], before the home is read again:
 * the item's new [data] on each of its cards. It leaves « Reprendre » when taken out of it or
 * now watched (the server lists only items in progress), and « Favoris » when no longer a
 * favourite. A new favourite waits for the reload; an emptied shelf goes.
 */
internal fun shelvesAfter(shelves: List<Shelf>, itemId: String, action: ItemAction, data: UserItemData): List<Shelf> =
    shelves.mapNotNull { shelf ->
        val leaves = when {
            shelf.resume -> action == ItemAction.RemoveFromResume || data.played
            shelf.title == FAVORITES_SHELF -> !data.isFavorite
            else -> false
        }
        val items = if (leaves) {
            shelf.items.filter { it.id != itemId }
        } else {
            shelf.items.map { if (it.id == itemId) it.copy(userData = data) else it }
        }
        if (items.isEmpty()) null else shelf.copy(items = items)
    }

/**
 * The card to focus after an action on [itemId] in [shelfTitle]: itself when still there, else
 * the card that took its place, else the one before; null when the shelf is gone, so the home
 * falls back on its usual target.
 */
internal fun focusAfter(before: List<Shelf>, after: List<Shelf>, shelfTitle: String, itemId: String): Pair<String, String>? {
    val now = after.firstOrNull { it.title == shelfTitle } ?: return null
    if (now.items.any { it.id == itemId }) return shelfTitle to itemId
    val old = before.firstOrNull { it.title == shelfTitle }?.items.orEmpty()
    val at = old.indexOfFirst { it.id == itemId }
    if (at < 0) return shelfTitle to now.items.first().id
    val ids = now.items.map { it.id }.toSet()
    val next = old.drop(at + 1).firstOrNull { it.id in ids } ?: old.take(at).lastOrNull { it.id in ids }
    return shelfTitle to (next ?: now.items.first()).id
}

/**
 * A card's menu (ADR 0097), opened by a long press on OK: one button per action, the first
 * focused. Stays open while the server answers, with its message on failure; Back closes it
 * and the focus goes back to the card.
 */
@Composable
internal fun CardMenu(
    item: BaseItemDto,
    actions: List<ItemAction>,
    onDismiss: () -> Unit,
    /** Sends the action and updates the home; throws [EmbyException] on failure. */
    perform: suspend (ItemAction) -> Unit,
) {
    val first = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    Popup(alignment = Alignment.Center, onDismissRequest = onDismiss, properties = PopupProperties(focusable = true)) {
        Column(
            Modifier.widthIn(min = 260.dp, max = 360.dp).background(Palette.surface, RoundedCornerShape(12.dp)).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                if (item.type == "Episode") item.seriesName ?: item.name else item.name,
                color = Palette.textSecondary,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            actions.forEachIndexed { index, action ->
                OutlinedButton(
                    onClick = {
                        // Pressed again while the server answers: nothing more is sent
                        if (busy) return@OutlinedButton
                        busy = true
                        message = null
                        scope.launch {
                            try {
                                perform(action)
                                onDismiss()
                            } catch (e: EmbyException) {
                                message = actionFailed(e.error)
                            } finally {
                                busy = false
                            }
                        }
                    },
                    modifier = (if (index == 0) Modifier.focusRequester(first) else Modifier).alpha(if (busy) 0.5f else 1f),
                ) {
                    Text(action.label, maxLines = 1)
                }
            }
            message?.let { Text(it, color = Palette.error, style = MaterialTheme.typography.bodySmall) }
        }
    }
    LaunchedEffect(Unit) {
        withFrameNanos {}
        runCatching { first.requestFocus() }
    }
}

/**
 * A button of a detail page sending [action] for [item] (ADR 0097). While the server answers
 * it is dimmed and a press does nothing; it stays focusable, so the focus does not jump.
 * [onDone] gets the new user data; a failure goes to [onMessage] and changes nothing.
 */
@Composable
internal fun ActionButton(
    item: BaseItemDto,
    action: ItemAction,
    client: EmbyClient,
    userId: String,
    onSessionExpired: () -> Unit,
    onMessage: (String?) -> Unit,
    label: String = action.label,
    onDone: (UserItemData) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    OutlinedButton(
        onClick = {
            if (busy) return@OutlinedButton
            busy = true
            onMessage(null)
            scope.launch {
                try {
                    onDone(client.perform(userId, item.id, action))
                } catch (e: EmbyException) {
                    if (e.error == EmbyError.SessionExpired) onSessionExpired() else onMessage(actionFailed(e.error))
                } finally {
                    busy = false
                }
            }
        },
        modifier = Modifier.alpha(if (busy) 0.5f else 1f),
    ) {
        Text(label, maxLines = 1)
    }
}

/** « Marquer comme vu / non vu » and « Ajouter aux / Retirer des favoris » for [item], side by side. */
@Composable
internal fun UserDataButtons(
    item: BaseItemDto,
    client: EmbyClient,
    userId: String,
    onSessionExpired: () -> Unit,
    onMessage: (String?) -> Unit,
    onPlayed: (UserItemData) -> Unit,
    onFavorite: (UserItemData) -> Unit,
) {
    ActionButton(item, playedAction(item), client, userId, onSessionExpired, onMessage, onDone = onPlayed)
    ActionButton(item, favoriteAction(item), client, userId, onSessionExpired, onMessage, onDone = onFavorite)
}
