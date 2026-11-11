package io.github.quentinpn.viewer.tv.ui

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpeningPressGuardTest {
    /** What the guard consumes of [events], each an OK flag and a down flag. */
    private fun consumed(vararg events: Pair<Boolean, Boolean>): List<Boolean> {
        val guard = OpeningPressGuard()
        return events.map { (ok, down) -> guard.consume(ok, down) }
    }

    @Test
    fun theHeldOkAndItsReleaseAreSwallowed() {
        // Shield remote: repeats of the held OK, then its release; the next OK goes through
        assertEquals(
            listOf(true, true, true, false, false),
            consumed(true to true, true to true, true to false, true to true, true to false),
        )
    }

    @Test
    fun aLateCecReleaseBeforeTheNextKeyIsSwallowed() {
        // LG Magic Remote through CEC: a fresh OK press, then its release sent with ▼
        assertEquals(
            listOf(true, true, false),
            consumed(true to true, true to false, false to true),
        )
    }

    @Test
    fun anotherKeyFirstLetsOkThrough() {
        // ▼ before any release: the user moved on, OK works from then on
        assertEquals(listOf(false, false, false), consumed(false to true, true to true, true to false))
    }

    @Test
    fun okKeys() {
        assertTrue(isOkKey(KeyEvent.KEYCODE_DPAD_CENTER))
        assertTrue(isOkKey(KeyEvent.KEYCODE_ENTER))
        assertTrue(isOkKey(KeyEvent.KEYCODE_NUMPAD_ENTER))
        assertTrue(isOkKey(KeyEvent.KEYCODE_BUTTON_A))
        assertFalse(isOkKey(KeyEvent.KEYCODE_DPAD_DOWN))
    }
}
