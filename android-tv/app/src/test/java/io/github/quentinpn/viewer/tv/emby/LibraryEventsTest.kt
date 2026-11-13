package io.github.quentinpn.viewer.tv.emby

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryEventsTest {
    private fun changed(data: String) = """{"MessageType":"LibraryChanged","MessageId":"m","Data":$data}"""

    @Test
    fun addedItemsRefresh() {
        assertTrue(isLibraryChange(changed("""{"ItemsAdded":["1"],"ItemsUpdated":[],"ItemsRemoved":[]}""")))
    }

    @Test
    fun updatedOrRemovedItemsRefresh() {
        assertTrue(isLibraryChange(changed("""{"ItemsUpdated":["1"]}""")))
        assertTrue(isLibraryChange(changed("""{"ItemsRemoved":["1"],"Extra":{"x":1}}""")))
    }

    @Test
    fun folderOnlyOrEmptyChangesAreIgnored() {
        assertFalse(isLibraryChange(changed("""{"FoldersAddedTo":["f"],"ItemsAdded":[]}""")))
        assertFalse(isLibraryChange(changed("{}")))
        assertFalse(isLibraryChange("""{"MessageType":"LibraryChanged"}"""))
    }

    @Test
    fun otherMessagesAndGarbageAreIgnored() {
        assertFalse(isLibraryChange("""{"MessageType":"UserDataChanged","Data":{"ItemsAdded":["1"]}}"""))
        assertFalse(isLibraryChange("not json"))
        assertFalse(isLibraryChange("[]"))
        assertFalse(isLibraryChange("""{"MessageType":1,"Data":{"ItemsAdded":["1"]}}"""))
        assertFalse(isLibraryChange(changed("""{"ItemsAdded":"1"}""")))
    }

    @Test
    fun reconnectBacksOffThenCaps() {
        assertEquals(listOf(2_000L, 5_000L, 15_000L, 30_000L, 30_000L, 30_000L), (0..5).map(::reconnectDelay))
        assertEquals(2_000L, reconnectDelay(-1))
    }

    @Test
    fun aBurstReadsTheShelvesOnceAfterItSettles() = runBlocking {
        // Real time, short scale: a burst of three changes 30 ms apart, then one alone
        val source = flow {
            emit(Unit); delay(30); emit(Unit); delay(30); emit(Unit)
            delay(400); emit(Unit)
            delay(400)
        }
        val start = System.nanoTime()
        val times = withTimeout(5_000) {
            source.settled(150).map { (System.nanoTime() - start) / 1_000_000 }.take(2).toList()
        }
        assertEquals(2, times.size)
        assertTrue("first after the burst settles: $times", times[0] in 200L..450L)
        assertTrue("second after the lone change: $times", times[1] >= 610L)
    }
}
