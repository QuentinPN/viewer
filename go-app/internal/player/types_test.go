package player

// The serde shapes of the types ported in wave 2. The goldens player.* and
// playback.engine_request.* are checked in internal/emby (playback_test.go), where the
// golden helpers live. The Rust tests of player/mod.rs (StallWatch, MediaSource,
// PlayerCommand, VideoFit) cover the engine side, ported in wave 3.

import (
	"encoding/json"
	"reflect"
	"testing"
)

func mustJSON(t *testing.T, v any) string {
	t.Helper()
	b, err := json.Marshal(v)
	if err != nil {
		t.Fatal(err)
	}
	return string(b)
}

func Test_go_track_pick_is_tagged_by_type(t *testing.T) {
	for want, pick := range map[string]TrackPick{
		`{"type":"auto"}`:              {},
		`{"type":"off"}`:               {Kind: TrackOff},
		`{"type":"stream","index":12}`: PickStream(12),
	} {
		if got := mustJSON(t, pick); got != want {
			t.Errorf("%+v: %s", pick, got)
		}
		var back TrackPick
		if err := json.Unmarshal([]byte(want), &back); err != nil || back != pick {
			t.Errorf("%s: %+v %v", want, back, err)
		}
	}
	// Extra keys are ignored, index of a unit variant too; the tag is exact.
	var p TrackPick
	if err := json.Unmarshal([]byte(`{"index":3,"type":"off","x":1}`), &p); err != nil || p != (TrackPick{Kind: TrackOff}) {
		t.Fatal(p, err)
	}
	for _, bad := range []string{`null`, `"auto"`, `{}`, `{"type":"Auto"}`, `{"type":"stream"}`, `{"type":"stream","index":null}`, `{"type":"stream","index":1.5}`, `{"type":null}`} {
		if err := json.Unmarshal([]byte(bad), &p); err == nil {
			t.Error(bad)
		}
	}
	if _, err := json.Marshal(TrackPick{Kind: 7}); err == nil {
		t.Fatal("unknown variant written")
	}
}

func Test_go_track_selection_and_style_default_like_serde(t *testing.T) {
	var s TrackSelection
	if err := json.Unmarshal([]byte(`{"subtitle":{"type":"off"}}`), &s); err != nil || s != (TrackSelection{Subtitle: TrackPick{Kind: TrackOff}}) {
		t.Fatal(s, err)
	}
	if err := json.Unmarshal([]byte(`{"audio":null}`), &s); err == nil {
		t.Fatal("null audio accepted")
	}
	if got := mustJSON(t, SubtitleStyle{}); got != `{"size":"normal","color":"white","background":"outline","position":"bottom","overrideAss":false}` {
		t.Fatal(got)
	}
	var style SubtitleStyle
	if err := json.Unmarshal([]byte(`{"size":"larger","position":"raised","background":"box","color":"yellow","overrideAss":true,"x":0}`), &style); err != nil ||
		style != (SubtitleStyle{Size: SizeLarger, Color: ColorYellow, Background: BackgroundBox, Position: PositionRaised, OverrideASS: true}) {
		t.Fatal(style, err)
	}
	for _, bad := range []string{`{"size":"huge"}`, `{"Size":"small","color":"Yellow"}`, `{"overrideAss":null}`, `[]`} {
		if err := json.Unmarshal([]byte(bad), &style); err == nil {
			t.Error(bad)
		}
	}
}

func Test_go_external_subtitle_and_request_need_a_source(t *testing.T) {
	var sub ExternalSubtitle
	if err := json.Unmarshal([]byte(`{"source":"https://h/s.srt","lang":null}`), &sub); err != nil ||
		!reflect.DeepEqual(sub, ExternalSubtitle{Source: "https://h/s.srt"}) {
		t.Fatal(sub, err)
	}
	for _, bad := range []string{`{}`, `{"source":null}`, `{"source":"s","selected":null}`} {
		if err := json.Unmarshal([]byte(bad), &sub); err == nil {
			t.Error(bad)
		}
	}
	var req PlaybackRequest
	if err := json.Unmarshal([]byte(`{"source":"C:\\v.mkv"}`), &req); err != nil ||
		mustJSON(t, req) != `{"source":"C:\\v.mkv","httpHeaders":[],"startSeconds":null,"subtitles":[],"tracks":{"audio":{"type":"auto"},"subtitle":{"type":"auto"}},"subtitleStyle":{"size":"normal","color":"white","background":"outline","position":"bottom","overrideAss":false}}` {
		t.Fatal(mustJSON(t, req), err)
	}
	if err := json.Unmarshal([]byte(`{"source":"u","httpHeaders":[["A","1"]],"startSeconds":2.5}`), &req); err != nil ||
		len(req.HTTPHeaders) != 1 || req.HTTPHeaders[0] != (Header{"A", "1"}) || *req.StartSeconds != 2.5 {
		t.Fatal(req, err)
	}
	for _, bad := range []string{`{"httpHeaders":[]}`, `{"source":"u","httpHeaders":null}`, `{"source":"u","httpHeaders":[["A"]]}`,
		`{"source":"u","httpHeaders":[["A","1","2"]]}`, `{"source":"u","httpHeaders":[["A",null]]}`, `{"source":"u","subtitles":[null]}`} {
		if err := json.Unmarshal([]byte(bad), &req); err == nil {
			t.Error(bad)
		}
	}
}

func Test_go_lists_are_written_empty_not_null(t *testing.T) {
	if got := mustJSON(t, PlayerDiagnostics{}); got[len(got)-21:] != `"subtitleOptions":[]}` {
		t.Fatal(got)
	}
	if got := mustJSON(t, List[Track](nil)); got != `[]` {
		t.Fatal(got)
	}
}
