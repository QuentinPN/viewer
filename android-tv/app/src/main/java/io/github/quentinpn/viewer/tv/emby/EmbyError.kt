package io.github.quentinpn.viewer.tv.emby

/** Error categories shared with the Windows client, with French messages for the UI. */
sealed class EmbyError(val message: String) {
    class InvalidUrl(reason: String) : EmbyError("Adresse invalide : $reason.")

    data object InsecureRemoteUrl :
        EmbyError("HTTP non chiffré n'est accepté que sur le réseau local. Utilise https://.")

    data object Unreachable : EmbyError("Serveur injoignable. Vérifie l'adresse et le réseau.")

    /** Another server answers at a saved address (ADR 0012, 0104): its token is never sent there. */
    data object ServerMismatch :
        EmbyError("Un autre serveur répond à cette adresse. Vérifie l'adresse ou connecte-toi manuellement.")

    data object InvalidCredentials :EmbyError("Nom d'utilisateur ou mot de passe incorrect.")

    data object SessionExpired : EmbyError("Session expirée. Reconnecte-toi.")

    data object Forbidden : EmbyError("Accès refusé par le serveur.")

    data object NotFound : EmbyError("Élément introuvable sur le serveur.")

    data object NotSignedIn : EmbyError("Non connecté.")

    data object NoCompatibleStream : EmbyError("Aucun flux lisible pour cet appareil.")

    class PlaybackRefused(reason: String) : EmbyError("Lecture refusée : $reason.")

    class Server(val status: Int) : EmbyError("Erreur du serveur (HTTP $status).")

    class InvalidResponse(reason: String) : EmbyError("Réponse inattendue du serveur : $reason.")
}

class EmbyException(val error: EmbyError, cause: Throwable? = null) : Exception(error.message, cause)
