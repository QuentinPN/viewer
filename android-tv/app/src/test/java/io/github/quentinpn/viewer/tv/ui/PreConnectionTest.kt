package io.github.quentinpn.viewer.tv.ui

import io.github.quentinpn.viewer.tv.emby.EmbyError
import io.github.quentinpn.viewer.tv.session.SavedServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PreConnectionTest {
    private val salon = SavedServer(server = "http://192.168.1.14:8096", serverName = "Salon", userId = "u1", userName = "Quentin")

    @Test
    fun backGoesUpOneLevel() {
        assertNull(backFrom(ShelfStep.Shelf))
        assertEquals(ShelfStep.Shelf, backFrom(ShelfStep.Manage))
        assertEquals(ShelfStep.Manage, backFrom(ShelfStep.Confirm("k")))
        assertEquals(ShelfStep.Shelf, backFrom(ShelfStep.Manual))
        assertEquals(ShelfStep.Shelf, backFrom(ShelfStep.Reauth("k")))
        assertEquals(ShelfStep.Shelf, backFrom(ShelfStep.Connecting("k")))
        assertEquals(ShelfStep.Shelf, backFrom(ShelfStep.Failed("k", EmbyError.Unreachable)))
    }

    @Test
    fun forgettingTheLastServerLeavesTheManageMode() {
        assertEquals(ShelfStep.Manage, afterForget(2))
        assertEquals(ShelfStep.Shelf, afterForget(0))
    }

    @Test
    fun aSessionRefusedInUseOpensItsReauthCard() {
        assertEquals(ShelfStep.Reauth(salon.key), firstStep(salon.key, listOf(salon)))
        assertEquals(ShelfStep.Shelf, firstStep("gone|u9", listOf(salon)))
        assertEquals(ShelfStep.Shelf, firstStep(null, listOf(salon)))
    }

    @Test
    fun tileTexts() {
        assertEquals("Quentin · 192.168.1.14:8096", tileSubtitle(salon))
        assertEquals("S", tileInitial(salon))
        assertEquals("1", tileInitial(salon.copy(serverName = null)))
        assertEquals("É", tileInitial(salon.copy(serverName = "  éMBY")))
    }

    @Test
    fun tileHueIsStableAndInRange() {
        for (key in listOf("", "a", salon.key, "http://x|y".repeat(40))) {
            val hue = tileHue(key)
            assertTrue(hue in 0f..359f)
            assertEquals(hue, tileHue(key))
        }
    }

    @Test
    fun shelfMessages() {
        assertEquals("Choisissez le serveur à oublier.", shelfMessage(manage = true, count = 2))
        assertEquals("Qui regarde ? Choisissez un serveur.", shelfMessage(manage = false, count = 1))
        assertEquals("Bienvenue. Ajoutez votre premier serveur Emby.", shelfMessage(manage = false, count = 0))
    }

    @Test
    fun failureTitles() {
        assertEquals("Serveur injoignable", failureTitle(EmbyError.Unreachable))
        assertEquals("Connexion impossible", failureTitle(EmbyError.ServerMismatch))
    }
}
