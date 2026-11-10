package io.github.quentinpn.viewer.tv.player

import io.github.quentinpn.viewer.tv.emby.PlayMethod
import io.github.quentinpn.viewer.tv.settings.BITRATE_CHOICES
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QualityTest {
    @Test
    fun choicesAreThoseOfReglages() {
        assertEquals(listOf<Long?>(null) + BITRATE_CHOICES, QUALITY_CHOICES)
        assertNull("Maximale first", QUALITY_CHOICES.first())
    }

    @Test
    fun stepsMoveAlongTheListAndHoldAtTheEnds() {
        assertEquals(40_000_000L, stepQuality(null, QualityChange.Lower))
        assertNull(stepQuality(40_000_000, QualityChange.Higher))
        assertEquals(10_000_000L, stepQuality(20_000_000, QualityChange.Lower))
        assertEquals(40_000_000L, stepQuality(20_000_000, QualityChange.Higher))
        assertNull("held at Maximale", stepQuality(null, QualityChange.Higher))
        assertEquals("held at the lowest", 1_500_000L, stepQuality(1_500_000, QualityChange.Lower))
        // A cap not offered (an older setting) reads as Maximale
        assertEquals(40_000_000L, stepQuality(12_345, QualityChange.Lower))
    }

    @Test
    fun aPickWaitsForOk() {
        val start = Quality.of(8_000_000, PlayMethod.DirectPlay)
        assertEquals(Quality(8_000_000, 8_000_000, QualityPhase.Idle, PlayMethod.DirectPlay), start)
        assertFalse(start.pending)

        val picked = start.step(QualityChange.Lower)
        assertEquals(4_000_000L, picked.chosen)
        assertEquals("playing stream unchanged", 8_000_000L, picked.applied)
        assertTrue(picked.pending)
        assertFalse("back to the playing cap", picked.step(QualityChange.Higher).pending)

        val applying = picked.applying()
        assertFalse(applying.pending)
        assertEquals("no change while applying", applying, applying.step(QualityChange.Lower))

        val done = applying.applied(PlayMethod.Transcode)
        assertEquals(Quality(4_000_000, 4_000_000, QualityPhase.Idle, PlayMethod.Transcode), done)
        assertFalse(done.pending)
    }

    @Test
    fun aRefusedChangeKeepsThePlayingCap() {
        val failed = Quality.of(null, PlayMethod.DirectPlay).step(QualityChange.Lower).applying().failed()
        assertEquals(Quality(null, null, QualityPhase.Failed, PlayMethod.DirectPlay), failed)
        assertFalse(failed.pending)
        // A new pick clears the failure
        assertEquals(QualityPhase.Idle, failed.step(QualityChange.Lower).phase)
        // A cap not offered starts as Maximale
        assertNull(Quality.of(12_345, null).applied)
    }

    @Test
    fun rowLabels() {
        val start = Quality.of(null, PlayMethod.DirectPlay)
        assertEquals("Maximale (automatique)", qualityValue(start))
        assertEquals("Lecture directe  ·  ◀ ▶ puis OK pour changer", qualityNote(start))
        assertEquals("◀ ▶ puis OK pour changer", qualityNote(Quality()))
        val picked = start.step(QualityChange.Lower).step(QualityChange.Lower)
        assertEquals("20 Mbit/s", qualityValue(picked))
        assertEquals("OK applique ce débit à cette lecture", qualityNote(picked))
        assertEquals("Changement en cours…", qualityNote(picked.applying()))
        assertEquals("Échec : la qualité n'a pas changé", qualityNote(picked.applying().failed()))
        // The reasons of « Informations de lecture », not « raison non précisée » (ADR 0086)
        val transcoded = picked.applying().applied(PlayMethod.Transcode, listOf("débit trop élevé"))
        assertEquals("Transcodage · débit trop élevé  ·  ◀ ▶ puis OK pour changer", qualityNote(transcoded))
        assertEquals("Transcodage · débit trop élevé  ·  ◀ ▶ puis OK pour changer", qualityNote(Quality.of(null, PlayMethod.Transcode, listOf("débit trop élevé"))))
    }

    @Test
    fun capFactSaysWhereTheCapComesFrom() {
        assertEquals("Maximale · réseau local", capFact(null, localAddress = true, chosenInPlayer = false))
        assertEquals("1,5 Mbit/s · accès distant", capFact(1_500_000, localAddress = false, chosenInPlayer = false))
        assertEquals("8 Mbit/s · réseau local · choisi pour cette lecture", capFact(8_000_000, localAddress = true, chosenInPlayer = true))
    }

    @Test
    fun startParameterIsFoundByName() {
        assertTrue(hasStartParameter("http://h/emby/videos/1/master.m3u8?MediaSourceId=a&StartTimeTicks=600"))
        assertTrue(hasStartParameter("http://h/emby/videos/1/stream.mkv?starttimeticks=0"))
        assertFalse(hasStartParameter("http://h/emby/videos/1/master.m3u8?MediaSourceId=a&PlaySessionId=b"))
        assertFalse(hasStartParameter("not a url"))
    }
}
