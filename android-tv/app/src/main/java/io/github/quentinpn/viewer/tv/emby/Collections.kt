package io.github.quentinpn.viewer.tv.emby

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

// The collections (Emby BoxSets) of a film and their titles (ADR 0071).

/** Collection rows on one film page: a film in more would push « Titres similaires » far down. */
internal const val COLLECTIONS_LIMIT = 3

/** Titles read for one collection: one request, more than any saga holds. */
internal const val COLLECTION_TITLES_LIMIT = 100

/** One entry of an item's `Collections` (`NameLongIdPair`): the BoxSet's id and name. */
data class CollectionRef(val id: String, val name: String)

/**
 * The collections of an item's `Collections`, documented as `NameLongIdPair[]`: `Id` is a
 * number there, read as any primitive like a person's. Anything else in that field, an entry
 * without a name or with an id `EmbyClient.segment` would refuse, is left out rather than
 * making the film page unreadable; duplicates too.
 */
internal fun collectionRefs(raw: JsonElement?, limit: Int = COLLECTIONS_LIMIT): List<CollectionRef> {
    val entries = raw as? JsonArray ?: return emptyList()
    return entries.asSequence()
        .mapNotNull { entry ->
            val fields = entry as? JsonObject ?: return@mapNotNull null
            val id = (fields["Id"] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content?.trim()
                ?.takeIf { it.isNotEmpty() && it.all { c -> c.isLetterOrDigit() && c.code < 128 || c == '-' } }
                ?: return@mapNotNull null
            val name = (fields["Name"] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content?.trim()
                ?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            CollectionRef(id, name)
        }
        .distinctBy { it.id }
        .take(limit)
        .toList()
}

/**
 * Query of `GET /Users/{UserId}/Items` for the titles of the BoxSet [collectionId], oldest
 * first, with the posters and user data of the library grid. `ParentId`, `SortBy`
 * (`PremiereDate`, `ProductionYear`, `SortName`) and `SortOrder` are documented
 * `ItemsService` parameters; a BoxSet is the folder of its titles.
 */
internal fun collectionItemsQuery(collectionId: String, limit: Int = COLLECTION_TITLES_LIMIT): List<Pair<String, String>> = listOf(
    "ParentId" to collectionId,
    "SortBy" to "PremiereDate,ProductionYear,SortName",
    "SortOrder" to "Ascending",
    "Limit" to limit.toString(),
    "Fields" to "PrimaryImageAspectRatio,ProductionYear",
    "EnableUserData" to "true",
    "ImageTypeLimit" to "1",
    "EnableImageTypes" to "Primary",
)

/**
 * Films and series only, once each, in release order: the release date, else the year, and
 * titles with neither last; same date, A → Z. The server sorts already: this keeps the order
 * whatever it does with missing dates.
 */
internal fun collectionTitles(items: List<BaseItemDto>): List<BaseItemDto> =
    items.filter { it.type == "Movie" || it.type == "Series" }
        .distinctBy { it.id }
        .sortedWith(
            compareBy<BaseItemDto> { releaseKey(it) == null }
                .thenBy { releaseKey(it) }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name },
        )

/** "2001-12-19" from the ISO date, else "2001" from the year: a year sorts before its own dates. */
private fun releaseKey(item: BaseItemDto): String? =
    item.premiereDate?.take(10)?.takeIf { it.length == 10 } ?: item.productionYear?.toString()?.padStart(4, '0')
