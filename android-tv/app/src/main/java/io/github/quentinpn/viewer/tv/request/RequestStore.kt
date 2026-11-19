package io.github.quentinpn.viewer.tv.request

import android.content.Context
import io.github.quentinpn.viewer.tv.session.KeyValueStore
import io.github.quentinpn.viewer.tv.session.KeystoreCipher
import io.github.quentinpn.viewer.tv.session.PreferencesStore
import io.github.quentinpn.viewer.tv.session.TokenCipher

private const val PREFS = "request"
internal const val REQUEST_KEY_ALIAS = "viewer.request"

/** The Emby account a session belongs to: `{embyServerId}/{embyUserId}` (ADR 0125). */
fun accountKey(embyServerId: String, embyUserId: String) = "$embyServerId/$embyUserId"

/**
 * What Request keeps on the device: its address, and per Emby account a session token
 * sealed by the Keystore, the address it was issued for, and the « signed out » flag. A token
 * is only ever returned for the address it came from.
 */
class RequestStore(private val prefs: KeyValueStore, private val cipher: TokenCipher) {
    constructor(context: Context) : this(
        PreferencesStore(context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)),
        KeystoreCipher(REQUEST_KEY_ALIAS),
    )

    var address: String?
        get() = prefs.getString(ADDRESS)
        set(value) = prefs.edit { if (value == null) remove(ADDRESS) else putString(ADDRESS, value) }

    /** The token of [account] in clear, only when it was issued for [address]; one that cannot be opened is dropped. */
    fun token(account: String, address: String): SessionToken? {
        if (prefs.getString(ORIGIN + account) != address) return null
        val sealed = prefs.getString(TOKEN + account) ?: return null
        return runCatching { SessionToken(cipher.decrypt(sealed)) }.getOrNull() ?: run {
            clearToken(account)
            null
        }
    }

    fun saveToken(account: String, address: String, token: SessionToken) {
        val sealed = cipher.encrypt(token.value)
        prefs.edit {
            putString(TOKEN + account, sealed)
            putString(ORIGIN + account, address)
        }
    }

    fun clearToken(account: String) = prefs.edit {
        remove(TOKEN + account)
        remove(ORIGIN + account)
    }

    /** Set by « Se déconnecter »: nothing reconnects this account until the user asks to. */
    fun isSignedOut(account: String) = prefs.getString(SIGNED_OUT + account) != null

    fun setSignedOut(account: String, signedOut: Boolean) = prefs.edit {
        if (signedOut) putString(SIGNED_OUT + account, "1") else remove(SIGNED_OUT + account)
    }

    /** Emby sign-out or « Oublier ce serveur »: the token and the flag of the account go. */
    fun forget(account: String) = prefs.edit {
        remove(TOKEN + account)
        remove(ORIGIN + account)
        remove(SIGNED_OUT + account)
    }

    private companion object {
        const val ADDRESS = "address"
        const val TOKEN = "token."
        const val ORIGIN = "origin."
        const val SIGNED_OUT = "signedOut."
    }
}
