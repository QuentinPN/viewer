package io.github.quentinpn.viewer.tv.session

import android.content.Context
import androidx.core.content.edit
import io.github.quentinpn.viewer.tv.emby.addSearch
import io.github.quentinpn.viewer.tv.emby.decodeSearches
import io.github.quentinpn.viewer.tv.emby.encodeSearches

/**
 * Recent searches (ADR 0075), per server and Emby user, in plain preferences: search
 * terms are not a secret, and nothing else (never the token) is kept here.
 */
class SearchHistoryStore(context: Context, private val server: String, private val userId: String) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(): List<String> = decodeSearches(prefs.getString(key(), null))

    /** Puts [query] first and returns the new history. */
    fun add(query: String): List<String> {
        val next = addSearch(load(), query)
        prefs.edit { putString(key(), encodeSearches(next)) }
        return next
    }

    fun clear() {
        prefs.edit { remove(key()) }
    }

    private fun key() = "$server|$userId.recent"

    private companion object {
        const val PREFS = "search"
    }
}
