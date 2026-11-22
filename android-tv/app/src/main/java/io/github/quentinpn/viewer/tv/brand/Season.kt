package io.github.quentinpn.viewer.tv.brand

import java.time.LocalDate

/**
 * The seasonal logos, in priority order where periods overlap (most specific first, [DEFAULT] last).
 * The rules and the shared test vectors live in `contracts/seasons`; the desktop app has the same function.
 * [id] is the file name of the desktop logo, [res] the suffix of the Android drawables (`icon_<res>`, `banner_<res>`).
 */
enum class Season(val id: String) {
    APRIL_FOOLS("april-fools"),
    MUSIC("music"),
    BASTILLE("bastille"),
    VALENTINE("valentine"),
    NEW_YEAR("new-year"),
    HALLOWEEN("halloween"),
    EASTER("easter"),
    EPIPHANY("epiphany"),
    CHRISTMAS("christmas"),
    WINTER("winter"),
    SUMMER("summer"),
    AUTUMN("autumn"),
    DEFAULT("default"),
    ;

    /** [id] with `_`: the part of the drawable names after `icon_` and `banner_`. */
    val res: String get() = id.replace('-', '_')
}

/** Gregorian Easter Sunday (anonymous Gregorian / Meeus algorithm). */
private fun easterSunday(year: Int): LocalDate {
    val a = year % 19
    val b = year / 100
    val c = year % 100
    val d = b / 4
    val e = b % 4
    val f = (b + 8) / 25
    val g = (b - f + 1) / 3
    val h = (19 * a + b - d - g + 15) % 30
    val i = c / 4
    val k = c % 4
    val l = (32 + 2 * e + 2 * i - h - k) % 7
    val m = (a + 11 * h + 22 * l) / 451
    val month = (h + l - 7 * m + 114) / 31
    val day = (h + l - 7 * m + 114) % 31 + 1
    return LocalDate.of(year, month, day)
}

/**
 * The season of a local calendar date ([month] 1 to 12). Where periods overlap the most specific one wins:
 * april-fools, music, bastille, valentine, new-year, halloween, easter, epiphany, christmas, winter, summer, autumn.
 */
fun seasonFor(year: Int, month: Int, day: Int): Season {
    val md = month * 100 + day
    return when {
        md == 401 -> Season.APRIL_FOOLS
        md == 621 -> Season.MUSIC
        md == 714 -> Season.BASTILLE
        md in 210..214 -> Season.VALENTINE
        md >= 1227 || md == 101 -> Season.NEW_YEAR
        md in 1024..1031 -> Season.HALLOWEEN
        // Holy Week Monday (Easter - 6 days) to Easter Monday (Easter + 1 day)
        LocalDate.of(year, month, day).toEpochDay() - easterSunday(year).toEpochDay() in -6L..1L -> Season.EASTER
        md in 101..115 -> Season.EPIPHANY
        md in 1201..1226 -> Season.CHRISTMAS
        // Jan 16 to the end of February (month 2 whatever its length)
        (month == 1 && day >= 16) || month == 2 -> Season.WINTER
        month == 7 || month == 8 -> Season.SUMMER
        month == 9 || month == 10 -> Season.AUTUMN
        else -> Season.DEFAULT
    }
}
