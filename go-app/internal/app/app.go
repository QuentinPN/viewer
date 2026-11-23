// Package app is the state of the Wails app, port of `AppState` and
// of the `setup` of src-tauri/src/lib.rs: the Emby session, the native player
// slot, the playback reporter, the linked playback, the session socket, the
// artwork cache, Request, segments and the updater, with the folders and
// names Tauri uses so the Go build reads the same data.
package app

import (
	"context"
	"errors"
	"log/slog"
	"os"
	"path/filepath"
	"strconv"
	"sync"
	"sync/atomic"
	"time"

	"viewer/internal/artworkcache"
	"viewer/internal/devsync"
	"viewer/internal/diagnostics"
	"viewer/internal/emby"
	"viewer/internal/libraryevents"
	"viewer/internal/player"
	"viewer/internal/segments"
	"viewer/internal/stremio"
	"viewer/internal/updater"
	"viewer/internal/request"
)

// Set at build time (`-ldflags -X`): Go has neither `CARGO_PKG_VERSION` nor
// `cfg!(debug_assertions)`. A plain `go build` / `go test` is a debug build, so it
// never touches the installed app's data; the release build passes
// `-X viewer/internal/app.buildMode=release -X viewer/internal/app.version=X.Y.Z`
// (package.json `build:wails:exe`).
var (
	version   = "0.0.0-dev"
	buildMode = "debug"
)

// Version is package.json's version, the `CARGO_PKG_VERSION` of the Rust build.
func Version() string { return version }

// DebugBuild is `cfg!(debug_assertions)`: anything but a release build.
func DebugBuild() bool { return buildMode != "release" }

const (
	// Identifier is tauri.conf.json `identifier`.
	Identifier = "io.github.quentinpn.viewer"
	// DebugIdentifier is lib.rs `DEBUG_IDENTIFIER`: debug builds are
	// another app, with their own config, cache, logs and WebView storage.
	DebugIdentifier = "io.github.quentinpn.viewer.debug"
	// PlayerEvent is lib.rs `PLAYER_EVENT`.
	PlayerEvent = "player://event"
	// Title is the main window's title (tauri.conf.json), « (debug) » appended in debug.
	Title = "Viewer"
	// SyncByeWait: a linked TV is told `bye stopped` before sign-out or a server
	// change, waiting at most this long (commands.rs `SYNC_BYE_WAIT`).
	SyncByeWait = 3 * time.Second
)

// IdentifierFor is the identifier of a build.
func IdentifierFor(debug bool) string {
	if debug {
		return DebugIdentifier
	}
	return Identifier
}

// VaultServiceFor is the credential service of a build (wave2A):
// `{serverId}/{userId}.Viewer`, or `.Viewer-debug` in debug builds.
func VaultServiceFor(debug bool) string {
	if debug {
		return "Viewer-debug"
	}
	return "Viewer"
}

// TitleFor is the window title of a build.
func TitleFor(debug bool) string {
	if debug {
		return Title + " (debug)"
	}
	return Title
}

// Paths are Tauri's app folders on Windows (tauri 2.12.5 `path/desktop.rs`):
// Config is `app_config_dir` (%APPDATA%\{id}), Cache is `app_cache_dir` and
// LocalData `app_local_data_dir` (both %LOCALAPPDATA%\{id}), Logs `app_log_dir`
// (%LOCALAPPDATA%\{id}\logs). WebView2 keeps its profile in LocalData\EBWebView
// (Tauri sets the local data folder as the user data folder, WebView2 adds
// EBWebView).
type Paths struct {
	Config    string
	Cache     string
	LocalData string
	Logs      string
}

