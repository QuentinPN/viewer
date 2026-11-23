// Package stremio is the Stremio integration: the account (api.strem.io) gives the
// list of installed addons, and the addons give streams. Every call is made here,
// in Go; the UI never sees the authKey, an addon address or a stream URL.
//
// The authKey, every addon transportUrl (it often carries a debrid key in its path)
// and every stream URL are secrets: never logged, never in an error, never sent to
// the UI. Logs name an addon by its manifest name only.
package stremio

import (
	"encoding/json"
	"errors"
)

// ErrorKind is the `kind` of an Error, as the UI reads it.
type ErrorKind string

const (
	// KindRejected: the Stremio API answered with an `error` object (wrong
	// password, unknown or expired session).
	KindRejected ErrorKind = "rejected"
	// KindUnreachable: no answer (network, timeout, cancellation).
	KindUnreachable ErrorKind = "unreachable"
	// KindServer: an answer that is not a usable success (status, JSON, manifest).
	KindServer ErrorKind = "server"
	// KindInvalid: an input refused before any call (empty e-mail or password, an
	// addon address that is neither https nor on the local network).
	KindInvalid ErrorKind = "invalid"
	// KindInvalidID: not an IMDb id, or a season without an episode.
	KindInvalidID ErrorKind = "invalidId"
	// KindSignedOut: no Stremio account in the vault.
	KindSignedOut ErrorKind = "signedOut"
	// KindReauthRequired: the stored session was refused; sign in again.
	KindReauthRequired ErrorKind = "reauthRequired"
	// KindCredentialStore: the Windows vault refused to save or delete the session.
	KindCredentialStore ErrorKind = "credentialStore"
)

// Error is a Stremio failure. Addon is the manifest name of the addon concerned,
// "" for the Stremio API. Its text never holds an address, a key or a URL.
//
// It is a value type: return it as `error`, read it back with errors.As, and match
// a kind with errors.Is (Is compares the kind only).
type Error struct {
	Kind  ErrorKind
	Addon string
	// login: a rejection of the e-mail and password, not of a stored session.
	login bool
}

// Variants without a payload.
var (
	ErrSignedOut      = Error{Kind: KindSignedOut}
	ErrReauthRequired = Error{Kind: KindReauthRequired}
	ErrInvalidID      = Error{Kind: KindInvalidID}
)

// Error is the French text shown to the user.
func (e Error) Error() string {
	switch e.Kind {
	case KindRejected:
		if e.login {
			return "Adresse e-mail ou mot de passe incorrect."
		}
		return "Session Stremio expirée. Reconnecte-toi."
	case KindUnreachable:
		if e.Addon != "" {
			return e.Addon + " ne répond pas."
		}
		return "Stremio ne répond pas. Réessaie plus tard."
	case KindServer:
		if e.Addon != "" {
			return "Réponse inattendue de " + e.Addon + "."
		}
		return "Réponse inattendue de Stremio."
	case KindInvalid:
		if e.Addon != "" {
			return "Adresse de l'addon " + e.Addon + " refusée : HTTPS requis hors du réseau local."
		}
		return "Saisis ton adresse e-mail et ton mot de passe."
	case KindInvalidID:
		return "Identifiant de contenu invalide."
	case KindSignedOut:
		return "Aucun compte Stremio connecté."
	case KindReauthRequired:
		return "Session Stremio expirée. Reconnecte-toi."
	case KindCredentialStore:
		return "Le coffre de Windows est indisponible pour la session Stremio."
	default:
		return "Erreur Stremio."
	}
}

// Is matches on the kind, so errors.Is(err, ErrSignedOut) works.
func (e Error) Is(target error) bool {
	var t Error
	return errors.As(target, &t) && t.Kind == e.Kind
}

// MarshalJSON is the bridge shape of every command error: `{kind, message}`.
func (e Error) MarshalJSON() ([]byte, error) {
	return json.Marshal(struct {
		Kind    ErrorKind `json:"kind"`
		Message string    `json:"message"`
	}{e.Kind, e.Error()})
}
