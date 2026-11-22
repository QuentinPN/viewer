package player

import (
	"encoding/json"
	"math"
)

// PlayerEvent is one event of the engine, sent to the UI on `player://event`
// (`PlayerEvent` in player/mod.rs, serde internally tagged by `type`). The variants
// are the value types below; consumers type-switch on them. == works on every
// variant but Tracks (a slice); on EndFile it compares the Error pointer, not the text.
type PlayerEvent interface {
	isPlayerEvent()
	json.Marshaler
}

type (
	// FileLoaded: the file is open and its streams are known.
	FileLoaded struct{}
	// TimePos: the position, in seconds.
	TimePos struct{ Seconds float64 }
	// Duration of the file, in seconds.
	Duration struct{ Seconds float64 }
	// Paused: the pause state changed.
	Paused struct{ Paused bool }
	// Buffering: mpv waits for its cache (`paused-for-cache`).
	Buffering struct{ Active bool }
	// Tracks: the track list changed.
	Tracks struct{ Tracks List[Track] }
	// VideoReconfigured: the video output was (re)configured.
	VideoReconfigured struct{}
	// Seeked: playback restarted after a seek (`PLAYBACK_RESTART`).
	Seeked struct{}
	// EndFile: the file ended; Error is mpv's message, nil when there is none.
	EndFile struct {
		Reason EndReason
		Error  *string
	}
	// Shutdown: the engine is gone; the overlay returns to idle.
	Shutdown struct{}
)

func (FileLoaded) isPlayerEvent()        {}
func (TimePos) isPlayerEvent()           {}
func (Duration) isPlayerEvent()          {}
func (Paused) isPlayerEvent()            {}
func (Buffering) isPlayerEvent()         {}
func (Tracks) isPlayerEvent()            {}
func (VideoReconfigured) isPlayerEvent() {}
func (Seeked) isPlayerEvent()            {}
func (EndFile) isPlayerEvent()           {}
func (Shutdown) isPlayerEvent()          {}

// seconds writes an f64 as serde_json does: a non-finite value is null.
func seconds(v float64) json.RawMessage {
	if math.IsNaN(v) || math.IsInf(v, 0) {
		return json.RawMessage("null")
	}
	b, _ := json.Marshal(v) // finite: cannot fail
	return b
}

func (FileLoaded) MarshalJSON() ([]byte, error) { return []byte(`{"type":"fileLoaded"}`), nil }

func (e TimePos) MarshalJSON() ([]byte, error) {
	return json.Marshal(struct {
		Type    string          `json:"type"`
		Seconds json.RawMessage `json:"seconds"`
	}{"timePos", seconds(e.Seconds)})
}

func (e Duration) MarshalJSON() ([]byte, error) {
	return json.Marshal(struct {
		Type    string          `json:"type"`
		Seconds json.RawMessage `json:"seconds"`
	}{"duration", seconds(e.Seconds)})
}

func (e Paused) MarshalJSON() ([]byte, error) {
	return json.Marshal(struct {
		Type   string `json:"type"`
		Paused bool   `json:"paused"`
	}{"paused", e.Paused})
}

func (e Buffering) MarshalJSON() ([]byte, error) {
	return json.Marshal(struct {
		Type   string `json:"type"`
		Active bool   `json:"active"`
	}{"buffering", e.Active})
}

func (e Tracks) MarshalJSON() ([]byte, error) {
	return json.Marshal(struct {
		Type   string      `json:"type"`
		Tracks List[Track] `json:"tracks"`
	}{"tracks", e.Tracks})
}

func (VideoReconfigured) MarshalJSON() ([]byte, error) {
	return []byte(`{"type":"videoReconfigured"}`), nil
}

func (Seeked) MarshalJSON() ([]byte, error) { return []byte(`{"type":"seeked"}`), nil }

func (e EndFile) MarshalJSON() ([]byte, error) {
	return json.Marshal(struct {
		Type   string    `json:"type"`
		Reason EndReason `json:"reason"`
		Error  *string   `json:"error"`
	}{"endFile", e.Reason, e.Error})
}

func (Shutdown) MarshalJSON() ([]byte, error) { return []byte(`{"type":"shutdown"}`), nil }

// EndReason is why a file ended, written camelCase.
type EndReason string

const (
	EndEOF     EndReason = "eof"
	EndStopped EndReason = "stopped"
	EndQuit    EndReason = "quit"
	EndError   EndReason = "error"
	EndOther   EndReason = "other"
)
