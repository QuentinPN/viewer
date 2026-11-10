package io.github.quentinpn.viewer.tv.ui

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import io.github.quentinpn.viewer.tv.emby.BaseItemDto

// Rangées de l'accueil (ADR 0094): each home row can be hidden from Réglages › Accueil.
// Kept on the device per Emby user, never on the server. The hero is not a row.

/** Stored key of the « Reprendre » row. */
internal const val RESUME_ROW = "resume"

/** Stored key of the « À suivre » row. */
internal const val NEXT_UP_ROW = "nextUp"

/** Stored key of the « Favoris » row. */
internal const val FAVORITES_ROW = "favorites"

/** Stored key of the « Listes de lecture » row. */
internal const val PLAYLISTS_ROW = "playlists"

/** Title of the « Reprendre » row. */
internal const val RESUME_SHELF = "Reprendre"

/** Title of the « À suivre » row. */
internal const val NEXT_UP_SHELF = "À suivre"

/** Stored key of a library's « Derniers ajouts » row: by id, so a renamed library keeps its choice. */
internal fun latestRowKey(libraryId: String) = "latest.$libraryId"

/** Title of a library's « Derniers ajouts » row. */
internal fun latestShelfTitle(library: Library) = "Derniers ajouts · ${library.name}"

/** A row the settings list: its stored [key] and the title the home shows. */
internal data class HomeRow(val key: String, val label: String)

/** Every row the home can show, in its order: the fixed rows around one « Derniers ajouts » per library. */
internal fun homeRows(libraries: List<Library>): List<HomeRow> = buildList {
    add(HomeRow(RESUME_ROW, RESUME_SHELF))
    add(HomeRow(NEXT_UP_ROW, NEXT_UP_SHELF))
    add(HomeRow(FAVORITES_ROW, FAVORITES_SHELF))
    for (library in libraries) add(HomeRow(latestRowKey(library.id), latestShelfTitle(library)))
    add(HomeRow(PLAYLISTS_ROW, PLAYLISTS_SHELF))
}

/** The film and series libraries of the user's views: those of the home bar and of the « Derniers ajouts » rows. */
internal fun homeLibraries(views: List<BaseItemDto>): List<Library> = views
    .filter { it.collectionType == "movies" || it.collectionType == "tvshows" }
    .map { Library(it.id, it.name, if (it.collectionType == "movies") "Movie" else "Series") }

/** Which rows are hidden; any other key, a new library's included, is shown. */
data class HomeRowVisibility(val hidden: Set<String> = emptySet()) {
    fun shows(key: String) = key !in hidden

    fun toggled(key: String) = HomeRowVisibility(if (shows(key)) hidden + key else hidden - key)

    /** The libraries whose « Derniers ajouts » row is shown: only these are requested. */
    internal fun latestLibraries(libraries: List<Library>) = libraries.filter { shows(latestRowKey(it.id)) }

    /** [shelves] without the hidden rows, e.g. those still on screen from before a setting changed. */
    internal fun visibleShelves(shelves: List<Shelf>) = shelves.filter { shows(rowKey(it)) }
}

/** The stored key of a home shelf. */
internal fun rowKey(shelf: Shelf): String = shelf.library?.let { latestRowKey(it.id) } ?: when (shelf.title) {
    RESUME_SHELF -> RESUME_ROW
    NEXT_UP_SHELF -> NEXT_UP_ROW
    FAVORITES_SHELF -> FAVORITES_ROW
    PLAYLISTS_SHELF -> PLAYLISTS_ROW
    else -> shelf.title
}

internal fun rowLabel(shown: Boolean) = if (shown) "Affichée" else "Masquée"

/** Where [HomeRowStore] keeps its sets: plain preferences on the device, a map in tests. */
interface StringSetPrefs {
    fun get(key: String): Set<String>?

    fun put(key: String, value: Set<String>)
}

private class SharedStringSetPrefs(private val prefs: SharedPreferences) : StringSetPrefs {
    // The returned set must not be changed: a copy is kept
    override fun get(key: String): Set<String>? = prefs.getStringSet(key, null)?.toSet()

    override fun put(key: String, value: Set<String>) = prefs.edit { putStringSet(key, value) }
}

/** The hidden home rows, per Emby user, in plain preferences: none is a secret. */
class HomeRowStore(private val prefs: StringSetPrefs, private val userId: String) {
    constructor(context: Context, userId: String) :
        this(SharedStringSetPrefs(context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)), userId)

    /** Every row shown when nothing is stored. Keys of libraries gone since stay, harmless, for when they come back. */
    fun load() = HomeRowVisibility(prefs.get(key()).orEmpty())

    /** Shows [rowKey] when hidden, else hides it, and returns the new state. */
    fun toggle(rowKey: String): HomeRowVisibility {
        val next = load().toggled(rowKey)
        prefs.put(key(), next.hidden)
        return next
    }

    private fun key() = "$userId.hidden"

    private companion object {
        const val PREFS = "home"
    }
}
