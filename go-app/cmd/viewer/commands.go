package main

import (
	"context"
	"maps"
	"slices"

	"viewer/internal/app"
	"viewer/internal/bridge"
	"viewer/internal/diagnostics"
	"viewer/internal/stremio"
	"viewer/internal/sysinfo"
	"viewer/internal/updater"
	"viewer/internal/request"
)

// registerCommands wires every command the frontend invokes (the names, arguments
// and results of the former Rust invoke_handler, plus the logo's app_is_debug and
// window_set_icon); commands_test.go pins the set.
// An invoke of any other name rejects with unknownCommand.
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

	// Réglages › Stremio and « Autres sources »: the 6 stremio_* commands. stremio_play
	// is in release builds too: the link it opens is Go's, never the UI's.
	stremioHandlers := stremio.Handlers(a.Stremio, a.Emby.ExternalRef)
	stremioHandlers["stremio_play"] = stremio.PlayHandler(a.Stremio, func(ctx context.Context, s stremio.Selected, resume bool) (any, error) {
		return stremioPlay(ctx, appPlayback{a}, stremioPick{s.ItemID, s.Target, s.Title, resume}, s.Stream.URL(), s.Stream.Headers())
	})
	for _, name := range slices.Sorted(maps.Keys(stremioHandlers)) {
		reg.Register(name, stremioHandlers[name])
	}

	// Réglages › À propos and the log folder.
	reg.Register("system_report", sysinfo.SystemReportHandler(a.Debug()))
	reg.Register("diagnostics_open_log_dir", diagnostics.OpenLogDirHandler(a.Paths().Logs, diagnostics.OpenInExplorer))

	// Updates, driven from Go only.
	reg.Register("app_update_pending", updater.PendingHandler(a.Updater))
	reg.Register("app_update_install", updater.InstallHandler(a.Updater))
	reg.Register("app_update_check", updater.CheckHandler(a.Updater, a.Debug()))
}