// DefaultPaths resolves the folders of a build. They are read from %APPDATA% and
// %LOCALAPPDATA% (os.UserConfigDir, os.UserCacheDir), where Rust's `dirs` asks
// the shell's known folders: the same folders on a normal session, and a process
// started with other variables (scripted checks) stays out of the owner's data.
func DefaultPaths(debug bool) (Paths, error) {
	id := IdentifierFor(debug)
	roaming, err := os.UserConfigDir()
	if err != nil {
		return Paths{}, err
	}
	local, err := os.UserCacheDir()
	if err != nil {
		return Paths{}, err
	}
	p := Paths{
		Config:    filepath.Join(roaming, id),
		Cache:     filepath.Join(local, id),
		LocalData: filepath.Join(local, id),
		// Debug builds only: scripted UI tests (scripts/e2e) use their own log folder.
		Logs: diagnostics.DefaultLogDir(id, debug),
	}
	return p, nil
}

// DeviceName is `device_name`: how this PC names itself to Emby and Request.
func DeviceName() string {
	if name, ok := os.LookupEnv("COMPUTERNAME"); ok {
		return name
	}
	return "PC Windows"
}

// ResolveLibmpv is `resolve_libmpv`: `VIEWER_LIBMPV` (debug builds only), next to
// the executable, then the repository's vendor/mpv in debug builds. Never a bare
// name: Windows would search the current directory and PATH for the DLL. A
// missing file ends in a LibraryUnavailable PlayerError.
func ResolveLibmpv(debug bool) string {
	if p := os.Getenv("VIEWER_LIBMPV"); debug && p != "" {
		return p
	}
	exe, err := os.Executable()
	beside := ""
	if err == nil {
		beside = filepath.Join(filepath.Dir(exe), "libmpv-2.dll")
	}
	if debug && !IsFile(beside) && exe != "" {
		// Rust uses CARGO_MANIFEST_DIR/../vendor; Go has no build-time source path,
		// so the repository is searched upwards from the executable (go-app/bin).
		for dir := filepath.Dir(exe); ; dir = filepath.Dir(dir) {
			candidate := filepath.Join(dir, "vendor", "mpv", "libmpv-2.dll")
			if IsFile(candidate) {
				return candidate
			}
			if filepath.Dir(dir) == dir {
				break
			}
		}
	}
	return beside
}

// IsFile is `Path::is_file`: an existing regular file.
func IsFile(p string) bool {
	if p == "" {
		return false
	}
	st, err := os.Stat(p)
	return err == nil && st.Mode().IsRegular()
}

// Options build an App.
type Options struct {
	Debug   bool
	Version string
	Paths   Paths
	// Store holds the Emby, Request and Stremio tokens.
	Store emby.CredentialStore
	// Libmpv is the DLL path (ResolveLibmpv).
	Libmpv string
	// Emit sends an event to the UI (Tauri's `app.emit`).
	Emit func(name string, payload any)
	// WID returns the main window's HWND (parent mode), read when the
	// engine is created; 0 lets mpv open its own window.
	WID func() uintptr
	// Log is the logger; nil is slog.Default().
	Log *slog.Logger
	// Recover is deferred first in each goroutine (diagnostics RecoverAndRepanic:
	// the panic reaches the log, then the crash goes on); nil does nothing.
	Recover func()
}

// App is `AppState` plus the services lib.rs `manage`s.
type App struct {
	opts Options
	log  *slog.Logger

	Emby        *emby.EmbyService
	Request *request.Service
	Stremio     *stremio.Service
	// StremioResume follows the Stremio playback going on (stremio-resume.json).
	StremioResume *stremio.ResumeTracker
	Segments      *segments.Service
	// Artwork is nil when the app has no cache folder.
	Artwork  *artworkcache.Cache
	Reporter *emby.Reporter
	Sync     *devsync.Service
	Library  *libraryevents.Events
	Updater  *updater.Updater

	ctx    context.Context
	cancel context.CancelFunc

	// engine is the player, created on first playback and torn down on close.
	// Readers never wait (the sync glue's `try_lock`); creation is serialized.
	engine   atomic.Pointer[player.MpvEngine]
	creating sync.Mutex
	started  atomic.Bool
}

