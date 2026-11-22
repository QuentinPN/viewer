package main

import (
	"context"
	"encoding/json"

	"viewer/internal/brand"
	"viewer/internal/bridge"
)

// registerBrand wires `window_set_icon`: the logo (a LogoId of
// src/lib/brand/logos.ts: a SeasonId or "debug", checked against the embedded
// icons) becomes the icon of the main window, in the title bar and on its
// taskbar button. Wails' App.SetIcon does nothing on Windows (v3.1.0), hence
// WM_SETICON on the HWND. `app_is_debug` is the cheap build signal the frontend
// needs to pick the "debug" logo (system_report carries the same flag, but it
// probes the GPU and the disks).
func registerBrand(reg *bridge.Registry, debug bool, win Window) {
	reg.Register("app_is_debug", func(context.Context, json.RawMessage) (any, error) {
		return debug, nil
	})
	var icon brand.WindowIcon
	reg.Register("window_set_icon", arg1("window_set_icon", "id", func(_ context.Context, id string) (any, error) {
		return unit(icon.Apply(win.NativeWindow(), id))
	}))
}
