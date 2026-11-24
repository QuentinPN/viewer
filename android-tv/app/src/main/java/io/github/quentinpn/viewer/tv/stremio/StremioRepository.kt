package io.github.quentinpn.viewer.tv.stremio

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.security.SecureRandom

private const val TAG = "Stremio"

/** How many stream lists are kept; a new one drops the oldest. */
private const val MAX_LISTS = 4

/** Any IMDb id: an addon serves streams when it accepts this one. */
private const val PROBE_ID = "tt0000001"

/** The account state shown in Réglages › Stremio. */
enum class StremioState { SignedOut, SignedIn, ReauthRequired, Unreachable }

/** What the UI sees of an addon that serves streams: its name and the content types it serves. Never its address. */
data class AddonInfo(val name: String, val movie: Boolean, val series: Boolean)

/** The account state and, signed in, its stream addons in collection order. */
data class StremioStatus(val state: StremioState, val addons: List<AddonInfo> = emptyList())

/** What the addons are asked for: a film by its IMDb id, an episode by its series' IMDb id and its numbers. */
class StreamTarget private constructor(val type: ContentType, val id: String) {
    override fun toString() = "StreamTarget($id)"

    companion object {
        fun movie(imdb: String) = StreamTarget(ContentType.Movie, streamId(imdb, 0, 0))

        /** [season] ≥ 0 (0: the specials), [episode] ≥ 1; [StremioErrorKind.InvalidId] otherwise. */
        fun episode(imdb: String, season: Int, episode: Int): StreamTarget {
            if (episode < 1) stremioError(StremioErrorKind.InvalidId)
            return StreamTarget(ContentType.Series, streamId(imdb, season, episode))
        }
    }
}

/** A playable stream as the UI sees it: an opaque [id], never its URL or headers. */
data class StreamChoice(val id: String, val name: String, val description: String, val size: Long?)

/** Where one addon of a list stands. */
sealed interface AddonStreams {
    data object Loading : AddonStreams

    /** [unsupported]: entries left out (torrents without a link and the like). */
    data class Ready(val streams: List<StreamChoice>, val unsupported: Int) : AddonStreams

    data class Failed(val error: StremioError) : AddonStreams
}

/** An addon of a list, by its position in the list and its manifest name. */
data class SourceAddon(val index: Int, val name: String, val streams: AddonStreams)

/** A stream list: its opaque id and its addons, in collection order. */
data class SourceList(val query: String, val addons: List<SourceAddon>)

/** What the player opens: never shown, never logged. */
class ResolvedStream(val url: String, val headers: Map<String, String>) {
    override fun toString() = "ResolvedStream(***)"
}

/**
 * Everything the app does with Stremio. The session key is sealed in [store]; the addon collection (its
 * addresses carry debrid keys) stays in memory only, fetched once per app session and again after a
 * sign-in. A refused session turns into [StremioState.ReauthRequired] until the next sign-in or sign-out:
 * the password is never kept, so nothing signs in again silently.
 */
