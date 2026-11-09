package io.github.quentinpn.viewer.tv.emby

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/** Most titles of the home hero, as on Windows (`HERO_SIZE`, ADR 0015, 0059). */
const val HERO_SIZE = 5

/** The Windows hero `Fields` (`HERO_FIELDS`): the overview and the year come with each item. */
internal const val HERO_FIELDS = "Overview,ProductionYear,PrimaryImageAspectRatio"

/** An image of the server, fetched through the image loader (the token stays in its header). */
data class HeroImage(val itemId: String, val tag: String)

/** The episode a series title plays, shown under its name. */
data class HeroEpisode(val name: String, val season: Int?, val index: Int?)

/**
 * One title of the home hero (ADR 0059, from Windows `HeroItem`). [id] and [type] name
 * the film or series page "Plus d'infos" opens; [playId] is what the main button plays
 * from [startTicks], none for a series with nothing left to watch.
 */
data class HeroTitle(
    val id: String,
    /** `Movie` or `Series`; a resumed episode stands for its series. */
    val type: String,
    val name: String,
    val backdrop: HeroImage,
    val year: Int? = null,
    /** A film's length; none for a series. */
    val runTimeTicks: Long? = null,
    val officialRating: String? = null,
    val overview: String? = null,
    val episode: HeroEpisode? = null,
    val playId: String? = null,
    val startTicks: Long = 0,
    /** From "Reprendre", not from the latest additions. */
    val resumed: Boolean = false,
)

/**
 * Home hero (Windows `EmbyService::hero`): up to [size] titles with a backdrop, those
 * of [resumed] (the "Reprendre" items, read once for the shelf and the hero) first, then
 * films and series added recently and not watched; a series plays its first episode
 * not watched yet, looked up per series, all at once.
 */
suspend fun EmbyClient.homeHero(userId: String, resumed: List<BaseItemDto>, size: Int = HERO_SIZE): List<HeroTitle> = coroutineScope {
    val latest = heroLatest(userId, 2 * size).items
    heroTitles(resumed, latest, size)
        .map { title -> async { if (title.playId != null || title.type != "Series") title else withFirstEpisode(this@homeHero, userId, title) } }
        .awaitAll()
}

/** The series' first episode not watched yet; the title stays without one when it cannot be read. */
private suspend fun withFirstEpisode(client: EmbyClient, userId: String, title: HeroTitle): HeroTitle {
    val page = try {
        client.firstUnplayedEpisode(userId, title.id)
    } catch (e: EmbyException) {
        if (e.error == EmbyError.SessionExpired) throw e
        return title
    }
    val episode = page.items.firstOrNull { it.type == "Episode" && isId(it.id) } ?: return title
    return title.withEpisode(episode)
}

/** Resumed items first, then the latest ones; one title per film or series, at most [size]. */
internal fun heroTitles(resumed: List<BaseItemDto>, latest: List<BaseItemDto>, size: Int = HERO_SIZE): List<HeroTitle> {
    val titles = mutableListOf<HeroTitle>()
    val candidates = resumed.asSequence().mapNotNull(::resumedHero) + latest.asSequence().mapNotNull(::latestHero)
    for (title in candidates) {
        if (titles.size == size) break
        if (titles.none { it.id == title.id }) titles += title
    }
    return titles
}

/** A resumed film, or a resumed episode standing for its series with the series' backdrop. */
internal fun resumedHero(dto: BaseItemDto): HeroTitle? {
    if (!isId(dto.id)) return null
    return when (dto.type) {
        "Movie" -> latestHero(dto)?.copy(resumed = true)
        "Episode" -> {
            val seriesId = dto.seriesId?.takeIf(::isId) ?: return null
            val backdropId = dto.parentBackdropItemId?.takeIf(::isId) ?: return null
            val tag = dto.parentBackdropImageTags.firstOrNull() ?: return null
            HeroTitle(
                id = seriesId,
                type = "Series",
                name = text(dto.seriesName) ?: return null,
                backdrop = HeroImage(backdropId, tag),
                overview = text(dto.overview),
                episode = heroEpisode(dto),
                playId = dto.id,
                startTicks = resumeTicks(dto),
                resumed = true,
            )
        }
        else -> null
    }
}

/** A film or series from its own backdrop; a film plays itself, a series gets its episode later. */
internal fun latestHero(dto: BaseItemDto): HeroTitle? {
    val movie = when (dto.type) {
        "Movie" -> true
        "Series" -> false
        else -> return null
    }
    if (!isId(dto.id)) return null
    val tag = dto.backdropImageTags.firstOrNull() ?: return null
    return HeroTitle(
        id = dto.id,
        type = dto.type,
        name = text(dto.name) ?: return null,
        backdrop = HeroImage(dto.id, tag),
        year = dto.productionYear,
        runTimeTicks = dto.runTimeTicks.takeIf { movie },
        officialRating = text(dto.officialRating),
        overview = text(dto.overview),
        playId = dto.id.takeIf { movie },
        startTicks = if (movie) resumeTicks(dto) else 0,
    )
}

/** The series title once its first episode not watched is known: it resumes it if started. */
internal fun HeroTitle.withEpisode(episode: BaseItemDto): HeroTitle =
    copy(episode = heroEpisode(episode), playId = episode.id, startTicks = resumeTicks(episode))

/** Where to start: the resume point, or the beginning of an item watched or not started. */
internal fun resumeTicks(dto: BaseItemDto): Long {
    val data = dto.userData ?: return 0
    return if (data.played || data.playbackPositionTicks <= 0) 0 else data.playbackPositionTicks
}

private fun heroEpisode(dto: BaseItemDto) = HeroEpisode(dto.name.trim(), dto.parentIndexNumber, dto.indexNumber)

private fun text(value: String?): String? = value?.trim()?.takeIf { it.isNotEmpty() }

private fun isId(id: String) = id.isNotEmpty() && id.all { it.isLetterOrDigit() && it.code < 128 || it == '-' }
