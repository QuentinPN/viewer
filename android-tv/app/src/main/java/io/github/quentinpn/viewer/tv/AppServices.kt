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
import io.github.quentinpn.viewer.tv.sync.SyncHub
import io.github.quentinpn.viewer.tv.request.EmbyIdentity
import io.github.quentinpn.viewer.tv.request.ImageAuth
import io.github.quentinpn.viewer.tv.request.RequestRepository
import io.github.quentinpn.viewer.tv.request.RequestStore
import io.github.quentinpn.viewer.tv.request.deviceLabel
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

    /** The session-wide socket and the linked playback (ADR 0123, 0124). */
    fun sync(context: Context): SyncHub = syncHub ?: synchronized(this) {
        syncHub ?: SyncHub(context.applicationContext).also { syncHub = it }
    }

    @Volatile private var syncHub: SyncHub? = null

    fun store(context: Context) = SessionStore(context)

    /** Request (ADR 0125): one repository per process, so its exchange lock covers every call. */
    fun requests(context: Context): RequestRepository = viewerRequests ?: synchronized(this) {
        val app = context.applicationContext
        viewerRequests ?: RequestRepository(
            store = RequestStore(app),
            http = http,
            identity = { embyIdentity(app) },
            deviceName = deviceLabel(Build.MODEL ?: "Android TV"),
            userAgent = "Viewer-TV/${device(app).version}",
        ).also { viewerRequests = it }
    }

    @Volatile private var viewerRequests: RequestRepository? = null

    /** The open Emby session as the identity traded for a Request token; the server id falls back to its address. */
    private fun embyIdentity(context: Context): EmbyIdentity? {
        val store = store(context)
        val session = store.load() ?: return null
        return EmbyIdentity(store.server(session.key)?.serverId ?: session.server, session.userId, session.token)
    }

    /** Posters and backdrops of Request: the Bearer goes to its origin only, never in the URL. */
    fun requestImages(context: Context): ImageLoader {
        val imagesHttp = http.newBuilder().addInterceptor(ImageAuth(requests(context))).build()
        return ImageLoader.Builder(context)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { imagesHttp })) }
            .diskCache { imageDiskCache(context.applicationContext) }
            .build()
    }

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
