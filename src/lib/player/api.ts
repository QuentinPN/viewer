import { invoke } from "@tauri-apps/api/core";
import { listen, type UnlistenFn } from "@tauri-apps/api/event";
import type {
  AppInfo,
  PlaybackRequest,
  PlayerCommand,
  PlayerDiagnostics,
  PlayerEvent,
  Track,
} from "./types";

// Thin typed wrapper over the Tauri commands declared in src-tauri/src/commands.rs.

export const PLAYER_EVENT = "player://event";

export const playerApi = {
  open: (request: PlaybackRequest) => invoke<void>("player_open", { request }),
  command: (command: PlayerCommand) =>
    invoke<void>("player_command", { command }),
  tracks: () => invoke<Track[]>("player_tracks"),
  diagnostics: () => invoke<PlayerDiagnostics>("player_diagnostics"),
  close: () => invoke<void>("player_close"),
  setFullscreen: (fullscreen: boolean) =>
    invoke<boolean>("window_set_fullscreen", { fullscreen }),
  appInfo: () => invoke<AppInfo>("app_info"),
  onEvent: (handler: (event: PlayerEvent) => void): Promise<UnlistenFn> =>
    listen<PlayerEvent>(PLAYER_EVENT, (e) => handler(e.payload)),
};
