package player

// Pure tests of player/mpv.rs, under the same names.

import (
	"math"
	"testing"

	"viewer/internal/redact"
)

func Test_delays_are_held_within_ten_seconds(t *testing.T) {
	for in, want := range map[float64]string{-0.3: "-0.3", 42: "10", -42: "-10"} {
		if got, err := delayValue(in); err != nil || got != want {
			t.Errorf("%v: %q %v", in, got, err)
		}
	}
	if _, err := delayValue(math.NaN()); err == nil {
		t.Fatal("NaN accepted")
	}
}

func Test_night_filter_is_the_compressor_or_nothing(t *testing.T) {
	if on := nightFilter(true); !contains(on, "acompressor") {
		t.Fatal(on)
	}
	if off := nightFilter(false); off != "" {
		t.Fatal(off)
	}
}

func contains(s, sub string) bool {
	for i := 0; i+len(sub) <= len(s); i++ {
		if s[i:i+len(sub)] == sub {
			return true
		}
	}
	return false
}

func Test_parses_mpv_track_list_json(t *testing.T) {
	json := `[{"id":1,"type":"video","codec":"h264","selected":true},
            {"id":1,"type":"audio","lang":"jpn","title":"Stéréo","codec":"aac","selected":true,"default":true},
            {"id":2,"type":"audio","lang":"fre","codec":"ac3","selected":false},
            {"id":1,"type":"sub","lang":"fre","codec":"ass","external":false,"selected":true}]`
	tracks, err := parseTracks(json)
	if err != nil {
		t.Fatal(err)
	}
	if len(tracks) != 4 {
		t.Fatal(len(tracks))
	}
	if tracks[1].Title == nil || *tracks[1].Title != "Stéréo" {
		t.Fatal(tracks[1].Title)
	}
	if !tracks[1].Default || !tracks[1].Selected {
		t.Fatal(tracks[1])
	}
	if tracks[3].Kind != KindSub {
		t.Fatal(tracks[3].Kind)
	}
}

func Test_redacts_tokens_in_logs(t *testing.T) {
	for in, want := range map[string]string{
		"Opening https://emby/emby/Videos/1/stream?static=true&api_key=abc123&x=1 failed": "Opening https://emby/emby/Videos/1/stream?static=true&api_key=<redacted>&x=1 failed",
		"no secret here":    "no secret here",
		"X-Emby-Token: abc": "X-Emby-Token: <redacted>",
		"x?ApiKey=abc&y=1":  "x?ApiKey=<redacted>&y=1",
	} {
		if got := redact.Redact(in); got != want {
			t.Errorf("%q: %q", in, got)
		}
	}
}

func Test_rejects_non_finite_values(t *testing.T) {
	if _, err := finite(math.NaN()); err == nil {
		t.Fatal("NaN")
	}
	if _, err := finite(math.Inf(1)); err == nil {
		t.Fatal("Inf")
	}
	if got := trackValue(nil); got != "no" {
		t.Fatal(got)
	}
	two := int64(2)
	if got := trackValue(&two); got != "2" {
		t.Fatal(got)
	}
}

func Test_finds_the_track_of_an_emby_stream_by_ff_index(t *testing.T) {
	json := `[{"id":1,"type":"video","ff-index":0},
            {"id":1,"type":"audio","ff-index":1},{"id":2,"type":"audio","ff-index":2},
            {"id":1,"type":"sub","ff-index":3},{"id":2,"type":"sub","external":true,"ff-index":4},
            {"id":3,"type":"sub","ff-index":4}]`
	if id, ok := trackWithFFIndex(json, "audio", 2); !ok || id != 2 {
		t.Fatal(id, ok)
	}
	if id, ok := trackWithFFIndex(json, "sub", 4); !ok || id != 3 {
		t.Fatal("not the added file", id, ok)
	}
	if id, ok := trackWithFFIndex(json, "sub", 1); ok {
		t.Fatal("an audio index", id)
	}
	if id, ok := trackWithFFIndex("not json", "audio", 1); ok {
		t.Fatal(id)
	}
}

func streams(a, s *int32) [2]any {
	out := [2]any{nil, nil}
	if a != nil {
		out[0] = *a
	}
	if s != nil {
		out[1] = *s
	}
	return out
}

