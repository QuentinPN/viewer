package main

import (
	"viewer/internal/bridge"
	"viewer/internal/request"
)

// registerCommands wires every ported command under its Tauri name
// (src-tauri/src/commands.rs). Each wave adds its own unit's commands here; an
// invoke of any other command rejects with unknownCommand.
func registerCommands(reg *bridge.Registry, emit bridge.Emitter) {
	_ = emit

	// --- request (wave 6) -------------------------------------------------
	// ur_open_trailer needs no session. The 16 other ur_* commands are ready in
	// request.Handlers(svc, identity) and wait for the Emby session service
	// (wave 4: EmbyService.exchange_identity) and the app-shell choices (vault
	// service name, config directory, version): see go-app/MAP.md.
	reg.Register("ur_open_trailer", request.OpenTrailerHandler(request.OpenInExplorer))
	// --- end request ------------------------------------------------------
}
