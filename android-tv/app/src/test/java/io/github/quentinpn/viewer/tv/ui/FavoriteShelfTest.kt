package io.github.quentinpn.viewer.tv.ui

import io.github.quentinpn.viewer.tv.emby.BaseItemDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FavoriteShelfTest {
    private fun item(id: String, type: String) = BaseItemDto(id = id, name = "Titre $id", type = type)

    @Test
    fun theRowShowsOnlyWithAFilmOrASeries() {
        assertNull(favoriteShelf(emptyList()))
        assertNull(favoriteShelf(listOf(item("b1", "BoxSet"), item("e1", "Episode"))))
        val shelf = favoriteShelf(listOf(item("m1", "Movie"), item("b1", "BoxSet"), item("s1", "Series")))!!
        assertEquals("Favoris", shelf.title)
        assertEquals(listOf("m1", "s1"), shelf.items.map { it.id })
        // Posters that open the film or series page, never played at once
        assertEquals(false, shelf.landscape)
        assertEquals(false, shelf.resume)
        assertNull(shelf.library)
    }
}
