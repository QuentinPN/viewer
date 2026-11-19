package io.github.quentinpn.viewer.tv.request

/** One choice of a filter pill: what the list shows, and the filters it sets. */
data class FilterChoice(val label: String, val apply: (BrowseFilters) -> BrowseFilters)

private val SORTS = listOf("popularity" to "Popularité", "rating" to "Note", "release" to "Date de sortie", "title" to "Titre")

private class YearRange(val label: String, val from: Int?, val to: Int?)

private val YEARS = listOf(
    YearRange("Toutes", null, null),
    YearRange("2020 et après", 2020, null),
    YearRange("2010 – 2019", 2010, 2019),
    YearRange("2000 – 2009", 2000, 2009),
    YearRange("1990 – 1999", 1990, 1999),
    YearRange("Avant 1990", null, 1989),
)

private val RATINGS = listOf<Int?>(null, 6, 7, 8)

fun sortLabel(filters: BrowseFilters): String = SORTS.firstOrNull { it.first == (filters.sort ?: "popularity") }?.second ?: "Popularité"

fun yearsLabel(filters: BrowseFilters): String =
    YEARS.firstOrNull { it.from == filters.yearFrom && it.to == filters.yearTo }?.label
        ?: when {
            filters.yearFrom != null && filters.yearTo != null -> "${filters.yearFrom} – ${filters.yearTo}"
            filters.yearFrom != null -> "${filters.yearFrom} et après"
            else -> "Jusqu'à ${filters.yearTo}"
        }

fun ratingLabel(filters: BrowseFilters): String = filters.rating?.let { "$it+" } ?: "Toutes"

/** « Tous », the genre's name when one is set, else how many. [genres] is empty until the list is read. */
fun genreLabel(filters: BrowseFilters, genres: List<Genre>): String {
    val ids = filters.genres.orEmpty()
    return when (ids.size) {
        0 -> "Tous"
        1 -> genres.firstOrNull { it.id == ids.single() }?.name ?: "1 genre"
        else -> "${ids.size} genres"
    }
}

fun sortChoices(): List<FilterChoice> = SORTS.map { (wire, label) -> FilterChoice(label) { it.copy(sort = wire) } }

fun yearChoices(): List<FilterChoice> = YEARS.map { range -> FilterChoice(range.label) { it.copy(yearFrom = range.from, yearTo = range.to) } }

fun ratingChoices(): List<FilterChoice> = RATINGS.map { rating -> FilterChoice(rating?.let { "$it+" } ?: "Toutes") { it.copy(rating = rating) } }

/** « Tous », then each genre: one at a time, as the pill shows. */
fun genreChoices(genres: List<Genre>): List<FilterChoice> =
    listOf(FilterChoice("Tous") { it.copy(genres = null) }) + genres.map { genre -> FilterChoice(genre.name) { it.copy(genres = listOf(genre.id)) } }

/** Index of the choice [filters] currently hold in [choices], by applying each to the defaults and comparing the relevant part. */
fun selectedChoice(choices: List<FilterChoice>, filters: BrowseFilters, same: (BrowseFilters, BrowseFilters) -> Boolean): Int =
    choices.indexOfFirst { same(it.apply(BrowseFilters()), filters) }.coerceAtLeast(0)

fun sameSort(a: BrowseFilters, b: BrowseFilters) = (a.sort ?: "popularity") == (b.sort ?: "popularity")

fun sameYears(a: BrowseFilters, b: BrowseFilters) = a.yearFrom == b.yearFrom && a.yearTo == b.yearTo

fun sameRating(a: BrowseFilters, b: BrowseFilters) = a.rating == b.rating

fun sameGenres(a: BrowseFilters, b: BrowseFilters) = a.genres.orEmpty() == b.genres.orEmpty()
