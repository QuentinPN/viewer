package io.github.quentinpn.viewer.tv.emby

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.util.Locale

// Search, ported from the Windows client (ADR 0011, 0020, src-tauri/src/emby/session.rs).

/** Results shown per type; one more is asked to know if more exist. */
const val SEARCH_SHOWN = 24

/**
 * Episodes asked per search: Emby also matches the series name, so the episodes
 * of a matching series come first and are dropped here.
 */
const val SEARCH_EPISODES_ASKED = 100

/** Shorter queries are not sent: the server answers nothing to one letter. */
const val SEARCH_MIN_CHARS = 2
const val SEARCH_MAX_CHARS = 100

/** [query] is the cleaned text the results answer, to drop a stale response. */
data class SearchResults(
    val query: String,
    val movies: List<BaseItemDto> = emptyList(),
    val series: List<BaseItemDto> = emptyList(),
    val episodes: List<BaseItemDto> = emptyList(),
    val moreMovies: Boolean = false,
    val moreSeries: Boolean = false,
    val moreEpisodes: Boolean = false,
) {
    val isEmpty get() = movies.isEmpty() && series.isEmpty() && episodes.isEmpty()
}

/** Search text as sent to the server: no control characters, single spaces, at most [SEARCH_MAX_CHARS] code points. */
fun cleanSearch(raw: String): String {
    val words = raw
        .filter { it.isWhitespace() || !it.isISOControl() }
        .map { if (it.isWhitespace()) ' ' else it }
        .joinToString("")
        .split(' ')
        .filter { it.isNotEmpty() }
        .joinToString(" ")
    val points = words.codePoints().limit(SEARCH_MAX_CHARS.toLong()).toArray()
    return String(points, 0, points.size).trimEnd()
}

/** Long enough to be sent to the server. */
fun isSearchable(cleaned: String): Boolean = cleaned.codePointCount(0, cleaned.length) >= SEARCH_MIN_CHARS

/**
 * Whether [query] starts a word of [title], ignoring case and accents, as Emby
 * matches titles. Used on episodes, which the server also finds by their series name.
 */
fun titleMatches(title: String, query: String): Boolean {
    val t = fold(title)
    val q = fold(query)
    if (q.isEmpty()) return false
    return t.indices.any { i -> (i == 0 || !t[i - 1].isLetterOrDigit()) && t.startsWith(q, i) }
}

private val ACCENTED = listOf(
    "àáâãäåāăą" to 'a',
    "çćĉċč" to 'c',
    "ďđ" to 'd',
    "èéêëēĕėęě" to 'e',
    "ĝğġģ" to 'g',
    "ĥħ" to 'h',
    "ìíîïĩīĭįı" to 'i',
    "ĵ" to 'j',
    "ķ" to 'k',
    "ĺļľŀł" to 'l',
    "ñńņňŉ" to 'n',
    "òóôõöøōŏő" to 'o',
    "ŕŗř" to 'r',
    "śŝşšſ" to 's',
    "ţťŧ" to 't',
    "ùúûüũūŭůűų" to 'u',
    "ŵ" to 'w',
    "ýÿŷ" to 'y',
    "źżž" to 'z',
)

/** Lower case without the accents of Latin letters: `É` and `é` become `e`; `œ` stays. */
private fun fold(text: String): String = buildString {
    for (c in text.lowercase(Locale.ROOT)) append(ACCENTED.firstOrNull { c in it.first }?.second ?: c)
}

/**
 * Films, series and episodes whose title matches [raw], all searched at once. A query
 * shorter than two characters once cleaned reaches no server.
 */
suspend fun searchTitles(client: EmbyClient, userId: String, raw: String): SearchResults = coroutineScope {
    val query = cleanSearch(raw)
    if (!isSearchable(query)) return@coroutineScope SearchResults(query)
    val asked = SEARCH_SHOWN + 1
    val movies = async { client.search(userId, query, "Movie", asked).items }
    val series = async { client.search(userId, query, "Series", asked).items }
    val episodes = async { client.search(userId, query, "Episode", SEARCH_EPISODES_ASKED).items }
    fun keep(items: List<BaseItemDto>, type: String) = items.filter { it.type == type }
    val foundMovies = keep(movies.await(), "Movie")
    val foundSeries = keep(series.await(), "Series")
    // Only episodes whose own title matches: a full page may hide others
    val page = episodes.await()
    val foundEpisodes = keep(page, "Episode").filter { titleMatches(it.name, query) }
    SearchResults(
        query = query,
        movies = foundMovies.take(SEARCH_SHOWN),
        series = foundSeries.take(SEARCH_SHOWN),
        episodes = foundEpisodes.take(SEARCH_SHOWN),
        moreMovies = foundMovies.size > SEARCH_SHOWN,
        moreSeries = foundSeries.size > SEARCH_SHOWN,
        moreEpisodes = foundEpisodes.size > SEARCH_SHOWN || page.size >= SEARCH_EPISODES_ASKED,
    )
}