class StremioRepository(
    private val store: StremioStore,
    private val client: StremioClient,
    private val log: (String) -> Unit = { Log.i(TAG, it) },
) {
    private class Query(val target: StreamTarget) {
        val streams = HashMap<String, Stream>()
    }

    /** Lets one collection request run at a time; the others wait for it. */
    private val fetch = Mutex()

    /** Guards the fields below; never held across a suspension. */
    private val lock = Any()

    /** Changes on each sign-in and sign-out: an answer fetched for an older one is dropped. */
    private var generation = 0L
    private var collection: List<Addon>? = null
    private var rejected = false

    /** The last [MAX_LISTS] lists, oldest first. */
    private var lists = LinkedHashMap<String, Query>()

    /** Signed in, the addons serving streams; never throws. */
    suspend fun status(): StremioStatus = try {
        val addons = collection().map { AddonInfo(it.name, it.serves(ContentType.Movie, PROBE_ID), it.serves(ContentType.Series, PROBE_ID)) }
        StremioStatus(StremioState.SignedIn, addons.filter { it.movie || it.series })
    } catch (e: StremioException) {
        when (e.error.kind) {
            StremioErrorKind.SignedOut -> StremioStatus(StremioState.SignedOut)
            StremioErrorKind.ReauthRequired -> StremioStatus(StremioState.ReauthRequired)
            else -> {
                log("addon collection unavailable (${e.error.kind})")
                StremioStatus(StremioState.Unreachable)
            }
        }
    }

    /**
     * Signs in with the e-mail (trimmed) and password, seals the key and forgets the previous collection and
     * lists. The password is used once. Throws [StremioException].
     */
    suspend fun signIn(email: String, password: String): StremioStatus {
        val address = email.trim()
        if (address.isEmpty() || password.isEmpty()) stremioError(StremioErrorKind.Invalid)
        val key = try {
            client.login(address, password)
        } catch (e: StremioException) {
            log("sign-in failed (${e.error.kind})")
            throw e
        }
        try {
            withContext(Dispatchers.IO) { store.save(key) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            log("Keystore refused the session")
            stremioError(StremioErrorKind.CredentialStore)
        }
        reset()
        log("signed in")
        return status()
    }

    /** Forgets the key, the collection and the lists. Local only: the session stays usable in the Stremio apps. */
    suspend fun signOut(): StremioStatus {
        withContext(Dispatchers.IO) { store.clear() }
        reset()
        log("signed out")
        return status()
    }

    /**
     * Opens a list of [target]'s streams: the account's addons serving it, all loading, then the list again
     * each time one of them answers. They are asked in parallel, each within its own deadline; one that fails
     * is [AddonStreams.Failed] without stopping the others. The flow fails with [StremioException] when there
     * is no usable account. Each collection opens a new list (the oldest of [MAX_LISTS] is dropped); a stream
     * stays resolvable while its list is kept, after the collector is gone.
     */
    fun sources(target: StreamTarget): Flow<SourceList> = channelFlow {
        val gen = synchronized(lock) { generation }
        val serving = try {
            collection()
        } catch (e: StremioException) {
            throw StremioException(e.error.onPage())
        }.filter { it.serves(target.type, target.id) }
        val id = newId("query_")
        val query = Query(target)
        synchronized(lock) {
            // Signed in or out meanwhile: these addons belong to another session
            if (generation != gen) throw StremioException(StremioError(StremioErrorKind.Expired, page = true))
            lists[id] = query
            if (lists.size > MAX_LISTS) lists.remove(lists.keys.first())
        }
        val sending = Mutex()
        var list = SourceList(id, serving.mapIndexed { i, a -> SourceAddon(i, a.name, AddonStreams.Loading) })
        send(list)
        serving.forEachIndexed { i, addon ->
            launch {
                val streams = addonStreams(gen, id, query, addon)
                sending.withLock {
                    list = list.copy(addons = list.addons.map { if (it.index == i) it.copy(streams = streams) else it })
                    send(list)
                }
            }
        }
    }

    /** The URL and headers behind [streamId]; null once its list is gone. */
    fun resolve(streamId: String): ResolvedStream? = synchronized(lock) {
        lists.values.firstNotNullOfOrNull { it.streams[streamId] }
    }?.let { ResolvedStream(it.url, it.headers) }

    override fun toString() = "StremioRepository"

    /** Asks [addon] for the streams of [query] and keeps them under new ids, while the list is kept. */
    private suspend fun addonStreams(gen: Long, id: String, query: Query, addon: Addon): AddonStreams {
        val result = try {
            client.streams(addon, query.target.type, query.target.id)
        } catch (e: StremioException) {
            log("addon streams unavailable: ${addon.name} (${e.error.kind})")
            return AddonStreams.Failed(e.error.onPage())
        }
        return synchronized(lock) {
            if (generation != gen || lists[id] !== query) {
                AddonStreams.Failed(StremioError(StremioErrorKind.Expired, page = true))
            } else {
                val choices = result.streams.map { s ->
                    StreamChoice(newId("stream_"), s.name, s.description, s.size).also { query.streams[it.id] = s }
                }
                AddonStreams.Ready(choices, result.unsupported)
            }
        }
    }

    /** Forgets the collection, the refusal and the lists, and starts a new generation. */
    private fun reset() = synchronized(lock) {
        generation++
        collection = null
        rejected = false
        lists = LinkedHashMap()
    }

    /**
     * The account's addons: from memory, or fetched with the stored key. [StremioErrorKind.SignedOut] without
     * a key, [StremioErrorKind.ReauthRequired] once the session was refused, the client's error otherwise
     * (not remembered: the next call asks again).
     */
    private suspend fun collection(): List<Addon> = fetch.withLock { load() }

    private suspend fun load(): List<Addon> {
        while (true) {
            val gen: Long
            val cached: List<Addon>?
            val refused: Boolean
            synchronized(lock) {
                gen = generation
                cached = collection
                refused = rejected
            }
            val key = withContext(Dispatchers.IO) { store.authKey() } ?: stremioError(StremioErrorKind.SignedOut)
            if (refused) stremioError(StremioErrorKind.ReauthRequired)
            if (cached != null) return cached
            val failure = try {
                val addons = client.addons(key)
                // Signed in or out meanwhile: this answer belongs to another key
                val current = synchronized(lock) { (generation == gen).also { if (it) collection = addons } }
                if (current) return addons
                null
            } catch (e: StremioException) {
                e
            }
            if (failure == null) continue
            val current = synchronized(lock) {
                (generation == gen).also { if (it && failure.error.kind == StremioErrorKind.Rejected) rejected = true }
            }
            if (!current) continue
            if (failure.error.kind == StremioErrorKind.Rejected) stremioError(StremioErrorKind.ReauthRequired)
            throw failure
        }
    }

    private companion object {
        val random = SecureRandom()

        /** [prefix] and 128 random bits in hex: never derived from a URL. */
        fun newId(prefix: String): String {
            val bytes = ByteArray(16).also(random::nextBytes)
            return prefix + bytes.joinToString("") { "%02x".format(it) }
        }
    }
}
