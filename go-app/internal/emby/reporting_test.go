package emby

// The inline tests of reporting.rs, under the same names, then the Reporter goroutine
// against the fake server.

import (
	"encoding/json"
	"math"
	"reflect"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"viewer/internal/player"
)

func rptPlayback() ActivePlayback {
	return ActivePlayback{
		ItemID: "30207", MediaSourceID: "mediasource_30207", PlaySessionID: ptr("ps1"),
		Method: MethodDirectPlay, StartTicks: 600_000_000,
	}
}

type rptKind struct {
	Kind  ReportKind
	Event string
}

func rptKinds(reports []Report) []rptKind {
	out := []rptKind{}
	for _, r := range reports {
		k := rptKind{Kind: r.Kind}
		if r.Body.EventName != nil {
			k.Event = *r.Body.EventName
		}
		out = append(out, k)
	}
	return out
}

func rptWant(t *testing.T, got []Report, want ...rptKind) {
	t.Helper()
	if want == nil {
		want = []rptKind{}
	}
	if k := rptKinds(got); !reflect.DeepEqual(k, want) {
		t.Fatalf("rapports %v, attendu %v", k, want)
	}
}

func rptLoaded(t *testing.T, t0 time.Time) *Tracker {
	t.Helper()
	var tracker Tracker
	if r := tracker.Begin(rptPlayback(), t0); len(r) != 0 {
		t.Fatalf("%v", r)
	}
	if r := tracker.OnEvent(player.FileLoaded{}, t0); len(r) == 0 {
		t.Fatal("pas de début")
	}
	return &tracker
}

func rptBody(t *testing.T, r Report) map[string]any {
	t.Helper()
	var m map[string]any
	if err := json.Unmarshal(marshal(t, r.Body), &m); err != nil {
		t.Fatal(err)
	}
	return m
}

func Test_start_is_reported_on_load_at_the_resume_position(t *testing.T) {
	t0 := time.Now()
	var tracker Tracker
	tracker.Begin(rptPlayback(), t0)
	if r := tracker.Tick(t0.Add(ProgressInterval)); len(r) != 0 {
		t.Fatal("not loaded")
	}
	start := tracker.OnEvent(player.FileLoaded{}, t0)
	rptWant(t, start, rptKind{Kind: ReportStart})
	assertSameJSON(t, "start", []byte(`{
		"ItemId": "30207", "MediaSourceId": "mediasource_30207",
		"PlaySessionId": "ps1", "PositionTicks": 600000000,
		"IsPaused": false, "CanSeek": true, "PlayMethod": "DirectPlay"
	}`), marshal(t, start[0].Body))
}

func Test_progress_follows_pause_seek_and_the_interval(t *testing.T) {
	t0 := time.Now()
	tr := rptLoaded(t, t0)
	pos := func(s float64) player.PlayerEvent { return player.TimePos{Seconds: s} }
	rptWant(t, tr.OnEvent(pos(61.0), t0))
	rptWant(t, tr.OnEvent(player.Duration{Seconds: 6000.0}, t0))
	// mpv reports the initial unpaused state: no change, nothing sent.
	rptWant(t, tr.OnEvent(player.Paused{Paused: false}, t0))
	rptWant(t, tr.Tick(t0.Add(9*time.Second)))

	progress := tr.Tick(t0.Add(ProgressInterval))
	rptWant(t, progress, rptKind{ReportProgress, "TimeUpdate"})
	if progress[0].Body.PositionTicks != 610_000_000 || progress[0].Body.RunTimeTicks == nil || *progress[0].Body.RunTimeTicks != 60_000_000_000 {
		t.Fatalf("%+v", progress[0].Body)
	}

	t1 := t0.Add(12 * time.Second)
	pause := tr.OnEvent(player.Paused{Paused: true}, t1)
	rptWant(t, pause, rptKind{ReportProgress, "Pause"})
	if p := pause[0].Body.IsPaused; p == nil || !*p {
		t.Fatal("IsPaused")
	}
	// Still reported while paused, from the last report.
	rptWant(t, tr.Tick(t1.Add(9*time.Second)))
	if n := len(tr.Tick(t1.Add(ProgressInterval))); n != 1 {
		t.Fatal(n)
	}

	t2 := t1.Add(11 * time.Second)
	rptWant(t, tr.OnEvent(player.Paused{Paused: false}, t2), rptKind{ReportProgress, "Unpause"})
	rptWant(t, tr.OnEvent(player.Seeked{}, t2))
	afterSeek := tr.OnEvent(pos(1200.5), t2)
	rptWant(t, afterSeek, rptKind{ReportProgress, "TimeUpdate"})
	if afterSeek[0].Body.PositionTicks != 12_005_000_000 {
		t.Fatal(afterSeek[0].Body.PositionTicks)
	}
	rptWant(t, tr.OnEvent(pos(1201.0), t2))
	rptWant(t, tr.OnEvent(pos(math.NaN()), t2))
}

