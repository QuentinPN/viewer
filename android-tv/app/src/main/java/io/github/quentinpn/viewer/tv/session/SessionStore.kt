package io.github.quentinpn.viewer.tv.session

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private const val PREFS = "session"
private const val KEY_ALIAS = "viewer.session"
private const val KEYSTORE = "AndroidKeyStore"
private const val GCM_TAG_BITS = 128

/** A signed-in account. [token] never leaves memory except encrypted. */
data class Session(val server: String, val userId: String, val userName: String, val token: String) {
    override fun toString() = "Session(server=$server, userId=$userId, userName=$userName, token=<redacted>)"
}

/**
 * Persists the session: server and user in plain preferences, the token encrypted
 * with an AES-GCM key held by the Android Keystore (non-exportable). As on Windows
 * (ADR 0005), a token that cannot be decrypted means signing in again.
 */
class SessionStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Random id per installation, sent as the Emby DeviceId. */
    val deviceId: String
        get() = prefs.getString("deviceId", null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString("deviceId", it).apply()
        }

    /** Last server typed, kept after sign-out to prefill the form. */
    val lastServer: String? get() = prefs.getString("server", null)

    fun load(): Session? {
        val server = prefs.getString("server", null) ?: return null
        val userId = prefs.getString("userId", null) ?: return null
        val userName = prefs.getString("userName", null) ?: return null
        val sealed = prefs.getString("token", null) ?: return null
        val token = runCatching { decrypt(sealed) }.getOrNull() ?: run {
            clear()
            return null
        }
        return Session(server, userId, userName, token)
    }

    fun save(session: Session) {
        prefs.edit()
            .putString("server", session.server)
            .putString("userId", session.userId)
            .putString("userName", session.userName)
            .putString("token", encrypt(session.token))
            .apply()
    }

    /** Forgets the account, keeps the server address and device id. */
    fun clear() {
        prefs.edit().remove("userId").remove("userName").remove("token").apply()
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    /** `base64(iv) . base64(ciphertext)`; the Keystore picks the IV. */
    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val sealed = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return b64(cipher.iv) + "." + b64(sealed)
    }

    private fun decrypt(text: String): String {
        val (iv, sealed) = text.split('.').also { require(it.size == 2) }.map { Base64.decode(it, Base64.NO_WRAP) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            .apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(GCM_TAG_BITS, iv)) }
        return String(cipher.doFinal(sealed), Charsets.UTF_8)
    }

    private fun b64(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)
}
