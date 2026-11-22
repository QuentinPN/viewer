package player

import "time"

// StallLimit is how long a loaded file may stay unpaused with its position frozen
// before the playback counts as interrupted (ADR 0022).
const StallLimit = 30 * time.Second

// OpenLimit is how long a file may take to load before the opening counts as
// failed (ADR 0027).
const OpenLimit = 45 * time.Second

// Stall is which wait of StallWatch ran out.
type Stall int

const (
	StallOpening Stall = iota + 1
	StallPlaying
)

// StallWatch is a pure watch over the player events: a file must load, then its
// position must move while playing. Time is passed in. FFmpeg retries at each read
// of the demuxer and skips the HLS segments that fail, so a dead stream may never
// reach an end of file by itself (ADR 0022, ADR 0027).
//
// This is the engine's only state machine (player/mod.rs has no other): idle
// (not armed), opening (armed, opening), playing (armed), paused (armed, paused),
// back to idle on EndFile, Shutdown or an expiry.
type StallWatch struct {
	limit        time.Duration
	openLimit    time.Duration
	armed        bool
	opening      bool
	paused       bool
	hasPosition  bool
	position     float64
	lastProgress time.Time
}

// NewStallWatch is `StallWatch::new`.
func NewStallWatch(limit, openLimit time.Duration, now time.Time) *StallWatch {
	return &StallWatch{limit: limit, openLimit: openLimit, lastProgress: now}
}

// OnLoadStarted records that mpv started on a file. This is not a PlayerEvent: the
// UI has no use for it.
func (w *StallWatch) OnLoadStarted(now time.Time) {
	w.armed = true
	w.opening = true
	w.hasPosition = false
	w.lastProgress = now
}

// OnEvent feeds one event to the watch.
func (w *StallWatch) OnEvent(event PlayerEvent, now time.Time) {
	switch e := event.(type) {
	case FileLoaded:
		w.armed = true
		w.opening = false
		w.hasPosition = false
		w.lastProgress = now
	case TimePos:
		// Rust: `self.position != Some(seconds)`; NaN never equals, so it counts.
		if !w.hasPosition || w.position != e.Seconds {
			w.hasPosition = true
			w.position = e.Seconds
			w.lastProgress = now
		}
	case Paused:
		w.paused = e.Paused
		if !w.opening {
			w.lastProgress = now
		}
	case EndFile, Shutdown:
		w.armed = false
		w.opening = false
	}
}

// Expired reports a limit once when it is reached; silent until the next file.
// Nothing plays during an opening, so a pause does not suspend it. ok is false
// when nothing ran out.
func (w *StallWatch) Expired(now time.Time) (stall Stall, ok bool) {
	limit, stall := w.limit, StallPlaying
	if w.opening {
		limit, stall = w.openLimit, StallOpening
	}
	waiting := w.opening || !w.paused
	// Rust's Instant::duration_since saturates at zero; a negative Sub is below limit too.
	if !w.armed || !waiting || now.Sub(w.lastProgress) < limit {
		return 0, false
	}
	w.armed = false
	w.opening = false
	return stall, true
}
