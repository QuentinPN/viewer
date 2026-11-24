package io.github.quentinpn.viewer.tv.stremio

import io.github.quentinpn.viewer.tv.emby.BaseItemDto
import io.github.quentinpn.viewer.tv.emby.segmentQuery

/**
 * What the addons are asked for an Emby [item], as the desktop's `stremio.Target`: a film by its IMDb id, an
 * episode by its [series]' IMDb id and its numbers. [StremioErrorKind.NoImdb] without one (also a missing
 * series, another item in its place, or an episode without its numbers); [StremioErrorKind.InvalidId] for
 * any other item or numbers the addons cannot name.
 */
fun embyTarget(item: BaseItemDto, series: BaseItemDto?): StreamTarget {
    if (item.type != "Movie" && item.type != "Episode") stremioError(StremioErrorKind.InvalidId)
    // The same ids, read the same way, as the intro services are asked by
    val query = segmentQuery(item, series)
    val imdb = query?.ids?.imdb ?: stremioError(StremioErrorKind.NoImdb)
    val episode = query.episode ?: return StreamTarget.movie(imdb)
    return StreamTarget.episode(imdb, episode.first, episode.second)
}

/** A Stremio account is linked: « Autres sources » shows. An expired session still shows it, to say so on click. */
fun stremioUsable(state: StremioState?): Boolean = state == StremioState.SignedIn || state == StremioState.ReauthRequired

/** Whether « Réessayer » can change the outcome: not when the fix is in Réglages or in the title itself. */
fun canRetry(kind: StremioErrorKind): Boolean = kind !in FINAL

private val FINAL = setOf(StremioErrorKind.SignedOut, StremioErrorKind.ReauthRequired, StremioErrorKind.NoImdb, StremioErrorKind.InvalidId)
