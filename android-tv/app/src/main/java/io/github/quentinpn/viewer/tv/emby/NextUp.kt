package io.github.quentinpn.viewer.tv.emby

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/** Cards of the "À suivre" row, as on Windows (`HOME_ROW`, ADR 0010). */
const val NEXT_UP_LIMIT = 16

/**
 * Home row "À suivre": the next episode of each series in progress, the Windows rules
 * (`EmbyService::next_up`, ADR 0010). Emby 4.10.1.0 answers an empty `/Shows/NextUp`
 * once an episode is watched and lists the next one in its "resume" home section
 * instead, so both are read at once.
 */
suspend fun EmbyClient.nextUpRow(userId: String, limit: Int = NEXT_UP_LIMIT): List<BaseItemDto> = coroutineScope {
    val shows = async { nextUp(userId, limit).items }
    val section = async { resumeSectionItems(this@nextUpRow, userId, limit) }
    mergeNextUp(shows.await(), section.await(), limit)
}

/** Items of the "resume" home section when it includes Next Up; none when the server has no home sections (404). */
private suspend fun resumeSectionItems(client: EmbyClient, userId: String, limit: Int): List<BaseItemDto> {
    val sections = try {
        client.homeSections(userId)
    } catch (e: EmbyException) {
        if (e.error == EmbyError.NotFound) return emptyList()
        throw e
    }
    val id = resumeSectionId(sections) ?: return emptyList()
    return client.sectionItems(userId, id, limit).items
}

/** The "resume" section declared with `IncludeNextUpInResume`, if any. */
internal fun resumeSectionId(sections: List<HomeSectionDto>): String? =
    sections.firstOrNull { it.sectionType == "resume" && it.includeNextUpInResume && isId(it.id) }?.id

/**
 * `/Shows/NextUp` first, then what only the section lists; episodes neither watched
 * nor started (those in progress stay in "Reprendre"), at most [limit].
 */
internal fun mergeNextUp(shows: List<BaseItemDto>, section: List<BaseItemDto>, limit: Int): List<BaseItemDto> {
    val items = shows.toMutableList()
    for (item in section) if (items.none { it.id == item.id }) items += item
    return items
        .filter { it.type == "Episode" && isId(it.id) }
        .filter { it.userData.let { u -> u == null || (!u.played && u.playbackPositionTicks == 0L) } }
        .take(limit)
}

/** Next-up episodes already in "Reprendre" are not shown twice (Windows `withoutDuplicates`). */
fun withoutDuplicates(nextUp: List<BaseItemDto>, inProgress: List<BaseItemDto>): List<BaseItemDto> {
    val seen = inProgress.mapTo(HashSet()) { it.id }
    return nextUp.filter { it.id !in seen }
}

private fun isId(id: String) = id.isNotEmpty() && id.all { it.isLetterOrDigit() && it.code < 128 || it == '-' }
