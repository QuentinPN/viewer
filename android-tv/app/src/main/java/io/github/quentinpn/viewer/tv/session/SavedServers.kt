package io.github.quentinpn.viewer.tv.session

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Plain key/value storage: the `session` preferences on the device, a map in tests. */
interface KeyValueStore {
    fun getString(key: String): String?

    /** Applies every change of [block] at once. */
    fun edit(block: KeyValueEditor.() -> Unit)
}

interface KeyValueEditor {
    fun putString(key: String, value: String)

    fun remove(key: String)
}

/** Seals the tokens: AES-GCM with the Android Keystore key on the device. */
interface TokenCipher {
    fun encrypt(plain: String): String

    /** Throws when [sealed] cannot be opened (key lost, data damaged). */
    fun decrypt(sealed: String): String
}

/** Identifies a saved server: its address and the Emby user signed in on it. */
fun serverKey(server: String, userId: String) = "$server|$userId"

/** `host` or `host:port` of a saved address, as shown under a tile; the address itself if unreadable. */
fun hostLabel(server: String): String {
    val url = server.toHttpUrlOrNull() ?: return server
    val defaultPort = if (url.isHttps) 443 else 80
    return if (url.port == defaultPort) url.host else "${url.host}:${url.port}"
}

/**
 * One server of the shelf (ADR 0104). Nothing here is a secret but [sealedToken], which is
 * the token sealed by the [TokenCipher]; null once signed out or refused by the server.
 */
@Serializable
data class SavedServer(
    @SerialName("server") val server: String,
    @SerialName("serverId") val serverId: String? = null,
    @SerialName("serverName") val serverName: String? = null,
    @SerialName("userId") val userId: String,
    @SerialName("userName") val userName: String,
    @SerialName("lastUsed") val lastUsed: Long = 0,
    @SerialName("token") val sealedToken: String? = null,
) {
    val key: String get() = serverKey(server, userId)

    /** False shows « Mot de passe requis » on the tile. */
    val hasToken: Boolean get() = sealedToken != null

    /** The server's own name once read, else its address. */
    val displayName: String get() = serverName?.trim()?.takeIf { it.isNotEmpty() } ?: hostLabel(server)

    override fun toString() =
        "SavedServer(server=$server, serverId=$serverId, serverName=$serverName, userId=$userId, " +
            "userName=$userName, lastUsed=$lastUsed, token=${if (sealedToken == null) "none" else "<sealed>"})"
}

/**
 * The saved servers and the open session (ADR 0104), over [prefs] and [cipher]. The list is
 * JSON under `servers`, the open one's key under `active`. The single session of earlier
 * versions (`server`, `userId`, `userName`, `token`) becomes the first saved server on first
 * read, still signed in: its sealed token is kept as is, sealed with the same key.
 */
