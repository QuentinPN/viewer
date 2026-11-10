package io.github.quentinpn.viewer.tv.settings

import android.content.Context
import android.content.pm.ApplicationInfo
import android.hardware.display.DisplayManager
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import android.view.Display

/**
 * What the app and the device say about themselves, for « À propos » (ADR 0076). Read
 * only: nothing is created or opened, libmpv included.
 */
object DeviceReport {
    fun appRows(context: Context): List<InfoRow> {
        val info = runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull()
        val debuggable = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        return listOf(
            InfoRow("Version", appVersion(info?.versionName, info?.longVersionCode ?: 0L)),
            InfoRow("Compilation", buildType(debuggable)),
        )
    }

    fun deviceRows(): List<InfoRow> = listOf(
        InfoRow("Modèle", deviceName(Build.MANUFACTURER, Build.MODEL)),
        InfoRow("Système", androidVersion(Build.VERSION.RELEASE, Build.VERSION.SDK_INT)),
    )

    /** Decoders of [describedMimes]; a few dozen codecs to walk, kept off the UI thread by the caller. */
    fun decoders(): List<DecoderInfo> = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
        .filter { !it.isEncoder && !isAlias(it) }
        .mapNotNull { info ->
            val profiles = info.supportedTypes
                .map { it.lowercase() }
                .filter { it in describedMimes }
                .associateWith { type ->
                    runCatching { info.getCapabilitiesForType(type).profileLevels.map { it.profile } }.getOrDefault(emptyList())
                }
            if (profiles.isEmpty()) null else DecoderInfo(info.name, hardware(info), profiles)
        }

    /** HDR types of the default display; null when it cannot be read. */
    fun displayHdrTypes(context: Context): IntArray? {
        val display = context.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY) ?: return null
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                display.mode.supportedHdrTypes
            } else {
                @Suppress("DEPRECATION")
                display.hdrCapabilities?.supportedHdrTypes
            }
        }.getOrNull()
    }

    private fun isAlias(info: MediaCodecInfo) = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && info.isAlias

    private fun hardware(info: MediaCodecInfo) =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) info.isHardwareAccelerated else !isSoftwareName(info.name)
}
