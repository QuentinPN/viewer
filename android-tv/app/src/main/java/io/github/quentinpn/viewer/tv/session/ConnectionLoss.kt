package io.github.quentinpn.viewer.tv.session

import io.github.quentinpn.viewer.tv.emby.EmbyClient
import io.github.quentinpn.viewer.tv.emby.EmbyError
import io.github.quentinpn.viewer.tv.emby.EmbyException
import io.github.quentinpn.viewer.tv.emby.isTransient

/** Automatic attempts after a failure; then the screen waits for « Réessayer » (ADR 0119, as desktop ADR 0012). */
const val MAX_AUTO_RETRIES = 6

private val BACKOFF_SECONDS = intArrayOf(2, 4, 8, 16, 30, 30)

/**
 * Delay before automatic attempt [attempt] (1-based): exponential up to 30 s, with a jitter of
 * 50 to 100 % so several clients do not hit a rebooting server together. Same curve as desktop.
 */
fun backoffDelayMs(attempt: Int, random: Double): Long {
    val index = attempt.coerceIn(1, BACKOFF_SECONDS.size) - 1
    val base = BACKOFF_SECONDS[index] * 1000.0
    val r = random.coerceIn(0.0, 1.0)
    return Math.round(base * (0.5 + 0.5 * r))
}

/** Errors that may mean the server went away (not a refusal): worth the « Connexion perdue » screen. */
fun isConnectionLoss(error: EmbyError): Boolean = error.isTransient()

/**
 * The « Connexion perdue » screen: the session stays (token and app state kept) while the
 * server is unreachable. [checking]: a check is in flight; [retryAt]: next automatic attempt
 * (elapsed-realtime ms), null when none is planned; [gaveUp]: automatic attempts are over.
 */
data class LostConnection(
    val error: EmbyError,
    val attempts: Int = 0,
    val checking: Boolean = false,
    val retryAt: Long? = null,
    val gaveUp: Boolean = false,
)

/** A check failed again: plans the next automatic attempt, or gives up after [MAX_AUTO_RETRIES]. */
fun LostConnection?.failed(error: EmbyError, now: Long, random: Double): LostConnection {
    val attempts = this?.attempts ?: 0
    return if (attempts >= MAX_AUTO_RETRIES) {
        LostConnection(error, attempts, checking = false, retryAt = null, gaveUp = true)
    } else {
        LostConnection(error, attempts, checking = false, retryAt = now + backoffDelayMs(attempts + 1, random))
    }
}

/** The planned attempt is due: one more automatic try. */
fun LostConnection.autoRetry(): LostConnection = copy(attempts = attempts + 1, checking = true, retryAt = null)

/** « Réessayer »: now, and the backoff starts over. */
fun LostConnection.retryNow(): LostConnection = copy(attempts = 0, checking = true, retryAt = null, gaveUp = false)

/** The status line under the error. */
fun LostConnection.status(now: Long): String = when {
    checking -> "Vérification de la connexion…"
    gaveUp -> "Tentatives automatiques suspendues. Appuie sur « Réessayer » quand le serveur est de retour."
    retryAt != null -> "Nouvelle tentative dans ${((retryAt - now).coerceAtLeast(0) + 999) / 1000} s…"
    else -> ""
}

/** What a check of the open session found. */
sealed interface SessionCheck {
    data object Ok : SessionCheck

    /** 401: the token is refused, the session ends as before (« Session expirée »). */
    data object Refused : SessionCheck

    data class Failed(val error: EmbyError) : SessionCheck
}

/** Checks the open session: the public probe, then `GET /Users/{Id}` with the token. */
suspend fun checkSession(client: EmbyClient, userId: String): SessionCheck = try {
    client.publicInfo()
    client.user(userId)
    SessionCheck.Ok
} catch (e: EmbyException) {
    if (e.error == EmbyError.SessionExpired) SessionCheck.Refused else SessionCheck.Failed(e.error)
}
