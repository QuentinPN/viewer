package stremio

// Where a Stremio playback stopped, per content (`imdb[:S:E]`, the target the
// addons are asked for), not per stream: the next playback of that film or episode
// offers « Reprendre » whatever stream it picks. Kept in `stremio-resume.json` on
// this PC, across Stremio sign-outs; Emby never hears of it.

import (
	"encoding/json"
	"log/slog"
	"math"
	"os"
	"sync"
	"time"

	"viewer/internal/atomicfile"
	"viewer/internal/iokind"
	"viewer/internal/player"
)

const (
	// ResumeFile is the file name, beside the session file.
	ResumeFile = "stremio-resume.json"
	// resumeLimit is how many contents the file keeps; the oldest update goes first.
	resumeLimit = 500
	// resumeMinSeconds: before this, nothing is written (an entry already there stays).
	resumeMinSeconds = 60
	// resumeDoneRatio: from this share of the duration on, the content is seen and
	// its entry goes.
	resumeDoneRatio = 0.95
	// resumeSaveEvery is how often a playback writes its position at most.
	resumeSaveEvery = 15 * time.Second
	// startSettled: a resumed playback this far past its start began where asked.
	startSettled = 5
)

// ResumePoint is where a content stopped, as the UI sees it.
type ResumePoint struct {
	PositionSeconds float64 `json:"positionSeconds"`
	DurationSeconds float64 `json:"durationSeconds"`
}

// resumeEntry is a ResumePoint in the file.
type resumeEntry struct {
	ResumePoint
	UpdatedAt time.Time `json:"updatedAt"`
}

func finitePositive(v float64) bool { return v > 0 && !math.IsInf(v, 0) && !math.IsNaN(v) }

// valid is an entry read back: finite, positive, the position inside the duration.
func (e resumeEntry) valid() bool {
	return finitePositive(e.PositionSeconds) && finitePositive(e.DurationSeconds) &&
		e.PositionSeconds < e.DurationSeconds
}

// ResumeStore is `stremio-resume.json`: target → where it stopped. A missing or
// unreadable file is an empty store.
type ResumeStore struct {
	path string
	now  func() time.Time

	mu      sync.Mutex
	entries map[string]resumeEntry
}

// LoadResumeStore reads the file at path. A corrupt file is logged without its
// content and starts empty; it is replaced on the next write.
func LoadResumeStore(path string) *ResumeStore {
	s := &ResumeStore{path: path, now: time.Now, entries: map[string]resumeEntry{}}
	b, err := os.ReadFile(path)
	if err != nil {
		if !os.IsNotExist(err) {
			slog.Warn("Stremio resume file unreadable", "kind", iokind.Text(err))
		}
		return s
	}
	var read map[string]resumeEntry
	if json.Unmarshal(b, &read) != nil {
		slog.Warn("Stremio resume file corrupt, starting empty")
		return s
	}
	for target, e := range read {
		if e.valid() {
			s.entries[target] = e
		}
	}
	s.evict()
	return s
}

// Get is the point saved for target.
func (s *ResumeStore) Get(target string) (ResumePoint, bool) {
	s.mu.Lock()
	defer s.mu.Unlock()
	e, ok := s.entries[target]
	return e.ResumePoint, ok
}

// Put saves p for target, dropping the oldest entries past resumeLimit.
func (s *ResumeStore) Put(target string, p ResumePoint) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.entries[target] = resumeEntry{ResumePoint: p, UpdatedAt: s.now().UTC()}
	s.evict()
	return s.save()
}

// Delete forgets target; nothing is written when it was not there.
func (s *ResumeStore) Delete(target string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if _, ok := s.entries[target]; !ok {
		return nil
	}
	delete(s.entries, target)
	return s.save()
}

// evict drops the oldest updates (then the smallest target) past resumeLimit.
func (s *ResumeStore) evict() {
	for len(s.entries) > resumeLimit {
		var oldest string
		first := true
		for target, e := range s.entries {
			o := s.entries[oldest]
			if first || e.UpdatedAt.Before(o.UpdatedAt) || (e.UpdatedAt.Equal(o.UpdatedAt) && target < oldest) {
				oldest, first = target, false
			}
		}
		delete(s.entries, oldest)
	}
}

func (s *ResumeStore) save() error {
	text, err := json.MarshalIndent(s.entries, "", "  ")
	if err != nil {
		return err
	}
	return atomicfile.Write(s.path, text)
}

// ResumeTracker follows the Stremio playback going on, fed every engine event
// (app.go's sink) before the UI: it writes where the playback stopped, and puts a
// start past the end of a shorter file back to 0 without letting that end of file
// reach the UI.
type ResumeTracker struct {
	store *ResumeStore
	// command drives the open engine (seek and pause); spawn runs f off the sink.
	command func(player.PlayerCommand) error
	spawn   func(f func())

	mu sync.Mutex
	// duration is the last one the engine gave, whatever played: a stream reopened
	// with the same duration may get no new Duration event.
	duration float64
	s        *tracked
}

