package player

import (
	"encoding/json"
	"errors"
	"fmt"
)

// ErrorKind is the variant of a PlayerError (`PlayerError` in player/mod.rs).
type ErrorKind int

const (
	// LibraryUnavailable: libmpv missing or invalid.
	LibraryUnavailable ErrorKind = iota
	// UnsupportedAPI: the library's client API major is not 2.
	UnsupportedAPI
	// InvalidSource: a source, header or value refused before reaching mpv.
	InvalidSource
	// CertificateStore: the Windows roots could not be exported.
	CertificateStore
	// Mpv: a libmpv call failed; Context names the option, property or command.
	Mpv
	// NotOpen: no playback is open.
	NotOpen
)

// PlayerError is the error of the engine and of the player_* commands. Like the
// Rust `impl Serialize`, it is written as a bare string (its French message),
// not as {kind, message} (golden errors.player_error.all_kinds).
type PlayerError struct {
	Kind ErrorKind
	// Detail is the payload of every variant but Mpv and NotOpen.
	Detail string
	// Context and Message are the payload of Mpv.
	Context string
	Message string
}

// ErrNotOpen is `PlayerError::NotOpen`.
var ErrNotOpen = &PlayerError{Kind: NotOpen}

// ErrShutdownFromSink is returned by Shutdown when the event sink calls it: the
// event loop would wait for the sink, and the sink for the loop.
var ErrShutdownFromSink = errors.New("Shutdown appelé depuis le puits d'événements : interblocage évité")

func newError(kind ErrorKind, detail string) *PlayerError {
	return &PlayerError{Kind: kind, Detail: detail}
}

func invalidSource(detail string) *PlayerError { return newError(InvalidSource, detail) }

func mpvError(context, message string) *PlayerError {
	return &PlayerError{Kind: Mpv, Context: context, Message: message}
}

// Error is the thiserror message of the variant.
func (e *PlayerError) Error() string {
	switch e.Kind {
	case LibraryUnavailable:
		return "libmpv introuvable ou invalide : " + e.Detail
	case UnsupportedAPI:
		return "version de l'API libmpv non prise en charge : " + e.Detail
	case InvalidSource:
		return "source invalide : " + e.Detail
	case CertificateStore:
		return "certificats Windows illisibles : " + e.Detail
	case Mpv:
		return fmt.Sprintf("erreur libmpv (%s) : %s", e.Context, e.Message)
	case NotOpen:
		return "aucune lecture en cours"
	}
	return fmt.Sprintf("erreur du lecteur %d", e.Kind)
}

// Is matches another PlayerError of the same kind, so errors.Is(err, ErrNotOpen) works.
func (e *PlayerError) Is(target error) bool {
	var t *PlayerError
	return errors.As(target, &t) && t.Kind == e.Kind
}

// MarshalJSON writes the message as a JSON string (Rust: `serialize_str(&self.to_string())`).
func (e *PlayerError) MarshalJSON() ([]byte, error) {
	return json.Marshal(e.Error())
}
