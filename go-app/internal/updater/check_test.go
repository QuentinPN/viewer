package updater

import (
	"context"
	"crypto/ed25519"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

// End-to-end checks against an httptest server on localhost: a manifest and a
// fake installer, signed with an ephemeral key pair. No other network.

type fakeServer struct {
	t         *testing.T
	srv       *httptest.Server
	priv      ed25519.PrivateKey
	pub       string
	installer []byte

	mu        sync.Mutex
	manifest  func(base string) (int, string)
	downloads atomic.Int32
	checks    atomic.Int32
	accept    atomic.Value // last Accept header of a manifest request
}

func newFakeServer(t *testing.T) *fakeServer {
	t.Helper()
	pub, priv, err := ed25519.GenerateKey(nil)
	if err != nil {
		t.Fatal(err)
	}
	f := &fakeServer{
		t:         t,
		priv:      priv,
		pub:       base64.StdEncoding.EncodeToString(pub),
		installer: []byte("MZ\x90\x00 fake NSIS installer"),
	}
	f.manifest = func(base string) (int, string) { return 200, f.signedManifest(base, "1.3.0", f.installer) }
	f.srv = httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/desktop/latest.json":
			f.checks.Add(1)
			f.accept.Store(r.Header.Get("Accept"))
			f.mu.Lock()
			status, body := f.manifest(f.srv.URL)
			f.mu.Unlock()
			w.WriteHeader(status)
			_, _ = w.Write([]byte(body))
		case "/setup.exe":
			f.downloads.Add(1)
			_, _ = w.Write(f.installer)
		default:
			http.NotFound(w, r)
		}
	}))
	t.Cleanup(f.srv.Close)
	return f
}

func (f *fakeServer) sign(version string, b []byte) string {
	return base64.StdEncoding.EncodeToString(ed25519.Sign(f.priv, SignedMessage(version, b)))
}

// signedManifest is desktop/latest.json as publish.ts writes it, plus `ed25519`.
func (f *fakeServer) signedManifest(base, version string, signed []byte) string {
	m := map[string]any{
		"version":  version,
		"notes":    "\n## Nouveautés\n- Lecture liée\n",
		"pub_date": "2026-11-22T10:00:00.000Z",
		"platforms": map[string]any{
			"windows-x86_64": map[string]any{
				"signature": "dW50cnVzdGVkIGNvbW1lbnQ6IG1pbmlzaWdu", // minisign, for the Tauri app
				"url":       base + "/setup.exe",
				"ed25519":   f.sign(version, signed),
			},
		},
	}
	b, _ := json.Marshal(m)
	return string(b)
}

func (f *fakeServer) setManifest(m func(base string) (int, string)) {
	f.mu.Lock()
	f.manifest = m
	f.mu.Unlock()
}

type recordedEvent struct {
	name    string
	payload any
}

type recorder struct {
	mu     sync.Mutex
	events []recordedEvent
}

func (r *recorder) Emit(name string, payload any) {
	r.mu.Lock()
	r.events = append(r.events, recordedEvent{name, payload})
	r.mu.Unlock()
}

func (r *recorder) all() []recordedEvent {
	r.mu.Lock()
	defer r.mu.Unlock()
	return append([]recordedEvent(nil), r.events...)
}

func (f *fakeServer) updater(rec *recorder) *Updater {
	cfg := DefaultConfig("1.2.0")
	cfg.Endpoints = []string{f.srv.URL + "/desktop/latest.json"}
	cfg.PublicKey = f.pub
	cfg.AppArgs = nil // not the arguments of the test binary
	cfg.HTTPClient = f.srv.Client()
	cfg.TempDir = f.t.TempDir()
	cfg.Launch = func(string, string) error { return errors.New("no launch in tests") }
	cfg.Quit = func() { f.t.Error("no quit in tests") }
	var emit Emitter
	if rec != nil {
		emit = rec
	}
	return New(cfg, emit)
}

func ctx(t *testing.T) context.Context {
	c, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	t.Cleanup(cancel)
	return c
}

