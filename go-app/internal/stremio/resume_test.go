package stremio

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"viewer/internal/player"
)

// clock is a time the test moves by hand.
type clock struct{ t time.Time }

func (c *clock) now() time.Time          { return c.t }
func (c *clock) advance(d time.Duration) { c.t = c.t.Add(d) }
func newClock() *clock                   { return &clock{t: time.Date(2026, 11, 23, 20, 0, 0, 0, time.UTC)} }
func resumePath(t *testing.T) string     { return filepath.Join(t.TempDir(), "roaming", ResumeFile) }
func point(pos, dur float64) ResumePoint {
	return ResumePoint{PositionSeconds: pos, DurationSeconds: dur}
}
func eof() player.EndFile        { return player.EndFile{Reason: player.EndEOF} }
func stopped() player.EndFile    { return player.EndFile{Reason: player.EndStopped} }
func startAt(s float64) *float64 { return &s }

func storeAt(path string, c *clock) *ResumeStore {
	s := LoadResumeStore(path)
	s.now = c.now
	return s
}

// trackerRig is a tracker over a store on disk, a hand clock, the engine commands
// it sent, the fixes it spawned (run by run) and the events it released.
type trackerRig struct {
	path     string
	clock    *clock
	store    *ResumeStore
	tr       *ResumeTracker
	commands []string
	fail     error
	spawned  []func()
	released []string
}

func newRig(t *testing.T) *trackerRig {
	r := &trackerRig{path: resumePath(t), clock: newClock()}
	r.store = storeAt(r.path, r.clock)
	r.tr = NewResumeTracker(r.store, func(c player.PlayerCommand) error {
		r.commands = append(r.commands, fmt.Sprintf("%s %v %v", c.Type, c.Seconds, c.Paused))
		return r.fail
	}, func(f func()) { r.spawned = append(r.spawned, f) }, func(ev player.PlayerEvent) {
		b, _ := json.Marshal(ev)
		r.released = append(r.released, string(b))
	})
	return r
}

// run runs the fixes spawned so far, as their goroutines would.
func (r *trackerRig) run() {
	fs := r.spawned
	r.spawned = nil
	for _, f := range fs {
		f()
	}
}

// feed sends events, failing on one held back.
func (r *trackerRig) feed(t *testing.T, evs ...player.PlayerEvent) {
	t.Helper()
	for _, ev := range evs {
		if !r.tr.PlayerEvent(ev) {
			b, _ := json.Marshal(ev)
			t.Fatalf("retenu : %s", b)
		}
	}
}

// onDisk is the file read back, as a fresh launch would.
func (r *trackerRig) onDisk(target string) (ResumePoint, bool) {
	return LoadResumeStore(r.path).Get(target)
}

// playing starts tracking target and loads its file of the given duration.
func (r *trackerRig) playing(t *testing.T, target string, start *float64, duration float64) {
	t.Helper()
	r.tr.Begin(target, start)
	r.feed(t, player.FileLoaded{}, player.Duration{Seconds: duration})
}

func TestResumeStoreKeepsTheLast500(t *testing.T) {
	path, c := resumePath(t), newClock()
	s := storeAt(path, c)
	for i := range 501 {
		if err := s.Put(fmt.Sprintf("tt%07d", i), point(600, 3000)); err != nil {
			t.Fatal(err)
		}
		c.advance(time.Second)
	}
	// Touched again, the first one is no longer the oldest.
	if err := s.Put("tt0000001", point(700, 3000)); err != nil {
		t.Fatal(err)
	}
	if err := s.Put("tt9999999", point(800, 3000)); err != nil {
		t.Fatal(err)
	}
	back := LoadResumeStore(path)
	if len(back.entries) != 500 {
		t.Fatal(len(back.entries))
	}
	for _, gone := range []string{"tt0000000", "tt0000002"} {
		if _, ok := back.Get(gone); ok {
			t.Fatal("gardé", gone)
		}
	}
	if p, ok := back.Get("tt0000001"); !ok || p != point(700, 3000) {
		t.Fatal(p, ok)
	}
	if p, ok := back.Get("tt9999999"); !ok || p != point(800, 3000) {
		t.Fatal(p, ok)
	}
	if _, err := os.Stat(strings.TrimSuffix(path, ".json") + ".json.tmp"); !os.IsNotExist(err) {
		t.Fatal("fichier temporaire laissé", err)
	}
}

