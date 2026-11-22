package main

import "viewer/internal/bridge"

// registerCommands wires every ported command under its Tauri name
// (src-tauri/src/commands.rs). Each wave adds its own unit's commands here;
// none is ported yet, so every invoke rejects with unknownCommand.
func registerCommands(reg *bridge.Registry, emit bridge.Emitter) {
	_, _ = reg, emit
}
