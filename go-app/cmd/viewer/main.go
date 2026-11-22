// Command viewer is the Go + Wails v3 build of Viewer, the
// counterpart of src-tauri/src/lib.rs `run`. It serves the shared Svelte frontend,
// built by `npm run build:wails` into frontend/dist, and answers its invoke calls
// through internal/bridge.
//
// Window operations (minimize, maximize, close, resize events) run in the
// frontend through the Wails runtime (src/lib/native/wails.ts); fullscreen is the
// window_set_fullscreen command.
package main

import (
	"embed"
	"io/fs"
	"log"
	"log/slog"
	"net/http"
	"os"
	"sync/atomic"
	"time"

	"github.com/wailsapp/wails/v3/pkg/application"
	"github.com/wailsapp/wails/v3/pkg/events"

	"viewer/internal/app"
	"viewer/internal/bridge"
	"viewer/internal/diagnostics"
	"viewer/internal/emby"
)

// frontend holds frontend/dist once built. frontend/.gitkeep is committed so
// the pattern always matches on a fresh clone (go vet / go build stay green);
// dist itself is gitignored and emptied by Vite on every build.
//
//go:embed all:frontend
var frontend embed.FS

func main() {
	assets, err := fs.Sub(frontend, "frontend/dist")
	if err != nil {
		log.Fatal(err)
	}
	if _, err := fs.Stat(assets, "index.html"); err != nil {
		log.Fatal("interface absente : lancer `npm run build:wails` avant `go build`")
	}

	debug := app.DebugBuild()
	paths, err := app.DefaultPaths(debug)
	if err != nil {
		log.Fatal(err)
	}
	// First, so everything after it is logged.
	logger, _ := diagnostics.Init(paths.Logs, debug, app.Version())
	if logger != nil {
		defer func() { _ = logger.Close() }()
	}
	// Debug builds only: scripted UI tests (scripts/e2e) check that a panic reaches
	// the log, redacted.
	if _, ok := os.LookupEnv("VIEWER_E2E_PANIC"); debug && ok && logger != nil {
		go func() {
			defer logger.RecoverAndRepanic()
			panic("e2e panic, api_key=e2e-secret-value")
		}()
	}

	// Deferred by the app's goroutines: a panic there reaches the log.
	var recoverPanic func()
	if logger != nil {
		recoverPanic = logger.RecoverAndRepanic
	}

	// Set once the Wails app and window exist, read from any goroutine.
	var wailsApp atomic.Pointer[application.App]
	var mainWin atomic.Pointer[application.WebviewWindow]
	emit := func(name string, payload any) {
		if w := wailsApp.Load(); w != nil {
			w.Event.Emit(name, payload)
		}
	}
	// Before any service: every ImageRef must point at this app's origin.
	useAppImageOrigins()
	state, err := app.New(app.Options{
		Debug:   debug,
		Version: app.Version(),
		Paths:   paths,
		Store:   emby.NewWindowsCredentialStore(app.VaultServiceFor(debug)),
		Libmpv:  app.ResolveLibmpv(debug),
		Emit:    emit,
		Recover: recoverPanic,
		// Parent mode: mpv renders into the main window's HWND, under the
		// transparent WebView2.
		WID: func() uintptr {
			if w := mainWin.Load(); w != nil {
				return uintptr(w.NativeWindow())
			}
			return 0
		},
	})
	if err != nil {
		slog.Error("Viewer setup failed: " + err.Error())
		log.Fatal(err)
	}

	// Debug builds only: the DevTools port of the scripted UI tests (scripted UI tests never take over the desktop),
	// from VIEWER_E2E_BROWSER_ARGS (Wails blanks WebView2's own variable).
	browserArgs := app.BrowserArgsFor(debug, os.Getenv(app.BrowserArgsEnv))

	reg := bridge.NewRegistry()
	wails := application.New(application.Options{
		Name: app.Title,
		Services: []application.Service{
			application.NewServiceWithOptions(bridge.New(reg), application.ServiceOptions{
				MarshalError: bridge.MarshalError,
			}),
		},
		Assets: application.AssetOptions{
			Handler: application.AssetFileServerFS(assets),
			Middleware: func(next http.Handler) http.Handler {
				return withCSP(withProtocols(state, next))
			},
		},
		Windows: application.WindowsOptions{
			// Tauri's WebView2 user data folder (app_local_data_dir): WebView2 keeps
			// its profile in EBWebView under it, like the Tauri build.
			WebviewUserDataPath:   paths.LocalData,
			DisabledFeatures:      browserArgs.DisabledFeatures,
			AdditionalBrowserArgs: browserArgs.Args,
		},
		// No single instance: the Tauri build has no single-instance plugin.
	})
	wailsApp.Store(wails)

	// Same window as src-tauri/tauri.conf.json: frameless and transparent
	// (mpv renders into this HWND under the WebView), maximized at start,
	// 1280x720 restored, 640x360 minimum.
	opts := application.WebviewWindowOptions{
		Name:           "main",
		Title:          app.TitleFor(debug),
		Width:          1280,
		Height:         720,
		MinWidth:       640,
		MinHeight:      360,
		StartState:     application.WindowStateMaximised,
		Frameless:      true,
		BackgroundType: application.BackgroundTypeTransparent,
		URL:            "/",
	}
	// Debug builds only: VIEWER_E2E_OFFSCREEN opens the main window off-screen, so
	// scripted UI tests (scripts/e2e) do not cover the user's desktop. Not
	// maximized: a maximized window would come back to the nearest monitor.
	if _, ok := os.LookupEnv("VIEWER_E2E_OFFSCREEN"); debug && ok {
		opts.InitialPosition = application.WindowXY
		opts.X, opts.Y = -20000, -20000
		opts.StartState = application.WindowStateNormal
	}
	win := wails.Window.NewWithOptions(opts)
	mainWin.Store(win)
	registerCommands(reg, state, wailsWindow{win})

	// Window close during playback: cancel it, tear mpv down off the UI thread,
	// tell a linked TV, let the stop report reach the server, then
	// close the window again (the hook lets it through once the player is gone):
	// Wails quits on the last window closed. app.Quit() with WebView2 alive logs
	// "Failed to unregister class Chrome_WidgetWin_0" (1412), see gate1-go.
	win.RegisterHook(events.Common.WindowClosing, func(e *application.WindowEvent) {
		if !state.HasPlayer() {
			return
		}
		e.Cancel()
		state.Go(func() {
			state.ClosePlayer()
			// A linked TV stops too (`bye stopped`, best effort).
			if !state.Sync.StopBlocking(2 * time.Second) {
				slog.Warn("linked TV not told before exit")
			}
			// Let the stop report reach the server before the process ends.
			if !state.Reporter.Flush(3 * time.Second) {
				slog.Warn("playback stop report not confirmed before exit")
			}
			win.Close()
		})
	})

	state.Start()
	runErr := wails.Run()
	slog.Info("Viewer exiting")
	state.Close(3 * time.Second)
	if runErr != nil {
		slog.Error("Viewer stopped: " + runErr.Error())
		log.Fatal(runErr)
	}
}

// wailsWindow is the Window of window_set_fullscreen.
type wailsWindow struct{ w *application.WebviewWindow }

func (w wailsWindow) IsFullscreen() bool { return w.w.IsFullscreen() }
func (w wailsWindow) IsMaximised() bool  { return w.w.IsMaximised() }
func (w wailsWindow) Maximise()          { w.w.Maximise() }
func (w wailsWindow) UnMaximise()        { w.w.UnMaximise() }
func (w wailsWindow) SetFullscreen(on bool) {
	if on {
		w.w.Fullscreen()
	} else {
		w.w.UnFullscreen()
	}
}
