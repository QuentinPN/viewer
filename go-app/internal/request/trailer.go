package request

// « Bande-annonce » of a title page of Demander (ADR 0127), port of
// src-tauri/src/request/trailer.rs: the `trailerKey` of Request is a YouTube
// video id, opened in the default browser. The WebView passes the key only; the
// address is built and checked here.

import (
	"context"
	"encoding/json"
	"errors"
	"io/fs"
	"os/exec"

	"viewer/internal/diagnostics"
)

// trailerKeyMax is the longest key accepted; YouTube ids are 11 characters.
const trailerKeyMax = 64

// TrailerError is `TrailerError`. It serializes as its bare message (a Rust
// `serialize_str`), so it has no MarshalJSON: bridge.MarshalError turns it into a
// JSON string.
type TrailerError struct {
	// Open is empty for Invalid, else the io::ErrorKind text of the failed launch.
	Open string
}

// ErrTrailerInvalid is `TrailerError::Invalid`.
var ErrTrailerInvalid = TrailerError{}

func (e TrailerError) Error() string {
	if e.Open == "" {
		return "bande-annonce invalide"
	}
	return "navigateur non ouvert : " + e.Open
}

// TrailerPage is `trailer_page`: the YouTube page of key when it is a plain video
// id (ASCII letters, digits, `-`, `_`), so nothing can be added to the address.
func TrailerPage(key string) (string, bool) {
	if key == "" || len(key) > trailerKeyMax {
		return "", false
	}
	for i := 0; i < len(key); i++ {
		c := key[i]
		if !((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '-' || c == '_') {
			return "", false
		}
	}
	return "https://www.youtube.com/watch?v=" + key, true
}

// Opener hands a checked web address to the Windows shell.
type Opener func(page string) error

// OpenInExplorer is `diagnostics::open_in_explorer`, the launcher shared with the
// log folder (cmd wires it as is).
var OpenInExplorer = diagnostics.OpenInExplorer

// ioKind is the Display of the io::ErrorKind Rust would report for err.
func ioKind(err error) string {
	switch {
	case errors.Is(err, fs.ErrNotExist), errors.Is(err, exec.ErrNotFound):
		return "entity not found"
	case errors.Is(err, fs.ErrPermission):
		return "permission denied"
	default:
		return "other error"
	}
}

// OpenTrailer is `ur_open_trailer`: opens the YouTube page of key in the default
// browser. Nothing else goes to YouTube: no token, no identity.
func OpenTrailer(key string, open Opener) error {
	page, ok := TrailerPage(key)
	if !ok {
		return ErrTrailerInvalid
	}
	if err := open(page); err != nil {
		return TrailerError{Open: ioKind(err)}
	}
	return nil
}

// OpenTrailerHandler is the `ur_open_trailer` command (`{ key }`), with open as the
// shell launcher.
func OpenTrailerHandler(open Opener) Handler {
	return func(_ context.Context, raw json.RawMessage) (any, error) {
		a, err := readArgs("ur_open_trailer", raw)
		if err != nil {
			return nil, err
		}
		var key string
		if err := a.arg("key", &key); err != nil {
			return nil, err
		}
		return nil, OpenTrailer(key, open)
	}
}
