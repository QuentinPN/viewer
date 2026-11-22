package devsync

import (
	"fmt"
	"reflect"
	"testing"
)

// Port of the tests of src-tauri/src/sync/leader.rs, same names and fixtures.

const (
	tvID   = "tv-1"
	linkID = "link-0001"
)

func strp(s string) *string { return &s }
func i32p(n int32) *int32   { return &n }
func i64p(n int64) *int64   { return &n }

func snap(item string, paused bool) *Snapshot {
	return &Snapshot{
		ItemID:              item,
		MediaSourceID:       strp("ms-" + item),
		PositionMs:          754_000,
		Paused:              paused,
		Buffering:           false,
		AudioStreamIndex:    i32p(1),
		SubtitleStreamIndex: i32p(-1),
	}
}

func peer() Peer { return Peer{SessionID: tvID, Name: "Shield"} }

func env(from, link string, seq uint32, body Body) Input {
	var name *string
	if from == tvID || from == "tv-2" {
		name = strp("Shield")
	}
	return Received{Env: Envelope{From: from, Link: link, Seq: seq, Body: body}, PeerName: name}
}

func tv(seq uint32, body Body) Input { return env(tvID, linkID, seq, body) }

func toTV(body Body) Effect { return Send{To: tvID, Link: linkID, Body: body} }

func bye(reason ByeReason) Body { return Bye{Reason: reason} }

func ready(item string) Body { return Ready{ItemID: item, PositionMs: 754_000} }

func state(item string, paused bool) Body {
	return State{ItemID: item, PositionMs: 754_000, Paused: paused, Buffering: false}
}

var (
	pause = PlayerAction{Kind: PlayerPause}
	play  = PlayerAction{Kind: PlayerPlay}
)

func invite(l *Leader, now Ms, s *Snapshot) []Effect {
	return l.Handle(now, s, Invite{Peer: peer(), Link: linkID})
}

// linked: invited at 0, ready at 1000, linked and playing.
func linked() *Leader {
	l := &Leader{}
	s := snap("68397", false)
	invite(l, 0, s)
	l.Handle(1000, snap("68397", true), tv(0, ready("68397")))
	return l
}

func hasSend(fx []Effect, kind string) bool {
	for _, e := range fx {
		if s, ok := e.(Send); ok && s.Body.Kind() == kind {
			return true
		}
	}
	return false
}

func contains(fx []Effect, want Effect) bool {
	for _, e := range fx {
		if reflect.DeepEqual(e, want) {
			return true
		}
	}
	return false
}

func hasNotice(fx []Effect) bool {
	for _, e := range fx {
		if _, ok := e.(Notice); ok {
			return true
		}
	}
	return false
}

func assertEffects(t *testing.T, got []Effect, want ...Effect) {
	t.Helper()
	if len(got) == 0 && len(want) == 0 {
		return
	}
	if !reflect.DeepEqual(got, want) {
		t.Fatalf("effects\ngot  %s\nwant %s", show(got), show(want))
	}
}

func assertNone(t *testing.T, got []Effect) {
	t.Helper()
	if len(got) != 0 {
		t.Fatalf("want no effect, got %s", show(got))
	}
}

func show(fx []Effect) string {
	out := "["
	for i, e := range fx {
		if i > 0 {
			out += ", "
		}
		out += fmt.Sprintf("%T%+v", e, e)
	}
	return out + "]"
}

func assertStatus(t *testing.T, l *Leader, want Status) {
	t.Helper()
	if got := l.Status(); !reflect.DeepEqual(got, want) {
		t.Fatalf("status %+v, want %+v", got, want)
	}
}

func assertPhase(t *testing.T, l *Leader, want Phase) {
	t.Helper()
	if got := l.Status().Phase; got != want {
		t.Fatalf("phase %s, want %s", got, want)
	}
}

func Test_invite_pauses_and_loads_then_ready_links_and_resumes(t *testing.T) {
	l := &Leader{}
	s := snap("68397", false)
	fx := invite(l, 0, s)
	assertEffects(t, fx,
		pause,
		toTV(Load{
			ItemID:              "68397",
			MediaSourceID:       strp("ms-68397"),
			PositionMs:          754_000,
			AudioStreamIndex:    i32p(1),
			SubtitleStreamIndex: i32p(-1),
		}),
		StatusChanged{},
	)
	assertStatus(t, l, Status{Phase: PhaseWaiting, Device: "Shield"})
	paused := snap("68397", true)
	fx = l.Handle(1000, paused, tv(0, ready("68397")))
	assertEffects(t, fx,
		play,
		toTV(state("68397", false)),
		Notice{Kind: NoticeFollowing, Device: "Shield"},
		StatusChanged{},
	)
	assertStatus(t, l, Status{Phase: PhaseLinked, Device: "Shield", DriftMs: nil})
}

