package player

// Tests of MediaSource and PlayerCommand/VideoFit in player/mod.rs, under the same names.

import (
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"testing"
)

func Test_accepts_http_and_https(t *testing.T) {
	got, err := ParseSource("https://emby.local:8920/emby/Videos/1/stream")
	if err != nil || got != (MediaSource{Kind: SourceHTTP, Value: "https://emby.local:8920/emby/Videos/1/stream"}) {
		t.Fatal(got, err)
	}
	if got, err := ParseSource("HTTP://x/y"); err != nil || got.Kind != SourceHTTP {
		t.Fatal(got, err)
	}
}

func Test_rejects_mpv_protocols_and_relative_paths(t *testing.T) {
	for _, bad := range []string{
		"av://lavfi:testsrc",
		"edl://a;b",
		"file:///C:/x.mkv",
		"ytdl://x",
		"relative.mkv",
		"",
	} {
		if _, err := ParseSource(bad); err == nil {
			t.Errorf("%s should be rejected", bad)
		}
	}
}

func Test_rejects_missing_local_file(t *testing.T) {
	missing := filepath.Join(os.TempDir(), "viewer-does-not-exist.mkv")
	if _, err := ParseSource(missing); err == nil {
		t.Fatal("missing file accepted")
	}
}

func Test_accepts_existing_local_file(t *testing.T) {
	file := filepath.Join(t.TempDir(), "viewer-source-test.bin")
	if err := os.WriteFile(file, []byte("x"), 0o600); err != nil {
		t.Fatal(err)
	}
	got, err := ParseSource(file)
	if err != nil || got != (MediaSource{Kind: SourceLocalFile, Value: file}) {
		t.Fatal(got, err)
	}
}

func Test_command_json_contract(t *testing.T) {
	for raw, want := range map[string]PlayerCommand{
		`{"type":"setSubtitleTrack","id":null}`:      {Type: CmdSetSubtitleTrack},
		`{"type":"seekAbsolute","seconds":12.5}`:     {Type: CmdSeekAbsolute, Seconds: 12.5},
		`{"type":"setSpeed","speed":1.5}`:            {Type: CmdSetSpeed, Speed: 1.5},
		`{"type":"setNightMode","on":true}`:          {Type: CmdSetNightMode, On: true},
		`{"type":"setSubtitleDelay","seconds":-0.3}`: {Type: CmdSetSubtitleDelay, Seconds: -0.3},
		`{"type":"setAudioDelay","seconds":0.2}`:     {Type: CmdSetAudioDelay, Seconds: 0.2},
		`{"type":"setVideoFit","fit":"fill"}`:        {Type: CmdSetVideoFit, Fit: FitFill},
	} {
		var got PlayerCommand
		if err := json.Unmarshal([]byte(raw), &got); err != nil || got != want {
			t.Errorf("%s: %+v %v", raw, got, err)
		}
	}
	var c PlayerCommand
	if err := json.Unmarshal([]byte(`{"type":"setVideoFit","fit":"zoom"}`), &c); err == nil {
		t.Fatal("zoom accepted")
	}
}

func Test_video_fit_sets_both_properties(t *testing.T) {
	for fit, want := range map[VideoFit][2][2]string{
		FitFill:     {{"panscan", "1"}, {"keepaspect", "yes"}},
		FitStretch:  {{"panscan", "0"}, {"keepaspect", "no"}},
		FitOriginal: {{"panscan", "0"}, {"keepaspect", "yes"}},
	} {
		if got := fit.MpvProperties(); got != want {
			t.Errorf("%s: %v", fit, got)
		}
	}
}

