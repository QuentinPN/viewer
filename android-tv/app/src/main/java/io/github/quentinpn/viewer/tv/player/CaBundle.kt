package io.github.quentinpn.viewer.tv.player

import android.util.Log
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.KeyStore
import java.util.Base64

/**
 * The libmpv of mpv-android ships no certificate store, so `tls-verify=yes` alone rejects every
 * https URL. mpv gets a PEM bundle of the system CAs through `tls-ca-file`. User-added CAs stay
 * out (`system:` aliases only): apps no longer trust them since API 24.
 */
object CaBundle {
    private const val TAG = "CaBundle"
    private const val SYSTEM_PREFIX = "system:"
    private const val FILE_NAME = "cacerts.pem"

    private var path: String? = null

    /** Rebuilt once per process, as the system store can change after an OS update. Null on failure: https then fails closed. */
    @Synchronized
    fun path(cacheDir: File): String? {
        path?.let { return it }
        return try {
            val keyStore = KeyStore.getInstance("AndroidCAStore").apply { load(null) }
            val certs = keyStore.aliases().toList()
                .filter { it.startsWith(SYSTEM_PREFIX) }
                .mapNotNull { keyStore.getCertificate(it)?.encoded }
            check(certs.isNotEmpty()) { "aucune AC système" }
            val target = File(cacheDir, FILE_NAME)
            val temp = File(cacheDir, "$FILE_NAME.tmp")
            temp.writeText(pem(certs), Charsets.US_ASCII)
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
            target.absolutePath.also { path = it }
        } catch (e: Exception) {
            Log.w(TAG, "paquet d'AC non écrit (${e.javaClass.simpleName}): https sera refusé")
            null
        }
    }

    /** DER certificates to PEM text, 64 characters per line. */
    fun pem(certificates: List<ByteArray>): String {
        val encoder = Base64.getMimeEncoder(64, "\n".toByteArray())
        return certificates.joinToString("") {
            "-----BEGIN CERTIFICATE-----\n${encoder.encodeToString(it)}\n-----END CERTIFICATE-----\n"
        }
    }
}