func Test_invite_while_paused_stays_paused_at_ready(t *testing.T) {
	l := &Leader{}
	s := snap("1", true)
	fx := invite(l, 0, s)
	if contains(fx, pause) {
		t.Fatalf("paused at invite: %s", show(fx))
	}
	fx = l.Handle(500, s, tv(0, ready("1")))
	if contains(fx, play) {
		t.Fatalf("played at ready: %s", show(fx))
	}
	if !contains(fx, toTV(state("1", true))) {
		t.Fatalf("no paused state: %s", show(fx))
	}
}

func Test_invite_needs_a_playback(t *testing.T) {
	l := &Leader{}
	fx := l.Handle(0, nil, Invite{Peer: peer(), Link: linkID})
	assertNone(t, fx)
	if l.IsActive() {
		t.Fatal("active")
	}
}

func Test_a_silent_tv_is_given_up_after_10_s(t *testing.T) {
	l := &Leader{}
	s := snap("1", false)
	invite(l, 0, s)
	assertNone(t, l.Handle(9_999, s, Tick{}))
	fx := l.Handle(10_000, s, Tick{})
	assertEffects(t, fx,
		toTV(bye(ByeTimeout)),
		play,
		Notice{Kind: NoticeNoAnswer, Device: "Shield"},
		StatusChanged{},
	)
	assertStatus(t, l, Status{Phase: PhaseIdle})
}

func Test_an_answering_tv_has_30_s_to_be_ready(t *testing.T) {
	l := &Leader{}
	s := snap("1", false)
	invite(l, 0, s)
	fx := l.Handle(2_000, s, tv(0, Ping{T: 5, DriftMs: nil}))
	assertEffects(t, fx, toTV(Pong{T: 5}))
	assertNone(t, l.Handle(29_999, s, Tick{}))
	fx = l.Handle(30_000, s, Tick{})
	if !contains(fx, toTV(bye(ByeTimeout))) {
		t.Fatalf("no bye timeout: %s", show(fx))
	}
	if l.IsActive() {
		t.Fatal("still active")
	}
}

func Test_join_while_idle_answers_bye_idle(t *testing.T) {
	l := &Leader{}
	fx := l.Handle(0, nil, env("tv-2", "link-new1", 0, Join{}))
	assertEffects(t, fx, Send{To: "tv-2", Link: "link-new1", Body: bye(ByeIdle)})
	if l.IsActive() {
		t.Fatal("active")
	}
}

func Test_join_while_playing_starts_and_announces(t *testing.T) {
	l := &Leader{}
	s := snap("1", false)
	fx := l.Handle(0, s, tv(3, Join{}))
	if len(fx) == 0 || !reflect.DeepEqual(fx[0], Effect(Notice{Kind: NoticeFollowing, Device: "Shield"})) {
		t.Fatalf("first effect: %s", show(fx))
	}
	if !contains(fx, pause) {
		t.Fatalf("no pause: %s", show(fx))
	}
	if !hasSend(fx, "load") {
		t.Fatalf("no load: %s", show(fx))
	}
	// Its seq is remembered: an older message of the TV is dropped.
	assertNone(t, l.Handle(10, s, tv(3, ready("1"))))
	fx = l.Handle(20, s, tv(4, ready("1")))
	if !contains(fx, play) {
		t.Fatalf("no play: %s", show(fx))
	}
	// Announced once only.
	if hasNotice(fx) {
		t.Fatalf("announced twice: %s", show(fx))
	}
}

func Test_join_from_an_unlisted_session_is_ignored(t *testing.T) {
	l := &Leader{}
	s := snap("1", false)
	assertNone(t, l.Handle(0, s, env("pc-9", "link-new1", 0, Join{})))
}

func Test_join_while_linked_answers_busy_to_the_other_tv(t *testing.T) {
	l := linked()
	fx := l.Handle(1500, nil, env("tv-2", "link-0009", 0, Join{}))
	assertEffects(t, fx, Send{To: "tv-2", Link: "link-0009", Body: bye(ByeBusy)})
	assertPhase(t, l, PhaseLinked)
}

func Test_commands_map_to_local_actions(t *testing.T) {
	l := linked()
	cases := []struct {
		cmd  Cmd
		want Effect
	}{
		{Cmd{Action: CmdPause}, pause},
		{Cmd{Action: CmdPlay}, play},
		{Cmd{Action: CmdSeek, PositionMs: 128_000}, PlayerAction{Kind: PlayerSeek, PositionMs: 128_000}},
		{Cmd{Action: CmdNext}, UINext},
		{Cmd{Action: CmdPrevious}, UIPrevious},
	}
	for i, c := range cases {
		fx := l.Handle(1100+Ms(i), nil, tv(1+uint32(i), c.cmd))
		assertEffects(t, fx, c.want)
	}
}

