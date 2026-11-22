package io.github.quentinpn.viewer.tv.brand

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import io.github.quentinpn.viewer.tv.BuildConfig
import io.github.quentinpn.viewer.tv.R
import java.time.LocalDate

// The icon and the banner of the launcher change with the season. Android has no API for that: each season is
// an `<activity-alias>` of MainActivity in the manifest with its own icon and banner, and exactly one is enabled.
// The decisions below are pure; [LauncherIcon] only reads and writes the component states.

/** The part of the alias name after `io.github.quentinpn.viewer.tv.`, as declared in the manifest. Launchers keep it, so it never changes. */
fun aliasName(season: Season): String = "Launcher_${season.res}"

/**
 * The season whose alias the launcher should show. A debug build keeps the hard hat icon of its own app,
 * unless a test asks for [override]; a release follows the calendar ([today]) when [seasonal] is on.
 */
fun wantedLauncherSeason(seasonal: Boolean, debugBuild: Boolean, today: Season, override: Season?): Season = when {
    debugBuild -> override ?: Season.DEFAULT
    seasonal -> today
    else -> Season.DEFAULT
}

/**
 * Whether a start is a launcher tile press on top of a task that already runs. Switching the enabled alias gives the tile
 * a new component, which the running task does not match: Android starts a second MainActivity instead of resuming it.
 */
fun isRedundantLauncherStart(isTaskRoot: Boolean, action: String?, categories: Set<String>?): Boolean =
    !isTaskRoot && action == Intent.ACTION_MAIN &&
        (categories?.any { it == Intent.CATEGORY_LEANBACK_LAUNCHER || it == Intent.CATEGORY_LAUNCHER } == true)

/** One change of component state. */
data class LauncherChange(val season: Season, val enable: Boolean)

/**
 * What to do so that only [wanted] is enabled when [enabled] are now: the alias to enable comes first, so the launcher
 * never sees the app without a tile; empty when nothing differs.
 */
fun launcherChanges(wanted: Season, enabled: Set<Season>): List<LauncherChange> {
    val enable = if (wanted in enabled) emptyList() else listOf(LauncherChange(wanted, true))
    return enable + Season.entries.filter { it != wanted && it in enabled }.map { LauncherChange(it, false) }
}

/** The season named by [id] (a [Season.id]), null for anything else. */
fun seasonOrNull(id: String?): Season? = Season.entries.find { it.id == id }

/** Whether a component is enabled: the manifest decides while no state was set, and only the default alias is enabled there. */
fun isLauncherEnabled(state: Int, season: Season): Boolean = when (state) {
    PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
    PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> season == Season.DEFAULT
    else -> false
}

/** The logo drawn inside the app: the season's, the default one when seasons are off or on a debug build (its hard hat). */
fun logoSeason(seasonal: Boolean, debugBuild: Boolean, today: Season): Season =
    if (debugBuild || !seasonal) Season.DEFAULT else today

/** The drawable of [season]'s logo, written out so that a missing file fails the build. */
fun logoRes(season: Season): Int = when (season) {
    Season.APRIL_FOOLS -> R.drawable.icon_april_fools
    Season.MUSIC -> R.drawable.icon_music
    Season.BASTILLE -> R.drawable.icon_bastille
    Season.VALENTINE -> R.drawable.icon_valentine
    Season.NEW_YEAR -> R.drawable.icon_new_year
    Season.HALLOWEEN -> R.drawable.icon_halloween
    Season.EASTER -> R.drawable.icon_easter
    Season.EPIPHANY -> R.drawable.icon_epiphany
    Season.CHRISTMAS -> R.drawable.icon_christmas
    Season.WINTER -> R.drawable.icon_winter
    Season.SUMMER -> R.drawable.icon_summer
    Season.AUTUMN -> R.drawable.icon_autumn
    Season.DEFAULT -> R.drawable.icon
}

private fun today(): Season = LocalDate.now().let { seasonFor(it.year, it.monthValue, it.dayOfMonth) }

/** Android side of the switch. */
object LauncherIcon {
    /** Debug builds only: `adb shell am start -n <package>/io.github.quentinpn.viewer.tv.MainActivity --es season christmas`. */
    const val EXTRA_SEASON = "season"

    private const val TAG = "ViewerLauncher"

    /** The drawable of the logo shown in the app now. */
    fun logo(context: Context): Int =
        logoRes(logoSeason(SeasonalIconStore(context).enabled, BuildConfig.DEBUG, today()))

    /**
     * Brings the enabled alias in line with the date and the setting. [requestedSeason] is the `season` extra of the
     * intent; it counts in a debug build only. Writes nothing when the right alias is already the only one enabled.
     */
    @Synchronized
    fun sync(context: Context, requestedSeason: String? = null) {
        runCatching { apply(context, requestedSeason) }.onFailure { Log.w(TAG, "launcher icon not synced: ${it.message}") }
    }

    private fun apply(context: Context, requestedSeason: String?) {
        val wanted = wantedLauncherSeason(
            seasonal = SeasonalIconStore(context).enabled,
            debugBuild = BuildConfig.DEBUG,
            today = today(),
            override = if (BuildConfig.DEBUG) seasonOrNull(requestedSeason) else null,
        )
        val packages = context.packageManager
        fun component(season: Season) = ComponentName(context.packageName, "io.github.quentinpn.viewer.tv.${aliasName(season)}")
        val enabled = Season.entries.filter { isLauncherEnabled(packages.getComponentEnabledSetting(component(it)), it) }.toSet()
        for (change in launcherChanges(wanted, enabled)) {
            val state = if (change.enable) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            packages.setComponentEnabledSetting(component(change.season), state, PackageManager.DONT_KILL_APP)
        }
    }
}

/** « Icônes saisonnières »: kept per device, not per Emby user, since the launcher is one for the whole TV. */
class SeasonalIconStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = prefs.getBoolean(KEY, true)
        set(value) = prefs.edit().putBoolean(KEY, value).apply()

    private companion object {
        const val KEY = "brand.seasonalIcons"
    }
}