func Test_go_check_downloads_verifies_and_emits(t *testing.T) {
	f := newFakeServer(t)
	rec := &recorder{}
	u := f.updater(rec)

	got := u.CheckOnce(ctx(t), true)
	want := Ready(UpdateInfo{Version: "1.3.0", Notes: "## Nouveautés\n- Lecture liée"})
	if got != want {
		t.Fatalf("got %+v, want %+v", got, want)
	}
	if a := f.accept.Load(); a != "application/json" {
		t.Errorf("manifest Accept %v", a)
	}
	ev := rec.all()
	if len(ev) != 1 || ev[0].name != UpdateReadyEvent || ev[0].payload != want.Info {
		t.Fatalf("events %+v", ev)
	}
	if mustJSON(t, ev[0].payload) != `{"version":"1.3.0","notes":"## Nouveautés\n- Lecture liée"}` {
		t.Errorf("event payload %s", mustJSON(t, ev[0].payload))
	}
	if info, ok := u.Pending(); !ok || info != want.Info {
		t.Errorf("pending %+v %v", info, ok)
	}

	// A second check finds the version already downloaded: no second download,
	// no second event.
	if again := u.CheckOnce(ctx(t), true); again != want {
		t.Errorf("second check %+v", again)
	}
	if n := f.downloads.Load(); n != 1 {
		t.Errorf("downloads = %d, want 1", n)
	}
	if n := len(rec.all()); n != 1 {
		t.Errorf("events = %d, want 1", n)
	}
}

func Test_go_concurrent_checks_download_once(t *testing.T) {
	f := newFakeServer(t)
	rec := &recorder{}
	u := f.updater(rec)
	var wg sync.WaitGroup
	outcomes := make([]CheckOutcome, 4)
	for i := range outcomes {
		wg.Add(1)
		go func() {
			defer wg.Done()
			outcomes[i] = u.CheckOnce(ctx(t), true)
		}()
	}
	wg.Wait()
	for _, o := range outcomes {
		if o.Status != StatusReady || o.Info.Version != "1.3.0" {
			t.Errorf("outcome %+v", o)
		}
	}
	if n := f.downloads.Load(); n != 1 {
		t.Errorf("downloads = %d, want 1", n)
	}
	if n := len(rec.all()); n != 1 {
		t.Errorf("events = %d, want 1", n)
	}
}

func Test_go_bad_signature_is_rejected(t *testing.T) {
	for name, mutate := range map[string]func(f *fakeServer){
		"other bytes signed": func(f *fakeServer) {
			f.setManifest(func(base string) (int, string) {
				return 200, f.signedManifest(base, "1.3.0", []byte("MZ another installer"))
			})
		},
		"other key": func(f *fakeServer) {
			_, other, _ := ed25519.GenerateKey(nil)
			f.priv = other
		},
		"not base64": func(f *fakeServer) {
			f.setManifest(func(base string) (int, string) {
				return 200, strings.Replace(f.signedManifest(base, "1.3.0", f.installer), `"ed25519":"`, `"ed25519":"%%`, 1)
			})
		},
		"no ed25519 field (a Tauri-only manifest)": func(f *fakeServer) {
			f.setManifest(func(base string) (int, string) {
				return 200, fmt.Sprintf(`{"version":"1.3.0","platforms":{"windows-x86_64":{"signature":"x","url":%q}}}`, base+"/setup.exe")
			})
		},
	} {
		t.Run(name, func(t *testing.T) {
			f := newFakeServer(t)
			mutate(f)
			rec := &recorder{}
			u := f.updater(rec)
			if got := u.CheckOnce(ctx(t), true); got != Failed("signature") {
				t.Errorf("got %+v", got)
			}
			if _, ok := u.Pending(); ok {
				t.Error("an unverified installer must not be kept")
			}
			if len(rec.all()) != 0 {
				t.Error("no event for an unverified installer")
			}
		})
	}
}

func Test_go_empty_pinned_key_fails_closed(t *testing.T) {
	f := newFakeServer(t)
	u := f.updater(nil)
	u.cfg.PublicKey = ""
	if got := u.CheckOnce(ctx(t), true); got != Failed("signature") {
		t.Errorf("got %+v", got)
	}
}

