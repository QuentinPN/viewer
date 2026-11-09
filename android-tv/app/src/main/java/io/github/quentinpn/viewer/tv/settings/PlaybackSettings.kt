package io.github.quentinpn.viewer.tv.settings

import android.content.Context
import androidx.core.content.edit
import io.github.quentinpn.viewer.tv.emby.MAX_STREAMING_BITRATE

// Réglages (ADR 0051), ported from the Windows client: the intro and credits options
// of src/lib/player/segments.ts (ADR 0030) and the streaming caps of
// src-tauri/src/emby/quality.rs (ADR 0037). Kept on the device, never on the server.

/** Caps offered, in bits per second, as `BITRATE_CHOICES` on Windows; null is "Maximale". */
val BITRATE_CHOICES: List<Long> = listOf(40_000_000, 20_000_000, 10_000_000, 8_000_000, 4_000_000, 3_000_000, 1_500_000)

/** What the player reads at each playback. Defaults are those of Windows. */
data class PlaybackSettings(
    /** Ask IntroDB and TheIntroDB when Emby has no marker. */
    val externalSegments: Boolean = true,
    /** Skip each intro and credits once per playback, without pressing the button. */
    val autoSkip: Boolean = false,
    /** Cap for a server reached through a local-network address; null sends the default limit. */
    val localCap: Long? = null,
    /** Cap for a server reached through any other address. */
    val remoteCap: Long? = null,
) {
    /** Drops caps outside the list, e.g. from an older version. */
    fun sanitized() = copy(localCap = offeredCap(localCap), remoteCap = offeredCap(remoteCap))

    /** The cap for a server reached through a local (or remote) address. */
    fun capFor(localAddress: Boolean): Long? = if (localAddress) localCap else remoteCap

    /** `MaxStreamingBitrate` of the next PlaybackInfo. */
    fun maxStreamingBitrate(localAddress: Boolean): Long = capFor(localAddress) ?: MAX_STREAMING_BITRATE
}

/** A cap from storage: anything not offered reads as no cap. */
fun offeredCap(cap: Long?): Long? = cap?.takeIf { it in BITRATE_CHOICES }

/** The cap after [cap] in the list shown, "Maximale" first, then back to it after the last. */
fun nextCap(cap: Long?): Long? {
    val choices = listOf<Long?>(null) + BITRATE_CHOICES
    val index = choices.indexOf(offeredCap(cap))
    return choices[(index + 1) % choices.size]
}

/** `40 Mbit/s`, `1,5 Mbit/s`. */
fun mbits(bitsPerSecond: Long): String {
    val tenths = bitsPerSecond / 100_000
    return if (tenths % 10 == 0L) "${tenths / 10} Mbit/s" else "${tenths / 10},${tenths % 10} Mbit/s"
}

/** Label of a cap: `Maximale (automatique)` for none. */
fun capLabel(cap: Long?): String = cap?.let(::mbits) ?: "Maximale (automatique)"

/** Which of the two caps the current server address uses. */
fun addressNote(localAddress: Boolean): String = if (localAddress) {
    "Ce serveur est joint par une adresse du réseau local : le réglage « Réseau local » s'applique."
} else {
    "Ce serveur est joint par une adresse hors du réseau local : le réglage « Accès distant » s'applique."
}

/** Kept per Emby user in plain preferences: none of these is a secret. */
class PlaybackSettingsStore(context: Context, private val userId: String) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Stored choices, defaults for anything missing or no longer offered. */
    fun load(): PlaybackSettings {
        val defaults = PlaybackSettings()
        fun cap(name: String) = if (prefs.contains(key(name))) prefs.getLong(key(name), 0) else null
        return PlaybackSettings(
            externalSegments = prefs.getBoolean(key(EXTERNAL), defaults.externalSegments),
            autoSkip = prefs.getBoolean(key(AUTO_SKIP), defaults.autoSkip),
            localCap = cap(LOCAL_CAP),
            remoteCap = cap(REMOTE_CAP),
        ).sanitized()
    }

    fun save(settings: PlaybackSettings) {
        val clean = settings.sanitized()
        prefs.edit {
            putBoolean(key(EXTERNAL), clean.externalSegments)
            putBoolean(key(AUTO_SKIP), clean.autoSkip)
            for ((name, cap) in listOf(LOCAL_CAP to clean.localCap, REMOTE_CAP to clean.remoteCap)) {
                if (cap == null) remove(key(name)) else putLong(key(name), cap)
            }
        }
    }

    private fun key(name: String) = "$userId.$name"

    private companion object {
        const val PREFS = "settings"
        const val EXTERNAL = "externalSegments"
        const val AUTO_SKIP = "autoSkip"
        const val LOCAL_CAP = "cap.local"
        const val REMOTE_CAP = "cap.remote"
    }
}
