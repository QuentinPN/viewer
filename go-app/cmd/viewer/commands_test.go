package main

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"regexp"
	"slices"
	"strings"
	"testing"
	"time"

	"viewer/internal/app"
	"viewer/internal/bridge"
	"viewer/internal/emby"
	"viewer/internal/request"
)

type fakeWindow struct {
	fullscreen, maximized bool
	calls                 []string
}

func (w *fakeWindow) IsFullscreen() bool { return w.fullscreen }
func (w *fakeWindow) IsMaximised() bool  { return w.maximized }
func (w *fakeWindow) SetFullscreen(on bool) {
	w.calls = append(w.calls, "fullscreen")
	w.fullscreen = on
}
func (w *fakeWindow) Maximise() {
	w.calls = append(w.calls, "maximise")
	w.maximized = true
}
func (w *fakeWindow) UnMaximise() {
	w.calls = append(w.calls, "unmaximise")
	w.maximized = false
}

type recorded struct {
	name    string
	payload string
}

// testApp is a debug app on temporary folders and a memory vault: signed out, no
// network, nothing started.
func testApp(t *testing.T, debug bool) (*app.App, *bridge.Bridge, *fakeWindow, *[]recorded) {
	t.Helper()
	dir := t.TempDir()
	var events []recorded
	a, err := app.New(app.Options{
		Debug:   debug,
		Version: "1.2.3",
		Paths: app.Paths{
			Config:    filepath.Join(dir, "roaming"),
			Cache:     filepath.Join(dir, "local"),
			LocalData: filepath.Join(dir, "local"),
			Logs:      filepath.Join(dir, "local", "logs"),
		},
		Store:  &emby.MemoryCredentialStore{},
		Libmpv: filepath.Join(dir, "absent", "libmpv-2.dll"),
		Emit: func(name string, payload any) {
			b, _ := json.Marshal(payload)
			events = append(events, recorded{name, string(b)})
		},
	})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { a.Close(time.Second) })
	reg := bridge.NewRegistry()
	win := &fakeWindow{maximized: true}
	registerCommands(reg, a, win)
	return a, bridge.New(reg), win, &events
}

func invoke(t *testing.T, b *bridge.Bridge, cmd, args string) (string, string) {
	t.Helper()
	out, err := b.Invoke(context.Background(), cmd, json.RawMessage(args))
	if err != nil {
		return "", string(bridge.MarshalError(err))
	}
	return string(out), ""
}

// rustCommands reads the invoke_handler of src-tauri/src/lib.rs: the last path
// segment of each `tauri::generate_handler!` entry.
func rustCommands(t *testing.T) []string {
	t.Helper()
	src, err := os.ReadFile(filepath.Join("..", "..", "..", "src-tauri", "src", "lib.rs"))
	if err != nil {
		t.Fatal(err)
	}
	block := regexp.MustCompile(`(?s)generate_handler!\[(.*?)\]`).FindSubmatch(src)
	if block == nil {
		t.Fatal("generate_handler! introuvable dans lib.rs")
	}
	var names []string
	for _, entry := range strings.Split(string(block[1]), ",") {
		entry = strings.TrimSpace(entry)
		if entry == "" {
			continue
		}
		names = append(names, entry[strings.LastIndex(entry, "::")+2:])
	}
	slices.Sort(names)
	return names
}

// Every command of the Rust invoke_handler is registered, and nothing else.
func TestRegisteredCommandsAreTheRustInvokeHandler(t *testing.T) {
	want := rustCommands(t)
	if len(want) != 82 {
		t.Fatalf("lib.rs liste %d commandes, MAP.md en compte 82", len(want))
	}
	reg := bridge.NewRegistry()
	a, _, win, _ := testApp(t, true)
	registerCommands(reg, a, win)
	got := reg.Names()
	if !slices.Equal(got, want) {
		for _, n := range want {
			if !slices.Contains(got, n) {
				t.Errorf("non portée : %s", n)
			}
		}
		for _, n := range got {
			if !slices.Contains(want, n) {
				t.Errorf("en trop : %s", n)
			}
		}
	}
}

// ur_open_trailer is registered and rejects a bad key with the bare string of the
// Rust TrailerError (a `serialize_str`), before any browser is started.
func TestUrOpenTrailerIsRegistered(t *testing.T) {
	_, b, _, _ := testApp(t, true)
	if _, err := invoke(t, b, "ur_open_trailer", `{"key":"a b"}`); err != `"bande-annonce invalide"` {
		t.Fatal(err)
	}
}

