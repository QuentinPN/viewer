package app

import (
	"errors"
	"os"
	"path/filepath"
	"sync/atomic"
	"testing"
	"time"

	"viewer/internal/emby"
	"viewer/internal/player"
)

// A plain `go build` / `go test` is a debug build: only the release
// build passes buildMode=release.
func Test_go_unflagged_builds_are_debug(t *testing.T) {
	if !DebugBuild() {
		t.Fatal("build sans drapeau vue comme release")
	}
	if Version() == "" {
		t.Fatal("version vide")
	}
}

// Names and folders are Tauri's, per build (lib.rs DEBUG_IDENTIFIER, tauri
// path/desktop.rs, wave2A vault names).
func Test_go_names_and_folders_follow_tauri(t *testing.T) {
	if IdentifierFor(false) != "io.github.quentinpn.viewer" || IdentifierFor(true) != "io.github.quentinpn.viewer.debug" {
		t.Fatal("identifiants")
	}
	if VaultServiceFor(false) != "Viewer" || VaultServiceFor(true) != "Viewer-debug" {
		t.Fatal("coffre")
	}
	if TitleFor(false) != "Viewer" || TitleFor(true) != "Viewer (debug)" {
		t.Fatal("titre")
	}
	t.Setenv("APPDATA", `C:\R`)
	t.Setenv("LOCALAPPDATA", `C:\L`)
	t.Setenv("VIEWER_E2E_LOG_DIR", `C:\E2E`)
	p, err := DefaultPaths(false)
	if err != nil {
		t.Fatal(err)
	}
	want := Paths{Config: `C:\R\io.github.quentinpn.viewer`, Cache: `C:\L\io.github.quentinpn.viewer`, LocalData: `C:\L\io.github.quentinpn.viewer`, Logs: `C:\L\io.github.quentinpn.viewer\logs`}
	if p != want {
		t.Fatalf("%+v", p)
	}
	p, _ = DefaultPaths(true)
	if p.Config != `C:\R\io.github.quentinpn.viewer.debug` || p.LocalData != `C:\L\io.github.quentinpn.viewer.debug` || p.Logs != `C:\E2E` {
		t.Fatalf("%+v", p)
	}
}

func Test_go_libmpv_is_never_a_bare_name(t *testing.T) {
	t.Setenv("VIEWER_LIBMPV", `C:\x\libmpv-2.dll`)
	if got := ResolveLibmpv(true); got != `C:\x\libmpv-2.dll` {
		t.Fatal(got)
	}
	got := ResolveLibmpv(false)
	if got == `C:\x\libmpv-2.dll` || !filepath.IsAbs(got) || filepath.Base(got) != "libmpv-2.dll" {
		t.Fatal(got)
	}
}

func Test_go_device_name(t *testing.T) {
	t.Setenv("COMPUTERNAME", "PC-SALON")
	if DeviceName() != "PC-SALON" {
		t.Fatal(DeviceName())
	}
	os.Unsetenv("COMPUTERNAME")
	if DeviceName() != "PC Windows" {
		t.Fatal(DeviceName())
	}
}

// Built on temporary folders: no player, Close is idempotent for the player slot,
// the artwork cache lives in Cache\artwork.
func Test_go_state_without_player(t *testing.T) {
	dir := t.TempDir()
	a, err := New(Options{
		Debug:   true,
		Version: "1.0.0",
		Paths:   Paths{Config: filepath.Join(dir, "c"), Cache: filepath.Join(dir, "l"), LocalData: filepath.Join(dir, "l")},
		Store:   &emby.MemoryCredentialStore{},
	})
	if err != nil {
		t.Fatal(err)
	}
	if a.HasPlayer() || a.Player() != nil {
		t.Fatal("lecteur au départ")
	}
	a.ClosePlayer()
	if a.Artwork == nil {
		t.Fatal("pas de cache d'illustrations")
	}
	if _, err := a.Identity(); err == nil {
		t.Fatal("identité sans session")
	}
	a.Close(time.Second)
	if a.Context().Err() == nil {
		t.Fatal("contexte non terminé")
	}
	if _, err := New(Options{Paths: Paths{Config: dir}}); err == nil {
		t.Fatal("sans coffre")
	}
}

// App.Go defers Options.Recover: a panic in an app goroutine reaches it.
func Test_go_app_goroutines_defer_recover(t *testing.T) {
	dir := t.TempDir()
	caught := make(chan any, 1)
	a, err := New(Options{
		Paths:   Paths{Config: filepath.Join(dir, "c"), Cache: filepath.Join(dir, "l"), LocalData: filepath.Join(dir, "l")},
		Store:   &emby.MemoryCredentialStore{},
		Recover: func() { caught <- recover() },
	})
	if err != nil {
		t.Fatal(err)
	}
	defer a.Close(time.Second)
	a.Go(func() { panic("boom") })
	select {
	case v := <-caught:
		if v != "boom" {
			t.Fatal(v)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("Recover non appelé")
	}
}

type fakeEngine struct {
	err  error
	shut int
}

func (f *fakeEngine) Shutdown() error { f.shut++; return f.err }

func Test_go_close_slot_keeps_the_engine_when_shutdown_is_refused(t *testing.T) {
	var slot atomic.Pointer[fakeEngine]
	e := &fakeEngine{err: player.ErrShutdownFromSink}
	slot.Store(e)
	if err := closeSlot(&slot); !errors.Is(err, player.ErrShutdownFromSink) {
		t.Fatal(err)
	}
	if slot.Load() != e {
		t.Fatal("moteur détaché malgré le refus")
	}
	e.err = nil
	if err := closeSlot(&slot); err != nil || slot.Load() != nil || e.shut != 2 {
		t.Fatal(err, slot.Load(), e.shut)
	}
	if err := closeSlot(&slot); err != nil {
		t.Fatal(err)
	}
}