// New builds the state (lib.rs `setup`, after the logger). The sync glue and the
// reporter start their goroutines; nothing touches the network before Start.
func New(opts Options) (*App, error) {
	if opts.Store == nil {
		return nil, errors.New("app: coffre absent")
	}
	if opts.Paths.Config == "" {
		return nil, errors.New("app: dossier de configuration absent")
	}
	if opts.Emit == nil {
		opts.Emit = func(string, any) {}
	}
	if opts.WID == nil {
		opts.WID = func() uintptr { return 0 }
	}
	if opts.Recover == nil {
		opts.Recover = func() {}
	}
	log := opts.Log
	if log == nil {
		log = slog.Default()
	}
	ctx, cancel := context.WithCancel(context.Background())
	a := &App{opts: opts, log: log, ctx: ctx, cancel: cancel, Library: &libraryevents.Events{}}

	device := DeviceName()
	svc := emby.NewEmbyService(opts.Store, filepath.Join(opts.Paths.Config, "session.json"), device, opts.Version)
	// Artwork cache: disposable, so it lives in the local cache folder.
	// Debug builds only: scripted UI tests (scripts/e2e) use their own folder.
	artworkDir := ""
	if dir := os.Getenv("VIEWER_E2E_ARTWORK_DIR"); opts.Debug && dir != "" {
		artworkDir = dir
	} else if opts.Paths.Cache != "" {
		artworkDir = filepath.Join(opts.Paths.Cache, "artwork")
	}
	if artworkDir != "" {
		a.Artwork = artworkcache.New(artworkDir, artworkcache.DefaultQuota)
		svc = svc.WithArtworkCache(a.Artwork)
	} else {
		log.Warn("no cache directory, artwork is not kept")
	}
	// Debug builds only: scripted UI tests (scripts/e2e) force a transcode with a
	// low limit, in bits per second.
	if v, err := strconv.ParseUint(os.Getenv("VIEWER_E2E_MAX_BITRATE"), 10, 64); opts.Debug && err == nil {
		svc = svc.WithMaxStreamingBitrate(v)
	}
	a.Emby = svc
	a.Request = request.NewService(opts.Store, filepath.Join(opts.Paths.Config, "request.json"), device, opts.Version).
		WithArtworkCache(a.Artwork)
	// Beside session.json, kept across Stremio sign-outs: a history of this PC.
	resume := stremio.LoadResumeStore(filepath.Join(opts.Paths.Config, stremio.ResumeFile))
	a.Stremio = stremio.NewService(opts.Store, stremio.NewClient(stremio.APIBase, opts.Version)).WithResume(resume)
	a.StremioResume = stremio.NewResumeTracker(resume, func(c player.PlayerCommand) error {
		if e := a.engine.Load(); e != nil {
			return e.Command(c)
		}
		return player.ErrNotOpen
	}, a.Go)
	a.Segments = segments.NewService(opts.Version)
	a.Reporter = emby.NewReporter(svc, opts.Emit)
	a.Sync = devsync.NewService(ctx, devsync.ServiceConfig{
		Session: svc,
		Engine: func() devsync.Player {
			if e := a.engine.Load(); e != nil {
				return e
			}
			return nil
		},
		Emit:    opts.Emit,
		Log:     log,
		Recover: opts.Recover,
	})
	a.Updater = updater.New(updater.DefaultConfig(opts.Version), emitter(opts.Emit))
	return a, nil
}

type emitter func(name string, payload any)

func (e emitter) Emit(name string, payload any) { e(name, payload) }

// Start runs what reaches the network for the app's lifetime: the session socket
// and, in release builds, the update loop.
func (a *App) Start() {
	if a.started.Swap(true) {
		return
	}
	sup := &libraryevents.Supervisor{Session: a.Emby, Sync: a.Sync, Events: a.Library, Emit: a.opts.Emit, Log: a.log, Recover: a.opts.Recover}
	a.Go(func() { sup.Run(a.ctx) })
	if !a.opts.Debug {
		a.Go(func() { a.Updater.Run(a.ctx) })
	}
}

// Go runs f on its own goroutine with Options.Recover deferred: a panic there
// reaches the log before the crash.
func (a *App) Go(f func()) {
	go func() {
		defer a.opts.Recover()
		f()
	}()
}

// Debug reports whether this is a debug build.
func (a *App) Debug() bool { return a.opts.Debug }

