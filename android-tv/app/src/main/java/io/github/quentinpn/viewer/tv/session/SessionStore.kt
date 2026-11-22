package io.github.quentinpn.viewer.tv.session

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import java.util.UUID

private const val PREFS = "session"

/** A signed-in account. [token] never leaves memory except encrypted. */
data class Session(val server: String, val userId: String, val userName: String, val token: String) {
    val key: String get() = serverKey(server, userId)

    override fun toString() = "Session(server=$server, userId=$userId, userName=$userName, token=<redacted>)"
}

/**
 * Persists the saved servers: addresses and users in plain preferences, each
 * token encrypted with an AES-GCM key held by the Android Keystore (non-exportable). As
 * on Windows, a token that cannot be decrypted means signing in again.
 */
class SessionStore(context: Context) :
    SavedServerRepository(PreferencesStore(prefs(context)), KeystoreCipher(SESSION_KEY_ALIAS)) {
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

internal class PreferencesStore(private val prefs: SharedPreferences) : KeyValueStore {
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
