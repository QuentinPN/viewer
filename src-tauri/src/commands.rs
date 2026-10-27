//! Typed IPC surface. Commands are `async` so they run off the UI thread: libmpv
//! teardown with an embedded window must never block the window's message loop.

use serde::Serialize;
use tauri::{State, WebviewWindow};

use crate::AppState;
use crate::player::{
    MediaSource, PlaybackRequest, PlayerCommand, PlayerDiagnostics, PlayerEngine, PlayerError,
    Track,
};
use crate::video_surface::SurfaceMode;

#[tauri::command]
pub async fn player_open(
    state: State<'_, AppState>,
    request: PlaybackRequest,
) -> Result<(), PlayerError> {
    let source = MediaSource::parse(&request.source)?;
    state.engine()?.load(&request, &source)
}

#[tauri::command]
pub async fn player_command(
    state: State<'_, AppState>,
    command: PlayerCommand,
) -> Result<(), PlayerError> {
    let engine = state
        .player
        .lock()
        .unwrap_or_else(|p| p.into_inner())
        .clone()
        .ok_or(PlayerError::NotOpen)?;
    engine.command(&command)
}

#[tauri::command]
pub async fn player_tracks(state: State<'_, AppState>) -> Result<Vec<Track>, PlayerError> {
    let engine = state
        .player
        .lock()
        .unwrap_or_else(|p| p.into_inner())
        .clone()
        .ok_or(PlayerError::NotOpen)?;
    engine.tracks()
}

#[tauri::command]
pub async fn player_diagnostics(
    state: State<'_, AppState>,
) -> Result<PlayerDiagnostics, PlayerError> {
    let engine = state
        .player
        .lock()
        .unwrap_or_else(|p| p.into_inner())
        .clone()
        .ok_or(PlayerError::NotOpen)?;
    Ok(engine.diagnostics())
}

#[tauri::command]
pub async fn player_close(state: State<'_, AppState>) -> Result<(), PlayerError> {
    state.close_player();
    Ok(())
}

#[tauri::command]
pub async fn window_set_fullscreen(
    window: WebviewWindow,
    fullscreen: bool,
) -> Result<bool, String> {
    window
        .set_fullscreen(fullscreen)
        .map_err(|e| e.to_string())?;
    window.is_fullscreen().map_err(|e| e.to_string())
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct AppInfo {
    pub version: &'static str,
    pub surface_mode: SurfaceMode,
    pub libmpv_found: bool,
}

#[tauri::command]
pub async fn app_info(state: State<'_, AppState>) -> Result<AppInfo, ()> {
    Ok(AppInfo {
        version: env!("CARGO_PKG_VERSION"),
        surface_mode: state.mode,
        libmpv_found: state.library.is_file(),
    })
}
