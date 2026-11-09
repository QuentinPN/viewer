package io.github.quentinpn.viewer.tv.player

import android.util.Log

/**
 * Keeps libmpv's verbose log out of logcat (ADR 0052): libplayer copies it there
 * itself, and lavf prints the unused Emby token header of an HLS transcode in it.
 */
internal object NativeLog {
    private val quiet: Boolean by lazy {
        System.loadLibrary("viewerlog")
        quietVerbose().also { if (!it) Log.w("ViewerMpv", "journal verbeux de mpv non coupé (Android < 11)") }
    }

    /** Once per process, before the first `MPVLib.create`. */
    fun ensureQuiet() = quiet

    @JvmStatic
    private external fun quietVerbose(): Boolean
}
