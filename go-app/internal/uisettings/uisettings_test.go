package uisettings

import (
	"context"
	"encoding/json"
	"errors"
	"maps"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"testing"
)

// The tests of ui_settings.rs, same names in snake_case.

func Test_export_then_import_round_trips(t *testing.T) {
	dir := filepath.Join(t.TempDir(), "roaming")
	entries := map[string]string{
		"viewer.player.volume":   `{"level":40,"muted":false}`,
		"viewer.version.v1:s:u:item":   "30502",
		"viewer.search-history.v1:s:u": `["dune","« été »"]`,
	}
	if err := Export(dir, entries); err != nil {
		t.Fatal(err)
	}
	got, err := Import(dir)
	if err != nil || !maps.Equal(got, entries) {
		t.Fatalf("%v %v", got, err)
	}
	if _, err := os.Stat(filepath.Join(dir, FileName+".tmp")); !errors.Is(err, os.ErrNotExist) {
		t.Fatal("temp file left behind")
	}
	// Read only: a second import gets the same entries.
	if got, _ := Import(dir); got == nil {
		t.Fatal("second import empty")
	}
	// A new export replaces the previous one.
	next := map[string]string{"viewer.player.skip": "{}"}
	if err := Export(dir, next); err != nil {
		t.Fatal(err)
	}
	if got, _ := Import(dir); !maps.Equal(got, next) {
		t.Fatal(got)
	}
}

func Test_import_without_export_is_none(t *testing.T) {
	got, err := Import(t.TempDir())
	if got != nil || err != nil {
		t.Fatal(got, err)
	}
}

func Test_allowed_keys(t *testing.T) {
	for _, k := range []string{"viewer.player.volume", "viewer.player.nightMode", "viewer.ui.seasonalLogo", "viewer.home-rows.v1:s:u", "viewer.library-options.v1:s:u:l"} {
		if !IsAllowedKey(k) {
			t.Error(k)
		}
	}
	for _, k := range []string{"", "token", "viewer.version.v1:", "viewer.player.volumeX"} {
		if IsAllowedKey(k) {
			t.Error(k)
		}
	}
}

func Test_export_refuses_what_breaks_the_limits(t *testing.T) {
	dir := filepath.Join(t.TempDir(), "roaming")
	refused := func(entries map[string]string, want error) {
		t.Helper()
		if err := Export(dir, entries); !errors.Is(err, want) {
			t.Errorf("%v, attendu %v", err, want)
		}
	}
	refused(map[string]string{"token": "x"}, ErrKeyNotAllowed)
	many := map[string]string{}
	for i := 0; i <= MaxEntries; i++ {
		many["viewer.version.v1:s:u:"+strconv.Itoa(i)] = "v"
	}
	refused(many, ErrTooManyEntries)
	// UTF-8 bytes, not characters.
	refused(map[string]string{"viewer.player.volume": strings.Repeat("é", MaxValueBytes/2+1)}, ErrValueTooLarge)
	total := map[string]string{}
	for i := range 8 {
		total["viewer.library-options.v1:s:u:"+strconv.Itoa(i)] = strings.Repeat("x", MaxValueBytes)
	}
	refused(total, ErrTooLarge)
	if _, err := os.Stat(dir); !errors.Is(err, os.ErrNotExist) {
		t.Fatal("nothing written when refused")
	}
}

func Test_import_refuses_a_bad_file(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, FileName)
	for _, c := range []struct {
		body string
		want error
	}{
		{"{oops", ReadError("JSON invalide")},
		{"null", ReadError("JSON invalide")},
		{`{"a":1}`, ReadError("JSON invalide")},
		{`{"token":"x"}`, ErrKeyNotAllowed},
	} {
		if err := os.WriteFile(path, []byte(c.body), 0o644); err != nil {
			t.Fatal(err)
		}
		if _, err := Import(dir); !errors.Is(err, c.want) {
			t.Errorf("%s : %v, attendu %v", c.body, err, c.want)
		}
	}
}

func Test_errors_serialize_as_bare_strings(t *testing.T) {
	for _, c := range []struct {
		err  error
		want string
	}{
		{ErrTooManyEntries, `"réglages refusés : plus de 200 entrées"`},
		{WriteError("access denied"), `"réglages non enregistrés : access denied"`},
	} {
		b, _ := json.Marshal(c.err)
		if string(b) != c.want {
			t.Errorf("%s, attendu %s", b, c.want)
		}
	}
}

// The file is what serde_json writes: compact, keys sorted, `<>&` as is.
func TestExportWritesCompactSortedJSON(t *testing.T) {
	dir := t.TempDir()
	if err := Export(dir, map[string]string{"viewer.player.skip": "<&>", "viewer.home-rows.v1:s:u": "{}"}); err != nil {
		t.Fatal(err)
	}
	b, _ := os.ReadFile(filepath.Join(dir, FileName))
	if want := `{"viewer.home-rows.v1:s:u":"{}","viewer.player.skip":"<&>"}`; string(b) != want {
		t.Fatalf("%s", b)
	}
}

// Without a config folder both commands answer ErrUnavailable; without an export
// the import is JSON null.
func TestHandlers(t *testing.T) {
	if _, err := ExportHandler("")(context.Background(), map[string]string{}); !errors.Is(err, ErrUnavailable) {
		t.Fatal(err)
	}
	if _, err := ImportHandler("")(context.Background(), nil); !errors.Is(err, ErrUnavailable) {
		t.Fatal(err)
	}
	dir := t.TempDir()
	if v, err := ImportHandler(dir)(context.Background(), nil); v != nil || err != nil {
		t.Fatal(v, err)
	}
	if _, err := ExportHandler(dir)(context.Background(), map[string]string{"viewer.player.skip": "{}"}); err != nil {
		t.Fatal(err)
	}
	v, err := ImportHandler(dir)(context.Background(), nil)
	if m, ok := v.(map[string]string); err != nil || !ok || m["viewer.player.skip"] != "{}" {
		t.Fatal(v, err)
	}
}
