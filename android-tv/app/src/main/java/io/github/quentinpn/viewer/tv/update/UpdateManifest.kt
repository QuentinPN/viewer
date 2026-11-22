package io.github.quentinpn.viewer.tv.update

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/**
 * `android/latest.json` of the releases repository: the newest APK, its size and
 * SHA-256. The signature, checked after download, is what proves where the APK comes from.
 */
@Serializable
data class UpdateManifest(
    val versionCode: Int,
    val versionName: String,
    val notes: String = "",
    val url: String,
    val sha256: String,
    val size: Long,
)

private val manifestJson = Json { ignoreUnknownKeys = true }
private val SHA256_HEX = Regex("^[0-9a-fA-F]{64}$")

/** The manifest, or null when it is not one: bad JSON, a missing field, an odd hash, size or address. */
internal fun parseManifest(text: String): UpdateManifest? {
    val manifest = runCatching { manifestJson.decodeFromString<UpdateManifest>(text) }.getOrNull() ?: return null
    val valid = manifest.versionCode > 0 &&
        manifest.versionName.isNotBlank() &&
        manifest.size > 0 &&
        SHA256_HEX.matches(manifest.sha256) &&
        (manifest.url.startsWith("http://") || manifest.url.startsWith("https://"))
    return manifest.takeIf { valid }
}

/** Newer only by version code: a rebuilt APK with the same code is not offered again. */
internal fun isNewer(manifest: UpdateManifest, installedVersionCode: Long): Boolean =
    manifest.versionCode.toLong() > installedVersionCode

internal fun sha256Hex(input: InputStream): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(64 * 1024)
    while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        digest.update(buffer, 0, read)
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

/** The file has the announced size and hash (case of the hex ignored). */
internal fun matchesManifest(file: File, size: Long, sha256: String): Boolean =
    file.isFile && file.length() == size && file.inputStream().use(::sha256Hex).equals(sha256, ignoreCase = true)

/** File name of a version's APK in the download folder. */
internal fun apkName(versionCode: Int) = "viewer-$versionCode.apk"
