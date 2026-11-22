package io.github.quentinpn.viewer.tv.emby

// The titles of one person in the user's library.

/** Titles read for a person page: one request, enough for any filmography in a home library. */
internal const val PERSON_TITLES_LIMIT = 100

/**
 * Query of `GET /Users/{UserId}/Items` for [personId]'s films and series, newest first, with
 * the posters and user data of the library grid. `PersonIds`, `Recursive`, `IncludeItemTypes`,
 * `SortBy` (`ProductionYear`, `SortName`) and `SortOrder` are documented `ItemsService`
 * parameters; the server sorts so that [PERSON_TITLES_LIMIT] keeps the newest ones.
 */
internal fun personItemsQuery(personId: String, limit: Int = PERSON_TITLES_LIMIT): List<Pair<String, String>> = listOf(
    "PersonIds" to personId,
    "Recursive" to "true",
    "IncludeItemTypes" to "Movie,Series",
    "SortBy" to "ProductionYear,SortName",
    "SortOrder" to "Descending",
    "Limit" to limit.toString(),
    "Fields" to "PrimaryImageAspectRatio,ProductionYear",
    "EnableUserData" to "true",
    "ImageTypeLimit" to "1",
    "EnableImageTypes" to "Primary",
)

/**
 * Films and series only, once each, newest year first and titles without a year last; one
 * `SortOrder` applies to every `SortBy` key, so the server would also put names Z → A within
 * a year: the client sorts them A → Z again.
 */
internal fun personTitles(items: List<BaseItemDto>): List<BaseItemDto> =
    items.filter { it.type == "Movie" || it.type == "Series" }
        .distinctBy { it.id }
        .sortedWith(
            compareBy<BaseItemDto> { it.productionYear == null }
                .thenByDescending { it.productionYear ?: 0 }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name },
        )