func Test_stop_carries_the_last_position_and_failure(t *testing.T) {
	t0 := time.Now()
	for _, c := range []struct {
		event  player.PlayerEvent
		failed bool
	}{
		{player.EndFile{Reason: player.EndEOF}, false},
		{player.EndFile{Reason: player.EndError, Error: ptr("x")}, true},
		{player.Shutdown{}, false},
	} {
		tr := rptLoaded(t, t0)
		// 10 s before the end of the 6000 s file: a normal end.
		tr.OnEvent(player.Duration{Seconds: 6000.0}, t0)
		tr.OnEvent(player.TimePos{Seconds: 5990.0}, t0)
		stop := tr.OnEvent(c.event, t0)
		rptWant(t, stop, rptKind{Kind: ReportStopped})
		body := rptBody(t, stop[0])
		if body["PositionTicks"] != 59_900_000_000.0 || body["Failed"] != c.failed {
			t.Fatalf("%#v: %v", c.event, body)
		}
		if _, ok := body["IsPaused"]; ok {
			t.Fatal("IsPaused")
		}
		// Nothing more once stopped.
		rptWant(t, tr.OnEvent(player.Shutdown{}, t0))
		rptWant(t, tr.Tick(t0.Add(3*ProgressInterval)))
	}
}

func Test_an_early_end_of_file_is_reported_as_failed_at_the_last_position(t *testing.T) {
	t0 := time.Now()
	tr := rptLoaded(t, t0)
	tr.OnEvent(player.Duration{Seconds: 6000.0}, t0)
	tr.OnEvent(player.TimePos{Seconds: 1000.0}, t0)
	stop := tr.OnEvent(player.EndFile{Reason: player.EndEOF}, t0)
	body := rptBody(t, stop[0])
	if body["Failed"] != true || body["PositionTicks"] != 10_000_000_000.0 {
		t.Fatal(body)
	}
}

func Test_a_file_that_never_loads_is_never_reported(t *testing.T) {
	t0 := time.Now()
	var tr Tracker
	tr.Begin(rptPlayback(), t0)
	rptWant(t, tr.OnEvent(player.EndFile{Reason: player.EndStopped}, t0))
	rptWant(t, tr.OnEvent(player.EndFile{Reason: player.EndError}, t0))
	rptWant(t, tr.OnEvent(player.FileLoaded{}, t0))

	// Events without any announced playback (local files of the prototype).
	var idle Tracker
	rptWant(t, idle.OnEvent(player.FileLoaded{}, t0))
	rptWant(t, idle.OnEvent(player.Shutdown{}, t0))
}

func Test_a_lost_stop_report_is_resent_with_a_growing_delay_then_given_up(t *testing.T) {
	t0 := time.Now()
	s := newResendSchedule(t0)
	if s.due(t0.Add(4*time.Second)) || !s.due(t0.Add(stopResendFirst)) {
		t.Fatal("premier délai")
	}

	t1 := t0.Add(stopResendFirst)
	s.postpone(t1)
	if s.due(t1.Add(9*time.Second)) || !s.due(t1.Add(10*time.Second)) {
		t.Fatal("délai doublé")
	}

	t2 := t1.Add(10 * time.Second)
	s.postpone(t2)
	if !s.due(t2.Add(stopResendCap)) {
		t.Fatal("plafond")
	}
	s.postpone(t2)
	if !s.due(t2.Add(stopResendCap)) {
		t.Fatal("capped")
	}

	if s.expired(t0.Add(stopResendGiveUp-time.Second)) || !s.expired(t0.Add(stopResendGiveUp)) {
		t.Fatal("abandon")
	}
}

