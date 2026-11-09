package io.github.quentinpn.viewer.tv.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class EpisodeLabelTest {
    @Test
    fun numbersThenName() {
        assertEquals("S1 · É3 — Le forgeron", episodeLabel(1, 3, "Le forgeron"))
        assertEquals("É3 — Le forgeron", episodeLabel(null, 3, "Le forgeron"))
        assertEquals("Le forgeron", episodeLabel(null, null, "Le forgeron"))
        assertEquals("S2 · É1", episodeLabel(2, 1, ""))
    }
}