func Test_go_check_outcomes(t *testing.T) {
	for _, c := range []struct {
		name     string
		manifest func(f *fakeServer, base string) (int, string)
		download bool
		want     CheckOutcome
	}{
		{"same version", func(f *fakeServer, base string) (int, string) {
			return 200, f.signedManifest(base, "1.2.0", f.installer)
		}, true, UpToDate()},
		{"older version", func(f *fakeServer, base string) (int, string) {
			return 200, f.signedManifest(base, "v1.1.9", f.installer)
		}, true, UpToDate()},
		{"pre-release of the current version", func(f *fakeServer, base string) (int, string) {
			return 200, f.signedManifest(base, "1.2.0-rc.1", f.installer)
		}, true, UpToDate()},
		{"not newer and no target is not an error", func(*fakeServer, string) (int, string) {
			return 200, `{"version":"1.2.0","platforms":{}}`
		}, true, UpToDate()},
		{"204 no content", func(*fakeServer, string) (int, string) { return 204, "" }, true, UpToDate()},
		{"debug build reports without downloading", func(f *fakeServer, base string) (int, string) {
			return 200, f.signedManifest(base, "v1.3.0", f.installer)
		}, false, Available("1.3.0")},
		{"non-2xx manifest", func(*fakeServer, string) (int, string) { return 500, "oops" }, true, Failed("manifest")},
		{"404 manifest", func(*fakeServer, string) (int, string) { return 404, "" }, true, Failed("manifest")},
		{"body is not JSON (a request error, as in reqwest)", func(*fakeServer, string) (int, string) {
			return 200, "<html>"
		}, true, Failed("network")},
		{"no version", func(*fakeServer, string) (int, string) { return 200, `{"notes":"x"}` }, true, Failed("manifest")},
		{"version is not semver", func(*fakeServer, string) (int, string) {
			return 200, `{"version":"1.3","url":"http://x/y","ed25519":"x"}`
		}, true, Failed("manifest")},
		{"bad pub_date", func(*fakeServer, string) (int, string) {
			return 200, `{"version":"1.3.0","pub_date":"hier","url":"http://x/y","ed25519":"x"}`
		}, true, Failed("manifest")},
		{"no target for windows", func(f *fakeServer, base string) (int, string) {
			return 200, `{"version":"1.3.0","platforms":{"darwin-aarch64":{"url":"http://x/y","ed25519":"x"}}}`
		}, true, Failed("manifest")},
		{"download fails", func(f *fakeServer, base string) (int, string) {
			return 200, strings.Replace(f.signedManifest(base, "1.3.0", f.installer), "/setup.exe", "/missing.exe", 1)
		}, true, Failed("network")},
	} {
		t.Run(c.name, func(t *testing.T) {
			f := newFakeServer(t)
			f.setManifest(func(base string) (int, string) { return c.manifest(f, base) })
			u := f.updater(nil)
			if got := u.CheckOnce(ctx(t), c.download); got != c.want {
				t.Errorf("got %+v, want %+v", got, c.want)
			}
			if c.want.Status != StatusFailed && c.want.Status != StatusReady && f.downloads.Load() != 0 {
				t.Errorf("downloaded %d times", f.downloads.Load())
			}
		})
	}
}

func Test_go_dynamic_format_and_nsis_target(t *testing.T) {
	f := newFakeServer(t)
	f.setManifest(func(base string) (int, string) {
		return 200, fmt.Sprintf(`{"name":"v1.4.0","url":%q,"ed25519":%q,"signature":"minisign"}`, base+"/setup.exe", f.sign("1.4.0", f.installer))
	})
	if got := f.updater(nil).CheckOnce(ctx(t), true); got != Ready(UpdateInfo{Version: "1.4.0"}) {
		t.Errorf("dynamic: %+v", got)
	}

	g := newFakeServer(t)
	g.setManifest(func(base string) (int, string) {
		return 200, fmt.Sprintf(`{"version":"1.5.0","platforms":{
			"windows-x86_64":{"url":"http://127.0.0.1:1/never","ed25519":"x"},
			"windows-x86_64-nsis":{"url":%q,"ed25519":%q}}}`, base+"/setup.exe", g.sign("1.5.0", g.installer))
	})
	if got := g.updater(nil).CheckOnce(ctx(t), true); got != Ready(UpdateInfo{Version: "1.5.0"}) {
		t.Errorf("nsis target first: %+v", got)
	}
}

func Test_go_setup_failures(t *testing.T) {
	f := newFakeServer(t)
	u := f.updater(nil)
	u.cfg.Endpoints = nil
	if got := u.CheckOnce(ctx(t), true); got != Failed("other") {
		t.Errorf("no endpoint: %+v", got)
	}
	u = f.updater(nil)
	u.cfg.AllowInsecureTransport = false
	if got := u.CheckOnce(ctx(t), true); got != Failed("transport") {
		t.Errorf("http without the flag: %+v", got)
	}
	if f.checks.Load() != 0 {
		t.Error("a setup failure must not reach the server")
	}
}

func Test_go_unreachable_and_cancelled(t *testing.T) {
	f := newFakeServer(t)
	u := f.updater(nil)
	f.srv.Close()
	if got := u.CheckOnce(ctx(t), true); got != Failed("network") {
		t.Errorf("closed server: %+v", got)
	}
	c, cancel := context.WithCancel(context.Background())
	cancel()
	if got := u.CheckOnce(c, true); got != Failed("network") {
		t.Errorf("cancelled: %+v", got)
	}
}

