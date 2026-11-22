package io.github.quentinpn.viewer.tv.brand

import android.content.Intent
import android.content.pm.PackageManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LauncherIconTest {
    @Test
    fun releaseFollowsTheCalendarWhenSeasonsAreOn() {
        for (today in Season.entries) {
            assertEquals(today, wantedLauncherSeason(seasonal = true, debugBuild = false, today = today, override = null))
        }
    }

    @Test
    fun releaseKeepsTheDefaultWhenSeasonsAreOff() {
        for (today in Season.entries) {
            assertEquals(Season.DEFAULT, wantedLauncherSeason(seasonal = false, debugBuild = false, today = today, override = null))
        }
    }

    @Test
    fun releaseIgnoresTheOverride() {
        assertEquals(Season.HALLOWEEN, wantedLauncherSeason(true, debugBuild = false, today = Season.HALLOWEEN, override = Season.CHRISTMAS))
        assertEquals(Season.DEFAULT, wantedLauncherSeason(false, debugBuild = false, today = Season.HALLOWEEN, override = Season.CHRISTMAS))
    }

    @Test
    fun debugStaysOnTheHardHatUnlessAskedOtherwise() {
        for (seasonal in listOf(true, false)) {
            assertEquals(Season.DEFAULT, wantedLauncherSeason(seasonal, debugBuild = true, today = Season.CHRISTMAS, override = null))
            assertEquals(Season.HALLOWEEN, wantedLauncherSeason(seasonal, debugBuild = true, today = Season.CHRISTMAS, override = Season.HALLOWEEN))
        }
    }

    @Test
    fun nothingToDoWhenOnlyTheWantedAliasIsEnabled() {
        for (season in Season.entries) assertTrue(launcherChanges(season, setOf(season)).isEmpty())
    }

    @Test
    fun theNewAliasIsEnabledBeforeTheOthersAreDisabled() {
        assertEquals(
            listOf(LauncherChange(Season.CHRISTMAS, true), LauncherChange(Season.DEFAULT, false)),
            launcherChanges(Season.CHRISTMAS, setOf(Season.DEFAULT)),
        )
        val many = launcherChanges(Season.WINTER, setOf(Season.DEFAULT, Season.EASTER))
        assertEquals(LauncherChange(Season.WINTER, true), many.first())
        assertEquals(setOf(LauncherChange(Season.DEFAULT, false), LauncherChange(Season.EASTER, false)), many.drop(1).toSet())
        assertTrue(many.drop(1).none { it.enable })
    }

    @Test
    fun aDuplicateIsDisabledAndTheWantedOneKept() {
        assertEquals(listOf(LauncherChange(Season.EASTER, false)), launcherChanges(Season.WINTER, setOf(Season.WINTER, Season.EASTER)))
    }

    @Test
    fun anAppWithoutAnyEnabledAliasGetsTheWantedOne() {
        assertEquals(listOf(LauncherChange(Season.SUMMER, true)), launcherChanges(Season.SUMMER, emptySet()))
    }

    @Test
    fun theManifestDecidesWhileNoStateWasSet() {
        val unset = PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
        assertTrue(isLauncherEnabled(unset, Season.DEFAULT))
        assertFalse(isLauncherEnabled(unset, Season.CHRISTMAS))
        assertTrue(isLauncherEnabled(PackageManager.COMPONENT_ENABLED_STATE_ENABLED, Season.CHRISTMAS))
        assertFalse(isLauncherEnabled(PackageManager.COMPONENT_ENABLED_STATE_DISABLED, Season.DEFAULT))
    }

    @Test
    fun theOverrideIsAnExactSeasonId() {
        assertEquals(Season.CHRISTMAS, seasonOrNull("christmas"))
        assertEquals(Season.APRIL_FOOLS, seasonOrNull("april-fools"))
        assertNull(seasonOrNull("april_fools"))
        assertNull(seasonOrNull("Christmas"))
        assertNull(seasonOrNull(""))
        assertNull(seasonOrNull(null))
        for (season in Season.entries) assertEquals(season, seasonOrNull(season.id))
    }

    @Test
    fun theLogoFollowsTheSeasonOnlyInAReleaseWithSeasonsOn() {
        assertEquals(Season.HALLOWEEN, logoSeason(seasonal = true, debugBuild = false, today = Season.HALLOWEEN))
        assertEquals(Season.DEFAULT, logoSeason(seasonal = false, debugBuild = false, today = Season.HALLOWEEN))
        assertEquals(Season.DEFAULT, logoSeason(seasonal = true, debugBuild = true, today = Season.HALLOWEEN))
    }

    @Test
    fun everySeasonHasItsOwnLogoAndItsOwnAlias() {
        assertEquals(Season.entries.size, Season.entries.map(::logoRes).toSet().size)
        assertEquals(Season.entries.size, Season.entries.map(::aliasName).toSet().size)
    }

    @Test
    fun onlyATilePressOverARunningTaskIsRedundant() {
        val tv = setOf(Intent.CATEGORY_LEANBACK_LAUNCHER)
        assertTrue(isRedundantLauncherStart(false, Intent.ACTION_MAIN, tv))
        assertTrue(isRedundantLauncherStart(false, Intent.ACTION_MAIN, setOf(Intent.CATEGORY_LAUNCHER)))
        assertFalse(isRedundantLauncherStart(true, Intent.ACTION_MAIN, tv))
        assertFalse(isRedundantLauncherStart(false, Intent.ACTION_MAIN, null))
        assertFalse(isRedundantLauncherStart(false, Intent.ACTION_VIEW, tv))
    }
}
