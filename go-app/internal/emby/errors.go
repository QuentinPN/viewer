package emby

// EmbyError, port of `EmbyError` (src-tauri/src/emby/mod.rs): the failure categories
// surfaced to the UI. Messages are French and never contain the token or the password.
//
// Serialized shape: {"kind": "<camelCase kind>", "message": "<French text>"}, as the
// Rust `impl Serialize for EmbyError`. Wails v3 puts a bound method's error, marshaled
// with encoding/json, in the `cause` of its CallError: the Svelte adapter reads it there.

import (
	"encoding/json"
	"errors"
	"fmt"
)

// ErrorKind is the `kind` of an EmbyError, as the UI reads it.
type ErrorKind string

const (
	KindInvalidURL         ErrorKind = "invalidUrl"
	KindInsecureRemoteURL  ErrorKind = "insecureRemoteUrl"
	KindUnreachable        ErrorKind = "unreachable"
	KindTimeout            ErrorKind = "timeout"
	KindInvalidCredentials ErrorKind = "invalidCredentials"
	KindForbidden          ErrorKind = "forbidden"
	KindServer             ErrorKind = "server"
	KindInvalidResponse    ErrorKind = "invalidResponse"
	KindNotFound           ErrorKind = "notFound"
	KindNotSignedIn        ErrorKind = "notSignedIn"
	KindSessionExpired     ErrorKind = "sessionExpired"
	// KindAccountUnavailable: the token is valid but the account is disabled or deleted (ADR 0012).
	KindAccountUnavailable ErrorKind = "accountUnavailable"
	// KindServerMismatch: another Emby server answers at the session's address; the token is not sent.
	KindServerMismatch ErrorKind = "serverMismatch"
	// KindCancelled: superseded by a sign-in, sign-out or server change made meanwhile.
	KindCancelled       ErrorKind = "cancelled"
	KindCredentialStore ErrorKind = "credentialStore"
	KindPlaybackRefused ErrorKind = "playbackRefused"
	KindNoCompatible    ErrorKind = "noCompatibleStream"
	// KindPlayer: the player could not start the stream chosen by the server.
	KindPlayer ErrorKind = "player"
	// KindSettings: a setting refused or not written (ADR 0037).
	KindSettings ErrorKind = "settings"
	// KindSync: linked playback with a TV refused or failed (ADR 0124).
	KindSync ErrorKind = "sync"
)

// EmbyError is one failure. Detail is the text of the variants that carry one
// (InvalidUrl, Unreachable, InvalidResponse, CredentialStore, PlaybackRefused,
// Player, Settings, Sync); Status is the HTTP status of KindServer.
//
// It is a value type: return it as `error`, read it back with errors.As, and
// match a kind with errors.Is(err, ErrNotFound) (Is compares the kind only).
type EmbyError struct {
	Kind   ErrorKind
	Detail string
	Status uint16
}

// Variants without a payload.
var (
	ErrInsecureRemoteURL  = EmbyError{Kind: KindInsecureRemoteURL}
	ErrTimeout            = EmbyError{Kind: KindTimeout}
	ErrInvalidCredentials = EmbyError{Kind: KindInvalidCredentials}
	ErrForbidden          = EmbyError{Kind: KindForbidden}
	ErrNotFound           = EmbyError{Kind: KindNotFound}
	ErrNotSignedIn        = EmbyError{Kind: KindNotSignedIn}
	ErrSessionExpired     = EmbyError{Kind: KindSessionExpired}
	ErrAccountUnavailable = EmbyError{Kind: KindAccountUnavailable}
	ErrServerMismatch     = EmbyError{Kind: KindServerMismatch}
	ErrCancelled          = EmbyError{Kind: KindCancelled}
	ErrNoCompatibleStream = EmbyError{Kind: KindNoCompatible}
)