func Test_go_install_launches_the_quiet_installer_then_quits(t *testing.T) {
	f := newFakeServer(t)
	u := f.updater(nil)
	if err := u.Install(); err != ErrNotReady {
		t.Fatalf("install before a download: %v", err)
	}
	if u.CheckOnce(ctx(t), true).Status != StatusReady {
		t.Fatal("no update ready")
	}
	var launchedPath, launchedArgs string
	quits := 0
	u.cfg.Launch = func(path, args string) error {
		launchedPath, launchedArgs = path, args
		return nil
	}
	u.cfg.Quit = func() { quits++ }
	if err := u.Install(); err != nil {
		t.Fatalf("install: %v", err)
	}
	if launchedArgs != "/S /UPDATE /R /ARGS" {
		t.Errorf("args %q", launchedArgs)
	}
	if filepath.Base(launchedPath) != "Viewer-1.3.0-installer.exe" ||
		!strings.HasPrefix(filepath.Base(filepath.Dir(launchedPath)), "Viewer-1.3.0-updater-") ||
		!strings.HasPrefix(launchedPath, u.cfg.TempDir) {
		t.Errorf("path %s", launchedPath)
	}
	written, err := os.ReadFile(launchedPath)
	if err != nil || string(written) != string(f.installer) {
		t.Errorf("written installer %q, %v", written, err)
	}
	if quits != 1 {
		t.Errorf("quit %d times", quits)
	}
}

func Test_go_install_failures_are_reported_not_quit(t *testing.T) {
	f := newFakeServer(t)
	f.installer = []byte("PK zip, not an exe")
	u := f.updater(nil)
	if u.CheckOnce(ctx(t), true).Status != StatusReady {
		t.Fatal("no update ready")
	}
	launched := false
	u.cfg.Launch = func(string, string) error { launched = true; return nil }
	if err := u.Install(); err != ErrInstallStart {
		t.Errorf("not an exe: %v", err)
	}
	if launched {
		t.Error("a file without MZ header must not be launched")
	}

	g := newFakeServer(t)
	v := g.updater(nil)
	if v.CheckOnce(ctx(t), true).Status != StatusReady {
		t.Fatal("no update ready")
	}
	v.cfg.Launch = func(string, string) error { return errors.New("ShellExecute: 2") }
	if err := v.Install(); err != ErrInstallStart {
		t.Errorf("launch failure: %v", err)
	}
	if mustJSON(t, ErrInstallStart) != `"L'installation n'a pas pu démarrer."` {
		t.Errorf("install error JSON %s", mustJSON(t, ErrInstallStart))
	}
}

func Test_go_handlers(t *testing.T) {
	f := newFakeServer(t)
	u := f.updater(nil)
	pending := PendingHandler(u)
	if v, err := pending(ctx(t), nil); err != nil || mustJSON(t, v) != "null" {
		t.Errorf("pending before: %s, %v", mustJSON(t, v), err)
	}
	if v, err := CheckHandler(u, true)(ctx(t), nil); err != nil || mustJSON(t, v) != `{"status":"available","version":"1.3.0"}` {
		t.Errorf("debug check: %s, %v", mustJSON(t, v), err)
	}
	if v, err := CheckHandler(u, false)(ctx(t), json.RawMessage(`{}`)); err != nil || v.(CheckOutcome).Status != StatusReady {
		t.Errorf("release check: %v, %v", v, err)
	}
	if v, err := pending(ctx(t), nil); err != nil || mustJSON(t, v) != `{"version":"1.3.0","notes":"## Nouveautés\n- Lecture liée"}` {
		t.Errorf("pending after: %s, %v", mustJSON(t, v), err)
	}
	quit := false
	u.cfg.Launch = func(string, string) error { return nil }
	u.cfg.Quit = func() { quit = true }
	if v, err := InstallHandler(u)(ctx(t), nil); err != nil || v != nil || !quit {
		t.Errorf("install: %v, %v, quit %v", v, err, quit)
	}
}

func Test_go_run_checks_after_the_delay_then_periodically(t *testing.T) {
	f := newFakeServer(t)
	f.setManifest(func(string) (int, string) { return 204, "" })
	u := f.updater(nil)
	c, cancel := context.WithCancel(context.Background())
	done := make(chan struct{})
	go func() {
		u.run(c, 10*time.Millisecond, 10*time.Millisecond)
		close(done)
	}()
	deadline := time.Now().Add(5 * time.Second)
	for f.checks.Load() < 3 && time.Now().Before(deadline) {
		time.Sleep(5 * time.Millisecond)
	}
	cancel()
	<-done
	if n := f.checks.Load(); n < 3 {
		t.Errorf("checks = %d, want at least 3", n)
	}
}
