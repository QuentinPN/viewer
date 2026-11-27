package io.github.quentinpn.viewer.tv.player

/** As the tracker and Windows (`EARLY_EOF_SECONDS`): an end further than this from the duration is a cut stream. */
private const val NATURAL_END_MARGIN_SECONDS = 30.0

/** Whether an end of file at [position] is the file's own end; an unknown duration counts as one. */
fun naturalEnd(position: Double, duration: Double): Boolean =
    duration <= 0 || duration - position <= NATURAL_END_MARGIN_SECONDS

/**
 * Whether a stream that is not Emby's (Stremio) broke instead of ending: mpv's END_FILE ([endFile]), which
 * keep-open only sends when the file fails to open or breaks, or an end of file far from its duration.
 */
fun streamBroke(endFile: Boolean, position: Double, duration: Double): Boolean =
    endFile || !naturalEnd(position, duration)
