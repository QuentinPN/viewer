package io.github.quentinpn.viewer.tv.player

import io.github.quentinpn.viewer.tv.emby.ChapterInfo
import io.github.quentinpn.viewer.tv.ui.seconds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChaptersTest {
    private fun info(ticks: Long, name: String?, marker: String? = null) = ChapterInfo(ticks, name, marker)

    // Film 30207 of the 2026-11-07 evidence: its first four chapters
    private val film = listOf(
        Chapter(0.0, "Chapitre 1"),
        Chapter(487.57, "Chapitre 2"),
        Chapter(726.558, "Chapitre 3"),
        Chapter(1059.14, "Chapitre 4"),
    )

    @Test
    fun mapsTheRealAnswerOfAFilm() {
        // `/Users/{Id}/Items/43189` on Emby 4.10.2.0, as the Windows test
        val found = chapters(
            listOf(
                info(0, "Opening Credits", "Chapter"),
                info(2_421_170_000, "Horn Island 1977", "Chapter"),
                info(5_489_650_000, "Someone to Take the Fall", "Chapter"),
                info(7_002_000_000, "Secret of Fly Fishing", "Chapter"),
            ),
            61_154_010_000,
        )
        assertEquals(
            listOf(
                Chapter(0.0, "Opening Credits"),
                Chapter(242.117, "Horn Island 1977"),
                Chapter(548.965, "Someone to Take the Fall"),
                Chapter(700.2, "Secret of Fly Fishing"),
            ),
            found,
        )
    }

    @Test
    fun keepsRegularChaptersOnlySortedAndNamed() {
        val found = chapters(
            listOf(
                info(6_000_000_000, null),
                info(0, "  Début\n du film ", "Chapter"),
                info(300_000_000, null, "IntroStart"),
                info(900_000_000, null, "IntroEnd"),
                info(3_000_000_000, "", "Chapter"),
                info(24_000_000_000, null, "CreditsStart"),
            ),
            null,
        )
        assertEquals(listOf(Chapter(0.0, "Début du film"), Chapter(300.0, "Chapitre 2"), Chapter(600.0, "Chapitre 3")), found)
    }

    @Test
    fun dropsWhatCannotBeNavigated() {
        // Negative, past the end, and a duplicate within a second
        val found = chapters(
            listOf(
                info(-10, "Avant"),
                info(0, "A"),
                info(5_000_000, "Doublon"),
                info(1_200_000_000, "B"),
                info(2_000_000_000, "Après la fin"),
            ),
            1_800_000_000,
        )
        assertEquals(listOf("A", "B"), found.map { it.name })
    }

    @Test
    fun fewerThanTwoChaptersGiveNone() {
        assertTrue(chapters(listOf(info(0, "Seul")), null).isEmpty())
        assertTrue(chapters(emptyList(), 1).isEmpty())
        assertTrue("markers only", chapters(listOf(info(0, null, "IntroStart"), info(1, null, "IntroEnd")), null).isEmpty())
    }

    @Test
    fun longNamesAreCutBetweenCharacters() {
        val found = chapters(listOf(info(0, "x".repeat(500)), info(600_000_000, "🎬".repeat(130))), null)
        assertEquals(120, found[0].name.length)
        assertEquals(120, found[1].name.codePointCount(0, found[1].name.length))
    }

    @Test
    fun findsTheChapterPlaying() {
        assertEquals(0, chapterIndex(film, 0.0))
        assertEquals(0, chapterIndex(film, 487.0))
        assertEquals(1, chapterIndex(film, 500.0))
        assertEquals(3, chapterIndex(film, 5000.0))
        assertEquals("a seek that lands just before a start", 1, chapterIndex(film, 487.2))
        assertEquals(-1, chapterIndex(listOf(Chapter(30.0, "A"), Chapter(60.0, "B")), 10.0))
        assertEquals(-1, chapterIndex(emptyList(), 10.0))
    }

    @Test
    fun startsReadAsTheClock() {
        assertEquals("0:00", seconds(0.0))
        assertEquals("8:07", seconds(487.57))
        assertEquals("17:39", seconds(1059.14))
        assertEquals("1:41:43", seconds(6103.389))
    }
}
