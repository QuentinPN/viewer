package io.github.quentinpn.viewer.tv.request

import org.junit.Assert.assertEquals
import org.junit.Test

class LabelsTest {
    @Test
    fun mediaStatusesUseTheSpecWording() {
        assertEquals("Disponible", mediaStatusLabel(MediaStatus.Available).text)
        assertEquals("Partiel", mediaStatusLabel(MediaStatus.Partial).text)
        assertEquals("Demandé", mediaStatusLabel(MediaStatus.Requested).text)
        assertEquals("En attente", mediaStatusLabel(MediaStatus.Pending).text)
        assertEquals("Refusé", mediaStatusLabel(MediaStatus.Declined).text)
        assertEquals("Téléchargement", mediaStatusLabel(MediaStatus.Downloading).text)
    }

    @Test
    fun downloadingShowsItsProgressAsAPercentage() {
        assertEquals("Téléchargement 60 %", mediaStatusLabel(MediaStatus.Downloading, 0.6).text)
        assertEquals("Téléchargement 100 %", mediaStatusLabel(MediaStatus.Downloading, 1.4).text)
        // Only a download shows it
        assertEquals("Partiel", mediaStatusLabel(MediaStatus.Partial, 0.6).text)
    }

    @Test
    fun requestStatusesUseTheSpecWording() {
        val expected = mapOf(
            RequestStatus.Pending to "En attente",
            RequestStatus.Approved to "Approuvée",
            RequestStatus.Sent to "Envoyée",
            RequestStatus.Available to "Disponible",
            RequestStatus.Declined to "Refusée",
            RequestStatus.Cancelled to "Annulée",
            RequestStatus.Failed to "Échec",
        )
        assertEquals(RequestStatus.entries.toSet(), expected.keys)
        for ((status, text) in expected) assertEquals(text, requestStatusLabel(status).text)
    }

    @Test
    fun tonesFollowTheDesignTokens() {
        assertEquals(Tone.Success, mediaStatusLabel(MediaStatus.Available).tone)
        assertEquals(Tone.Warning, mediaStatusLabel(MediaStatus.Partial).tone)
        assertEquals(Tone.Warning, requestStatusLabel(RequestStatus.Pending).tone)
        assertEquals(Tone.Danger, requestStatusLabel(RequestStatus.Failed).tone)
        assertEquals(Tone.Muted, requestStatusLabel(RequestStatus.Cancelled).tone)
    }
}
