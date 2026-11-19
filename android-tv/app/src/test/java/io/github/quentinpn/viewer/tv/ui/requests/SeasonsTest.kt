package io.github.quentinpn.viewer.tv.ui.requests

import io.github.quentinpn.viewer.tv.request.MediaDetails
import io.github.quentinpn.viewer.tv.request.MediaStatus
import io.github.quentinpn.viewer.tv.request.MediaType
import io.github.quentinpn.viewer.tv.request.QuotaUsage
import io.github.quentinpn.viewer.tv.request.RequestQuota
import io.github.quentinpn.viewer.tv.request.Season
import io.github.quentinpn.viewer.tv.request.viewerJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

private fun fixture(name: String) = File("../../contracts/request/fixtures/$name.json").readText()

private fun details(name: String) = viewerJson.decodeFromString(MediaDetails.serializer(), fixture(name))

private const val TODAY = "2026-11-19"

class SeasonsTest {
    private val series = details("media-tv")

    @Test
    fun theAiredSeasonsWithNoStatusOrAPartialOneAreRequestable() {
        // 0 specials, 1 available, 4 not aired yet, 5 pending and undated: all out
        assertEquals(listOf(2, 3), requestableSeasons(series, TODAY))
    }

    @Test
    fun aSeasonAiringAfterTodayIsNotRequestable() {
        // Season 3 airs on 2024-01-18, season 2 (partial) on 2024-10-03, season 4 on 2027-10-08
        assertEquals(emptyList<Int>(), requestableSeasons(series, "2024-01-17"))
        assertEquals(listOf(3), requestableSeasons(series, "2024-01-18"))
        assertEquals(listOf(2, 3), requestableSeasons(series, "2024-10-03"))
        assertEquals(listOf(2, 3, 4), requestableSeasons(series, "2027-10-08"))
    }

    @Test
    fun theSpecialsAreNeverRequestable() {
        val specials = Season(seasonNumber = 0, episodeCount = 3, airDate = "2021-11-20")
        assertFalse(isSeasonRequestable(specials, TODAY))
    }

    @Test
    fun aDeclinedSeasonCanBeAskedAgainButAnEmptyOneCannot() {
        assertTrue(isSeasonRequestable(Season(1, 8, "2020-01-01", status = MediaStatus.Declined), TODAY))
        assertFalse(isSeasonRequestable(Season(1, 0, "2020-01-01"), TODAY))
        assertFalse(isSeasonRequestable(Season(1, 8, null), TODAY))
    }

    @Test
    fun aPartialSeriesWithASeasonToAskForIsRequestable() {
        assertTrue(isRequestable(series, TODAY))
    }

    @Test
    fun aSeriesWithNothingLeftToAskForIsNot() {
        // Its own status is partial, which alone leaves room for a request; waiting on a request does not
        val done = series.copy(status = MediaStatus.Pending, seasons = series.seasons.filter { it.seasonNumber == 1 })
        assertFalse(isRequestable(done, TODAY))
        assertTrue(isRequestable(done.copy(status = MediaStatus.Partial), TODAY))
        assertFalse(isRequestable(series.copy(status = MediaStatus.Available), TODAY))
    }

    @Test
    fun askingNeedsASeasonToTickWhenTheSeasonsAreListed() {
        // Partial overall, but every listed season is available, waiting or not aired: nothing to tick
        val stuck = series.copy(seasons = series.seasons.filter { it.seasonNumber in setOf(1, 4, 5) })
        assertTrue(isRequestable(stuck, TODAY))
        assertFalse(canAskNow(stuck, TODAY))
        assertTrue(canAskNow(series, TODAY))
        // No season listed: asked for whole
        assertTrue(canAskNow(series.copy(seasons = emptyList()), TODAY))
        assertTrue(canAskNow(details("media-movie"), TODAY))
    }

    @Test
    fun aFilmIsRequestableWithNoStatusPartialOrDeclinedOnly() {
        val film = details("media-movie")
        assertNull(film.status)
        assertTrue(isRequestable(film, TODAY))
        assertTrue(isRequestable(film.copy(status = MediaStatus.Partial), TODAY))
        assertTrue(isRequestable(film.copy(status = MediaStatus.Declined), TODAY))
        for (status in listOf(MediaStatus.Available, MediaStatus.Pending, MediaStatus.Requested, MediaStatus.Downloading)) {
            assertFalse(status.name, isRequestable(film.copy(status = status), TODAY))
        }
    }

    @Test
    fun aSeasonsLabelIsInTheSingularForOne() {
        assertEquals("Demander 1 saison", seasonsLabel(1))
        assertEquals("Demander 2 saisons", seasonsLabel(2))
    }

    @Test
    fun aSeasonThatCannotBeTickedSaysWhy() {
        val byNumber = series.seasons.associateBy { it.seasonNumber }
        assertNull(seasonNote(byNumber.getValue(2), TODAY))
        assertEquals("Disponible", seasonNote(byNumber.getValue(1), TODAY))
        assertEquals("Pas encore diffusée", seasonNote(byNumber.getValue(4), TODAY))
        assertEquals("En attente", seasonNote(byNumber.getValue(5), TODAY))
    }

    @Test
    fun theQuotaLineFollowsTheSpecAndHidesForAnExemptMember() {
        val quota = RequestQuota(QuotaUsage(limit = 10, days = 7, used = 2, remaining = 8), QuotaUsage(limit = 10, days = 7, used = 2, remaining = 8), exempt = false)
        assertEquals("Quota : 8 saisons sur 10 restantes", quotaLine(quota, MediaType.Tv))
        assertEquals("Quota : 8 films sur 10 restants", quotaLine(quota, MediaType.Movie))
        val last = quota.copy(movies = QuotaUsage(10, 7, 9, 1))
        assertEquals("Quota : 1 film sur 10 restant", quotaLine(last, MediaType.Movie))
        assertNull(quotaLine(quota.copy(exempt = true), MediaType.Tv))
    }

    @Test
    fun askingForMoreThanTheQuotaIsBlockedUnlessExempt() {
        val quota = RequestQuota(QuotaUsage(10, 7, 2, 8), QuotaUsage(5, 7, 4, 1), exempt = false)
        assertTrue(exceedsQuota(quota, MediaType.Tv, 2))
        assertFalse(exceedsQuota(quota, MediaType.Tv, 1))
        assertFalse(exceedsQuota(quota.copy(exempt = true), MediaType.Tv, 9))
        assertFalse(exceedsQuota(null, MediaType.Tv, 9))
    }

    @Test
    fun aRuntimeReadsInHoursAndMinutes() {
        assertEquals("48 min", runtimeLabel(48))
        assertEquals("1 h 52", runtimeLabel(112))
        assertEquals("2 h 05", runtimeLabel(125))
        assertNull(runtimeLabel(null))
        assertNull(runtimeLabel(0))
    }
}