open class SavedServerRepository(
    private val prefs: KeyValueStore,
    private val cipher: TokenCipher,
    private val now: () -> Long = System::currentTimeMillis,
) {
    /** Most recently used first. */
    fun servers(): List<SavedServer> = read().sortedByDescending { it.lastUsed }

    fun server(key: String): SavedServer? = read().find { it.key == key }

    /** Address to prefill the manual form: the last server used, else the address of an old sign-out. */
    val lastServer: String? get() = servers().firstOrNull()?.server ?: prefs.getString(LEGACY_SERVER)

    /** The open session, or null: none open, or its token could not be opened (then dropped). */
    fun load(): Session? {
        val key = activeKey() ?: return null
        val entry = server(key)
        val token = entry?.let { token(it.key) }
        if (entry == null || token == null) {
            prefs.edit { remove(ACTIVE) }
            return null
        }
        return Session(entry.server, entry.userId, entry.userName, token)
    }

    /** The token of [key] in clear, or null; one that cannot be opened is dropped. */
    fun token(key: String): String? {
        val sealed = server(key)?.sealedToken ?: return null
        return runCatching { cipher.decrypt(sealed) }.getOrNull() ?: run {
            dropToken(key)
            null
        }
    }

    /**
     * Saves a new sign-in and opens it. The same user on the same server (same address, or
     * same server id at another address) replaces its tile; beyond [MAX_SERVERS] the oldest goes.
     */
    fun save(session: Session, serverId: String? = null, serverName: String? = null) {
        val previous = read()
        val same = previous.filter { it.key == serverKey(session.server, session.userId) || (serverId != null && it.serverId == serverId && it.userId == session.userId) }
        val entry = SavedServer(
            server = session.server,
            serverId = serverId ?: same.firstNotNullOfOrNull { it.serverId },
            serverName = serverName ?: same.firstNotNullOfOrNull { it.serverName },
            userId = session.userId,
            userName = session.userName,
            lastUsed = now(),
            sealedToken = cipher.encrypt(session.token),
        )
        val next = (listOf(entry) + (previous - same.toSet()).sortedByDescending { it.lastUsed }).take(MAX_SERVERS)
        write(next) { putString(ACTIVE, entry.key) }
    }

    /** Opens [key] again after its token was accepted; [serverId] and [serverName] refresh the tile. */
    fun activate(key: String, serverId: String? = null, serverName: String? = null) {
        val list = read()
        if (list.none { it.key == key }) return
        write(list.map { if (it.key == key) it.copy(lastUsed = now(), serverId = serverId ?: it.serverId, serverName = serverName ?: it.serverName) else it }) {
            putString(ACTIVE, key)
        }
    }

    /** Server id and name read once signed in, for a tile that has none yet (a migrated session). */
    fun describe(key: String, serverId: String, serverName: String) {
        val list = read()
        val entry = list.find { it.key == key } ?: return
        if (entry.serverId == serverId && entry.serverName == serverName) return
        write(list.map { if (it.key == key) it.copy(serverId = serverId, serverName = serverName) else it })
    }

    /** Signing out: the tile stays, without its token; the next OK asks for the password. */
    fun signOut() {
        val key = activeKey() ?: return
        dropToken(key)
    }

    /** « Changer de serveur »: closes the session, its token kept for its tile. */
    fun deactivate() {
        prefs.edit { remove(ACTIVE) }
    }

    /** Forgets the token of [key] (signed out, refused, unreadable); closes it if it was open. */
    fun dropToken(key: String) {
        val list = read()
        val active = prefs.getString(ACTIVE) == key
        write(list.map { if (it.key == key) it.copy(sealedToken = null) else it }) { if (active) remove(ACTIVE) }
    }

    /**
     * Removes [key]: address, user and token. Returns its token in clear, if it could be opened,
     * for a best-effort sign-out on the server.
     */
    fun forget(key: String): String? {
        val token = server(key)?.sealedToken?.let { runCatching { cipher.decrypt(it) }.getOrNull() }
        val list = read()
        val active = prefs.getString(ACTIVE) == key
        write(list.filterNot { it.key == key }) { if (active) remove(ACTIVE) }
        return token
    }

    /** Key of the open session; read after the list so an old session is migrated first. */
    fun activeKey(): String? {
        read()
        return prefs.getString(ACTIVE)
    }

    private fun read(): List<SavedServer> {
        val text = prefs.getString(SERVERS) ?: return migrate()
        return decode(text)
    }

    private fun write(list: List<SavedServer>, extra: KeyValueEditor.() -> Unit = {}) {
        prefs.edit {
            putString(SERVERS, json.encodeToString(LIST, list))
            extra()
        }
    }

    /** The single session of earlier versions, as the first saved server; done once. */
    private fun migrate(): List<SavedServer> {
        val server = prefs.getString(LEGACY_SERVER)
        val userId = prefs.getString(LEGACY_USER_ID)
        val userName = prefs.getString(LEGACY_USER_NAME)
        val sealed = prefs.getString(LEGACY_TOKEN)
        val entries = if (server != null && userId != null && userName != null) {
            listOf(SavedServer(server = server, userId = userId, userName = userName, lastUsed = now(), sealedToken = sealed))
        } else {
            emptyList()
        }
        write(entries) {
            if (sealed != null && entries.isNotEmpty()) putString(ACTIVE, entries.first().key)
            remove(LEGACY_USER_ID)
            remove(LEGACY_USER_NAME)
            remove(LEGACY_TOKEN)
            // LEGACY_SERVER stays: the manual form's address once every tile is forgotten
        }
        return entries
    }

    private fun decode(text: String): List<SavedServer> = try {
        json.decodeFromString(LIST, text).filter { it.server.isNotBlank() && it.userId.isNotBlank() }.distinctBy { it.key }
    } catch (_: SerializationException) {
        emptyList()
    } catch (_: IllegalArgumentException) {
        emptyList()
    }

    companion object {
        const val MAX_SERVERS = 8
        internal const val SERVERS = "servers"
        internal const val ACTIVE = "active"
        internal const val LEGACY_SERVER = "server"
        internal const val LEGACY_USER_ID = "userId"
        internal const val LEGACY_USER_NAME = "userName"
        internal const val LEGACY_TOKEN = "token"
        private val LIST = ListSerializer(SavedServer.serializer())
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }
    }
}
