package io.github.quentinpn.viewer.tv.emby

import okhttp3.HttpUrl

/** The `ItemSortBy` the server shuffles with, listed by the `/Users/{UserId}/Items` documentation (ADR 0077). */
const val RANDOM_SORT = "Random"

/**
 * The grid's items query turned into one random title (ADR 0077): parent, types and
 * filters stay, the sort becomes [RANDOM_SORT] and the answer one id with no image.
 */
fun randomPick(query: HttpUrl.Builder): HttpUrl.Builder = query
    .setQueryParameter("SortBy", RANDOM_SORT)
    // A shuffle has no direction
    .removeAllQueryParameters("SortOrder")
    .removeAllQueryParameters("StartIndex")
    .setQueryParameter("Limit", "1")
    .setQueryParameter("EnableImages", "false")
    .setQueryParameter("EnableUserData", "false")
