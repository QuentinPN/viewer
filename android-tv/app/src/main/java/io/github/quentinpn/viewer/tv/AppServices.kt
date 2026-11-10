package io.github.quentinpn.viewer.tv

import android.content.Context
import android.os.Build
import coil3.ImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import io.github.quentinpn.viewer.tv.emby.DeviceIdentity
import io.github.quentinpn.viewer.tv.emby.EmbyClient
import io.github.quentinpn.viewer.tv.emby.SegmentSources
import io.github.quentinpn.viewer.tv.emby.ServerUrl
import io.github.quentinpn.viewer.tv.session.PendingStopStore
import io.github.quentinpn.viewer.tv.session.Session
import io.github.quentinpn.viewer.tv.session.SessionStore
import okhttp3.OkHttpClient

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
        return ImageLoader.Builder(context)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { imagesHttp })) }
            .build()
    }
}
