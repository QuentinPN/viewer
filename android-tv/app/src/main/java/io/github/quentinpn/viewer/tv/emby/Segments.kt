package io.github.quentinpn.viewer.tv.emby

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

// Intro and credits segments (ADR 0030, 0048), ported from the Windows client
// (src-tauri/src/segments): Emby's chapter markers first, then IntroDB, then
// TheIntroDB. Only the film's or series' provider ids leave the app, never a
// token, a server address or a title.

private const val TICKS_PER_SECOND = 10_000_000.0

/** Shorter than this is not worth a button. */
private const val MIN_SEGMENT_SECONDS = 1.0

/** TheIntroDB refuses longer submissions; anything beyond is malformed. */
private const val MAX_POSITION_SECONDS = 6.0 * 3600

/** Larger answers are refused: a real one is under 1 KiB. */
private const val MAX_BODY_BYTES = 64 * 1024L

/** Items kept in memory; the cache is emptied when full. */
private const val CACHE_ENTRIES = 256

enum class SegmentKind { Intro, Credits }

enum class SegmentSource { Emby, IntroDb, TheIntroDb }

/** A range to skip, in seconds of the file. A null [end] runs to the end of the file (credits only). */
data class Segment(val kind: SegmentKind, val start: Double, val end: Double?, val source: SegmentSource) {
    companion object {
        /** Null for a range that cannot be skipped: negative, reversed, too short, beyond six hours, or an intro with no end. */
        fun of(kind: SegmentKind, start: Double, end: Double?, source: SegmentSource): Segment? {
            fun valid(t: Double) = t.isFinite() && t in 0.0..MAX_POSITION_SECONDS
            if (!valid(start)) return null
            if (end == null && kind == SegmentKind.Intro) return null
            if (end != null && (!valid(end) || end - start < MIN_SEGMENT_SECONDS)) return null
            return Segment(kind, start, end, source)
        }
    }
}

/** Segments from Emby's chapter markers: `IntroStart`–`IntroEnd`, and `CreditsStart` to the end of the file. */
fun embyMarkers(chapters: List<ChapterInfo>): List<Segment> {
    fun at(marker: String) = chapters.firstOrNull { it.markerType == marker }?.let { it.startPositionTicks / TICKS_PER_SECOND }
    val introStart = at("IntroStart")
    val introEnd = at("IntroEnd")
    val intro = if (introStart != null && introEnd != null) Segment.of(SegmentKind.Intro, introStart, introEnd, SegmentSource.Emby) else null
    val credits = at("CreditsStart")?.let { Segment.of(SegmentKind.Credits, it, null, SegmentSource.Emby) }
    return listOfNotNull(intro, credits)
}

/** External ids of a film or series, validated before they reach a URL. */
data class MediaIds(val imdb: String? = null, val tmdb: Long? = null, val tvdb: Long? = null) {
    val isEmpty get() = imdb == null && tmdb == null && tvdb == null

    companion object {
        private val IMDB = Regex("tt[0-9]{7,8}")
        private val DIGITS = Regex("[0-9]{1,9}")

        /** From Emby's `ProviderIds`, whatever the key case (`Imdb`, `IMDB`). */
        fun from(ids: Map<String, String>): MediaIds {
            fun get(name: String) = ids.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.trim()
            fun number(name: String) = get(name)?.takeIf { DIGITS.matches(it) }?.toLong()?.takeIf { it in 1..100_000_000 }
            return MediaIds(get("Imdb")?.takeIf { IMDB.matches(it) }, number("Tmdb"), number("Tvdb"))
        }
    }
}

/** What the external services are asked: a film, or an episode by its series' ids and numbers. */
data class SegmentQuery(
    val ids: MediaIds,
    /** Season and episode numbers, for an episode. */
    val episode: Pair<Int, Int>? = null,
    /** Length of the file, which picks the release version at TheIntroDB. */
    val durationMs: Long? = null,
)

