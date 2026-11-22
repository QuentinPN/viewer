package io.github.quentinpn.viewer.tv.brand

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** The manifest and [Season] must agree: one alias per season, its own icon and banner, only the default enabled. */
class LauncherManifestTest {
    private val android = "http://schemas.android.com/apk/res/android"
    private val manifest = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        .newDocumentBuilder().parse(File("src/main/AndroidManifest.xml")).documentElement

    private fun Element.attr(name: String): String = getAttributeNS(android, name)

    private fun Element.children(tag: String): List<Element> =
        (0 until getElementsByTagName(tag).length).map { getElementsByTagName(tag).item(it) as Element }

    private val aliases = manifest.children("activity-alias").associateBy { it.attr("name") }

    private fun Element.isLauncherEntry() = children("intent-filter").any { filter ->
        filter.children("action").any { it.attr("name") == "android.intent.action.MAIN" } &&
            filter.children("category").any { it.attr("name") == "android.intent.category.LEANBACK_LAUNCHER" }
    }

    @Test
    fun oneAliasPerSeason() {
        assertEquals(Season.entries.map { ".${aliasName(it)}" }.toSet(), aliases.keys)
    }

    @Test
    fun eachAliasHasItsIconAndBannerAndTheLauncherFilter() {
        val res = File("src/main/res/drawable-nodpi")
        for (season in Season.entries) {
            val alias = aliases.getValue(".${aliasName(season)}")
            val suffix = if (season == Season.DEFAULT) "" else "_${season.res}"
            assertEquals("@drawable/icon$suffix", alias.attr("icon"))
            assertEquals("@drawable/banner$suffix", alias.attr("banner"))
            assertTrue(File(res, "icon$suffix.png").isFile)
            assertTrue(File(res, "banner$suffix.png").isFile)
            assertEquals(".MainActivity", alias.attr("targetActivity"))
            assertEquals("true", alias.attr("exported"))
            assertTrue(season.id, alias.isLauncherEntry())
        }
    }

    @Test
    fun onlyTheDefaultAliasIsEnabled() {
        for ((name, alias) in aliases) {
            assertEquals(name, name == ".${aliasName(Season.DEFAULT)}", alias.attr("enabled") != "false")
        }
    }

    @Test
    fun mainActivityStaysStartableButIsNoLauncherEntryItself() {
        val main = manifest.children("activity").first { it.attr("name") == ".MainActivity" }
        assertEquals("true", main.attr("exported"))
        assertFalse("a second tile next to the alias", main.isLauncherEntry())
        assertTrue(manifest.children("activity").filter { it.isLauncherEntry() }.isEmpty())
    }
}