// Log is the app's logger.
func (a *App) Log() *slog.Logger { return a.log }

// Version is the app's version.
func (a *App) Version() string { return a.opts.Version }

// Paths are the app's folders.
func (a *App) Paths() Paths { return a.opts.Paths }

// Libmpv is the DLL path the engine loads.
func (a *App) Libmpv() string { return a.opts.Libmpv }

// Emit sends an event to the UI.
func (a *App) Emit(name string, payload any) { a.opts.Emit(name, payload) }

// Player is the open engine, nil when none (`state.player.lock().clone()`).
func (a *App) Player() *player.MpvEngine { return a.engine.Load() }

// HasPlayer reports whether an engine is open: the window close hook reads it on
// the UI thread, so it never waits.
func (a *App) HasPlayer() bool { return a.engine.Load() != nil }

// Engine is `AppState::engine`: the open engine, or a new one fed to the
// reporter, the linked playback and the UI.
func (a *App) Engine() (*player.MpvEngine, error) {
	if e := a.engine.Load(); e != nil {
		return e, nil
	}
	a.creating.Lock()
	defer a.creating.Unlock()
	if e := a.engine.Load(); e != nil {
		return e, nil
	}
	cfg := player.DefaultMpvConfig()
	cfg.WID = a.opts.WID()
	// Debug builds only: scripted UI tests (scripts/e2e) stay silent.
	_, silent := os.LookupEnv("VIEWER_E2E_SILENT")
	cfg.Silent = a.opts.Debug && silent
	if a.opts.Paths.Cache != "" {
		cfg.TLSRoots = filepath.Join(a.opts.Paths.Cache, "tls-roots.pem")
	}
	cfg.Logger = a.log
	e, err := player.NewMpvEngine(a.opts.Libmpv, cfg, a.playerEvent)
	if err != nil {
		return nil, err
	}
	a.engine.Store(e)
	return e, nil
}

// playerEvent is the engine's sink: the Stremio tracker first, which holds back
// the end of file of a start past the end, then the reporter, the linked playback
// and the UI.
func (a *App) playerEvent(ev player.PlayerEvent) {
	if !a.StremioResume.PlayerEvent(ev) {
		return
	}
	a.Reporter.PlayerEvent(ev)
	a.Sync.PlayerEvent(ev)
	a.opts.Emit(PlayerEvent, ev)
}

// ClosePlayer is `AppState::close_player`: detaches and destroys the engine.
// Never on the UI thread (mpv's embedded window teardown messages the parent
// window) nor from the event sink. The engine sends Shutdown to the reporter,
// the linked playback and the UI itself (its sink), so nothing is sent again here.
func (a *App) ClosePlayer() {
	if err := closeSlot(&a.engine); err != nil {
		a.log.Warn("player teardown refused: " + err.Error())
	}
}

// closeSlot shuts the engine in slot down, then detaches it: a refused Shutdown
// (ErrShutdownFromSink) keeps it attached.
func closeSlot[T any, P interface {
	*T
	Shutdown() error
}](slot *atomic.Pointer[T]) error {
	e := slot.Load()
	if e == nil {
		return nil
	}
	if err := P(e).Shutdown(); err != nil {
		return err
	}
	slot.CompareAndSwap(e, nil)
	return nil
}

// Close is the end of the process (Go has no Drop): the player, if still open, is
// torn down, then the goroutines stop and the reporter gets `timeout` to send
// what it holds.
func (a *App) Close(timeout time.Duration) {
	a.ClosePlayer()
	a.cancel()
	if !a.Reporter.Close(timeout) {
		a.log.Warn("playback reports not confirmed before exit")
	}
}

// Context ends with Close.
func (a *App) Context() context.Context { return a.ctx }

// Identity is `request::commands::identity`: the open Emby session for
// Request (`EmbyService::exchange_identity`).
func (a *App) Identity() (request.Identity, error) {
	serverID, userID, token, err := a.Emby.ExchangeIdentity()
	if err != nil {
		return request.Identity{}, err
	}
	return request.Identity{ServerID: serverID, UserID: userID, EmbyToken: token}, nil
}
