package io.github.quentinpn.viewer.tv.settings

/**
 * « Cache des images » of the settings (ADR 0080): the bounds of Coil's disk cache and
 * the pure French texts of its rows. Android reads and clears it in `SettingsScreen`.
 */

/** Directory under `cacheDir`: the one Coil's default disk cache already used, so posters already saved are kept. */
const val IMAGE_CACHE_DIRECTORY = "coil3_disk_cache"

/** Fixed bound: 256 Mo, a few thousand posters and backdrops, a small share of a Shield's storage. */
const val IMAGE_CACHE_MAX_BYTES = 256L * 1024 * 1024

private const val MEGABYTE = 1024L * 1024
private const val GIGABYTE = 1024L * MEGABYTE

/**
 * A size in binary units with French words: « 84 Mo », « 1,2 Go », « 2 Go »,
 * « moins de 1 Mo » for a few bytes, « 0 Mo » for none. Rounded to the nearest unit.
 */
fun sizeLabel(bytes: Long): String {
    if (bytes <= 0) return "0 Mo"
    if (bytes < MEGABYTE) return "moins de 1 Mo"
    val megabytes = (bytes + MEGABYTE / 2) / MEGABYTE
    if (megabytes < 1024) return "$megabytes Mo"
    val tenths = (bytes * 10 + GIGABYTE / 2) / GIGABYTE
    return if (tenths % 10 == 0L) "${tenths / 10} Go" else "${tenths / 10},${tenths % 10} Go"
}

/** What the « Cache des images » row knows: still measuring, the measure, or no disk cache to read. */
sealed interface ImageCacheUsage {
    data object Measuring : ImageCacheUsage

    data class Measured(val size: Long, val maxSize: Long) : ImageCacheUsage

    data object Unavailable : ImageCacheUsage
}

/** « 84 Mo sur 256 Mo ». */
fun usageLabel(usage: ImageCacheUsage): String = when (usage) {
    ImageCacheUsage.Measuring -> "Calcul en cours…"
    is ImageCacheUsage.Measured -> "${sizeLabel(usage.size)} sur ${sizeLabel(usage.maxSize)}"
    ImageCacheUsage.Unavailable -> "Taille inconnue"
}

/** The « Vider le cache » row: nothing yet, running (OK ignored), done, or failed. */
enum class CacheClearing(val label: String) {
    Idle(""),
    Running("En cours…"),
    Done("Vidé"),
    Failed("Échec"),
}