/**
 * The query for [item]: a film by its ids, an episode by [series]' ids and its numbers.
 * Null when the item has no usable id, or the series is missing for an episode.
 */
fun segmentQuery(item: BaseItemDto, series: BaseItemDto?): SegmentQuery? {
    val durationMs = item.runTimeTicks?.takeIf { it > 0 }?.let { it / 10_000 }
    val query = when (item.type) {
        "Movie" -> SegmentQuery(MediaIds.from(item.providerIds), null, durationMs)
        "Episode" -> {
            val season = item.parentIndexNumber
            val episode = item.indexNumber
            if (series == null || series.id != item.seriesId || season == null || episode == null) return null
            SegmentQuery(MediaIds.from(series.providerIds), season to episode, durationMs)
        }
        else -> return null
    }
    return query.takeUnless { it.ids.isEmpty }
}

/** One source's answer: its segments, and the kinds it confirmed the item has none of (IntroDB's `no_intro`, `no_outro`). */
data class Found(val segments: List<Segment> = emptyList(), val absent: Set<SegmentKind> = emptySet())

private val lenient = Json { ignoreUnknownKeys = true; explicitNulls = false }

@Serializable
private class IntroDbAnswer(
    val intro: IntroDbSegment? = null,
    /** End credits. */
    val outro: IntroDbSegment? = null,
    @SerialName("no_intro") val noIntro: Boolean = false,
    @SerialName("no_outro") val noOutro: Boolean = false,
)

@Serializable
private class IntroDbSegment(@SerialName("start_sec") val startSec: Double, @SerialName("end_sec") val endSec: Double)

@Serializable
private class TheIntroDbAnswer(val intro: List<TheIntroDbRange> = emptyList(), val credits: List<TheIntroDbRange> = emptyList())

@Serializable
private class TheIntroDbRange(@SerialName("start_ms") val startMs: Long? = null, @SerialName("end_ms") val endMs: Long? = null)

private inline fun <T> decode(body: String, read: (String) -> T): T? = try {
    read(body)
} catch (_: SerializationException) {
    null
} catch (_: IllegalArgumentException) {
    null
}

/** `GET /segments`: `null` is "not timed yet", unless `no_*` confirms there is none. */
fun parseIntroDb(body: String): Found? {
    val answer = decode(body) { lenient.decodeFromString(IntroDbAnswer.serializer(), it) } ?: return null
    fun segment(kind: SegmentKind, s: IntroDbSegment?) = s?.let { Segment.of(kind, it.startSec, it.endSec, SegmentSource.IntroDb) }
    return Found(
        listOfNotNull(segment(SegmentKind.Intro, answer.intro), segment(SegmentKind.Credits, answer.outro)),
        setOfNotNull(SegmentKind.Intro.takeIf { answer.noIntro }, SegmentKind.Credits.takeIf { answer.noOutro }),
    )
}

/**
 * `GET /v4/media`: a null start is the beginning of the file, a null end its end;
 * an intro with no end, or a 0 end ("no segment"), is dropped.
 */
fun parseTheIntroDb(body: String): Found? {
    val answer = decode(body) { lenient.decodeFromString(TheIntroDbAnswer.serializer(), it) } ?: return null
    fun ranges(kind: SegmentKind, list: List<TheIntroDbRange>) = list.filter { it.endMs != 0L }.mapNotNull {
        Segment.of(kind, (it.startMs ?: 0) / 1000.0, it.endMs?.let { ms -> ms / 1000.0 }, SegmentSource.TheIntroDb)
    }
    return Found(ranges(SegmentKind.Intro, answer.intro) + ranges(SegmentKind.Credits, answer.credits))
}

/** A failure worth retrying at the next playback: network, timeout, 429, 5xx or an unreadable answer. */
private class Transient : Exception()

/**
 * IntroDB and TheIntroDB, both anonymous, asked in order with definitive answers
 * kept in memory for the life of the process. [http] must not carry Emby's
 * authorization: it never sees Emby requests.
 */
