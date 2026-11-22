package io.github.quentinpn.viewer.tv.request

import io.github.quentinpn.viewer.tv.emby.trailerUrl

/** A YouTube video id: letters, digits, `-` and `_`. */
private val TRAILER_KEY = Regex("[A-Za-z0-9_-]{1,64}")

/**
 * The YouTube page of [key], the `trailerKey` of a title (Request only keeps YouTube trailers),
 * checked again by [trailerUrl]; null when it is not a plain video id.
 */
fun trailerPage(key: String?): String? =
    key?.takeIf { TRAILER_KEY.matches(it) }?.let { trailerUrl("https://www.youtube.com/watch?v=$it") }
