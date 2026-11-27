package io.github.quentinpn.viewer.tv.stremio

/** The kinds of the desktop's Stremio errors (`go-app/internal/stremio/stremio.go`). */
enum class StremioErrorKind {
    /** The Stremio API answered with an `error` object: wrong password, unknown or expired session. */
    Rejected,

    /** No answer: network, timeout. */
    Unreachable,

    /** An answer that is not a usable success: status, JSON, manifest. */
    Server,

    /** An input refused before any call: empty e-mail or password, an addon address that is neither https nor local. */
    Invalid,

    /** Not an IMDb id, or a season without an episode. */
    InvalidId,

    /** The Emby film, or the series of the episode, has no IMDb id: the only id the addons are asked by. */
    NoImdb,
    SignedOut,

    /** The stored session was refused: sign in again. */
    ReauthRequired,

    /** The Keystore refused to seal the session key. */
    CredentialStore,

    /** A stream list no longer kept. */
    Expired,
}

/**
 * A Stremio failure. [addon] is the manifest name of the addon concerned, null for the Stremio API: the
 * text never holds an address, a key or a URL. [login] marks a refusal of the e-mail and password, not of a
 * stored session; [page] the texts of « Autres sources », which name a failing addon as unavailable.
 */
data class StremioError(
    val kind: StremioErrorKind,
    val addon: String? = null,
    val login: Boolean = false,
    val page: Boolean = false,
) {
    /** The French text shown to the user, the desktop's. */
    val message: String
        get() = when (kind) {
            StremioErrorKind.Rejected ->
                if (login) "Adresse e-mail ou mot de passe incorrect." else "Session Stremio expirée. Reconnecte-toi."
            StremioErrorKind.Unreachable -> when {
                addon != null && page -> "$addon : indisponible."
                addon != null -> "$addon ne répond pas."
                else -> "Stremio ne répond pas. Réessaie plus tard."
            }
            StremioErrorKind.Server -> when {
                addon != null && page -> "$addon : indisponible."
                addon != null -> "Réponse inattendue de $addon."
                else -> "Réponse inattendue de Stremio."
            }
            StremioErrorKind.Invalid ->
                if (addon != null) {
                    "Adresse de l'addon $addon refusée : HTTPS requis hors du réseau local."
                } else {
                    "Saisis ton adresse e-mail et ton mot de passe."
                }
            StremioErrorKind.InvalidId -> "Identifiant de contenu invalide."
            StremioErrorKind.NoImdb -> "Ce titre n'a pas d'identifiant IMDb : les addons ne peuvent pas le chercher."
            StremioErrorKind.SignedOut ->
                if (page) "Connecte ton compte Stremio dans les Réglages." else "Aucun compte Stremio connecté."
            StremioErrorKind.ReauthRequired ->
                if (page) "Reconnecte Stremio dans les Réglages." else "Session Stremio expirée. Reconnecte-toi."
            StremioErrorKind.CredentialStore -> "Le Keystore Android est indisponible pour la session Stremio."
            StremioErrorKind.Expired -> "La liste a expiré, rouvre-la."
        }

    /** This error with the texts of a detail page. */
    fun onPage() = copy(page = true)
}

/**
 * Thrown by the Stremio client and repository. Never carries a cause: OkHttp's messages hold the URL (an
 * addon's debrid key) and kotlinx.serialization's quote the JSON (the session key, stream URLs).
 */
class StremioException(val error: StremioError) : Exception(error.message)

internal fun stremioError(kind: StremioErrorKind, addon: String? = null, login: Boolean = false): Nothing =
    throw StremioException(StremioError(kind, addon, login))