// Not in Rust: the serde rules of every variant (required field, Option that may
// be missing, null refused, integer seconds, unknown keys ignored), and the shape
// written back.
func Test_go_command_variants_follow_serde(t *testing.T) {
	two := int64(2)
	for raw, want := range map[string]PlayerCommand{
		`{"type":"setPaused","paused":true}`:     {Type: CmdSetPaused, Paused: true},
		`{"type":"togglePause","x":1}`:           {Type: CmdTogglePause},
		`{"type":"seekRelative","seconds":-10}`:  {Type: CmdSeekRelative, Seconds: -10},
		`{"type":"setAudioTrack","id":2}`:        {Type: CmdSetAudioTrack, ID: &two},
		`{"type":"setAudioTrack"}`:               {Type: CmdSetAudioTrack},
		`{"type":"setVolume","percent":80}`:      {Type: CmdSetVolume, Percent: 80},
		`{"type":"stop"}`:                        {Type: CmdStop},
		`{"seconds":1,"type":"seekAbsolute"}`:    {Type: CmdSeekAbsolute, Seconds: 1},
		`{"type":"setVideoFit","fit":"stretch"}`: {Type: CmdSetVideoFit, Fit: FitStretch},
	} {
		var got PlayerCommand
		err := json.Unmarshal([]byte(raw), &got)
		same := err == nil && got.Type == want.Type && got.Paused == want.Paused && got.Seconds == want.Seconds &&
			got.Percent == want.Percent && got.Fit == want.Fit && (got.ID == nil) == (want.ID == nil) &&
			(got.ID == nil || *got.ID == *want.ID)
		if !same {
			t.Errorf("%s: %+v %v", raw, got, err)
		}
	}
	for _, bad := range []string{
		`null`, `{}`, `[]`, `{"type":"SetPaused","paused":true}`, `{"type":"setPaused"}`,
		`{"type":"setPaused","paused":null}`, `{"type":"seekAbsolute","seconds":"1"}`,
		`{"type":"setAudioTrack","id":1.5}`, `{"type":"setAudioTrack","id":1.0}`, `{"type":null}`,
		`{"type":"setVideoFit"}`, `{"type":"setVideoFit","fit":null}`, `{"type":"setVideoFit","fit":"Fill"}`,
	} {
		var c PlayerCommand
		if err := json.Unmarshal([]byte(bad), &c); err == nil {
			t.Errorf("%s accepted: %+v", bad, c)
		}
	}
	for want, c := range map[string]PlayerCommand{
		`{"type":"togglePause"}`:                 {Type: CmdTogglePause},
		`{"type":"setSubtitleTrack","id":null}`:  {Type: CmdSetSubtitleTrack},
		`{"type":"setAudioTrack","id":2}`:        {Type: CmdSetAudioTrack, ID: &two},
		`{"type":"seekAbsolute","seconds":12.5}`: {Type: CmdSeekAbsolute, Seconds: 12.5},
		`{"type":"setVideoFit","fit":"fill"}`:    {Type: CmdSetVideoFit, Fit: FitFill},
	} {
		if got, err := json.Marshal(c); err != nil || string(got) != want {
			t.Errorf("%+v: %s %v", c, got, err)
		}
	}
}

// Not in Rust: the messages and kinds of the source errors, a URL with a space,
// and a path that is a directory.
func Test_go_source_errors_and_https(t *testing.T) {
	for raw, want := range map[string]string{
		"https://h/a b":   "source invalide : URL contenant des espaces",
		"av://lavfi:x":    "source invalide : protocole non autorisé",
		"relative.mkv":    "source invalide : chemin local non absolu",
		os.TempDir():      "source invalide : fichier introuvable",
		"  HTTPS://h/x  ": "",
	} {
		src, err := ParseSource(raw)
		if want == "" {
			if err != nil || src.Value != "HTTPS://h/x" || !src.IsHTTPS() {
				t.Errorf("%q: %+v %v", raw, src, err)
			}
			continue
		}
		if err == nil || err.Error() != want || !errors.Is(err, invalidSource("")) {
			t.Errorf("%q: %v", raw, err)
		}
	}
	if (MediaSource{Kind: SourceHTTP, Value: "http://h"}).IsHTTPS() || (MediaSource{Kind: SourceLocalFile, Value: "https://x"}).IsHTTPS() {
		t.Fatal("not https")
	}
}
