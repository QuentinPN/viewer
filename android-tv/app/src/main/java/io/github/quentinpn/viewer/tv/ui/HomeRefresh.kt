package io.github.quentinpn.viewer.tv.ui

// The home reads its shelves again on its own: a title added on the
// server shows in « Ajoutés récemment » without restarting the app.

/** While the home stays shown, its shelves are read again after this long. */
internal const val HOME_STALE_MS = 5 * 60_000L

/** Back to the app from the launcher: shelves older than this are read again. */
internal const val HOME_STALE_ON_RETURN_MS = 30_000L

/** How often the shown home checks its age. */
internal const val HOME_CHECK_MS = 60_000L

/** True when shelves loaded at [loadedAt] (0: never) are at least [after] ms old. */
internal fun homeIsStale(loadedAt: Long, now: Long, after: Long): Boolean =
    loadedAt > 0 && now - loadedAt >= after
