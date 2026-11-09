package io.github.quentinpn.viewer.tv.emby

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

// Playback negotiation, ported from the Windows client (src-tauri/src/emby/playback.rs,
// ADR 0007 and 0009): the libmpv profile, the choice of Direct Play, Direct Stream or
// HLS transcode, and stream URLs with their token removed (it travels in a header).

/** Sent when no cap is chosen, as on Windows (ADR 0037). */
const val MAX_STREAMING_BITRATE = 400_000_000L

/** Text formats a transcode delivers as separate files instead of burning them in. */
private val EXTERNAL_TEXT_SUBTITLES = listOf("ass", "ssa", "subrip", "srt", "vtt")

/** Query parameters that carry the token in server-built URLs (compared lowercase). */
private val TOKEN_PARAMS = setOf("api_key", "apikey", "x-emby-token", "accesstoken")

enum class PlayMethod { DirectPlay, DirectStream, Transcode }

/** An external subtitle file, URL without token. */
data class ExternalSubtitle(val url: String, val title: String?, val language: String?, val selected: Boolean)

/** The source chosen for the player. [url] holds no token. */
data class PlaybackPlan(
    val method: PlayMethod,
    val url: String,
    /** Extra headers the server requires for this source (never authentication). */
    val headers: Map<String, String>,
    val mediaSourceId: String,
    val playSessionId: String?,
    val runTimeTicks: Long?,
    val subtitles: List<ExternalSubtitle>,
    /** Tracks the server picked from the account's preferences, that this playback can show. */
    val serverTracks: ServerTracks = ServerTracks(),
)

/**
 * Emby `Index` of the audio and subtitle the server picked (`DefaultAudioStreamIndex`,
 * `DefaultSubtitleStreamIndex`); null when it named none or one this playback cannot
 * show. Same filter as `tracks::choose` on Windows (ADR 0035): when playing the file,
 * only its own streams, as the server's external files are not loaded; for a
 * transcode, only subtitles delivered as files (the others would be burnt in).
 */
data class ServerTracks(val audio: Int? = null, val subtitle: Int? = null)

internal fun serverTracks(source: MediaSourceInfo, method: PlayMethod): ServerTracks {
    fun pick(index: Int?, type: String, shown: (MediaStreamDto) -> Boolean) =
        index?.takeIf { i -> source.mediaStreams.any { it.index == i && it.type == type && shown(it) } }
    return ServerTracks(
        audio = pick(source.defaultAudioStreamIndex, "Audio") { !it.isExternal },
        subtitle = pick(source.defaultSubtitleStreamIndex, "Subtitle") {
            if (method == PlayMethod.Transcode) it.deliveryMethod == "External" && it.deliveryUrl != null else !it.isExternal
        },
    )
}

/**
 * Same declaration as the Windows libmpv profile: the mpv-android build ships the
 * same FFmpeg decoders. What actually decodes in hardware is in the Gate TV-1 evidence.
 */
fun deviceProfile(maxStreamingBitrate: Long = MAX_STREAMING_BITRATE) = DeviceProfile(
    name = "Viewer TV (libmpv)",
    maxStreamingBitrate = maxStreamingBitrate,
    directPlayProfiles = listOf(
        DirectPlayProfile(
            type = "Video",
            container = "mkv,webm,mp4,m4v,mov,avi,ts,m2ts,mpegts,mpg,mpeg,wmv,asf,flv,3gp,ogv",
            videoCodec = "h264,hevc,av1,vp8,vp9,mpeg4,mpeg2video,mpeg1video,vc1,wmv3,msmpeg4v3,h263,theora",
            audioCodec = "aac,ac3,eac3,dts,truehd,mlp,flac,opus,vorbis,mp3,mp2,alac,pcm_s16le,pcm_s24le," +
                "pcm_bluray,pcm_dvd,wmav2,wmapro",
        ),
    ),
    transcodingProfiles = listOf(
        TranscodingProfile(
            type = "Video",
            container = "ts",
            protocol = "hls",
            videoCodec = "h264",
            audioCodec = "aac,ac3,eac3",
            context = "Streaming",
            maxAudioChannels = "8",
            breakOnNonKeyFrames = true,
        ),
    ),
    subtitleProfiles =
        listOf("ass", "ssa", "subrip", "srt", "pgssub", "dvdsub", "vtt", "mov_text").map { SubtitleProfile(it, "Embed") } +
            EXTERNAL_TEXT_SUBTITLES.map { SubtitleProfile(it, "External") },
)