// Signed out, the first-run commands answer as Tauri does, without network.
func Test_go_first_run_answers(t *testing.T) {
	_, b, _, _ := testApp(t, true)
	cases := []struct{ cmd, args, out, err string }{
		{"emby_restore", `{}`, `{"state":"signedOut"}`, ""},
		{"emby_shelf", `{}`, `[]`, ""},
		{"emby_last_server", `{}`, `null`, ""},
		{"app_info", `{}`, `{"version":"1.2.3","surfaceMode":"parent","libmpvFound":false,"spike":false}`, ""},
		{"sync_status", `{}`, `{"phase":"idle"}`, ""},
		{"sync_unlink", `{}`, `null`, ""},
		{"emby_library_events_start", `{}`, `null`, ""},
		{"emby_library_events_stop", `{}`, `null`, ""},
		{"artwork_cache_stats", `{}`, `{"entries":0,"bytes":0,"quotaBytes":536870912,"hits":0,"misses":0}`, ""},
		{"app_update_pending", `{}`, `null`, ""},
		{"ui_settings_import", `{}`, `null`, ""},
		{"player_close", `{}`, `null`, ""},
		{"player_tracks", `{}`, "", `"aucune lecture ouverte"`},
		{"emby_libraries", `{}`, "", `{"kind":"notSignedIn","message":"non connecté au serveur Emby"}`},
		{"sync_devices", `{}`, "", `{"kind":"notSignedIn","message":"non connecté au serveur Emby"}`},
		{"ur_status", `{}`, "", `{"kind":"embySignedOut","message":"connecte-toi d'abord à Emby"}`},
	}
	for _, c := range cases {
		out, err := invoke(t, b, c.cmd, c.args)
		if c.err != "" {
			if !sameKind(err, c.err) {
				t.Errorf("%s : erreur %s, attendu %s", c.cmd, err, c.err)
			}
			continue
		}
		if err != "" || out != c.out {
			t.Errorf("%s : %s %s, attendu %s", c.cmd, out, err, c.out)
		}
	}
}

// sameKind compares two rejections: a {kind, message} object on its kind (the
// French messages belong to the ported packages), anything else exactly.
func sameKind(got, want string) bool {
	var g, w struct{ Kind string }
	if json.Unmarshal([]byte(want), &w) == nil && w.Kind != "" {
		return json.Unmarshal([]byte(got), &g) == nil && g.Kind == w.Kind
	}
	return got != ""
}

// Arguments are read with Tauri's rules and texts.
func Test_go_arguments_follow_tauri(t *testing.T) {
	_, b, _, _ := testApp(t, true)
	cases := []struct{ cmd, args, err string }{
		{"emby_items", `{"startIndex":0,"limit":10}`, `"command emby_items missing required key libraryId"`},
		{"emby_items", `{"libraryId":"l","startIndex":-1,"limit":10}`, "invalid args `startIndex` for command `emby_items`"},
		{"emby_items", `{"libraryId":5,"startIndex":0,"limit":10}`, "invalid args `libraryId` for command `emby_items`"},
		{"emby_probe", `{"URL":"http://h"}`, `"command emby_probe missing required key url"`},
		{"emby_set_favorite", `{"itemId":"i","favorite":null}`, "invalid args `favorite` for command `emby_set_favorite`"},
		{"window_set_fullscreen", `{}`, `"command window_set_fullscreen missing required key fullscreen"`},
		{"emby_play", `[]`, "invalid args for command `emby_play`: expected an object"},
		{"ui_settings_export", `{}`, `"command ui_settings_export missing required key entries"`},
		{"ui_settings_export", `{"entries":null}`, "invalid args `entries` for command `ui_settings_export`"},
		{"ui_settings_export", `{"entries":{"a":1}}`, "invalid args `entries` for command `ui_settings_export`"},
	}
	for _, c := range cases {
		_, err := invoke(t, b, c.cmd, c.args)
		if !strings.Contains(err, c.err) || !strings.HasPrefix(err, `"`) {
			t.Errorf("%s %s : %s", c.cmd, c.args, err)
		}
	}
	// An Option may be missing or null: the session check is then reached.
	for _, args := range []string{`{"libraryId":"l","startIndex":0,"limit":10}`, `{"libraryId":"l","startIndex":0,"limit":10,"options":null}`} {
		if _, err := invoke(t, b, "emby_items", args); !sameKind(err, `{"kind":"notSignedIn"}`) {
			t.Errorf("%s : %s", args, err)
		}
	}
	// emby_change_quality refuses a negative position before any request.
	_, err := invoke(t, b, "emby_change_quality", `{"itemId":"i","mediaSourceId":"m","cap":null,"positionSeconds":-1}`)
	if !strings.Contains(err, `"kind":"invalidResponse"`) || !strings.Contains(err, "position invalide") {
		t.Fatal(err)
	}
	// emby_play validates the track languages first.
	_, err = invoke(t, b, "emby_play", `{"itemId":"i","resume":false,"preferences":{"tracks":{"audioLanguage":"xx"}}}`)
	if !strings.Contains(err, "langue inconnue") {
		t.Fatal(err)
	}
}

// player_open is the Gate 1 bench: refused in release builds.
func Test_go_player_open_is_debug_only(t *testing.T) {
	_, b, _, _ := testApp(t, false)
	req := `{"request":{"source":"C:\\v.mkv"}}`
	if _, err := invoke(t, b, "player_open", req); err != `"source invalide : prototype indisponible dans cette version"` {
		t.Fatal(err)
	}
	// Debug build: the request goes on to the source check (no such file here).
	_, b, _, _ = testApp(t, true)
	if _, err := invoke(t, b, "player_open", req); err != `"source invalide : fichier introuvable"` {
		t.Fatal(err)
	}
}