// A corrupt file is an empty store, logged without its content; the next write
// replaces it. Entries that make no sense are dropped on read.
func TestResumeStoreCorruptFileIsEmpty(t *testing.T) {
	logs := captureLogs(t)
	path := resumePath(t)
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(path, []byte(`{"tt0111161": SECRETCONTENT`), 0o644); err != nil {
		t.Fatal(err)
	}
	s := storeAt(path, newClock())
	if len(s.entries) != 0 || !strings.Contains(logs.String(), "Stremio resume file corrupt") || strings.Contains(logs.String(), "SECRETCONTENT") {
		t.Fatal(s.entries, logs.String())
	}
	if err := s.Put("tt0111161", point(600, 3000)); err != nil {
		t.Fatal(err)
	}
	if p, ok := LoadResumeStore(path).Get("tt0111161"); !ok || p != point(600, 3000) {
		t.Fatal(p, ok)
	}

	odd := `{"tt1":{"positionSeconds":-1,"durationSeconds":100,"updatedAt":"2026-11-23T20:00:00Z"},` +
		`"tt2":{"positionSeconds":200,"durationSeconds":100,"updatedAt":"2026-11-23T20:00:00Z"},` +
		`"tt3":{"positionSeconds":70,"durationSeconds":100,"updatedAt":"2026-11-23T20:00:00Z"}}`
	if err := os.WriteFile(path, []byte(odd), 0o644); err != nil {
		t.Fatal(err)
	}
	back := LoadResumeStore(path)
	if len(back.entries) != 1 {
		t.Fatal(back.entries)
	}
	if p, ok := back.Get("tt3"); !ok || p != point(70, 100) {
		t.Fatal(p, ok)
	}
	if missing := LoadResumeStore(filepath.Join(t.TempDir(), "absent.json")); len(missing.entries) != 0 {
		t.Fatal(missing.entries)
	}
}

// The file holds the target, both numbers and the update time, nothing else.
func TestResumeStoreFileShape(t *testing.T) {
	path, c := resumePath(t), newClock()
	if err := storeAt(path, c).Put("tt0944947:2:5", point(2530.5, 3000)); err != nil {
		t.Fatal(err)
	}
	b, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	var raw map[string]map[string]any
	if json.Unmarshal(b, &raw) != nil || fmt.Sprint(raw) != "map[tt0944947:2:5:map[durationSeconds:3000 positionSeconds:2530.5 updatedAt:2026-11-23T20:00:00Z]]" {
		t.Fatal(string(b))
	}
}

// Before 60 s nothing is written and an entry already there stays; from 95 % on
// the entry goes.
func TestResumeThresholds(t *testing.T) {
	r := newRig(t)
	r.playing(t, "tt0111161", nil, 3000)
	r.feed(t, player.TimePos{Seconds: 59})
	r.tr.End()
	if _, ok := r.onDisk("tt0111161"); ok {
		t.Fatal("écrit avant 60 s")
	}

	if err := r.store.Put("tt0111161", point(1200, 3000)); err != nil {
		t.Fatal(err)
	}
	r.playing(t, "tt0111161", nil, 3000)
	r.feed(t, player.TimePos{Seconds: 30}, stopped())
	if p, ok := r.onDisk("tt0111161"); !ok || p != point(1200, 3000) {
		t.Fatal("entrée perdue avant 60 s", p, ok)
	}

	r.playing(t, "tt0111161", nil, 3000)
	r.feed(t, player.TimePos{Seconds: 61}, stopped())
	if p, ok := r.onDisk("tt0111161"); !ok || p != point(61, 3000) {
		t.Fatal(p, ok)
	}

	r.playing(t, "tt0111161", nil, 3000)
	r.feed(t, player.TimePos{Seconds: 2850}, stopped())
	if _, ok := r.onDisk("tt0111161"); ok {
		t.Fatal("gardé à 95 %")
	}
}

