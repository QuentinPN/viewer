package io.github.quentinpn.viewer.tv.ui.stremio

import io.github.quentinpn.viewer.tv.stremio.AddonStreams
import io.github.quentinpn.viewer.tv.stremio.SourceAddon
import io.github.quentinpn.viewer.tv.stremio.SourceList
import io.github.quentinpn.viewer.tv.stremio.StreamChoice
import io.github.quentinpn.viewer.tv.stremio.StremioError
import io.github.quentinpn.viewer.tv.stremio.StremioErrorKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceSectionsTest {
    private fun choice(id: String) = StreamChoice(id, "Addon $id", "", null)

    @Test
    fun everyAddonKeepsItsPlaceAndOnlyAnsweredOnesHaveRows() {
        val list = SourceList(
            "q",
            listOf(
                SourceAddon(0, "Lent", AddonStreams.Loading),
                SourceAddon(1, "Comet", AddonStreams.Failed(StremioError(StremioErrorKind.Unreachable, addon = "Comet", page = true))),
                SourceAddon(2, "Vide", AddonStreams.Ready(emptyList(), 0)),
                SourceAddon(3, "Torrentio", AddonStreams.Ready(listOf(choice("a"), choice("b")), 2)),
            ),
        )
        val sections = sourceSections(list)
        assertEquals(listOf("Lent", "Comet", "Vide", "Torrentio"), sections.map { it.name })
        assertEquals(listOf(emptyList(), emptyList(), emptyList(), listOf("a", "b")), sections.map { s -> s.rows.map { it.id } })
        // Each addon without rows says where it stands; the one with rows also tells its left-out entries
        assertTrue(sections.all { it.notes.isNotEmpty() })
    }

    @Test
    fun anAddonWithOnlyUnplayableEntriesSaysSoOnce() {
        val section = sourceSections(SourceList("q", listOf(SourceAddon(0, "T", AddonStreams.Ready(emptyList(), 3))))).single()
        assertEquals(1, section.notes.size)
        assertTrue(section.notes.single().contains("3"))
    }

    @Test
    fun anExpiredListIsReportedForTheWholeList() {
        val expired = StremioError(StremioErrorKind.Expired, page = true)
        val list = SourceList(
            "q",
            listOf(SourceAddon(0, "A", AddonStreams.Ready(listOf(choice("a")), 0)), SourceAddon(1, "B", AddonStreams.Failed(expired))),
        )
        assertEquals(expired, expiredList(list))
        assertNull(expiredList(list.copy(addons = list.addons.take(1))))
    }

    @Test
    fun aRowKeepsTheAddonsLinesWithoutBlanks() {
        val row = streamRow(StreamChoice("s", "Torrentio\n4K", "  Film.2160p.mkv \n\n👤 12\r\n", 3L * 1024 * 1024 * 1024))
        assertEquals(listOf("Film.2160p.mkv", "👤 12"), row.details)
        assertTrue(row.title.contains("Torrentio") && row.title.contains("4K") && '\n' !in row.title)
        assertTrue(row.size != null)
    }
}
