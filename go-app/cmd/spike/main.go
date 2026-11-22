// Command spike is the Go + Wails v3 playback spike for ADR 0128: it replays
// the Gate 1 checks of ADR 0003 (libmpv embedded in the main window HWND under
// a transparent WebView) without cgo.
//
//	spike -gen <dir>                            generate synthetic test media with libmpv
//	spike -selftest -media <path|url> -out <dir> automated scenario (see selftest.go)
//	spike -media <path|url>                     interactive: play and stay open
package main

import (
	"embed"
	"flag"
	"fmt"
	"io/fs"
	"log"
	"os"
	"path/filepath"
	"time"

	"github.com/wailsapp/wails/v3/pkg/application"
	"github.com/wailsapp/wails/v3/pkg/events"

	"viewer/internal/mpv"
)

//go:embed frontend
var frontend embed.FS

func main() {
	var (
		mpvPath  = flag.String("mpv", "", "path to libmpv-2.dll (default: vendor/mpv next to the repo, or next to the exe)")
		genDir   = flag.String("gen", "", "generate synthetic test media into this directory and exit")
		selftest = flag.Bool("selftest", false, "run the automated Gate 1 scenario")
		media    = flag.String("media", "", "media path or http URL")
		out      = flag.String("out", "", "selftest output directory")
		subFile  = flag.String("sub", "", "external subtitle file (default: <media dir>/spike.ass when local)")
		audio2   = flag.String("audio2", "", "external second audio track (default: <media dir>/audio2.mka when local)")
	)
	flag.Parse()

	dll, err := resolveMpv(*mpvPath)
	if err != nil {
		log.Fatal(err)
	}
	api, err := mpv.Load(dll)
	if err != nil {
		log.Fatal(err)
	}

	if *genDir != "" {
		if err := generate(api, *genDir); err != nil {
			log.Fatal(err)
		}
		return
	}
	if *selftest && (*media == "" || *out == "") {
		log.Fatal("-selftest needs -media and -out")
	}

	assets, err := fs.Sub(frontend, "frontend")
	if err != nil {
		log.Fatal(err)
	}
	player := &Player{api: api, tdDone: make(chan struct{})}
	app := application.New(application.Options{
		Name:     "Viewer spike",
		Services: []application.Service{application.NewService(player)},
		Assets:   application.AssetOptions{Handler: application.AssetFileServerFS(assets)},
	})
	player.app = app

	win := app.Window.NewWithOptions(application.WebviewWindowOptions{
		Name:           "main",
		Title:          "Viewer spike Go",
		Width:          1280,
		Height:         720,
		Frameless:      true,
		BackgroundType: application.BackgroundTypeTransparent,
		URL:            "/",
	})

	cfg := scenario{
		media:  *media,
		out:    *out,
		sub:    *subFile,
		audio2: *audio2,
		dll:    dll,
		api:    api,
	}
	go func() {
		hwnd := waitHWND(win, 15*time.Second)
		if hwnd == 0 {
			log.Print("window handle never became available")
			app.Quit()
			return
		}
		if *selftest {
			runSelftest(app, win, player, hwnd, cfg)
			return
		}
		if *media != "" {
			if _, err := player.start(hwnd, externalTracks(cfg)); err != nil {
				log.Print(err)
				return
			}
			if err := player.Open(*media); err != nil {
				log.Print(err)
			}
		}
	}()
	// Window close with an active player: cancel it, tear mpv down off the UI
	// thread (ADR 0003), then close the window again (the hook lets it through
	// once the player is gone) so Wails quits on the last window closed:
	// app.Quit() made WebView2 log "Error = 1412" in the self-test.
	win.RegisterHook(events.Common.WindowClosing, func(e *application.WindowEvent) {
		if player.handle() == nil {
			return
		}
		e.Cancel()
		go func() {
			player.teardown()
			win.Close()
		}()
	})

	if err := app.Run(); err != nil {
		log.Fatal(err)
	}
}

// waitHWND polls until Wails has created the native window.
func waitHWND(w *application.WebviewWindow, timeout time.Duration) uintptr {
	deadline := time.Now().Add(timeout)
	for time.Now().Before(deadline) {
		if p := w.NativeWindow(); p != nil {
			return uintptr(p)
		}
		time.Sleep(50 * time.Millisecond)
	}
	return 0
}

// resolveMpv finds libmpv-2.dll: explicit flag, next to the exe, or the repo's
// vendor/mpv (gitignored) found by walking up from the working directory.
func resolveMpv(flagPath string) (string, error) {
	if flagPath != "" {
		return filepath.Abs(flagPath)
	}
	if exe, err := os.Executable(); err == nil {
		if p := filepath.Join(filepath.Dir(exe), "libmpv-2.dll"); fileExists(p) {
			return p, nil
		}
	}
	dir, _ := os.Getwd()
	for range 6 {
		if p := filepath.Join(dir, "vendor", "mpv", "libmpv-2.dll"); fileExists(p) {
			return p, nil
		}
		dir = filepath.Dir(dir)
	}
	return "", fmt.Errorf("libmpv-2.dll introuvable : passer -mpv")
}

func fileExists(p string) bool {
	st, err := os.Stat(p)
	return err == nil && !st.IsDir()
}