func Test_commands_during_a_load_are_ignored(t *testing.T) {
	l := &Leader{}
	s := snap("1", false)
	invite(l, 0, s)
	assertNone(t, l.Handle(10, s, tv(0, Cmd{Action: CmdPause})))
}

func Test_state_goes_every_second_and_after_a_local_change(t *testing.T) {
	l := linked()
	s := snap("68397", false)
	assertNone(t, l.Handle(1999, s, Tick{}))
	fx := l.Handle(1000+StateEvery, s, Tick{})
	assertEffects(t, fx, toTV(state("68397", false)))
	p := snap("68397", true)
	fx = l.Handle(2100, p, LocalChanged{Resumed: false})
	assertEffects(t, fx, toTV(state("68397", true)))
	// Nothing plays (end of file): nothing to tell.
	assertNone(t, l.Handle(3200, nil, Tick{}))
}

func Test_ping_is_answered_and_its_drift_shown(t *testing.T) {
	l := linked()
	fx := l.Handle(1200, nil, tv(1, Ping{T: 99, DriftMs: i64p(-42)}))
	assertEffects(t, fx, toTV(Pong{T: 99}), StatusChanged{})
	assertStatus(t, l, Status{Phase: PhaseLinked, Device: "Shield", DriftMs: i64p(-42)})
	// Same drift: no new status.
	fx = l.Handle(1300, nil, tv(2, Ping{T: 100, DriftMs: i64p(-42)}))
	assertEffects(t, fx, toTV(Pong{T: 100}))
}

func Test_five_seconds_of_silence_lose_the_link(t *testing.T) {
	l := linked()
	l.Handle(2000, nil, tv(1, Ping{T: 1, DriftMs: nil}))
	assertNone(t, l.Handle(6999, nil, Tick{}))
	fx := l.Handle(7000, nil, Tick{})
	assertEffects(t, fx, Notice{Kind: NoticeLost, Device: "Shield"}, StatusChanged{})
	assertStatus(t, l, Status{Phase: PhaseIdle})
	// No automatic relink: a later ping of the old link is ignored.
	assertNone(t, l.Handle(7100, nil, tv(2, Ping{T: 2, DriftMs: nil})))
}

func Test_tv_buffering_pauses_then_resumes(t *testing.T) {
	l := linked()
	s := snap("68397", false)
	fx := l.Handle(1100, s, tv(1, Buffering{On: true}))
	assertEffects(t, fx, pause, StatusChanged{})
	assertPhase(t, l, PhaseWaiting)
	p := snap("68397", true)
	fx = l.Handle(1500, p, tv(2, Buffering{On: false}))
	assertEffects(t, fx, play, StatusChanged{})
	assertPhase(t, l, PhaseLinked)
}

func Test_tv_buffering_does_not_resume_a_user_pause(t *testing.T) {
	l := linked()
	s := snap("68397", false)
	l.Handle(1100, s, tv(1, Buffering{On: true}))
	// The user resumed, then paused again, while the TV buffered.
	l.Handle(1200, s, LocalChanged{Resumed: true})
	p := snap("68397", true)
	l.Handle(1300, p, LocalChanged{Resumed: false})
	fx := l.Handle(1500, p, tv(2, Buffering{On: false}))
	if contains(fx, play) {
		t.Fatalf("resumed a user pause: %s", show(fx))
	}
}

func Test_tv_buffering_15_s_ends_the_link(t *testing.T) {
	l := linked()
	s := snap("68397", false)
	l.Handle(1000, s, tv(1, Buffering{On: true}))
	p := snap("68397", true)
	// Pings keep coming; the buffering does not end.
	i := 0
	for at := Ms(2000); at < 16_000; at += 1000 {
		l.Handle(at, p, tv(2+uint32(i), Ping{T: at, DriftMs: nil}))
		if hasSend(l.Handle(at, p, Tick{}), "bye") {
			t.Fatalf("bye at %d", at)
		}
		i++
	}
	fx := l.Handle(16_000, p, Tick{})
	assertEffects(t, fx,
		toTV(bye(ByeTimeout)),
		play,
		Notice{Kind: NoticeNoAnswer, Device: "Shield"},
		StatusChanged{},
	)
}

func Test_bye_stopped_stops_the_player(t *testing.T) {
	l := linked()
	fx := l.Handle(1100, nil, tv(1, bye(ByeStopped)))
	assertEffects(t, fx, StatusChanged{}, UIStop)
	if l.IsActive() {
		t.Fatal("still active")
	}
}

