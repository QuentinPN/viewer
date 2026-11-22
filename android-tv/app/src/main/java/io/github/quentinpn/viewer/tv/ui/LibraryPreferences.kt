package io.github.quentinpn.viewer.tv.ui

import android.content.Context
import io.github.quentinpn.viewer.tv.emby.ItemsOptions
import io.github.quentinpn.viewer.tv.emby.SortKey
import io.github.quentinpn.viewer.tv.emby.WatchFilter
import io.github.quentinpn.viewer.tv.emby.isUsableDecade
import io.github.quentinpn.viewer.tv.emby.isUsableGenre

/** Sort and filters kept per Emby user and per library, in plain preferences: none is a secret. */
class LibraryPreferenceStore(context: Context, private val userId: String) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Stored choices, defaults for anything missing or no longer valid. */
    fun load(library: Library): ItemsOptions {
        val key = key(library)
        val sort = prefs.getString("$key.sort", null)?.let { name -> SortKey.entries.firstOrNull { it.name == name } } ?: SortKey.Name
        val watch = prefs.getString("$key.watch", null)?.let { name -> WatchFilter.entries.firstOrNull { it.name == name } }
            ?.takeIf { it in watchFilters(library.itemTypes) } ?: WatchFilter.All
        return ItemsOptions(
            sort = sort,
            descending = prefs.getBoolean("$key.descending", sort.descendingByDefault),
            watch = watch,
            favorites = prefs.getBoolean("$key.favorites", false),
            genre = prefs.getString("$key.genre", null)?.takeIf(::isUsableGenre),
            decade = prefs.getInt("$key.decade", NO_DECADE).takeIf(::isUsableDecade),
        )
    }

    fun save(library: Library, options: ItemsOptions) {
        val key = key(library)
        prefs.edit()
            .putString("$key.sort", options.sort.name)
            .putBoolean("$key.descending", options.descending)
            .putString("$key.watch", options.watch.name)
            .putBoolean("$key.favorites", options.favorites)
            .putString("$key.genre", options.genre)
            .putInt("$key.decade", options.decade ?: NO_DECADE)
            .apply()
    }

    private fun key(library: Library) = "$userId.${library.id}"

    private companion object {
        const val PREFS = "library"

        /** Stored for « Toutes » : never a usable decade. */
        const val NO_DECADE = -1
    }
}

/** The « Années » pill: « Toutes » or the decade's first year. */
internal fun decadePillLabel(decade: Int?): String = "Années : ${decade ?: "Toutes"}"

/** A line of the decade list: « Toutes les années », then « Années 1990 ». */
internal fun decadeChoiceLabel(decade: Int?): String = if (decade == null) "Toutes les années" else "Années $decade"

/** "En cours" only makes sense for films: a series is never resumed as a whole. */
internal fun watchFilters(itemTypes: String): List<WatchFilter> =
    if (itemTypes == "Movie") WatchFilter.entries else WatchFilter.entries - WatchFilter.InProgress

/** The next sort key, with that key's usual direction. */
internal fun ItemsOptions.nextSort(): ItemsOptions {
    val next = SortKey.entries[(sort.ordinal + 1) % SortKey.entries.size]
    return copy(sort = next, descending = next.descendingByDefault)
}

internal fun ItemsOptions.nextWatch(itemTypes: String): ItemsOptions {
    val choices = watchFilters(itemTypes)
    return copy(watch = choices[(choices.indexOf(watch) + 1) % choices.size])
}
