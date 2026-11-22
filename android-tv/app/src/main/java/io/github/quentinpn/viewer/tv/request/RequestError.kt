package io.github.quentinpn.viewer.tv.request

import kotlin.math.max

/** Error categories shared with the Windows client, with French messages for the UI. */
sealed class RequestError(val message: String) {
    data object NotConfigured : RequestError("Request n'est pas connecté.")

    class InvalidAddress(reason: String) : RequestError("Adresse invalide : $reason.")

    data object Unreachable : RequestError("Request est injoignable. Vérifie l'adresse et le réseau.")

    class TooOld(val apiVersion: String, val missing: List<String>) :
        RequestError("Ce serveur Request est trop ancien (API $apiVersion).")

    /** The session was refused twice, or the Emby token is no longer valid there. */
    class Rejected(message: String? = null) : RequestError(message ?: "Connexion refusée par Request.")

    /** Too many failed sign-ins from this address: never retried automatically. */
    class RateLimited(val retryAfterSeconds: Long) :
        RequestError("Trop de tentatives. Réessaie dans ${minutes(retryAfterSeconds)}.")

    class Forbidden(message: String) : RequestError(message)

    class NotFound(message: String) : RequestError(message)

    class Server(val status: Int, message: String) : RequestError(message)

    data object InvalidResponse : RequestError("Réponse inattendue d'Request.")

    /** « Se déconnecter » was used: the tab offers « Se connecter », nothing reconnects by itself. */
    data object SignedOut : RequestError("Déconnecté d'Request.")

    data object EmbySignedOut : RequestError("Connecte-toi à Emby pour utiliser les demandes.")

    private companion object {
        fun minutes(seconds: Long): String {
            val m = max(1, (seconds + 59) / 60)
            return if (m == 1L) "1 minute" else "$m minutes"
        }
    }
}

class RequestException(val error: RequestError, cause: Throwable? = null) : Exception(error.message, cause)
