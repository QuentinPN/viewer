package io.github.quentinpn.viewer.tv.emby

// Recent searches: kept on the device only, never sent to the server.

/** Searches kept, newest first. */
const val SEARCH_HISTORY_MAX = 8

/**
 * [history] with [raw] first: cleaned as sent to the server, an older spelling of the
 * same term (case and accents aside) dropped, at most [SEARCH_HISTORY_MAX]. A term too
 * short to be searched leaves the history as it was.
 */
fun addSearch(history: List<String>, raw: String): List<String> {
    val query = cleanSearch(raw)
    if (!isSearchable(query)) return history
    val key = fold(query)
    return (listOf(query) + history.filter { fold(it) != key }).take(SEARCH_HISTORY_MAX)
}

/**
 * One search per line: cleaned text never holds a line break. Stored lines are read
 * through [addSearch] again, so a hand-edited or older value is cleaned the same way.
 */
fun encodeSearches(history: List<String>): String = history.joinToString("\n")

fun decodeSearches(stored: String?): List<String> =
    stored.orEmpty().split('\n').asReversed().fold(emptyList<String>(), ::addSearch)