func Test_bye_busy_during_a_load_resumes_alone(t *testing.T) {
	l := &Leader{}
	s := snap("1", false)
	invite(l, 0, s)
	fx := l.Handle(100, s, tv(0, bye(ByeBusy)))
	assertEffects(t, fx, play, Notice{Kind: NoticeBusy, Device: "Shield"}, StatusChanged{})
}

func Test_bye_left_lets_the_computer_play_on(t *testing.T) {
	l := linked()
	fx := l.Handle(1100, nil, tv(1, bye(ByeLeft)))
	assertEffects(t, fx, StatusChanged{})
}

func Test_unlink_and_local_stop_tell_the_tv(t *testing.T) {
	l := linked()
	assertEffects(t, l.Handle(1100, nil, Unlink{}), toTV(bye(ByeLeft)), StatusChanged{})
	l = linked()
	assertEffects(t, l.Handle(1100, nil, LocalStopped{}), toTV(bye(ByeStopped)), StatusChanged{})
	// Nothing linked: nothing sent.
	assertNone(t, (&Leader{}).Handle(0, nil, LocalStopped{}))
}

func Test_continue_without_the_tv_during_a_load_resumes(t *testing.T) {
	l := &Leader{}
	s := snap("1", false)
	invite(l, 0, s)
	fx := l.Handle(100, s, Unlink{})
	assertEffects(t, fx, toTV(bye(ByeLeft)), play, StatusChanged{})
}

func Test_next_episode_reloads_on_the_same_link(t *testing.T) {
	l := linked()
	next := snap("68398", false)
	fx := l.Handle(5000, next, FileLoaded{})
	if len(fx) < 2 || !reflect.DeepEqual(fx[0], Effect(pause)) {
		t.Fatalf("first effect: %s", show(fx))
	}
	if send, ok := fx[1].(Send); !ok || send.Link != linkID {
		t.Fatalf("second effect: %s", show(fx))
	} else if load, ok := send.Body.(Load); !ok || load.ItemID != "68398" {
		t.Fatalf("second effect: %s", show(fx))
	}
	assertPhase(t, l, PhaseWaiting)
	// A ready for the previous title is not this one.
	p := snap("68398", true)
	assertNone(t, l.Handle(5100, p, tv(1, ready("68397"))))
	fx = l.Handle(5200, p, tv(2, ready("68398")))
	if !contains(fx, play) {
		t.Fatalf("no play: %s", show(fx))
	}
	// Not announced again.
	if hasNotice(fx) {
		t.Fatalf("announced again: %s", show(fx))
	}
	// The reloaded TV has 30 s, even before it pings.
	l = linked()
	l.Handle(5000, next, FileLoaded{})
	assertNone(t, l.Handle(14_999+1, next, Tick{}))
	if !hasSend(l.Handle(35_000, next, Tick{}), "bye") {
		t.Fatal("no bye at 35 s")
	}
}

func Test_same_item_reloaded_only_sends_state(t *testing.T) {
	l := linked()
	s := snap("68397", false)
	assertEffects(t, l.Handle(5000, s, FileLoaded{}), toTV(state("68397", false)))
}

func Test_messages_of_others_are_ignored_while_linked(t *testing.T) {
	l := linked()
	assertNone(t, l.Handle(1100, nil, env("tv-2", linkID, 9, Cmd{Action: CmdPause})))
	assertNone(t, l.Handle(1100, nil, env(tvID, "link-0002", 9, Cmd{Action: CmdPause})))
}

// ---------------------------------------------------------- Go-only tests

// Rust subtracts with saturating_sub; a clock that goes back never wraps around.
func Test_go_a_clock_going_back_does_not_time_out(t *testing.T) {
	l := &Leader{}
	s := snap("1", false)
	invite(l, 10_000, s)
	assertNone(t, l.Handle(0, s, Tick{}))
	l = linked()
	assertNone(t, l.Handle(0, s, Tick{}))
	assertPhase(t, l, PhaseLinked)
}

// An invite while linked is ignored: one link at a time.
func Test_go_invite_while_linked_is_ignored(t *testing.T) {
	l := linked()
	assertNone(t, l.Handle(1100, snap("68397", false), Invite{Peer: Peer{SessionID: "tv-2", Name: "Autre"}, Link: "link-0002"}))
	assertStatus(t, l, Status{Phase: PhaseLinked, Device: "Shield"})
}

// The status reads the drift by value: changing the returned pointer does not
// change the leader.
func Test_go_status_copies_the_drift(t *testing.T) {
	l := linked()
	l.Handle(1200, nil, tv(1, Ping{T: 1, DriftMs: i64p(-42)}))
	st := l.Status()
	*st.DriftMs = 7
	assertStatus(t, l, Status{Phase: PhaseLinked, Device: "Shield", DriftMs: i64p(-42)})
}
