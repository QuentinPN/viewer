package io.github.quentinpn.viewer.tv

import android.content.Context
import android.os.Build
import coil3.ImageLoader
import coil3.disk.DiskCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import io.github.quentinpn.viewer.tv.emby.DeviceIdentity
import io.github.quentinpn.viewer.tv.emby.EmbyClient
import io.github.quentinpn.viewer.tv.emby.EmbyException
import io.github.quentinpn.viewer.tv.emby.SegmentSources
import io.github.quentinpn.viewer.tv.emby.ServerUrl
import io.github.quentinpn.viewer.tv.player.AccountTracks
import io.github.quentinpn.viewer.tv.player.accountTracks
import io.github.quentinpn.viewer.tv.session.PendingStopStore
import io.github.quentinpn.viewer.tv.session.Session
import io.github.quentinpn.viewer.tv.session.SessionStore
import io.github.quentinpn.viewer.tv.settings.IMAGE_CACHE_DIRECTORY
import io.github.quentinpn.viewer.tv.settings.IMAGE_CACHE_MAX_BYTES
import okhttp3.OkHttpClient
import okio.Path.Companion.toOkioPath

/** Process-wide objects: one HTTP client (shared connection pool) and the session store. */
object AppServices {
    val http: OkHttpClient by lazy { EmbyClient.defaultHttp() }

    /** Playback reports, with the stop reports kept on disk when the network fails (ADR 0040, 0066). */
    fun reporter(context: Context): PlaybackReporter = playbackReporter ?: synchronized(this) {
        playbackReporter ?: PlaybackReporter(PendingStopStore(context)).also { playbackReporter = it }
    }

    @Volatile private var playbackReporter: PlaybackReporter? = null

    /** Intro and credits sources, with their in-memory cache (ADR 0048). */
    fun segments(context: Context): SegmentSources = segmentSources ?: synchronized(this) {
        segmentSources ?: SegmentSources(SegmentSources.http(http, device(context).version)).also { segmentSources = it }
    }

    @Volatile private var segmentSources: SegmentSources? = null

    /**
     * The account's track preferences (ADR 0065), read once per server and user while the
     * app runs. Null when they cannot be read: playback then keeps the server's and mpv's
     * picks, and the next player tries again.
     */
    suspend fun accountTracks(client: EmbyClient, userId: String): AccountTracks? {
        val key = "${client.server.url}|$userId"
        accountCache?.takeIf { it.first == key }?.let { return it.second }
        val read = try {
            client.userConfiguration(userId)?.let(::accountTracks)
        } catch (_: EmbyException) {
            return null
        }
        accountCache = key to read
        return read
    }

    @Volatile private var accountCache: Pair<String, AccountTracks?>? = null

    fun store(context: Context) = SessionStore(context)

    fun device(context: Context): DeviceIdentity {
        val version = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "0"
        return DeviceIdentity(Build.MODEL ?: "Android TV", store(context).deviceId, version)
    }

    fun client(context: Context, session: Session) =
        EmbyClient(ServerUrl.parse(session.server), device(context), http).withToken(session.token)

    /**
     * Posters through the same client, with the Emby authorization header added only
     * for this server's origin: image URLs never carry the token.
     */
    fun images(context: Context, client: EmbyClient): ImageLoader {
        val base = client.server.url
        val header = client.authorizationHeader()
        val imagesHttp = http.newBuilder().addInterceptor { chain ->
            val request = chain.request()
            val sameOrigin = request.url.scheme == base.scheme && request.url.host == base.host && request.url.port == base.port
            chain.proceed(if (sameOrigin) request.newBuilder().header("X-Emby-Authorization", header).build() else request)
        }.build()
        val app = context.applicationContext
        return ImageLoader.Builder(context)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { imagesHttp })) }
            .diskCache { imageDiskCache(app) }
            .build()
    }

    /**
     * One disk cache per process, shared by every loader (one per session): two caches
     * on the same directory would corrupt its journal. Bounded to 256 Mo (ADR 0080).
     */
    private fun imageDiskCache(context: Context): DiskCache = imageCache ?: synchronized(this) {
        imageCache ?: DiskCache.Builder()
            .directory(context.cacheDir.toOkioPath() / IMAGE_CACHE_DIRECTORY)
            .maxSizeBytes(IMAGE_CACHE_MAX_BYTES)
            .build()
            .also { imageCache = it }
    }

    @Volatile private var imageCache: DiskCache? = null
}
