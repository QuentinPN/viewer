package io.github.quentinpn.viewer.tv.request

import io.github.quentinpn.viewer.tv.ui.requests.mergeNew
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.time.ZoneOffset

private fun fixture(name: String) = File("../../contracts/request/fixtures/$name.json").readText()

private val TOTALS = RequestTotals(pending = 2, approved = 1, declined = 3, cancelled = 4, sent = 5, failed = 6, available = 7)

class RequestViewsTest {
    private fun media(id: Int, type: MediaType = MediaType.Movie, embyItemId: String? = null, blocked: Blocked? = null) =
        MediaSummary(tmdbId = id, type = type, title = "T$id", embyItemId = embyItemId, blocked = blocked)

    @Test
    fun readIsOfferedOnlyWhenEmbyHasTheTitle() {
        assertEquals(EmbyTitle(MediaType.Movie, "51877"), embyTitle(MediaType.Movie, "51877"))
        assertEquals(EmbyTitle(MediaType.Tv, "9"), embyTitle(MediaType.Tv, "9"))
        assertNull(embyTitle(MediaType.Movie, null))
        assertNull(embyTitle(MediaType.Tv, ""))
    }

    @Test
    fun segmentsAskForTheStatusesOfTheSpecInOneCommaSeparatedParameter() {
        assertEquals(emptyList<RequestStatus>(), StatusFilter.All.statuses)
        assertEquals(listOf(RequestStatus.Pending), StatusFilter.Pending.statuses)
        assertEquals(listOf(RequestStatus.Approved, RequestStatus.Sent), StatusFilter.InProgress.statuses)
        assertEquals(listOf(RequestStatus.Available), StatusFilter.Available.statuses)
        assertEquals(listOf(RequestStatus.Declined, RequestStatus.Failed, RequestStatus.Cancelled), StatusFilter.Refused.statuses)
        assertEquals("status=approved,sent", myRequestsQuery(StatusFilter.InProgress.statuses, 1).first { it.first == "status" }.let { "status=" + it.second })
        assertEquals(null, myRequestsQuery(StatusFilter.All.statuses, 1).firstOrNull { it.first == "status" })
    }

    @Test
    fun everyStatusBelongsToExactlyOneSegmentOtherThanAll() {
        val owned = StatusFilter.entries.filter { it != StatusFilter.All }.flatMap { it.statuses }
        assertEquals(RequestStatus.entries.toSet(), owned.toSet())
        assertEquals(owned.size, owned.toSet().size)
    }

    @Test
    fun segmentCountsComeFromTheTotals() {
        assertEquals(2, StatusFilter.Pending.count(TOTALS))
        assertEquals(6, StatusFilter.InProgress.count(TOTALS))
        assertEquals(7, StatusFilter.Available.count(TOTALS))
        assertEquals(13, StatusFilter.Refused.count(TOTALS))
        assertEquals(28, StatusFilter.All.count(TOTALS))
        assertEquals(8, inFlightCount(TOTALS))
    }

    @Test
    fun theRealFixtureTotalsAddUp() {
        val page = viewerJson.decodeFromString(RequestPage.serializer(), fixture("request-page"))
        assertEquals(StatusFilter.entries.filter { it != StatusFilter.All }.sumOf { it.count(page.totals) }, StatusFilter.All.count(page.totals))
    }

    @Test
    fun searchOffersOnlyTitlesNotInEmbyAndNotBlocked() {
        val results = listOf(
            media(1),
            media(2, embyItemId = "51877"),
            media(3, blocked = Blocked(by = "keyword", keyword = "x")),
            media(4, MediaType.Tv),
            // The same title twice would crash a lazy row keyed by it
            media(1),
        )
        assertEquals(listOf(1, 4), toRequest(results).map { it.tmdbId })
    }

    @Test
    fun aMergedPageKeepsTheOrderAndDropsEntriesAlreadyHeld() {
        val merged = mergeNew(listOf(media(1), media(2)), listOf(media(2), media(3), media(3))) { it.key() }
        assertEquals(listOf(3), merged.map { it.tmdbId })
    }

    @Test
    fun aFilmAndASeriesWithTheSameIdAreTwoTitles() {
        val merged = mergeNew(listOf(media(7, MediaType.Movie)), listOf(media(7, MediaType.Tv))) { it.key() }
        assertEquals(listOf("tv:7"), merged.map { it.key() })
    }

    @Test
    fun eventsHaveFrenchLabelsAndAnUnknownOneShowsAsSent() {
        assertEquals("Demande envoyée", eventLabel("requested"))
        assertEquals("Approuvée", eventLabel("approved"))
        assertEquals("Refusée", eventLabel("declined"))
        assertEquals("Annulée", eventLabel("cancelled"))
        assertEquals("Envoyée", eventLabel("sent"))
        assertEquals("Échec", eventLabel("failed"))
        assertEquals("Nouvel essai", eventLabel("retried"))
        assertEquals("Disponible", eventLabel("available"))
        assertEquals("quelque-chose", eventLabel("quelque-chose"))
    }

    @Test
    fun datesReadInFrenchInTheZoneGiven() {
        assertEquals("15 nov. 2026", formatWhen("2026-11-15T20:12:00.000Z", zone = ZoneOffset.UTC))
        assertEquals("15 nov. 2026 · 20:12", formatWhen("2026-11-15T20:12:00.000Z", time = true, zone = ZoneOffset.UTC))
        assertEquals("16 nov. 2026 · 01:12", formatWhen("2026-11-15T20:12:00.000Z", time = true, zone = ZoneOffset.ofHours(5)))
        // An unreadable stamp falls back to its date part
        assertEquals("2026-11-15", formatWhen("2026-11-15 20:12"))
    }

    @Test
    fun aRequestMetaNamesItsKindSeasonsAndDay() {
        val view = viewerJson.decodeFromString(RequestDetail.serializer(), fixture("request-detail")).request
        assertEquals("Série · Saison 3 · 15 nov. 2026", requestMeta(view, ZoneOffset.UTC))
        assertNull(seasonsText(emptyList()))
        assertEquals("Saisons 1, 2", seasonsText(listOf(2, 1)))
    }
}