func Test_a_late_progress_carries_the_position_of_the_lost_stop(t *testing.T) {
	stop := Report{Kind: ReportStopped, Body: PlaybackReportBody{
		ItemID: "30207", MediaSourceID: "ms", PlaySessionID: ptr("ps1"), PositionTicks: 7_583_000_000,
		RunTimeTicks: ptr[int64](60_000_000_000), PlayMethod: ptr("Transcode"), Failed: ptr(true),
	}}
	late := stop.LateProgress()
	b := late.Body
	if late.Kind != ReportProgress || b.PositionTicks != 7_583_000_000 || *b.PlaySessionID != "ps1" ||
		*b.PlayMethod != "Transcode" || *b.IsPaused || !*b.CanSeek || *b.EventName != "TimeUpdate" || b.Failed != nil {
		t.Fatalf("%+v", late)
	}
}

func Test_only_a_passing_failure_keeps_the_stop_report(t *testing.T) {
	for _, err := range []error{ErrTimeout, NewUnreachable("x"), NewServer(503)} {
		if !isTransient(err) {
			t.Fatal(err)
		}
	}
	for _, err := range []error{ErrSessionExpired, ErrNotSignedIn, ErrCancelled} {
		if isTransient(err) {
			t.Fatal(err)
		}
	}
}

func Test_a_new_playback_stops_the_current_one(t *testing.T) {
	t0 := time.Now()
	tr := rptLoaded(t, t0)
	next := rptPlayback()
	next.ItemID = "80475"
	stop := tr.Begin(next, t0)
	rptWant(t, stop, rptKind{Kind: ReportStopped})
	if stop[0].Body.ItemID != "30207" {
		t.Fatal(stop[0].Body.ItemID)
	}
	if start := tr.OnEvent(player.FileLoaded{}, t0); start[0].Body.ItemID != "80475" {
		t.Fatal(start[0].Body.ItemID)
	}
}

func rptSwitched(method PlayMethod, session string) ActivePlayback {
	p := rptPlayback()
	p.PlaySessionID, p.Method, p.StartTicks = ptr(session), method, 900_000_000
	return p
}

func Test_quality_switch_goes_on_with_a_quality_change_progress(t *testing.T) {
	t0 := time.Now()
	tr := rptLoaded(t, t0)
	rptWant(t, tr.Switch(rptSwitched(MethodTranscode, "ps2"), t0))
	rptWant(t, tr.OnEvent(player.EndFile{Reason: player.EndStopped}, t0)) // no stop
	reports := tr.OnEvent(player.FileLoaded{}, t0)
	rptWant(t, reports, rptKind{ReportProgress, "QualityChange"})
	if *reports[0].Body.PlaySessionID != "ps2" || reports[0].Body.PositionTicks != 900_000_000 {
		t.Fatalf("%+v", reports[0].Body)
	}
	// The old stream was direct play: nothing to stop on the server.
	if e := tr.TakeEndedEncoding(); e != nil {
		t.Fatal(*e)
	}
}

func Test_a_replaced_transcode_is_stopped_once(t *testing.T) {
	t0 := time.Now()
	tr := rptLoaded(t, t0)
	tr.Switch(rptSwitched(MethodTranscode, "ps2"), t0)
	tr.OnEvent(player.FileLoaded{}, t0)
	tr.Switch(rptSwitched(MethodDirectPlay, "ps3"), t0)
	tr.OnEvent(player.FileLoaded{}, t0)
	if e := tr.TakeEndedEncoding(); e == nil || *e != "ps2" {
		t.Fatal(e)
	}
	if e := tr.TakeEndedEncoding(); e != nil {
		t.Fatal(*e)
	}
}

func Test_a_failed_switch_stops_the_old_playback(t *testing.T) {
	t0 := time.Now()
	tr := rptLoaded(t, t0)
	tr.Switch(rptSwitched(MethodTranscode, "ps2"), t0)
	stop := tr.OnEvent(player.EndFile{Reason: player.EndError, Error: ptr("x")}, t0)
	rptWant(t, stop, rptKind{Kind: ReportStopped})
	if *stop[0].Body.PlaySessionID != "ps1" {
		t.Fatal(*stop[0].Body.PlaySessionID)
	}
}

