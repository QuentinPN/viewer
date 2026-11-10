package io.github.quentinpn.viewer.tv.emby

// The user's favourite films and series on the home, read only (ADR 0091).

/** Favourites on the home row, as many as a "Derniers ajouts" shelf. */
internal const val FAVORITES_LIMIT = 16

/**
 * Query of `GET /Users/{UserId}/Items` for the user's favourite films and series, A → Z: the
 * Windows `favorites` query (ADR 0014). `Filters` comes from the grid's [ItemsOptions], so the
 * row and a grid filtered on « Favoris » ask the server the same thing.
 */
internal fun favoritesQuery(limit: Int = FAVORITES_LIMIT): List<Pair<String, String>> = listOf(
    "Filters" to ItemsOptions(favorites = true).filters()!!,
    "Recursive" to "true",
    "IncludeItemTypes" to "Movie,Series",
    "SortBy" to "SortName",
    "SortOrder" to "Ascending",
    "Limit" to limit.toString(),
    "Fields" to "PrimaryImageAspectRatio,ProductionYear",
    "EnableUserData" to "true",
    "ImageTypeLimit" to "1",
    "EnableImageTypes" to "Primary",
)

/**
 * Films and series of the answer, once each, in the server's order, at most [limit]: the
 * pages a card can open. An id that cannot go into an image path is left out, since the
 * card would fail to build its poster URL.
 */
internal fun favoriteTitles(items: List<BaseItemDto>, limit: Int = FAVORITES_LIMIT): List<BaseItemDto> =
    items.filter { (it.type == "Movie" || it.type == "Series") && isUsableId(it.id) }.distinctBy { it.id }.take(limit)

private fun isUsableId(id: String) = id.isNotEmpty() && id.all { it.isLetterOrDigit() && it.code < 128 || it == '-' }
