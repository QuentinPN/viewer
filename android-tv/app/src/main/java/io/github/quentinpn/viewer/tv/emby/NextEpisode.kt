package io.github.quentinpn.viewer.tv.emby

/**
 * The episode after [episodeId] in an `AdjacentTo` answer, other series left out.
 * Null when the episode is not in it or is the last one.
 */
fun nextEpisode(episodes: List<BaseItemDto>, episodeId: String, seriesId: String): BaseItemDto? {
    val series = episodes.filter { it.seriesId == null || it.seriesId == seriesId }
    val index = series.indexOfFirst { it.id == episodeId }
    return if (index < 0) null else series.getOrNull(index + 1)
}

/** Where the next episode starts: its resume point, unless it was watched. */
fun BaseItemDto.startTicks(): Long = userData?.takeUnless { it.played }?.playbackPositionTicks ?: 0
