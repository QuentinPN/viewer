package io.github.quentinpn.viewer.tv.player

import io.github.quentinpn.viewer.tv.settings.SubtitleBackground
import io.github.quentinpn.viewer.tv.settings.SubtitleColor
import io.github.quentinpn.viewer.tv.settings.SubtitlePosition
import io.github.quentinpn.viewer.tv.settings.SubtitleSize
import io.github.quentinpn.viewer.tv.settings.SubtitleStyle
import org.junit.Assert.assertEquals
import org.junit.Test

class SubtitleOptionsTest {
    @Test
    fun defaultsLeaveAssAlone() {
        assertEquals(
            listOf(
                "sub-scale" to "1",
                "sub-color" to "#FFFFFF",
                "sub-border-style" to "outline-and-shadow",
                "sub-back-color" to "#B3000000",
                "sub-align-y" to "bottom",
                "sub-ass-override" to "no",
            ),
            subtitleOptions(SubtitleStyle()),
        )
    }

    @Test
    fun valuesAreThoseOfWindows() {
        // Same case as applies_subtitle_style_and_track_preferences in src-tauri/src/player/mpv.rs
        val style = SubtitleStyle(SubtitleSize.Larger, SubtitleColor.Yellow, SubtitleBackground.Box, SubtitlePosition.Raised, overrideAss = true)
        assertEquals(
            listOf(
                "sub-scale" to "1.5",
                "sub-color" to "#FFE14D",
                "sub-border-style" to "background-box",
                "sub-back-color" to "#B3000000",
                "sub-align-y" to "bottom",
                "sub-ass-override" to "force",
            ),
            subtitleOptions(style),
        )
        val scales = SubtitleSize.entries.map { size -> subtitleOptions(SubtitleStyle(size = size)).toMap()["sub-scale"] }
        assertEquals(listOf("0.8", "1", "1.25", "1.5"), scales)
        assertEquals("top", subtitleOptions(SubtitleStyle(position = SubtitlePosition.Top)).toMap()["sub-align-y"])
    }

    @Test
    fun positionCombinesWithTheControls() {
        assertEquals(100, subtitlePosition(SubtitlePosition.Bottom, 0f))
        assertEquals(85, subtitlePosition(SubtitlePosition.Raised, 0f))
        // Controls over the bottom 25 %: both bottom places move above them
        assertEquals(75, subtitlePosition(SubtitlePosition.Bottom, 0.25f))
        assertEquals(75, subtitlePosition(SubtitlePosition.Raised, 0.25f))
        // Controls lower than the raised place: it holds
        assertEquals(85, subtitlePosition(SubtitlePosition.Raised, 0.1f))
        assertEquals(90, subtitlePosition(SubtitlePosition.Bottom, 0.1f))
        // At the top: Windows' 100, still clear of the controls for ASS left at the bottom
        assertEquals(100, subtitlePosition(SubtitlePosition.Top, 0f))
        assertEquals(70, subtitlePosition(SubtitlePosition.Top, 0.3f))
        // Out of range fractions stay within mpv's 0..100
        assertEquals(0, subtitlePosition(SubtitlePosition.Bottom, 1.5f))
        assertEquals(100, subtitlePosition(SubtitlePosition.Bottom, -0.2f))
    }
}
