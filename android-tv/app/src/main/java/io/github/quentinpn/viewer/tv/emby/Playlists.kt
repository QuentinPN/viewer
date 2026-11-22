package io.github.quentinpn.viewer.tv.emby

// The user's playlists and their entries, read only.

/** Playlists on the home row: more would make a row nobody scrolls to the end of. */
internal const val PLAYLISTS_LIMIT = 50

/** Entries read for one playlist: one request, no paging. */
internal const val PLAYLIST_ITEMS_LIMIT = 300

/**
 * Query of `GET /Users/{UserId}/Items` for the user's playlists, A → Z, with their entry
 * count. No `MediaTypes`: on Emby 4.10.2.0 `MediaTypes=Video` next to
 * `IncludeItemTypes=Playlist` answered every video of the server (60 423 items) instead of
 * the playlists; audio ones are left out by [videoPlaylists].
 */
internal fun playlistsQuery(limit: Int = PLAYLISTS_LIMIT): List<Pair<String, String>> = listOf(
    "IncludeItemTypes" to "Playlist",
    "Recursive" to "true",
    "SortBy" to "SortName",
    "SortOrder" to "Ascending",
    "Limit" to limit.toString(),
    "Fields" to "ChildCount,PrimaryImageAspectRatio",
    "EnableUserData" to "false",
    "ImageTypeLimit" to "1",
    "EnableImageTypes" to "Primary",
)

/**
 * Query of `GET /Playlists/{Id}/Items` (`PlaylistService`), whose `UserId` is documented:
 * the entries in playlist order, with the 16:9 images and user data of the « Reprendre » cards.
 */
internal fun playlistItemsQuery(userId: String, limit: Int = PLAYLIST_ITEMS_LIMIT): List<Pair<String, String>> = listOf(
    "UserId" to userId,
    "Limit" to limit.toString(),
    "Fields" to "PrimaryImageAspectRatio",
    "EnableUserData" to "true",
    "ImageTypeLimit" to "1",
    "EnableImageTypes" to "Primary,Backdrop,Thumb",
)

/** Playlists of videos, once each, in the server's order; one without `MediaType` is kept. */
internal fun videoPlaylists(items: List<BaseItemDto>): List<BaseItemDto> =
    items.filter { it.type == "Playlist" && (it.mediaType == null || it.mediaType == "Video") }.distinctBy { it.id }

/**
 * Films and episodes of a playlist, in its order; anything else (a song, a trailer, a
 * folder) has no page to open. An item listed twice shows once, where it comes first.
 */
internal fun playlistEntries(items: List<BaseItemDto>): List<BaseItemDto> =
    items.filter { it.type == "Movie" || it.type == "Episode" }.distinctBy { it.id }