// A real end of file deletes the entry; one well before the end (a stream cut
// short) keeps its position, so the reopened list offers it.
func TestResumeEndOfFile(t *testing.T) {
	r := newRig(t)
	if err := r.store.Put("tt0111161", point(1200, 3000)); err != nil {
		t.Fatal(err)
	}
	r.playing(t, "tt0111161", nil, 3000)
	r.feed(t, player.TimePos{Seconds: 2999.9}, eof())
	if _, ok := r.onDisk("tt0111161"); ok {
		t.Fatal("gardé après la fin")
	}

	r.playing(t, "tt0111161", nil, 3000)
	r.feed(t, player.TimePos{Seconds: 1500}, eof())
	if p, ok := r.onDisk("tt0111161"); !ok || p != point(1500, 3000) {
		t.Fatal("flux coupé perdu", p, ok)
	}

	// Without a duration nothing is written; its end of file still deletes.
	r.tr.PlayerEvent(player.Shutdown{})
	r.tr.Begin("tt0111161", nil)
	r.feed(t, player.FileLoaded{}, player.TimePos{Seconds: 1600}, stopped())
	if p, ok := r.onDisk("tt0111161"); !ok || p != point(1500, 3000) {
		t.Fatal(p, ok)
	}
	r.tr.Begin("tt0111161", nil)
	r.feed(t, player.FileLoaded{}, player.TimePos{Seconds: 1600}, eof())
	if _, ok := r.onDisk("tt0111161"); ok {
		t.Fatal("gardé après la fin sans durée")
	}
}

// A playback writes at most every 15 s, and on its end.
func TestResumeWritesEvery15Seconds(t *testing.T) {
	r := newRig(t)
	r.playing(t, "tt0111161", nil, 3000)
	step := func(pos float64, by time.Duration) {
		r.clock.advance(by)
		r.feed(t, player.TimePos{Seconds: pos})
	}
	step(100, 14*time.Second)
	if _, ok := r.onDisk("tt0111161"); ok {
		t.Fatal("écrit avant 15 s")
	}
	step(101, time.Second)
	if p, _ := r.onDisk("tt0111161"); p != point(101, 3000) {
		t.Fatal(p)
	}
	step(110, 10*time.Second)
	if p, _ := r.onDisk("tt0111161"); p != point(101, 3000) {
		t.Fatal("écrit avant 15 s", p)
	}
	step(116, 5*time.Second)
	if p, _ := r.onDisk("tt0111161"); p != point(116, 3000) {
		t.Fatal(p)
	}
	step(120, time.Second)
	r.feed(t, player.Shutdown{})
	if p, _ := r.onDisk("tt0111161"); p != point(120, 3000) {
		t.Fatal("arrêt non écrit", p)
	}
	// Shut down, nothing is followed any more.
	r.clock.advance(time.Minute)
	r.feed(t, player.TimePos{Seconds: 500})
	if p, _ := r.onDisk("tt0111161"); p != point(120, 3000) {
		t.Fatal(p)
	}
}