func Test_selected_streams_read_ff_indexes(t *testing.T) {
	json := `[
            {"id":1,"type":"video","ff-index":0,"selected":true},
            {"id":1,"type":"audio","ff-index":1,"selected":false},
            {"id":2,"type":"audio","ff-index":2,"selected":true},
            {"id":1,"type":"sub","ff-index":4,"selected":true},
            {"id":2,"type":"sub","external":true,"selected":false}
        ]`
	if got := streams(selectedStreams(json)); got != [2]any{int32(2), int32(4)} {
		t.Fatal(got)
	}
	none := `[{"id":1,"type":"audio","ff-index":1,"selected":true},
            {"id":1,"type":"sub","ff-index":3,"selected":false}]`
	if got := streams(selectedStreams(none)); got != [2]any{int32(1), int32(-1)} {
		t.Fatal("no subtitle shown", got)
	}
	external := `[{"id":2,"type":"sub","external":true,"selected":true}]`
	if got := streams(selectedStreams(external)); got != [2]any{nil, nil} {
		t.Fatal("external: no index", got)
	}
	if got := streams(selectedStreams("not json")); got != [2]any{nil, nil} {
		t.Fatal(got)
	}
}

// Not in Rust: serde_json's as_i64 and as_str rules on odd track lists, a non-array
// list, trailing data, and an id-less match that goes on to the next track.
func Test_go_track_lists_follow_serde_json(t *testing.T) {
	tracks, err := parseTracks(`[{"id":1.0,"type":"audio"},{"id":"2","type":"audio"},{"id":3,"type":"data"},
		{"type":"sub"},{"id":4,"type":"sub","title":5,"selected":"yes"},7,null]`)
	if err != nil || len(tracks) != 1 || tracks[0].ID != 4 || tracks[0].Title != nil || tracks[0].Selected {
		t.Fatal(tracks, err)
	}
	if tracks, err := parseTracks(`{"id":1}`); err != nil || tracks == nil || len(tracks) != 0 {
		t.Fatal("not an array: empty", tracks, err)
	}
	for _, bad := range []string{`[] x`, `[`, ``} {
		if _, err := parseTracks(bad); err == nil || err.Error()[:28] != "erreur libmpv (track-list) :" {
			t.Errorf("%q: %v", bad, err)
		}
	}
	if id, ok := trackWithFFIndex(`[{"type":"audio","ff-index":1},{"id":9,"type":"audio","ff-index":1}]`, "audio", 1); !ok || id != 9 {
		t.Fatal(id, ok)
	}
	if got := streams(selectedStreams(`[{"type":"audio","selected":true,"ff-index":3000000000}]`)); got != [2]any{nil, int32(-1)} {
		t.Fatal("an index past i32", got)
	}
}

// Not in Rust: every value of the subtitle style (ADR 0035).
func Test_go_subtitle_options_of_each_style(t *testing.T) {
	def := subtitleOptions(SubtitleStyle{})
	if def != [7][2]string{{"sub-scale", "1"}, {"sub-color", "#FFFFFF"}, {"sub-border-style", "outline-and-shadow"},
		{"sub-back-color", "#B3000000"}, {"sub-pos", "100"}, {"sub-align-y", "bottom"}, {"sub-ass-override", "no"}} {
		t.Fatal(def)
	}
	for i, n := range subtitleOptionNames {
		if def[i][0] != n {
			t.Fatal(i, n)
		}
	}
	for size, want := range map[SubtitleSize]string{SizeSmall: "0.8", SizeLarge: "1.25", SizeLarger: "1.5"} {
		if got := subtitleOptions(SubtitleStyle{Size: size})[0][1]; got != want {
			t.Error(size, got)
		}
	}
	top := subtitleOptions(SubtitleStyle{Position: PositionTop})
	raised := subtitleOptions(SubtitleStyle{Position: PositionRaised})
	if top[4][1] != "100" || top[5][1] != "top" || raised[4][1] != "85" || raised[5][1] != "bottom" {
		t.Fatal(top, raised)
	}
}

// Not in Rust: Rust's f64 Display, used for seek, volume, speed and delays.
func Test_go_floats_are_written_like_rust(t *testing.T) {
	for in, want := range map[float64]string{20: "20", 12.5: "12.5", 1e21: "1000000000000000000000", 1e-7: "0.0000001", 130: "130"} {
		if got := formatFloat(in); got != want {
			t.Errorf("%v: %s", in, got)
		}
	}
	if !math.IsNaN(clamp(math.NaN(), 0, 1)) || clamp(math.Inf(1), 0, 130) != 130 || clamp(-1, 0.25, 4) != 0.25 {
		t.Fatal("clamp")
	}
}
