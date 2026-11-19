package io.github.quentinpn.viewer.tv.ui.requests

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.quentinpn.viewer.tv.request.RequestException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** One page read: how many pages there are, and its entries. */
internal class Fetched<T>(val totalPages: Int, val items: List<T>)

/** [current] followed by the entries of [next] whose key it does not hold yet, so a page read twice adds nothing. */
internal fun <T> mergeNew(current: List<T>, next: List<T>, keyOf: (T) -> String): List<T> {
    val seen = current.mapTo(HashSet(), keyOf)
    return next.filter { seen.add(keyOf(it)) }
}

/**
 * The pages of a list read so far (Parcourir, Mes demandes), kept in the [RequestsMemory] so coming back
 * from a title shows it as it was. A new [reset] cancels what is on its way and drops its answer; it never
 * retries by itself: after a failure only [retry] asks again.
 */
internal class Pager<T>(private val keyOf: (T) -> String) {
    val items = mutableStateListOf<T>()
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    /** The [RequestsMemory.revision] the items were read under. */
    var stamp = -1
    private var page = 0
    private var totalPages = 1
    private var generation = 0
    private var job: Job? = null

    /** Nothing read yet and nothing on its way: the first page was never asked for, or its read was cut short. */
    val untouched: Boolean get() = page == 0 && !loading && error == null

    /** Every page is read. */
    val finished: Boolean get() = page > 0 && page >= totalPages

    /** Starts over from the first page. */
    fun reset(scope: CoroutineScope, fetch: suspend (page: Int) -> Fetched<T>) {
        job?.cancel()
        generation++
        items.clear()
        page = 0
        totalPages = 1
        error = null
        loading = false
        more(scope, fetch)
    }

    /** The next page, unless one is on its way, the last is read, or the last attempt failed. */
    fun more(scope: CoroutineScope, fetch: suspend (page: Int) -> Fetched<T>) {
        if (loading || error != null || page >= totalPages) return
        loading = true
        val mine = generation
        val wanted = page + 1
        job = scope.launch {
            try {
                val read = fetch(wanted)
                if (mine == generation) {
                    items.addAll(mergeNew(items, read.items, keyOf))
                    page = wanted
                    totalPages = read.totalPages
                }
            } catch (e: RequestException) {
                if (mine == generation) error = e.error.message
            } finally {
                if (mine == generation) loading = false
            }
        }
    }

    fun retry(scope: CoroutineScope, fetch: suspend (page: Int) -> Fetched<T>) {
        error = null
        more(scope, fetch)
    }
}