// An Emby playback (End) or another Stremio one (Begin) saves the one going on and
// stops following it; the previous file's last events, before the new FileLoaded,
// belong to no one.
func TestResumeStopsOnAnotherLoad(t *testing.T) {
	r := newRig(t)
	r.playing(t, "tt0111161", nil, 3000)
	r.feed(t, player.TimePos{Seconds: 700})
	r.tr.End()
	if p, _ := r.onDisk("tt0111161"); p != point(700, 3000) {
		t.Fatal(p)
	}
	r.clock.advance(time.Minute)
	r.feed(t, player.TimePos{Seconds: 900}, player.Duration{Seconds: 1500}, stopped(), player.FileLoaded{}, player.TimePos{Seconds: 1000}, eof())
	if p, _ := r.onDisk("tt0111161"); p != point(700, 3000) {
		t.Fatal("lecture Emby attribuée au contenu Stremio", p)
	}

	r.playing(t, "tt0111161", nil, 3000)
	r.feed(t, player.TimePos{Seconds: 800})
	r.tr.Begin("tt0944947:2:5", nil)
	if p, _ := r.onDisk("tt0111161"); p != point(800, 3000) {
		t.Fatal(p)
	}
	// The old file goes on a moment, then ends as replaced: not the new one's.
	r.clock.advance(time.Minute)
	r.feed(t, player.TimePos{Seconds: 805}, stopped())
	if _, ok := r.onDisk("tt0944947:2:5"); ok {
		t.Fatal("position de l'ancien fichier sous le nouveau contenu")
	}
	r.feed(t, player.FileLoaded{}, player.Duration{Seconds: 2600}, player.TimePos{Seconds: 300}, stopped())
	if p, _ := r.onDisk("tt0944947:2:5"); p != point(300, 2600) {
		t.Fatal(p)
	}
	if p, _ := r.onDisk("tt0111161"); p != point(800, 3000) {
		t.Fatal(p)
	}
}

// A start inside the file is left alone; a resumed stream reopened with the same
// duration (no new Duration event) still ends normally.
func TestResumeStartInsideTheFile(t *testing.T) {
	r := newRig(t)
	r.playing(t, "tt0111161", startAt(1200), 3000)
	r.feed(t, player.TimePos{Seconds: 1200}, player.TimePos{Seconds: 2999.9}, eof())
	if len(r.spawned)+len(r.commands) != 0 {
		t.Fatal(r.commands)
	}
	r.tr.Begin("tt0111161", startAt(1200))
	r.feed(t, player.FileLoaded{}, player.TimePos{Seconds: 1200}, player.TimePos{Seconds: 1206}, player.TimePos{Seconds: 2999.9}, eof())
	if len(r.spawned)+len(r.commands) != 0 {
		t.Fatal(r.commands)
	}
}

const fixed = "seekAbsolute 0 false,setPaused 0 false"

// A start past the end of a shorter file (seen with libmpv 0.40 and keep-open:
// FileLoaded, TimePos at the end, Duration, Seeked, Paused, EndFile eof) is put
// back to 0 and played; its end of file never reaches the UI, and the entry stays.
func TestResumeStartPastTheEnd(t *testing.T) {
	r := newRig(t)
	if err := r.store.Put("tt0111161", point(5000, 6000)); err != nil {
		t.Fatal(err)
	}
	r.tr.Begin("tt0111161", startAt(5000))
	r.feed(t, player.FileLoaded{}, player.TimePos{Seconds: 3000}, player.Duration{Seconds: 3000})
	r.feed(t, player.Seeked{}, player.Paused{Paused: true})
	if r.tr.PlayerEvent(eof()) {
		t.Fatal("fin de fichier montrée à l'UI")
	}
	r.run()
	if got := strings.Join(r.commands, ","); got != fixed || len(r.released) != 0 {
		t.Fatal(got, r.released)
	}
	r.clock.advance(time.Minute)
	r.feed(t, player.TimePos{Seconds: 3000}, player.TimePos{Seconds: 0}, player.Paused{Paused: false}, player.TimePos{Seconds: 30})
	r.tr.End()
	if p, ok := r.onDisk("tt0111161"); !ok || p != point(5000, 6000) {
		t.Fatal("entrée perdue", p, ok)
	}

	// The seek answered first, its end of file comes after: still held back once.
	r.tr.Begin("tt0111161", startAt(5000))
	r.feed(t, player.FileLoaded{}, player.Duration{Seconds: 3000})
	r.run()
	if r.tr.PlayerEvent(eof()) {
		t.Fatal("fin de fichier montrée à l'UI")
	}
	// From 0 on, the playback is followed as any other: its end goes to the UI.
	r.feed(t, player.TimePos{Seconds: 0}, player.TimePos{Seconds: 2999.9}, eof())
	if _, ok := r.onDisk("tt0111161"); ok {
		t.Fatal("gardé après la fin")
	}

	// The seek won the race: no end of file came, the next real one is shown.
	r.tr.Begin("tt0111161", startAt(5000))
	r.feed(t, player.FileLoaded{}, player.Duration{Seconds: 3000})
	r.run()
	r.feed(t, player.TimePos{Seconds: 0}, player.TimePos{Seconds: 2999.9}, eof())
}

