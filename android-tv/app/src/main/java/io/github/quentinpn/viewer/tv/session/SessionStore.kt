package io.github.quentinpn.viewer.tv.session

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
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
    val key: String get() = serverKey(server, userId)

    override fun toString() = "Session(server=$server, userId=$userId, userName=$userName, token=<redacted>)"
}

/**
 * Persists the saved servers (ADR 0104): addresses and users in plain preferences, each
 * token encrypted with an AES-GCM key held by the Android Keystore (non-exportable). As
 * on Windows (ADR 0005), a token that cannot be decrypted means signing in again.
 */
class SessionStore(context: Context) :
    SavedServerRepository(PreferencesStore(prefs(context)), KeystoreTokenCipher) {
    private val prefs = prefs(context)

    /** Random id per installation, sent as the Emby DeviceId. */
    val deviceId: String
        get() = prefs.getString("deviceId", null) ?: UUID.randomUUID().toString().also {
            prefs.edit { putString("deviceId", it) }
        }

    private companion object {
        fun prefs(context: Context): SharedPreferences =
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }
}

private class PreferencesStore(private val prefs: SharedPreferences) : KeyValueStore {
    override fun getString(key: String): String? = prefs.getString(key, null)

    override fun edit(block: KeyValueEditor.() -> Unit) {
        prefs.edit {
            val editor = this
            object : KeyValueEditor {
                override fun putString(key: String, value: String) {
                    editor.putString(key, value)
                }

                override fun remove(key: String) {
                    editor.remove(key)
                }
            }.block()
        }
    }
}

/** `base64(iv) . base64(ciphertext)`, the format of earlier versions; the Keystore picks the IV. */
private object KeystoreTokenCipher : TokenCipher {
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

    override fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val sealed = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return b64(cipher.iv) + "." + b64(sealed)
    }

    override fun decrypt(sealed: String): String {
        val (iv, data) = sealed.split('.').also { require(it.size == 2) }.map { Base64.decode(it, Base64.NO_WRAP) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            .apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(GCM_TAG_BITS, iv)) }
        return String(cipher.doFinal(data), Charsets.UTF_8)
    }

    private fun b64(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)
}
