package io.github.quentinpn.viewer.tv.request

import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response

/**
 * Adds the Request Bearer to image requests for the configured origin only (never to a
 * TMDB or Emby URL), and on a 401 renews the session once and replays. Image URLs never carry the token.
 */
class ImageAuth(private val repository: RequestRepository) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val origin = repository.origin()
        if (origin == null || !sameOrigin(request.url, origin)) return chain.proceed(request)
        val token = tokenOrNull(null) ?: return chain.proceed(request)
        val response = chain.proceed(request.withBearer(token))
        if (response.code != 401) return response
        response.close()
        val renewed = tokenOrNull(token) ?: return chain.proceed(request)
        return chain.proceed(request.withBearer(renewed))
    }

    private fun tokenOrNull(failed: SessionToken?): SessionToken? = try {
        runBlocking { repository.accessToken(failed) }
    } catch (_: RequestException) {
        null
    }

    private fun Request.withBearer(token: SessionToken) = newBuilder().header("Authorization", "Bearer ${token.value}").build()

    private fun sameOrigin(url: HttpUrl, origin: HttpUrl) =
        url.scheme == origin.scheme && url.host == origin.host && url.port == origin.port
}