// tracked is the Stremio playback followed.
type tracked struct {
	target string
	// loaded: its FileLoaded came. Before it, the events are the previous file's.
	loaded bool
	pos    float64
	hasPos bool
	saved  time.Time
	// check is the start asked, while not known to be inside the file.
	check float64
	// fixing: put back to 0 after a start past the end; until a position under
	// fixBelow, an end of file and the positions are still that start's.
	fixing   bool
	fixBelow float64
}

// NewResumeTracker follows playbacks into store.
func NewResumeTracker(store *ResumeStore, command func(player.PlayerCommand) error, spawn func(f func())) *ResumeTracker {
	return &ResumeTracker{store: store, command: command, spawn: spawn}
}

// Saved is the point saved for target.
func (t *ResumeTracker) Saved(target string) (ResumePoint, bool) { return t.store.Get(target) }

// Begin follows a Stremio playback of target, loaded next, from start (nil: from
// the beginning). The playback followed until now is saved first.
func (t *ResumeTracker) Begin(target string, start *float64) {
	t.mu.Lock()
	defer t.mu.Unlock()
	t.finish(false)
	s := &tracked{target: target, saved: t.store.now()}
	if start != nil && finitePositive(*start) {
		s.check = *start
	}
	t.s = s
}

// End saves the Stremio playback followed and stops following it: another
// playback loads, or this one failed to.
func (t *ResumeTracker) End() {
	t.mu.Lock()
	defer t.mu.Unlock()
	t.finish(false)
}

// PlayerEvent follows ev; false when the UI must not see it (the end of file of a
// start past the end).
func (t *ResumeTracker) PlayerEvent(ev player.PlayerEvent) bool {
	t.mu.Lock()
	defer t.mu.Unlock()
	s := t.s
	switch e := ev.(type) {
	case player.Duration:
		if !finitePositive(e.Seconds) {
			break
		}
		t.duration = e.Seconds
		// Before its FileLoaded, a duration may be the previous file's; a check
		// left pending ends on an end of file or once past the start.
		if s != nil && s.loaded && s.check > 0 {
			if s.check >= e.Seconds {
				t.fix(s, e.Seconds)
			}
			s.check = 0
		}
	case player.FileLoaded:
		if s != nil {
			s.loaded = true
		}
	case player.TimePos:
		if s == nil || !s.loaded || math.IsInf(e.Seconds, 0) || math.IsNaN(e.Seconds) || e.Seconds < 0 {
			break
		}
		if s.fixing {
			if e.Seconds >= s.fixBelow {
				break
			}
			s.fixing = false
		}
		if s.check > 0 {
			if e.Seconds < s.check+startSettled {
				break
			}
			s.check = 0
		}
		s.pos, s.hasPos = e.Seconds, true
		if now := t.store.now(); now.Sub(s.saved) >= resumeSaveEvery {
			t.persist(s, false)
			s.saved = now
		}
	case player.EndFile:
		// Before its FileLoaded, an end is the previous file's (replaced) or a
		// link that did not open: nothing to write either way.
		if s == nil || !s.loaded {
			break
		}
		if e.Reason == player.EndEOF && (s.fixing || s.check > 0) {
			// No Duration came first: the start was past the end all the same.
			if !s.fixing {
				t.fix(s, s.check)
			}
			return false
		}
		t.finish(e.Reason == player.EndEOF)
	case player.Shutdown:
		t.finish(false)
		t.duration = 0
	}
	return true
}

// fix seeks back to 0 and resumes (keep-open pauses at the end) off the sink.
// Until a position under half of span (the duration, else the start asked), the
// positions and end of file are still those of the start past the end.
func (t *ResumeTracker) fix(s *tracked, span float64) {
	s.fixing, s.fixBelow, s.check = true, span/2, 0
	command := t.command
	t.spawn(func() {
		err := command(player.PlayerCommand{Type: player.CmdSeekAbsolute, Seconds: 0})
		if err == nil {
			err = command(player.PlayerCommand{Type: player.CmdSetPaused, Paused: false})
		}
		if err != nil {
			slog.Warn("Stremio start past the end not put back to 0")
		}
	})
}

// finish saves the playback followed, if any, and stops following it.
func (t *ResumeTracker) finish(eof bool) {
	if t.s != nil {
		t.persist(t.s, eof)
		t.s = nil
	}
}

// persist writes s by the rules: nothing before resumeMinSeconds or without a
// duration, the entry deleted at the end (an end of file there, or
// resumeDoneRatio), else saved. An end of file well before the end is a stream cut
// short: its position is kept, the list reopens on it.
func (t *ResumeTracker) persist(s *tracked, eof bool) {
	if !s.hasPos {
		return
	}
	var err error
	switch d := t.duration; {
	case d <= 0:
		if eof {
			err = t.store.Delete(s.target)
		}
	case s.pos >= resumeDoneRatio*d:
		err = t.store.Delete(s.target)
	case s.pos < resumeMinSeconds:
	default:
		err = t.store.Put(s.target, ResumePoint{PositionSeconds: s.pos, DurationSeconds: d})
	}
	if err != nil {
		slog.Warn("Stremio resume not saved", "target", s.target, "kind", iokind.Text(err))
	}
}
