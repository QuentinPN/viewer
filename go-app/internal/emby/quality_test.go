package emby

import (
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func u64(v uint64) *uint64 { return &v }

func tempQualityPath(t *testing.T) string {
	return filepath.Join(t.TempDir(), "viewer-quality-test", "playback-quality.json")
}

// emby/quality.rs quality_caps_accept_only_the_offered_choices
func Test_quality_caps_accept_only_the_offered_choices(t *testing.T) {
	ok := QualityCaps{Local: nil, Remote: u64(4_000_000)}
	got, err := ok.Validated()
	if err != nil || !got.Equal(ok) {
		t.Fatal(got, err)
	}
	for _, bad := range []uint64{0, 1, 3_500_000, 400_000_000, ^uint64(0)} {
		if _, err := (QualityCaps{Local: u64(bad)}).Validated(); err == nil {
			t.Error(bad)
		} else if !errors.Is(err, NewSettings("")) {
			t.Error(err)
		}
	}
}

// emby/quality.rs quality_caps_pick_the_cap_of_the_address
func Test_quality_caps_pick_the_cap_of_the_address(t *testing.T) {
	caps := QualityCaps{Local: u64(40_000_000), Remote: u64(1_500_000)}
	if v := caps.ForAddress(true); v == nil || *v != 40_000_000 {
		t.Fatal(v)
	}
	if v := caps.ForAddress(false); v == nil || *v != 1_500_000 {
		t.Fatal(v)
	}
	if (QualityCaps{}).ForAddress(false) != nil {
		t.Fatal("default")
	}
}

// emby/quality.rs quality_store_round_trips_per_server
func Test_quality_store_round_trips_per_server(t *testing.T) {
	path := tempQualityPath(t)
	store := LoadQualityStore(path)
	if !store.Get("a").Equal(QualityCaps{}) {
		t.Fatal("empty store")
	}
	caps := QualityCaps{Local: u64(8_000_000), Remote: u64(3_000_000)}
	if err := store.Set("a", caps); err != nil {
		t.Fatal(err)
	}
	if _, err := os.Stat(path + ".tmp"); !os.IsNotExist(err) {
		t.Fatal("temp file left", err)
	}
	reopened := LoadQualityStore(path)
	if !reopened.Get("a").Equal(caps) || !reopened.Get("b").Equal(QualityCaps{}) {
		t.Fatal(reopened.Get("a"), reopened.Get("b"))
	}
	// Back to "Maximale" twice: the server leaves the file.
	if err := reopened.Set("a", QualityCaps{}); err != nil {
		t.Fatal(err)
	}
	text, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	if strings.Contains(string(text), `"a"`) {
		t.Fatal(string(text))
	}
}

// emby/quality.rs quality_store_refuses_a_value_off_the_list_and_keeps_the_old_one
func Test_quality_store_refuses_a_value_off_the_list_and_keeps_the_old_one(t *testing.T) {
	store := LoadQualityStore(tempQualityPath(t))
	caps := QualityCaps{Local: u64(4_000_000)}
	if err := store.Set("a", caps); err != nil {
		t.Fatal(err)
	}
	if err := store.Set("a", QualityCaps{Local: u64(5_000_000)}); err == nil {
		t.Fatal("accepted")
	}
	if !store.Get("a").Equal(caps) {
		t.Fatal(store.Get("a"))
	}
}

// emby/quality.rs quality_store_ignores_an_unreadable_file_and_unknown_values
func Test_quality_store_ignores_an_unreadable_file_and_unknown_values(t *testing.T) {
	path := tempQualityPath(t)
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(path, []byte("{ not json"), 0o644); err != nil {
		t.Fatal(err)
	}
	if !LoadQualityStore(path).Get("a").Equal(QualityCaps{}) {
		t.Fatal("unreadable file")
	}
	if err := os.WriteFile(path, []byte(`{"servers":{"a":{"local":123,"remote":4000000}}}`), 0o644); err != nil {
		t.Fatal(err)
	}
	if got := LoadQualityStore(path).Get("a"); !got.Equal(QualityCaps{Remote: u64(4_000_000)}) {
		t.Fatal(got)
	}
}

// The file is written as serde_json::to_vec_pretty writes it.
func TestQualityFileShape(t *testing.T) {
	path := tempQualityPath(t)
	store := LoadQualityStore(path)
	if err := store.Set("srv", QualityCaps{Remote: u64(4_000_000)}); err != nil {
		t.Fatal(err)
	}
	text, _ := os.ReadFile(path)
	want := "{\n  \"servers\": {\n    \"srv\": {\n      \"local\": null,\n      \"remote\": 4000000\n    }\n  }\n}"
	if string(text) != want {
		t.Fatalf("%q", text)
	}
	// A negative or fractional cap makes the whole file unreadable, as serde.
	if err := os.WriteFile(path, []byte(`{"servers":{"a":{"remote":4000000},"b":{"local":-1}}}`), 0o644); err != nil {
		t.Fatal(err)
	}
	if !LoadQualityStore(path).Get("a").Equal(QualityCaps{}) {
		t.Fatal("whole file must be dropped")
	}
}

func TestQualityCapsDecodeLikeSerde(t *testing.T) {
	var c QualityCaps
	for _, bad := range []string{`{"local":-1}`, `{"local":1.5}`, `{"local":"4000000"}`, `null`, `[]`} {
		if err := json.Unmarshal([]byte(bad), &c); err == nil {
			t.Errorf("%s accepted", bad)
		}
	}
	if err := json.Unmarshal([]byte(`{"Local":4000000,"remote":null}`), &c); err != nil || !c.Equal(QualityCaps{}) {
		t.Fatal(c, err)
	}
}

func TestGolden_quality_bitrate_choices(t *testing.T) {
	assertTypedGolden(t, "quality.bitrate_choices.json", BitrateChoices)
}

func TestGolden_quality_caps_validation(t *testing.T) {
	var rows []map[string]any
	for _, js := range []string{
		`{}`, `{"local":null,"remote":4000000}`, `{"local":40000000,"remote":1500000}`,
		`{"local":0}`, `{"local":3500000}`, `{"remote":400000000}`,
	} {
		var c QualityCaps
		if err := json.Unmarshal([]byte(js), &c); err != nil {
			t.Fatal(err)
		}
		if ok, err := c.Validated(); err != nil {
			rows = append(rows, map[string]any{"caps": c, "error": err})
		} else {
			rows = append(rows, map[string]any{
				"caps": c, "valid": ok, "forLocalAddress": ok.ForAddress(true), "forRemoteAddress": ok.ForAddress(false),
			})
		}
	}
	assertTableGolden(t, "quality.caps_validation.json", rows)
}

// What emby_playback_quality and emby_set_playback_quality answer: the caps of the
// server, the address kind and the choices (the session code that reads them is
// wave 2+; the value here is built the same way, session.rs `playback_quality`).
func TestGolden_quality_emby_playback_quality(t *testing.T) {
	store := LoadQualityStore(tempQualityPath(t))
	assertTypedGolden(t, "quality.emby_playback_quality.default.json", NewPlaybackQuality(store.Get(fixtureServerID), true))
	if err := store.Set(fixtureServerID, QualityCaps{Local: u64(40_000_000), Remote: u64(4_000_000)}); err != nil {
		t.Fatal(err)
	}
	assertTypedGolden(t, "quality.emby_set_playback_quality.json", NewPlaybackQuality(store.Get(fixtureServerID), true))
	err := store.Set(fixtureServerID, QualityCaps{Local: u64(1)})
	if err == nil {
		t.Fatal("cap off the list accepted")
	}
	assertTypedGolden(t, "errors.service.quality_not_offered.json", err)
}
