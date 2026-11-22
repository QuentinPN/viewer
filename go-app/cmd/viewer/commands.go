package main

import (
	"slices"

	"viewer/internal/app"
	"viewer/internal/bridge"
	"viewer/internal/diagnostics"
	"viewer/internal/sysinfo"
	"viewer/internal/uisettings"
	"viewer/internal/updater"
	"viewer/internal/request"
)

// registerCommands wires every command of the Rust invoke_handler
// (src-tauri/src/lib.rs) under its Tauri name, with the same arguments and
// results (plus goOnlyCommands); commands_test.go checks the set against lib.rs. An invoke of any other
// name rejects with unknownCommand.
func registerCommands(reg *bridge.Registry, a *app.App, win Window) {
	registerPlayer(reg, a, win)
	registerEmby(reg, a)
	registerSync(reg, a)
	registerBrand(reg, a.Debug(), win)

	// Request: the 16 ur_* commands bound to the Emby session.
	handlers := request.Handlers(a.Request, a.Identity)
	names := make([]string, 0, len(handlers))
	for name := range handlers {
		names = append(names, name)
	}
	slices.Sort(names)
	for _, name := range names {
		reg.Register(name, handlers[name])
	}
	reg.Register("ur_open_trailer", request.OpenTrailerHandler(request.OpenInExplorer))

	// Réglages › À propos and the log folder.
	reg.Register("system_report", sysinfo.SystemReportHandler(a.Debug()))
	reg.Register("diagnostics_open_log_dir", diagnostics.OpenLogDirHandler(a.Paths().Logs, diagnostics.OpenInExplorer))

	// Updates, driven from Go only.
	reg.Register("app_update_pending", updater.PendingHandler(a.Updater))
	reg.Register("app_update_install", updater.InstallHandler(a.Updater))
	reg.Register("app_update_check", updater.CheckHandler(a.Updater, a.Debug()))

	// UI preferences handed over from the Tauri app (localStorage is per origin).
	reg.Register("ui_settings_export", arg1("ui_settings_export", "entries", uisettings.ExportHandler(a.Paths().Config)))
	reg.Register("ui_settings_import", uisettings.ImportHandler(a.Paths().Config))
}
