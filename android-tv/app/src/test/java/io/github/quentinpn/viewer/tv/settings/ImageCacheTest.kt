package io.github.quentinpn.viewer.tv.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class ImageCacheTest {
    private val mo = 1024L * 1024
    private val go = 1024L * mo

    @Test
    fun emptyAndTinySizes() {
        assertEquals("0 Mo", sizeLabel(0))
        assertEquals("0 Mo", sizeLabel(-5))
        assertEquals("moins de 1 Mo", sizeLabel(1))
        assertEquals("moins de 1 Mo", sizeLabel(mo - 1))
    }

    @Test
    fun megabytesAreRounded() {
        assertEquals("1 Mo", sizeLabel(mo))
        assertEquals("84 Mo", sizeLabel(84 * mo))
        assertEquals("84 Mo", sizeLabel(84 * mo + mo / 2 - 1))
        assertEquals("85 Mo", sizeLabel(84 * mo + mo / 2))
        assertEquals("256 Mo", sizeLabel(IMAGE_CACHE_MAX_BYTES))
        assertEquals("1023 Mo", sizeLabel(1023 * mo))
    }

    @Test
    fun gigabytesUseAFrenchDecimalComma() {
        assertEquals("1 Go", sizeLabel(go))
        assertEquals("1,2 Go", sizeLabel(go + go / 5))
        assertEquals("2 Go", sizeLabel(2 * go))
        assertEquals("1,5 Go", sizeLabel(go + go / 2))
    }

    @Test
    fun justBelowAGigabyteSwitchesToGigabytes() {
        // 1023,6 Mo would round to 1024 Mo: shown as 1 Go instead
        assertEquals("1 Go", sizeLabel(1023 * mo + mo * 6 / 10))
    }

    @Test
    fun usageRow() {
        assertEquals("84 Mo sur 256 Mo", usageLabel(ImageCacheUsage.Measured(84 * mo, IMAGE_CACHE_MAX_BYTES)))
        assertEquals("0 Mo sur 256 Mo", usageLabel(ImageCacheUsage.Measured(0, IMAGE_CACHE_MAX_BYTES)))
        assertEquals("Calcul en cours…", usageLabel(ImageCacheUsage.Measuring))
        assertEquals("Taille inconnue", usageLabel(ImageCacheUsage.Unavailable))
    }

    @Test
    fun clearingLabels() {
        assertEquals("", CacheClearing.Idle.label)
        assertEquals("En cours…", CacheClearing.Running.label)
        assertEquals("Vidé", CacheClearing.Done.label)
        assertEquals("Échec", CacheClearing.Failed.label)
    }

    @Test
    fun boundIs256Megabytes() {
        assertEquals(268_435_456L, IMAGE_CACHE_MAX_BYTES)
    }
}
