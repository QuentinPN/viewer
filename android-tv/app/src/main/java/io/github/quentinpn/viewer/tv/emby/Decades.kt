package io.github.quentinpn.viewer.tv.emby

/** Oldest decade the grid offers: earlier years are not films. */
const val FIRST_DECADE = 1880

/** Latest decade accepted from the server or the preferences; a later year is a typo. */
const val LAST_DECADE = 2190

/** The decade holding [year], by its first year: 1987 → 1980. */
fun decadeOf(year: Int): Int = Math.floorDiv(year, 10) * 10

/** A decade the server can be asked for: its first year, between [FIRST_DECADE] and [LAST_DECADE]. */
fun isUsableDecade(decade: Int): Boolean = decade % 10 == 0 && decade in FIRST_DECADE..LAST_DECADE

/** The `Years` value of [decade]: its ten production years, comma-separated. */
fun decadeYears(decade: Int): String = (decade until decade + 10).joinToString(",")

/** The decades the [years] of a library fall in, newest first, without duplicates nor odd years. */
fun decadesOf(years: List<Int?>): List<Int> =
    years.filterNotNull().map(::decadeOf).filter(::isUsableDecade).distinct().sortedDescending()
