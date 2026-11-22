package main

import (
	"context"

	"viewer/internal/brand"
	"viewer/internal/bridge"
)

// goOnlyCommands are the commands the Rust invoke_handler does not have: the
// Tauri build never calls them (the frontend only does on the Wails backend).
// TestRegisteredCommandsAreTheRustInvokeHandler allows exactly these.
var goOnlyCommands = []string{"window_set_icon"}

// registerBrand wires `window_set_icon`: the logo (a SeasonId of
// src/lib/brand/season.ts, checked against the embedded icons) becomes the icon
// of the main window, in the title bar and on its taskbar button. Wails'
// App.SetIcon does nothing on Windows (v3.1.0), hence WM_SETICON on the HWND.
func registerBrand(reg *bridge.Registry, win Window) {
	var icon brand.WindowIcon
	reg.Register("window_set_icon", arg1("window_set_icon", "id", func(_ context.Context, id string) (any, error) {
		return unit(icon.Apply(win.NativeWindow(), id))
	}))
}
