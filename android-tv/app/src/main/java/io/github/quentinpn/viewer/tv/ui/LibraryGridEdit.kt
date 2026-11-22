package io.github.quentinpn.viewer.tv.ui

import io.github.quentinpn.viewer.tv.emby.BaseItemDto
import io.github.quentinpn.viewer.tv.emby.ItemsOptions
import io.github.quentinpn.viewer.tv.emby.LetterStart
import io.github.quentinpn.viewer.tv.emby.UserItemData
import io.github.quentinpn.viewer.tv.emby.WatchFilter

// A card menu's write on the library grid (« Grille bibliothèque »): the card changes
// in place, or leaves the grid when its filters no longer hold it. The grid is not read again.

/**
 * True when an item with the user data [data] is still listed by a grid on [options]. Only the
 * watch filter and « Favoris » follow the user's data; genre, decade and studio do not change.
 */
internal fun stillMatches(data: UserItemData, options: ItemsOptions): Boolean {
    val watch = when (options.watch) {
        WatchFilter.All -> true
        WatchFilter.Unplayed -> !data.played
        WatchFilter.Played -> data.played
        WatchFilter.InProgress -> !data.played && data.playbackPositionTicks > 0
    }
    return watch && (!options.favorites || data.isFavorite)
}

/**
 * The server's new [data] on a card that had [old]. A series' episodes left are kept from the
 * card when the answer leaves them out, so its corner mark does not vanish.
 */
internal fun mergedUserData(old: UserItemData?, data: UserItemData): UserItemData =
    if (data.unplayedItemCount != null) data else data.copy(unplayedItemCount = old?.unplayedItemCount)

/** What a write does to the loaded grid. */
internal sealed interface GridChange {
    /** The card at [index] shows [item] from now on. */
    data class Updated(val index: Int, val item: BaseItemDto) : GridChange

    /** The card at [index] leaves; [focus] is the card that takes the focus, null when none is left. */
    data class Removed(val index: Int, val focus: String?) : GridChange

    /** The item is not in the loaded grid: nothing to change. */
    data object Absent : GridChange
}

/**
 * The change [data], the server's answer for [itemId], makes to the loaded [items] of a grid on
 * [options]: the card updated, or removed when it no longer matches. Focus then goes to the card
 * that took its place, else to the one before.
 */
internal fun gridChange(items: List<BaseItemDto>, itemId: String, data: UserItemData, options: ItemsOptions): GridChange {
    val index = items.indexOfFirst { it.id == itemId }
    if (index < 0) return GridChange.Absent
    val item = items[index]
    val merged = mergedUserData(item.userData, data)
    if (stillMatches(merged, options)) return GridChange.Updated(index, item.copy(userData = merged))
    val neighbour = items.getOrNull(index + 1) ?: items.getOrNull(index - 1)
    return GridChange.Removed(index, neighbour?.id)
}

/**
 * The letter index once the title at [position] left the grid, which now holds [total] titles:
 * later starts move up by one. A letter that started there starts on the title now at
 * [position] when no other letter starts there; else, or past the end, it has no title left.
 * That title is [next] when loaded; otherwise the removed id stays, and a jump finds the title
 * by its position.
 */
internal fun lettersAfterRemoval(
    letters: Map<Char, LetterStart>,
    position: Int,
    removedId: String,
    next: String?,
    total: Int,
): Map<Char, LetterStart> {
    val nextStartsLetter = letters.values.any { it.position == position + 1 }
    return letters.mapNotNull { (letter, start) ->
        when {
            start.id == removedId || start.position == position ->
                if (nextStartsLetter || position >= total) null else letter to LetterStart(position, next ?: removedId)
            start.position > position -> letter to start.copy(position = start.position - 1)
            else -> letter to start
        }
    }.toMap()
}