// window_set_fullscreen starts from a restored window and maximizes again after.
func Test_go_fullscreen_goes_through_a_restored_window(t *testing.T) {
	_, b, win, _ := testApp(t, true)
	if out, err := invoke(t, b, "window_set_fullscreen", `{"fullscreen":true}`); out != "true" || err != "" {
		t.Fatal(out, err)
	}
	if out, _ := invoke(t, b, "window_set_fullscreen", `{"fullscreen":false}`); out != "false" {
		t.Fatal(out)
	}
	if got := strings.Join(win.calls, ","); got != "unmaximise,fullscreen,fullscreen,maximise" {
		t.Fatal(got)
	}
	// Not maximized before: left restored.
	win.calls, win.maximized = nil, false
	invoke(t, b, "window_set_fullscreen", `{"fullscreen":true}`)
	invoke(t, b, "window_set_fullscreen", `{"fullscreen":false}`)
	if got := strings.Join(win.calls, ","); got != "fullscreen,fullscreen" {
		t.Fatal(got)
	}
}

// The artwork protocols refuse what they cannot parse, and answer 401 signed out.
func Test_go_artwork_protocols(t *testing.T) {
	a, _, _, _ := testApp(t, true)
	next := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { w.WriteHeader(299) })
	h := withProtocols(a, next)
	cases := map[string]int{
		"/emby-img/srv/item/Primary?tag=abc&maxWidth=240": http.StatusUnauthorized,
		"/emby-img/srv/item/Primary?tag=abc":              http.StatusBadRequest,
		"/emby-img/srv/item/Banner?tag=abc&maxWidth=240":  http.StatusBadRequest,
		"/requestimg/w342/abc.jpg":                           http.StatusUnauthorized,
		"/requestimg/../etc":                                 http.StatusBadRequest,
		"/index.html":                                     299,
	}
	for target, want := range cases {
		rec := httptest.NewRecorder()
		h.ServeHTTP(rec, httptest.NewRequest(http.MethodGet, "http://wails.localhost"+target, nil))
		if rec.Code != want || rec.Body.Len() != 0 && want != 299 {
			t.Errorf("%s : %d, attendu %d", target, rec.Code, want)
		}
	}
	statuses := []struct {
		err  error
		want int
	}{
		{emby.ErrNotSignedIn, 401},
		{emby.ErrSessionExpired, 401},
		{emby.ErrNotFound, 404},
		{emby.ErrForbidden, 403},
		{emby.NewUnreachable("x"), 502},
	}
	for _, c := range statuses {
		if got := embyImageStatus(c.err); got != c.want {
			t.Errorf("emby %v : %d, attendu %d", c.err, got, c.want)
		}
	}
	urStatuses := []struct {
		err  error
		want int
	}{
		// The identity error: NotSignedIn is embySignedOut, the rest credentialStore.
		{emby.ErrNotSignedIn, 401},
		{emby.NewCredentialStore("x"), 502},
		{request.ErrNotConfigured, 403},
		{request.NewForbidden("x"), 403},
		{request.NewRateLimited(3), 429},
		{request.NewRejected("x"), 401},
		{request.ErrSignedOut, 401},
		{request.ErrEmbySignedOut, 401},
		{request.NewNotFound("x"), 404},
		{request.NewServer(500, "boom"), 502},
		{request.ErrUnreachable, 502},
	}
	for _, c := range urStatuses {
		if got, _ := urImageStatus(c.err); got != c.want {
			t.Errorf("requestimg %v : %d, attendu %d", c.err, got, c.want)
		}
	}
}

// Marking an item played announces the new state on emby://playback-stopped:
// signed out, nothing is announced.
func Test_go_set_played_needs_a_session(t *testing.T) {
	_, b, _, events := testApp(t, true)
	if _, err := invoke(t, b, "emby_set_played", `{"itemId":"i","played":true}`); !sameKind(err, `{"kind":"notSignedIn"}`) {
		t.Fatal(err)
	}
	if len(*events) != 0 {
		t.Fatal(*events)
	}
}

// ui_settings_export then ui_settings_import through the bridge, in the app config
// folder (a refused export leaves the previous one).
func TestUISettingsHandover(t *testing.T) {
	a, b, _, _ := testApp(t, true)
	if _, err := invoke(t, b, "ui_settings_export", `{"entries":{"viewer.player.volume":"7"}}`); err != "" {
		t.Fatal(err)
	}
	if _, err := invoke(t, b, "ui_settings_export", `{"entries":{"token":"x"}}`); err != `"réglages refusés : clé non autorisée"` {
		t.Fatal(err)
	}
	if _, err := os.Stat(filepath.Join(a.Paths().Config, "ui-settings.json")); err != nil {
		t.Fatal(err)
	}
	if out, err := invoke(t, b, "ui_settings_import", `{}`); out != `{"viewer.player.volume":"7"}` || err != "" {
		t.Fatal(out, err)
	}
}
