package io.github.quentinpn.viewer.tv.stremio

import io.github.quentinpn.viewer.tv.request.Season

/**
 * « Regarder maintenant » on a Demander series, as the desktop's `episodes.ts`: the seasons that can be
 * picked, those with episodes, in order, the specials (season 0) last. The count may include episodes
 * not aired yet: the request server has no better.
 */
fun pickableSeasons(seasons: List<Season>): List<Season> =
    seasons.filter { it.episodeCount > 0 }.sortedBy { if (it.seasonNumber == 0) Int.MAX_VALUE else it.seasonNumber }

fun seasonLabel(seasonNumber: Int): String = if (seasonNumber == 0) "Épisodes spéciaux" else "Saison $seasonNumber"

/** The first regular season of [pickable], else the specials: the one the picker opens on. */
fun defaultSeason(pickable: List<Season>): Int? = (pickable.firstOrNull { it.seasonNumber > 0 } ?: pickable.firstOrNull())?.seasonNumber

/** 1 … [count]. */
fun episodeNumbers(count: Int): List<Int> = (1..count).toList()

/** What the addons are asked for a title outside Emby: a film by its IMDb id, an episode with its numbers too. */
fun externalTarget(imdb: String, season: Int? = null, episode: Int? = null): StreamTarget =
    if (season != null && episode != null) StreamTarget.episode(imdb, season, episode) else StreamTarget.movie(imdb)
