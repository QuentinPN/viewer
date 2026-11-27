package io.github.quentinpn.viewer.tv.stremio

import android.content.Context
import io.github.quentinpn.viewer.tv.session.KeyValueStore
import io.github.quentinpn.viewer.tv.session.KeystoreCipher
import io.github.quentinpn.viewer.tv.session.PreferencesStore
import io.github.quentinpn.viewer.tv.session.TokenCipher

private const val PREFS = "stremio"
internal const val STREMIO_KEY_ALIAS = "viewer.stremio"

/** The Stremio session key, sealed by the Keystore: one account per install. The addons are never stored. */
class StremioStore(private val prefs: KeyValueStore, private val cipher: TokenCipher) {
    constructor(context: Context) : this(
        PreferencesStore(context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)),
        KeystoreCipher(STREMIO_KEY_ALIAS),
    )

    /** The key in clear; one that cannot be opened is dropped. */
    fun authKey(): AuthKey? {
        val sealed = prefs.getString(KEY) ?: return null
        return runCatching { AuthKey(cipher.decrypt(sealed)) }.getOrNull() ?: run {
            clear()
            null
        }
    }

    /** Throws when the Keystore cannot seal it. */
    fun save(key: AuthKey) {
        val sealed = cipher.encrypt(key.value)
        prefs.edit { putString(KEY, sealed) }
    }

    fun clear() = prefs.edit { remove(KEY) }

    private companion object {
        const val KEY = "authKey"
    }
}
