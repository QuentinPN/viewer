package io.github.quentinpn.viewer.tv.emby

/** What one round of resending did: stops received, stops refused for good, and the error that ended it. */
data class ResendOutcome(val sent: Int = 0, val dropped: Int = 0, val error: EmbyError? = null)

/**
 * Keeps lost stop reports on disk, per account, and sends them again to the signed-in
 * account only. Not thread-safe: [PlaybackReporter] calls it from its one coroutine.
 */
class StopResender(private val storage: PendingStopStorage, private val clock: () -> Long = System::currentTimeMillis) {
    private var client: EmbyClient? = null

    /** The signed-in account, or null: kept stops then wait for its next sign-in. */
    var account: String? = null
        private set

    /** The server refused the token: nothing more is sent until the account signs in again. */
    private var expired = false
    private var failures = 0
    private var nextAt = 0L

    /** An account is signed in (sign-in or a restored session): its kept stops are due now. */
    fun use(key: String, client: EmbyClient) {
        account = key
        this.client = client
        expired = false
        failures = 0
        val now = clock()
        nextAt = now
        // Stops of accounts that never come back are given up here too
        for (k in storage.keys()) {
            val all = storage.load(k)
            val kept = pruneStops(all, now)
            if (kept.size != all.size) storage.save(k, kept)
        }
    }

    fun signedOut() {
        account = null
        client = null
    }

    /** A stop report of [key]'s account failed for a cause that may pass. */
    fun keep(key: String, report: PlaybackReport) {
        val now = clock()
        storage.save(key, enqueueStop(storage.load(key), PendingStop(report, now), now))
        if (key == account) {
            failures = maxOf(failures, 1)
            nextAt = maxOf(nextAt, now + resendDelay(failures))
        }
    }

    /** A newer report of [itemId] reached the server, or a new playback of it began: its kept stop is older. */
    fun supersede(key: String, itemId: String) {
        val all = storage.load(key)
        val kept = withoutItem(all, itemId)
        if (kept.size != all.size) storage.save(key, kept)
    }

    /** Another report went through: the server is back, kept stops need not wait. */
    fun reachable() {
        if (failures == 0) return
        failures = 0
        nextAt = clock()
    }

    fun due() = account != null && !expired && clock() >= nextAt

    /**
     * Sends the signed-in account's kept stops, oldest first, each after a progress report
     * that carries its position. Stops at the first failure that may pass.
     */
    suspend fun resend(): ResendOutcome {
        val key = account ?: return ResendOutcome()
        val client = client ?: return ResendOutcome()
        if (!due()) return ResendOutcome()
        var queue = pruneStops(storage.load(key), clock())
        var sent = 0
        var dropped = 0
        var error: EmbyError? = null
        while (queue.isNotEmpty()) {
            val failure = send(client, Report(ReportKind.Stopped, queue.first().report))
            when {
                failure == null -> sent++
                failure.isTransient() -> {
                    failures++
                    nextAt = clock() + resendDelay(failures)
                }
                // Kept for this account's next sign-in, never sent under another one
                failure == EmbyError.SessionExpired || failure == EmbyError.NotSignedIn -> expired = true
                else -> dropped++
            }
            if (failure != null) error = failure
            if (failure.isTransient() || expired) break
            queue = queue.drop(1)
        }
        if (sent > 0) failures = 0
        storage.save(key, queue)
        return ResendOutcome(sent, dropped, error)
    }

    private suspend fun send(client: EmbyClient, stop: Report): EmbyError? = try {
        // The server ignores the position of a stop once it closed the playback itself;
        // a progress report carries it and reopens the playback
        client.reportPlayback(stop.lateProgress())
        client.reportPlayback(stop)
        null
    } catch (e: EmbyException) {
        e.error
    }
}