func Test_a_switch_to_another_item_is_a_new_playback(t *testing.T) {
	t0 := time.Now()
	tr := rptLoaded(t, t0)
	other := rptPlayback()
	other.ItemID = "80475"
	rptWant(t, tr.Switch(other, t0), rptKind{Kind: ReportStopped})
}

// The Reporter goroutine against the fake server: start, stop, then the state the
// server holds is announced; the token only travels in the header.
func Test_go_reporter_sends_the_reports_and_announces_the_state(t *testing.T) {
	server, fx, _ := signedInBrowser(t)
	var mu sync.Mutex
	var events []string
	var states []PlaybackStopped
	r := NewReporter(fx.service, func(event string, payload any) {
		mu.Lock()
		defer mu.Unlock()
		events = append(events, event)
		states = append(states, payload.(PlaybackStopped))
	})
	r.Begin(rptPlayback())
	r.PlayerEvent(player.Tracks{})
	r.PlayerEvent(player.FileLoaded{})
	r.PlayerEvent(player.TimePos{Seconds: 61})
	r.PlayerEvent(player.EndFile{Reason: player.EndStopped})
	if !r.Flush(5 * time.Second) {
		t.Fatal("flush")
	}
	if !r.Close(5 * time.Second) {
		t.Fatal("close")
	}
	var paths []string
	for _, req := range server.requests() {
		if strings.HasPrefix(req.Path, "/emby/Sessions/Playing") {
			paths = append(paths, req.Path)
			if !req.hasToken() || strings.Contains(req.Path, fakeToken) {
				t.Fatalf("%+v", req)
			}
		}
	}
	if !reflect.DeepEqual(paths, []string{"/emby/Sessions/Playing", "/emby/Sessions/Playing/Stopped"}) {
		t.Fatal(paths)
	}
	mu.Lock()
	defer mu.Unlock()
	if !reflect.DeepEqual(events, []string{PlaybackStoppedEvent}) || len(states) != 1 {
		t.Fatal(events)
	}
	// Closed: nothing more is taken.
	if r.Flush(time.Second) {
		t.Fatal("flush après Close")
	}
}

// A stop report lost to a passing failure is retried once, kept, then sent again by
// Flush and ResendStop with a late progress ahead of it (ADR 0024).
func Test_go_reporter_resends_a_lost_stop_report(t *testing.T) {
	revoked := new(atomic.Bool)
	down := new(atomic.Bool)
	down.Store(true)
	base := fakeBrowseLike(revoked)
	server := startFakeEmby(t, func(r fakeRequest) (int, string) {
		if r.Path == "/emby/Sessions/Playing/Stopped" && down.Load() {
			return 503, ""
		}
		return base(r)
	})
	fx := newFixture(t)
	mustSignIn(t, fx, server.URL)
	r := NewReporter(fx.service, nil)
	defer r.Close(5 * time.Second)
	r.Begin(rptPlayback())
	r.PlayerEvent(player.FileLoaded{})
	r.PlayerEvent(player.EndFile{Reason: player.EndStopped})
	if !r.Flush(5 * time.Second) {
		t.Fatal("flush")
	}
	down.Store(false)
	r.ResendStop(bg)
	var paths []string
	for _, req := range server.requests() {
		if strings.HasPrefix(req.Path, "/emby/Sessions/Playing") {
			paths = append(paths, req.Path)
		}
	}
	want := []string{
		"/emby/Sessions/Playing",
		"/emby/Sessions/Playing/Stopped", "/emby/Sessions/Playing/Stopped", // the try and its retry
		// Flush resends at once, as Rust's: the server is still down, the stop is kept.
		"/emby/Sessions/Playing/Progress", "/emby/Sessions/Playing/Stopped",
		"/emby/Sessions/Playing/Progress", "/emby/Sessions/Playing/Stopped", // ResendStop
	}
	if !reflect.DeepEqual(paths, want) {
		t.Fatal(paths)
	}
}
