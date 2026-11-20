package io.github.quentinpn.viewer.tv.request

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrailerTest {
    @Test
    fun aVideoIdOpensItsYoutubePage() {
        assertEquals("https://www.youtube.com/watch?v=9YGuN8sQpL0", trailerPage("9YGuN8sQpL0"))
        assertEquals("https://www.youtube.com/watch?v=uygf0l_WzjA", trailerPage("uygf0l_WzjA"))
        assertEquals("https://www.youtube.com/watch?v=a-b", trailerPage("a-b"))
    }

    @Test
    fun anythingElseHasNoPage() {
        assertNull(trailerPage(null))
        assertNull(trailerPage(""))
        assertNull(trailerPage("abc&list=x"))
        assertNull(trailerPage("abc def"))
        assertNull(trailerPage("abc\n"))
        assertNull(trailerPage("../x"))
        assertNull(trailerPage("https://evil.example/x"))
        assertNull(trailerPage("a".repeat(65)))
    }
}
