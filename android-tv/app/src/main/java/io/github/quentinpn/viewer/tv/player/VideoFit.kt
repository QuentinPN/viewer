package io.github.quentinpn.viewer.tv.player

// "Format" of the track menu: how the picture fills the screen.

/** mpv's pan-and-scan, 0 to 1: 1 crops until the picture fills the screen, aspect kept. */
const val PANSCAN_PROPERTY = "panscan"

/** mpv's aspect lock: `no` stretches the picture to the screen. */
const val KEEPASPECT_PROPERTY = "keepaspect"

/** How the picture fills the screen; the order is the menu's, ◀ ▶ step through it. */
enum class VideoFit(
    /** The menu's value. */
    val label: String,
    /** Shown in the controls; null for « Original ». */
    val badge: String?,
) {
    /** Aspect kept, black bands where it does not match the screen: mpv's default. */
    Original("Original", null),

    /** Aspect kept, the sides (or top and bottom) cropped until no band is left. */
    Fill("Remplir l'écran", "Remplir"),

    /** The whole screen, the picture distorted to it. */
    Stretch("Étirer", "Étiré"),
}

/** ◀ previous, ▶ next, OK back to « Original ». */
enum class FitChange { Previous, Next, Reset }

/** The fit after [change], held at both ends of the list, as « Vitesse ». */
fun changeFit(current: VideoFit, change: FitChange): VideoFit {
    val all = VideoFit.entries
    return when (change) {
        FitChange.Reset -> VideoFit.Original
        FitChange.Previous -> all[(current.ordinal - 1).coerceAtLeast(0)]
        FitChange.Next -> all[(current.ordinal + 1).coerceAtMost(all.lastIndex)]
    }
}

/**
 * The mpv properties for [fit], both always set so no value of the previous fit lingers.
 * `video-unscaled` stays at its default (`no`): it would disable `panscan`.
 */
fun fitProperties(fit: VideoFit): List<Pair<String, String>> = when (fit) {
    VideoFit.Original -> listOf(PANSCAN_PROPERTY to "0", KEEPASPECT_PROPERTY to "yes")
    VideoFit.Fill -> listOf(PANSCAN_PROPERTY to "1", KEEPASPECT_PROPERTY to "yes")
    VideoFit.Stretch -> listOf(PANSCAN_PROPERTY to "0", KEEPASPECT_PROPERTY to "no")
}

/**
 * The fit mpv holds, from `panscan` and `keepaspect` read back as text; null when
 * either is unreadable or they match no offered fit (a partial pan-and-scan).
 */
fun readFit(panscan: String?, keepaspect: String?): VideoFit? {
    val scan = panscan?.trim()?.toDoubleOrNull() ?: return null
    return when (keepaspect?.trim()) {
        "no" -> if (scan == 0.0) VideoFit.Stretch else null
        "yes" -> when (scan) {
            0.0 -> VideoFit.Original
            1.0 -> VideoFit.Fill
            else -> null
        }
        else -> null
    }
}
