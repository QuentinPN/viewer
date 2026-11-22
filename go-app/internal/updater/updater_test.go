package updater

import (
	"bytes"
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

// The 6 tests of updater.rs, under their Rust names, and the two
// events.update_ready goldens.

func str(s string) *string { return &s }

func mustJSON(t *testing.T, v any) string {
	t.Helper()
	b, err := json.Marshal(v)
	if err != nil {
		t.Fatalf("marshal %#v: %v", v, err)
	}
	return string(b)
}

func Test_info_trims_and_defaults_notes(t *testing.T) {
	if got := NewUpdateInfo(" 1.2.0 ", nil); got != (UpdateInfo{Version: "1.2.0", Notes: ""}) {
		t.Errorf("got %#v", got)
	}
	if got := NewUpdateInfo("1.2.0", str("\n- a\n")).Notes; got != "- a" {
		t.Errorf("notes %q", got)
	}
}

func Test_slot_downloads_each_version_once(t *testing.T) {
	var slot Slot[[]byte]
	if _, ok := slot.Downloaded("1.2.0"); ok {
		t.Error("empty slot has a download")
	}
	if _, ok := slot.Info(); ok {
		t.Error("empty slot has info")
	}
	slot.Store(NewUpdateInfo("1.2.0", str("notes")), []byte{1})
	if info, ok := slot.Downloaded(" 1.2.0"); !ok || info.Notes != "notes" {
		t.Errorf("downloaded(\" 1.2.0\") = %#v, %v", info, ok)
	}
	if _, ok := slot.Downloaded("1.3.0"); ok {
		t.Error("1.3.0 is not downloaded")
	}
	if info, ok := slot.Info(); !ok || info.Version != "1.2.0" {
		t.Errorf("info = %#v, %v", info, ok)
	}
	slot.Store(NewUpdateInfo("1.3.0", nil), []byte{2})
	if p, ok := slot.Payload(); !ok || !bytes.Equal(p, []byte{2}) {
		t.Errorf("payload = %v, %v", p, ok)
	}
}

func Test_payload_serializes_for_the_ui(t *testing.T) {
	got := mustJSON(t, NewUpdateInfo("1.2.0", str("- x")))
	if want := `{"version":"1.2.0","notes":"- x"}`; got != want {
		t.Errorf("got %s, want %s", got, want)
	}
}

func Test_outcomes_serialize_tagged_for_the_ui(t *testing.T) {
	for _, c := range []struct {
		outcome CheckOutcome
		want    string
	}{
		{UpToDate(), `{"status":"upToDate"}`},
		{Ready(NewUpdateInfo("1.3.0", nil)), `{"status":"ready","info":{"version":"1.3.0","notes":""}}`},
		{Available("1.3.0"), `{"status":"available","version":"1.3.0"}`},
		{failed(newError(KindNetwork, errors.New("x")), "check"), `{"status":"failed","category":"network"}`},
	} {
		if got := mustJSON(t, c.outcome); got != c.want {
			t.Errorf("got %s, want %s", got, c.want)
		}
	}
	if _, err := json.Marshal(CheckOutcome{Status: "bogus"}); err == nil {
		t.Error("an unknown status must not serialize")
	}
}

func Test_failures_log_a_category_only(t *testing.T) {
	if got := Category(newError(KindNetwork, errors.New("http://x"))); got != "network" {
		t.Errorf("network: %s", got)
	}
	if got := Category(newError(KindReleaseNotFound, nil)); got != "manifest" {
		t.Errorf("release not found: %s", got)
	}
	if got := Category(newError(KindMissingSignature, nil)); got != "signature" {
		t.Errorf("missing signature: %s", got)
	}
	if got := Category(newError(KindEmptyEndpoints, nil)); got != "other" {
		t.Errorf("empty endpoints: %s", got)
	}
}

// The Rust test parses tauri.conf.json; the Go counterpart checks DefaultConfig,
// which carries the same values. Deviation (MAP.md): the pinned key is injected
// at build time, so instead of `pubkey.len() > 40` the test checks that the
// committed placeholder is empty and fails closed.
func Test_shipped_config_is_quiet_and_signed(t *testing.T) {
	cfg := DefaultConfig("1.0.0")
	if len(cfg.Endpoints) != 1 || !strings.HasSuffix(cfg.Endpoints[0], "/desktop/latest.json") {
		t.Errorf("endpoints %v", cfg.Endpoints)
	}
	if !cfg.AllowInsecureTransport {
		t.Error("the LAN endpoint is plain http: insecure transport must be allowed")
	}
	if got := strings.Join(cfg.InstallerArgs, " "); got != "/S /UPDATE /R /ARGS" {
		t.Errorf("installer args %q", got)
	}
	if PublicKey != "" {
		t.Errorf("a real key must not be committed: PublicKey = %q", PublicKey)
	}
	if err := Verify([]byte("MZ"), "1.0.0", strings.Repeat("A", 86), cfg.PublicKey); Category(err) != "signature" {
		t.Errorf("an empty key must fail closed, got %v", err)
	}
	// tauri.conf.json is the source of these values until the cutover, which
	// removes src-tauri/: the cross-check then ends.
	conf, err := os.ReadFile(filepath.Join("..", "..", "..", "src-tauri", "tauri.conf.json"))
	if errors.Is(err, os.ErrNotExist) {
		t.Log("src-tauri/tauri.conf.json is gone (after the cutover): cross-check skipped")
		return
	}
	if err != nil {
		t.Fatalf("tauri.conf.json: %v", err)
	}
	var c struct {
		Plugins struct {
			Updater struct {
				Endpoints                          []string `json:"endpoints"`
				DangerousInsecureTransportProtocol bool     `json:"dangerousInsecureTransportProtocol"`
				Windows                            struct {
					InstallMode string `json:"installMode"`
				} `json:"windows"`
			} `json:"updater"`
		} `json:"plugins"`
	}
	if err := json.Unmarshal(conf, &c); err != nil {
		t.Fatalf("tauri.conf.json: %v", err)
	}
	up := c.Plugins.Updater
	if len(up.Endpoints) != 1 || up.Endpoints[0] != DefaultEndpoint {
		t.Errorf("tauri.conf.json endpoints %v, Go %s", up.Endpoints, DefaultEndpoint)
	}
	if !up.DangerousInsecureTransportProtocol || up.Windows.InstallMode != "quiet" {
		t.Errorf("tauri.conf.json updater %+v", up)
	}
}

// goldens

const goldenDir = "../../testdata/golden"

func assertGolden(t *testing.T, name string, v any) {
	t.Helper()
	raw, err := os.ReadFile(filepath.Join(goldenDir, name))
	if err != nil {
		t.Fatalf("golden %s: %v", name, err)
	}
	var want bytes.Buffer
	if err := json.Compact(&want, raw); err != nil {
		t.Fatalf("golden %s: %v", name, err)
	}
	if got := mustJSON(t, v); got != want.String() {
		t.Errorf("%s\ngot    %s\ngolden %s", name, got, want.String())
	}
}

// The payload of app://update-ready, as CheckOnce emits it (see also
// Test_go_check_downloads_verifies_and_emits).
func TestGolden_events_update_ready(t *testing.T) {
	assertGolden(t, "events.update_ready.json", NewUpdateInfo("1.6.0", str("## Nouveautés\n- Lecture liée")))
	assertGolden(t, "events.update_ready.no_notes.json", NewUpdateInfo("1.6.0", nil))
}
