package main

import (
	"context"
	"sync/atomic"

	"viewer/internal/app"
	"viewer/internal/bridge"
	"viewer/internal/player"
)

// Window is what `window_set_fullscreen` drives: the main Wails window
// (wailsWindow), or a fake in tests.
type Window interface {
	IsFullscreen() bool
	IsMaximised() bool
	SetFullscreen(on bool)
	Maximise()
	UnMaximise()
}

// appInfo is `AppInfo` (camelCase).
type appInfo struct {
	Version     string             `json:"version"`
	SurfaceMode player.SurfaceMode `json:"surfaceMode"`
	LibmpvFound bool               `json:"libmpvFound"`
	// Spike: the Gate 1 scenario of the Rust debug build (src/spike.rs) is not
	// part of the Go app, so the bench never starts here.
	Spike bool `json:"spike"`
}

// Player and window commands of commands.rs. Commands run on Wails call
// goroutines, never on the UI thread: libmpv teardown with an embedded window
// must not block the window's message loop (ADR 0003).
func registerPlayer(reg *bridge.Registry, a *app.App, win Window) {
	// Test bench of the Gate 1 prototype: the WebView picks any URL and headers.
	// Emby playback goes through emby_play, which picks them natively.
	reg.Register("player_open", arg1("player_open", "request", func(_ context.Context, request player.PlaybackRequest) (any, error) {
		if !a.Debug() {
			return nil, &player.PlayerError{Kind: player.InvalidSource, Detail: "prototype indisponible dans cette version"}
		}
		source, err := player.ParseSource(request.Source)
		if err != nil {
			return nil, err
		}
		engine, err := a.Engine()
		if err != nil {
			return nil, err
		}
		return unit(engine.Load(&request, source))
	}))
	reg.Register("player_command", arg1("player_command", "command", func(_ context.Context, command player.PlayerCommand) (any, error) {
		engine := a.Player()
		if engine == nil {
			return nil, player.ErrNotOpen
		}
		return unit(engine.Command(command))
	}))
	reg.Register("player_tracks", noArgs(func(context.Context) (any, error) {
		engine := a.Player()
		if engine == nil {
			return nil, player.ErrNotOpen
		}
		return result(engine.Tracks())
	}))
	reg.Register("player_diagnostics", noArgs(func(context.Context) (any, error) {
		engine := a.Player()
		if engine == nil {
			return nil, player.ErrNotOpen
		}
		return engine.Diagnostics(), nil
	}))
	reg.Register("player_close", noArgs(func(context.Context) (any, error) {
		a.ClosePlayer()
		return nil, nil
	}))

	// A maximized window that goes fullscreen keeps the work area as client area
	// on tao (the taskbar shows through the transparent strip): fullscreen starts
	// from a restored window, and leaving it maximizes the window again.
	var wasMaximized atomic.Bool
	reg.Register("window_set_fullscreen", arg1("window_set_fullscreen", "fullscreen", func(_ context.Context, fullscreen bool) (any, error) {
		if fullscreen && !win.IsFullscreen() {
			maximized := win.IsMaximised()
			wasMaximized.Store(maximized)
			if maximized {
				win.UnMaximise()
			}
		}
		win.SetFullscreen(fullscreen)
		if !fullscreen && wasMaximized.Swap(false) {
			win.Maximise()
		}
		return win.IsFullscreen(), nil
	}))
	reg.Register("app_info", noArgs(func(context.Context) (any, error) {
		return appInfo{
			Version:     a.Version(),
			SurfaceMode: player.SurfaceParent,
			LibmpvFound: app.IsFile(a.Libmpv()),
		}, nil
	}))
}