// Only the one end of file of the start past the end is held back: a second one
// goes to the UI, even before any position from 0.
func TestResumeHoldsBackOneEndOfFile(t *testing.T) {
	r := newRig(t)
	r.tr.Begin("tt0111161", startAt(5000))
	r.feed(t, player.FileLoaded{}, player.Duration{Seconds: 3000})
	r.run()
	if r.tr.PlayerEvent(eof()) {
		t.Fatal("première fin montrée")
	}
	r.feed(t, eof())
	// That end finished the playback: nothing is followed any more.
	r.feed(t, player.TimePos{Seconds: 0}, eof())
}

// A seek or resume that fails hands the end of file held back on to the UI, which
// shows the playback as it is; one that comes after the failure is not held back.
func TestResumeFailedFixReleasesTheEndOfFile(t *testing.T) {
	r := newRig(t)
	r.fail = errors.New("mpv")
	r.tr.Begin("tt0111161", startAt(5000))
	r.feed(t, player.FileLoaded{}, player.Duration{Seconds: 3000})
	if r.tr.PlayerEvent(eof()) {
		t.Fatal("fin montrée avant la réponse du seek")
	}
	r.run()
	if got := strings.Join(r.released, ","); got != `{"type":"endFile","reason":"eof","error":null}` {
		t.Fatal(got)
	}
	if r.tr.s != nil {
		t.Fatal("lecture encore suivie")
	}

	r.released = nil
	r.tr.Begin("tt0111161", startAt(5000))
	r.feed(t, player.FileLoaded{}, player.Duration{Seconds: 3000})
	r.run()
	r.feed(t, eof())
	if len(r.released) != 0 {
		t.Fatal(r.released)
	}
}

// Without a Duration first, an end of file on a resumed load is held back only
// when mpv stopped short of the start (the end of a shorter file).
func TestResumeEndOfFileBeforeAnyDuration(t *testing.T) {
	r := newRig(t)
	r.tr.Begin("tt0111161", startAt(5000))
	r.feed(t, player.FileLoaded{}, eof())
	r.tr.Begin("tt0111161", startAt(5000))
	r.feed(t, player.FileLoaded{}, player.TimePos{Seconds: 5000}, player.TimePos{Seconds: 5003}, eof())
	r.run()
	if len(r.spawned)+len(r.commands) != 0 {
		t.Fatal(r.commands)
	}

	r.tr.Begin("tt0111161", startAt(5000))
	r.feed(t, player.FileLoaded{}, player.TimePos{Seconds: 3000})
	if r.tr.PlayerEvent(eof()) {
		t.Fatal("fin de fichier montrée à l'UI")
	}
	r.run()
	if got := strings.Join(r.commands, ","); got != fixed || len(r.released) != 0 {
		t.Fatal(got, r.released)
	}
	r.feed(t, player.TimePos{Seconds: 0}, player.TimePos{Seconds: 2999.9}, eof())
}

// A fix sent for one load never touches the next: an Emby playback (End) or
// another Stremio one (Begin) came first.
func TestResumeFixSkipsALaterLoad(t *testing.T) {
	r := newRig(t)
	r.tr.Begin("tt0111161", startAt(5000))
	r.feed(t, player.FileLoaded{}, player.Duration{Seconds: 3000})
	r.tr.End()
	r.run()
	r.tr.Begin("tt0111161", startAt(5000))
	r.feed(t, player.FileLoaded{}, player.Duration{Seconds: 3000})
	r.tr.Begin("tt0944947:2:5", nil)
	r.run()
	if len(r.spawned)+len(r.commands) != 0 {
		t.Fatal(r.commands)
	}
	r.feed(t, player.FileLoaded{}, eof())
}