class SegmentSources(
    private val http: OkHttpClient,
    private val introDb: HttpUrl = "https://api.introdb.app".toHttpUrlOrNull()!!,
    private val theIntroDb: HttpUrl = "https://api.theintrodb.org/v4".toHttpUrlOrNull()!!,
) {
    private val cache = HashMap<String, List<Segment>>()

    fun cached(key: String): List<Segment>? = synchronized(cache) { cache[key] }

    /** IntroDB takes the IMDb id only. */
    fun introDbUrl(query: SegmentQuery): HttpUrl? {
        val imdb = query.ids.imdb ?: return null
        val builder = introDb.newBuilder().addPathSegment("segments").addQueryParameter("imdb_id", imdb)
        if (query.episode == null) builder.addQueryParameter("is_movie", "true")
        return builder.episode(query).build()
    }

    /** TheIntroDB takes one id, TVDB first, then TMDB, then IMDb, and the file length. */
    fun theIntroDbUrl(query: SegmentQuery): HttpUrl? {
        val ids = query.ids
        val (name, value) = ids.tvdb?.let { "tvdb_id" to it.toString() }
            ?: ids.tmdb?.let { "tmdb_id" to it.toString() }
            ?: ids.imdb?.let { "imdb_id" to it }
            ?: return null
        val builder = theIntroDb.newBuilder().addPathSegment("media").addQueryParameter(name, value)
        query.durationMs?.let { builder.addQueryParameter("duration_ms", it.toString()) }
        return builder.episode(query).build()
    }

    private fun HttpUrl.Builder.episode(query: SegmentQuery) = apply {
        query.episode?.let { (season, episode) ->
            addQueryParameter("season", season.toString())
            addQueryParameter("episode", episode.toString())
        }
    }

    /**
     * [markers] completed by the external sources when [query] is given. A source is
     * skipped once every kind is settled. Only complete answers are cached.
     */
    suspend fun lookup(key: String, markers: List<Segment>, query: SegmentQuery?): List<Segment> {
        val segments = markers.toMutableList()
        val settled = markers.mapTo(mutableSetOf()) { it.kind }
        var complete = true
        if (query != null) {
            val sources = listOf(introDbUrl(query) to ::parseIntroDb, theIntroDbUrl(query) to ::parseTheIntroDb)
            for ((url, parse) in sources) {
                if (settled.size == SegmentKind.entries.size) break
                if (url == null) continue
                try {
                    val found = fetch(url, parse)
                    val fresh = found.segments.filter { it.kind !in settled }
                    settled += fresh.map { it.kind }
                    settled += found.absent
                    segments += fresh
                } catch (_: Transient) {
                    complete = false
                }
            }
        }
        segments.sortBy { it.start }
        if (complete) {
            synchronized(cache) {
                if (cache.size >= CACHE_ENTRIES) cache.clear()
                cache[key] = segments.toList()
            }
        }
        return segments
    }

    private suspend fun fetch(url: HttpUrl, parse: (String) -> Found?): Found = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).header("Accept", "application/json").get().build()
        try {
            http.newCall(request).execute().use { res ->
                when (res.code) {
                    200 -> {}
                    429 -> throw Transient()
                    // A missing item, or a query it refuses: asking again will not help
                    in 400..499 -> return@use Found()
                    else -> throw Transient()
                }
                val source = res.body.source()
                if (source.request(MAX_BODY_BYTES + 1)) throw Transient()
                parse(source.buffer.readUtf8()) ?: throw Transient()
            }
        } catch (_: IOException) {
            throw Transient()
        }
    }

    companion object {
        /** Separate from Emby's requests: no Emby header, no redirect, short delays; TLS validated by default. */
        fun http(base: OkHttpClient, version: String): OkHttpClient = base.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(java.time.Duration.ofSeconds(3))
            .callTimeout(java.time.Duration.ofSeconds(4))
            .addInterceptor { chain -> chain.proceed(chain.request().newBuilder().header("User-Agent", "Viewer TV/$version").build()) }
            .build()
    }
}
