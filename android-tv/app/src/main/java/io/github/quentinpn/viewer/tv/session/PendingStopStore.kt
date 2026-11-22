package io.github.quentinpn.viewer.tv.session

import android.content.Context
import androidx.core.content.edit
import io.github.quentinpn.viewer.tv.emby.PendingStop
import io.github.quentinpn.viewer.tv.emby.PendingStopStorage
import io.github.quentinpn.viewer.tv.emby.decodeStops
import io.github.quentinpn.viewer.tv.emby.encodeStops

private const val PREFS = "pending_stops"

/**
 * Lost stop reports, one JSON list per account key, in the app's private
 * preferences (no backup, see the manifest). A report holds ids and a position, never the token.
 */
class PendingStopStore(context: Context) : PendingStopStorage {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override fun keys(): Set<String> = prefs.all.keys.toSet()

    override fun load(key: String): List<PendingStop> = decodeStops(prefs.getString(key, null))

    // Written at once: the process may be killed soon after the player closes
    override fun save(key: String, stops: List<PendingStop>) {
        if (stops.isEmpty() && !prefs.contains(key)) return
        prefs.edit(commit = true) {
            if (stops.isEmpty()) remove(key) else putString(key, encodeStops(stops))
        }
    }
}