// Each list carries the position saved for its content, null when none.
func TestSourcesCarryTheSavedPosition(t *testing.T) {
	svc, _ := signedIn(t)
	store := storeAt(resumePath(t), newClock())
	svc.WithResume(store)
	res := newResolver()
	h := Handlers(svc, res.resolve)
	out, errText := call(t, h, "stremio_sources", `{"itemId":"30207"}`)
	if errText != "" || !strings.HasSuffix(out, `,"resume":null}`) {
		t.Fatal(out, errText)
	}
	if err := store.Put("tt0111161", point(2530, 8520)); err != nil {
		t.Fatal(err)
	}
	if err := store.Put("tt0944947:2:5", point(600, 3000)); err != nil {
		t.Fatal(err)
	}
	out, errText = call(t, h, "stremio_sources", `{"itemId":"30207"}`)
	if errText != "" || !strings.HasSuffix(out, `,"resume":{"positionSeconds":2530,"durationSeconds":8520}}`) {
		t.Fatal(out, errText)
	}
	out, errText = call(t, h, "stremio_sources", `{"itemId":"80475"}`)
	if errText != "" || !strings.HasSuffix(out, `,"resume":{"positionSeconds":600,"durationSeconds":3000}}`) {
		t.Fatal(out, errText)
	}
	out, errText = call(t, h, "stremio_sources", `{"external":{"imdb":"tt0111161","name":"Les Évadés"}}`)
	if errText != "" || !strings.HasSuffix(out, `,"resume":{"positionSeconds":2530,"durationSeconds":8520}}`) {
		t.Fatal(out, errText)
	}
}

// stremio_play passes resume (false when absent) and the stream's content.
func TestPlayHandlerPassesResume(t *testing.T) {
	svc, _ := signedIn(t)
	res := newResolver()
	type played struct {
		target string
		resume bool
	}
	var got []played
	h := map[string]Handler{"stremio_play": PlayHandler(svc, func(_ context.Context, s Selected, resume bool) (any, error) {
		got = append(got, played{s.Target, resume})
		return "ok", nil
	})}
	q, ids := sourcesOf(t, svc, res, "80475")
	s, err := svc.AddonStreams(context.Background(), q, ids["Lumio · Profil"])
	if err != nil {
		t.Fatal(err)
	}
	id := s.Streams[0].ID
	for _, args := range []string{`{"streamId":%q}`, `{"streamId":%q,"resume":true}`, `{"streamId":%q,"resume":false}`, `{"streamId":%q,"resume":null}`} {
		if _, err := call(t, h, "stremio_play", fmt.Sprintf(args, id)); err != "" {
			t.Fatal(err)
		}
	}
	if fmt.Sprint(got) != "[{tt0944947:2:5 false} {tt0944947:2:5 true} {tt0944947:2:5 false} {tt0944947:2:5 false}]" {
		t.Fatal(got)
	}
}

// The previous file's duration, before the new FileLoaded, never decides whether
// the start is past the end.
func TestResumeStartCheckWaitsForTheNewFile(t *testing.T) {
	r := newRig(t)
	r.tr.Begin("tt0111161", startAt(5000))
	r.feed(t, player.Duration{Seconds: 1200}, stopped(), player.FileLoaded{}, player.TimePos{Seconds: 5000},
		player.Duration{Seconds: 7200}, player.TimePos{Seconds: 5010})
	r.tr.End()
	if len(r.spawned)+len(r.commands) != 0 {
		t.Fatal(r.commands)
	}
	if p, ok := r.onDisk("tt0111161"); !ok || p != point(5010, 7200) {
		t.Fatal(p, ok)
	}
}
