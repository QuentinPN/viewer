package io.github.quentinpn.viewer.tv.player

import io.github.quentinpn.viewer.tv.settings.SubtitleBackground
import io.github.quentinpn.viewer.tv.settings.SubtitleColor
import io.github.quentinpn.viewer.tv.settings.SubtitlePosition
import io.github.quentinpn.viewer.tv.settings.SubtitleSize
import io.github.quentinpn.viewer.tv.settings.SubtitleStyle
import kotlin.math.roundToInt

/**
 * mpv properties for [style], the values of `subtitle_options` on Windows
 * (src-tauri/src/player/mpv.rs). ASS and SSA keep their own styles unless
 * the user overrides them: `yes` rather than Windows' `no`, which would also pin them
 * under the TV controls (`sub-pos` is ignored with `no`; seen on the Shield).
 * `sub-pos` is left out: [subtitlePosition] gives it, as it also follows the controls.
 */
fun subtitleOptions(style: SubtitleStyle): List<Pair<String, String>> {
    val scale = when (style.size) {
        SubtitleSize.Small -> "0.8"
        SubtitleSize.Normal -> "1"
        SubtitleSize.Large -> "1.25"
        SubtitleSize.Larger -> "1.5"
    }
    val color = when (style.color) {
        SubtitleColor.White -> "#FFFFFF"
        SubtitleColor.Yellow -> "#FFE14D"
    }
    val border = when (style.background) {
        SubtitleBackground.Outline -> "outline-and-shadow"
        SubtitleBackground.Box -> "background-box"
    }
    val align = if (style.position == SubtitlePosition.Top) "top" else "bottom"
    return listOf(
        "sub-scale" to scale,
        "sub-color" to color,
        "sub-border-style" to border,
        // The box colour, 70 % opaque black (#AARRGGBB); the outline style ignores it
        "sub-back-color" to "#B3000000",
        "sub-align-y" to align,
        "sub-ass-override" to if (style.overrideAss) "force" else "yes",
    )
}

/**
 * `sub-pos` for [position] while the controls hide the bottom [covered] share of the
 * screen: the higher of the two, so the user's place holds and the controls never
 * cover the text. "En haut" starts from 100, Windows' value, and still follows the
 * controls: it only moves text subtitles, and ASS dialogue keeps its bottom place.
 */
fun subtitlePosition(position: SubtitlePosition, covered: Float): Int {
    val base = when (position) {
        SubtitlePosition.Bottom, SubtitlePosition.Top -> 100
        SubtitlePosition.Raised -> 85
    }
    return minOf(base, (100 * (1 - covered)).roundToInt()).coerceIn(0, 100)
}
