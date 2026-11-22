package player

// Tests of StallWatch in player/mod.rs, under the same names.

import (
	"testing"
	"time"
)

const (
	testLimit   = 30 * time.Second
	testOpening = 45 * time.Second
)

func at(start time.Time, seconds int) time.Time {
	return start.Add(time.Duration(seconds) * time.Second)
}

func loaded(start time.Time) *StallWatch {
	w := NewStallWatch(testLimit, testOpening, start)
	w.OnEvent(FileLoaded{}, start)
	return w
}

func expired(w *StallWatch, now time.Time) bool {
	_, ok := w.Expired(now)
	return ok
}

func expect(t *testing.T, w *StallWatch, now time.Time, want Stall, msg string) {
	t.Helper()
	got, ok := w.Expired(now)
	if want == 0 && ok {
		t.Errorf("%s: expired %v, want nothing", msg, got)
	}
	if want != 0 && (!ok || got != want) {
		t.Errorf("%s: got %v %v, want %v", msg, got, ok, want)
	}
}

func Test_stall_expires_once_when_the_position_freezes(t *testing.T) {
	start := time.Now()
	w := loaded(start)
	w.OnEvent(TimePos{Seconds: 1}, at(start, 10))
	if expired(w, at(start, 39)) {
		t.Fatal("39")
	}
	if !expired(w, at(start, 40)) {
		t.Fatal("40")
	}
	if expired(w, at(start, 80)) {
		t.Fatal("reported once")
	}
}

func Test_opening_expires_once_at_its_own_limit(t *testing.T) {
	start := time.Now()
	w := NewStallWatch(testLimit, testOpening, start)
	w.OnLoadStarted(start)
	expect(t, w, at(start, 44), 0, "44")
	expect(t, w, at(start, 45), StallOpening, "45")
	expect(t, w, at(start, 500), 0, "reported once")
}

func Test_opening_is_not_extended_by_a_pause(t *testing.T) {
	start := time.Now()
	w := NewStallWatch(testLimit, testOpening, start)
	w.OnLoadStarted(start)
	w.OnEvent(Paused{Paused: true}, at(start, 40))
	expect(t, w, at(start, 45), StallOpening, "45")
}

func Test_loading_the_file_hands_over_to_the_position_limit(t *testing.T) {
	start := time.Now()
	w := NewStallWatch(testLimit, testOpening, start)
	w.OnLoadStarted(start)
	w.OnEvent(FileLoaded{}, at(start, 40))
	expect(t, w, at(start, 69), 0, "69")
	expect(t, w, at(start, 70), StallPlaying, "70")
}

func Test_an_end_of_file_cancels_the_opening(t *testing.T) {
	start := time.Now()
	w := NewStallWatch(testLimit, testOpening, start)
	w.OnLoadStarted(start)
	w.OnEvent(EndFile{Reason: EndStopped}, at(start, 10))
	expect(t, w, at(start, 500), 0, "500")
	w.OnLoadStarted(at(start, 500))
	expect(t, w, at(start, 544), 0, "next file, own limit")
	expect(t, w, at(start, 545), StallOpening, "545")
}

func Test_stall_ignores_a_repeated_position(t *testing.T) {
	start := time.Now()
	w := loaded(start)
	w.OnEvent(TimePos{Seconds: 1}, at(start, 10))
	w.OnEvent(TimePos{Seconds: 1}, at(start, 35))
	if !expired(w, at(start, 40)) {
		t.Fatal("a repeated position is no progress")
	}
}

func Test_stall_waits_for_a_file_and_stops_with_it(t *testing.T) {
	start := time.Now()
	w := NewStallWatch(testLimit, testOpening, start)
	if expired(w, at(start, 60)) {
		t.Fatal("nothing loaded yet")
	}
	w.OnEvent(FileLoaded{}, at(start, 60))
	if expired(w, at(start, 89)) {
		t.Fatal("loading does not count")
	}
	w.OnEvent(EndFile{Reason: EndEOF}, at(start, 70))
	if expired(w, at(start, 200)) {
		t.Fatal("end of file")
	}
	w.OnEvent(FileLoaded{}, at(start, 200))
	if !expired(w, at(start, 230)) {
		t.Fatal("next file watched again")
	}
}

func Test_stall_does_not_count_a_user_pause(t *testing.T) {
	start := time.Now()
	w := loaded(start)
	w.OnEvent(Paused{Paused: true}, at(start, 10))
	if expired(w, at(start, 500)) {
		t.Fatal("paused")
	}
	w.OnEvent(Paused{Paused: false}, at(start, 500))
	if expired(w, at(start, 529)) {
		t.Fatal("counted from the unpause")
	}
	if !expired(w, at(start, 530)) {
		t.Fatal("530")
	}
}

// Not in Rust: Shutdown disarms like EndFile, a time before the last progress
// never expires (Instant::duration_since saturates), and a NaN position counts.
func Test_go_stall_shutdown_past_times_and_nan(t *testing.T) {
	start := time.Now()
	w := loaded(start)
	w.OnEvent(Shutdown{}, at(start, 1))
	if expired(w, at(start, 500)) {
		t.Fatal("shutdown disarms")
	}
	w = loaded(at(start, 100))
	if expired(w, at(start, 0)) {
		t.Fatal("time before the last progress")
	}
	w = loaded(start)
	nan := TimePos{Seconds: 0}
	nan.Seconds = nan.Seconds / nan.Seconds // NaN without a constant expression
	w.OnEvent(nan, at(start, 10))
	w.OnEvent(nan, at(start, 35))
	if expired(w, at(start, 40)) {
		t.Fatal("NaN never equals the last position, so it is progress")
	}
	// Other events leave the watch alone.
	w.OnEvent(Seeked{}, at(start, 100))
	w.OnEvent(Buffering{Active: true}, at(start, 100))
	if !expired(w, at(start, 65)) {
		t.Fatal("seeked and buffering are no progress")
	}
}