// Variants with a payload.
func NewInvalidURL(detail string) EmbyError  { return EmbyError{Kind: KindInvalidURL, Detail: detail} }
func NewUnreachable(detail string) EmbyError { return EmbyError{Kind: KindUnreachable, Detail: detail} }
func NewServer(status uint16) EmbyError      { return EmbyError{Kind: KindServer, Status: status} }
func NewInvalidResponse(detail string) EmbyError {
	return EmbyError{Kind: KindInvalidResponse, Detail: detail}
}
func NewCredentialStore(detail string) EmbyError {
	return EmbyError{Kind: KindCredentialStore, Detail: detail}
}
func NewPlaybackRefused(detail string) EmbyError {
	return EmbyError{Kind: KindPlaybackRefused, Detail: detail}
}
func NewPlayer(detail string) EmbyError   { return EmbyError{Kind: KindPlayer, Detail: detail} }
func NewSettings(detail string) EmbyError { return EmbyError{Kind: KindSettings, Detail: detail} }
func NewSync(detail string) EmbyError     { return EmbyError{Kind: KindSync, Detail: detail} }

// Error is the French message, the `#[error(...)]` text of the Rust variant.
func (e EmbyError) Error() string {
	switch e.Kind {
	case KindInvalidURL:
		return "adresse du serveur invalide : " + e.Detail
	case KindInsecureRemoteURL:
		return "connexion non chiffrée refusée : une adresse hors réseau local doit utiliser https://"
	case KindUnreachable:
		return "serveur injoignable : " + e.Detail
	case KindTimeout:
		return "le serveur ne répond pas (délai dépassé)"
	case KindInvalidCredentials:
		return "nom d'utilisateur ou mot de passe incorrect"
	case KindForbidden:
		return "accès refusé par le serveur"
	case KindServer:
		return fmt.Sprintf("erreur du serveur (HTTP %d)", e.Status)
	case KindInvalidResponse:
		return "réponse inattendue du serveur : " + e.Detail
	case KindNotFound:
		return "élément introuvable sur le serveur"
	case KindNotSignedIn:
		return "aucune session ouverte"
	case KindSessionExpired:
		return "session expirée ou révoquée : reconnecte-toi"
	case KindAccountUnavailable:
		return "compte désactivé ou supprimé sur le serveur"
	case KindServerMismatch:
		return "un autre serveur Emby répond à cette adresse"
	case KindCancelled:
		return "opération annulée"
	case KindCredentialStore:
		return "coffre d'identification Windows indisponible : " + e.Detail
	case KindPlaybackRefused:
		return "lecture refusée : " + e.Detail
	case KindNoCompatible:
		return "aucune version de ce média n'est lisible par cette application"
	case KindPlayer:
		return "lecteur indisponible : " + e.Detail
	case KindSettings:
		return "réglage non enregistré : " + e.Detail
	case KindSync:
		return "lecture liée impossible : " + e.Detail
	default:
		// Not a Rust variant: only reachable with a hand-made EmbyError.
		return "erreur inconnue : " + string(e.Kind)
	}
}

// Is matches any EmbyError of the same kind, whatever its detail or status.
func (e EmbyError) Is(target error) bool {
	var t EmbyError
	switch v := target.(type) {
	case EmbyError:
		t = v
	case *EmbyError:
		if v == nil {
			return false
		}
		t = *v
	default:
		return false
	}
	return t.Kind == e.Kind
}

type errorJSON struct {
	Kind    ErrorKind `json:"kind"`
	Message string    `json:"message"`
}

// MarshalJSON writes {kind, message}. A value receiver, so the shape holds when the
// error sits by value in another struct (SessionStatus).
func (e EmbyError) MarshalJSON() ([]byte, error) {
	return json.Marshal(errorJSON{Kind: e.Kind, Message: e.Error()})
}

// KindOf returns the kind of an EmbyError in err's chain.
func KindOf(err error) (ErrorKind, bool) {
	var e EmbyError
	if errors.As(err, &e) {
		return e.Kind, true
	}
	var p *EmbyError
	if errors.As(err, &p) && p != nil {
		return p.Kind, true
	}
	return "", false
}
