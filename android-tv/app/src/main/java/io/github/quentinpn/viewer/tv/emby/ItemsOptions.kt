package io.github.quentinpn.viewer.tv.emby

/** Grid sort keys (ADR 0013); ties fall back to the title. */
enum class SortKey(val sortBy: String) {
    Name("SortName"),
    DateAdded("DateCreated,SortName"),
    Year("ProductionYear,SortName"),
    Rating("CommunityRating,SortName"),
    ;

    /** Titles read A→Z by default, the others newest or best first. */
    val descendingByDefault get() = this != Name
}

/** What the grid shows from the user's watch state; [InProgress] applies to films only. */
enum class WatchFilter(val filter: String?) {
    All(null),
    Unplayed("IsUnplayed"),
    Played("IsPlayed"),
    InProgress("IsResumable"),
}

/** Sort and filters of a library grid, ported from the Windows client (ADR 0013). */
data class ItemsOptions(
    val sort: SortKey = SortKey.Name,
    val descending: Boolean = false,
    val watch: WatchFilter = WatchFilter.All,
    val favorites: Boolean = false,
    val genre: String? = null,
    /** First year of the decade shown, e.g. 1990 (ADR 0082); null for every year. */
    val decade: Int? = null,
) {
    /** The `Filters` value, or null when nothing filters. */
    fun filters(): String? =
        listOfNotNull(watch.filter, if (favorites) "IsFavorite" else null).joinToString(",").ifEmpty { null }

    /** The `Years` value, or null when the decade does not filter or cannot be asked for. */
    fun years(): String? = decade?.takeIf(::isUsableDecade)?.let(::decadeYears)

    /** True when something narrows the library, so an empty answer means "no match". */
    val narrowed get() = watch != WatchFilter.All || favorites || genre != null || decade != null
}

/** A genre name the server can be asked for: `|` separates genres in the query. */
fun isUsableGenre(name: String): Boolean =
    name.isNotBlank() && name.length <= 100 && name.none { it == '|' || it.isISOControl() }
