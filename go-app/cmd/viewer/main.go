// Command viewer is the Go + Wails v3 build of Viewer (ADR 0128). It
// serves the shared Svelte frontend, built by `npm run build:wails` into
// frontend/dist, and answers its invoke calls through internal/bridge.
//
// Window operations (minimize, maximize, close, resize events) run in the
// frontend through the Wails runtime (src/lib/native/wails.ts): no Go command.
package main

import (
	"embed"
	"io/fs"
	"log"

	"github.com/wailsapp/wails/v3/pkg/application"

	"viewer/internal/bridge"
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

	reg := bridge.NewRegistry()
	var win *application.WebviewWindow
	app := application.New(application.Options{
		Name: "Viewer",
		Services: []application.Service{
			application.NewServiceWithOptions(bridge.New(reg), application.ServiceOptions{
				MarshalError: bridge.MarshalError,
			}),
		},
		Assets: application.AssetOptions{Handler: application.AssetFileServerFS(assets), Middleware: withCSP},
		// A second launch exits and brings the running window forward.
		SingleInstance: &application.SingleInstanceOptions{
			UniqueID: "io.github.quentinpn.viewer",
			OnSecondInstanceLaunch: func(application.SecondInstanceData) {
				if win == nil {
					return
				}
				if win.IsMinimised() {
					win.UnMinimise()
				}
				win.Show()
				win.Focus()
			},
		},
	})
	registerCommands(reg, wailsEmitter{app: app})

	// Same window as src-tauri/tauri.conf.json: frameless and transparent
	// (ADR 0003: mpv renders into this HWND under the WebView), maximized at
	// start, 1280x720 restored, 640x360 minimum.
	win = app.Window.NewWithOptions(application.WebviewWindowOptions{
		Name:           "main",
		Title:          "Viewer",
		Width:          1280,
		Height:         720,
		MinWidth:       640,
		MinHeight:      360,
		StartState:     application.WindowStateMaximised,
		Frameless:      true,
		BackgroundType: application.BackgroundTypeTransparent,
		URL:            "/",
	})

	if err := app.Run(); err != nil {
		log.Fatal(err)
	}
}

// wailsEmitter is the bridge.Emitter of the app: Tauri's app.emit(name,
// payload) becomes a Wails event whose data is the payload.
type wailsEmitter struct{ app *application.App }

func (e wailsEmitter) Emit(name string, payload any) { e.app.Event.Emit(name, payload) }