/** Picks the first source the server offers and the best way to play it. */
fun plan(server: ServerUrl, response: PlaybackInfoResponse): PlaybackPlan {
    when (response.errorCode) {
        null -> {}
        "NotAllowed" -> throw EmbyException(EmbyError.PlaybackRefused("lecture non autorisée pour ce compte"))
        "RateLimitExceeded" -> throw EmbyException(EmbyError.PlaybackRefused("trop de lectures en cours sur le serveur"))
        else -> throw EmbyException(EmbyError.NoCompatibleStream)
    }
    val source = response.mediaSources.firstOrNull() ?: throw EmbyException(EmbyError.NoCompatibleStream)
    // A transcode also fills DirectStreamUrl with the transcoding URL, so the flags
    // decide, not which URL is present.
    val (method, raw) = when {
        source.directStreamUrl != null && source.supportsDirectPlay -> PlayMethod.DirectPlay to source.directStreamUrl
        source.directStreamUrl != null && source.supportsDirectStream -> PlayMethod.DirectStream to source.directStreamUrl
        source.transcodingUrl != null && source.supportsTranscoding -> PlayMethod.Transcode to source.transcodingUrl
        else -> throw EmbyException(EmbyError.NoCompatibleStream)
    }
    val subtitles = if (method == PlayMethod.Transcode) {
        source.mediaStreams
            .filter { it.type == "Subtitle" && it.deliveryMethod == "External" && it.deliveryUrl != null }
            .map {
                ExternalSubtitle(
                    url = streamUrl(server, it.deliveryUrl!!),
                    title = it.title,
                    language = it.language,
                    selected = it.index == source.defaultSubtitleStreamIndex,
                )
            }
    } else {
        emptyList()
    }
    return PlaybackPlan(
        method = method,
        url = streamUrl(server, raw),
        headers = requiredHeaders(source.requiredHttpHeaders.orEmpty()),
        mediaSourceId = source.id,
        playSessionId = response.playSessionId,
        runTimeTicks = source.runTimeTicks?.takeIf { it > 0 },
        subtitles = subtitles,
        serverTracks = serverTracks(source, method),
    )
}

/**
 * Absolute stream URL from a server-relative `/videos/…` path, without any
 * token-bearing parameter. Anything else (absolute URL, other path) is refused.
 */
fun streamUrl(server: ServerUrl, raw: String): String {
    fun invalid(): Nothing = throw EmbyException(EmbyError.InvalidResponse("adresse de flux inattendue"))
    if (!raw.startsWith("/") || raw.startsWith("//") || '\\' in raw) invalid()
    val base = server.api("/")
    // HttpUrl resolves "..", "%2e%2e" included, so the prefix check sees the final path
    val url: HttpUrl = "${server}/emby$raw".toHttpUrlOrNull() ?: invalid()
    val videos = "${base.encodedPath}videos/"
    if (url.scheme != base.scheme || url.host != base.host || url.port != base.port ||
        !url.encodedPath.lowercase().startsWith(videos.lowercase())
    ) {
        invalid()
    }
    val builder = url.newBuilder().query(null).fragment(null)
    for (i in 0 until url.querySize) {
        val name = url.queryParameterName(i)
        if (name.lowercase() !in TOKEN_PARAMS) builder.addQueryParameter(name, url.queryParameterValue(i))
    }
    return builder.build().toString()
}

/**
 * Server-required headers that are safe to hand to mpv: plain names, no control
 * characters, nothing that could replace authentication.
 */
internal fun requiredHeaders(headers: Map<String, String>): Map<String, String> =
    headers.filter { (name, value) ->
        val lower = name.lowercase()
        name.isNotEmpty() &&
            name.all { (it.isLetterOrDigit() && it.code < 128) || it == '-' } &&
            "auth" !in lower && "token" !in lower && "cookie" !in lower &&
            value.none { it.isISOControl() || it == ',' }
    }.toSortedMap()
