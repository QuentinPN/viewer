//! Typed IPC surface. Commands are `async` so they run off the UI thread: libmpv
//! teardown with an embedded window must never block the window's message loop.

use serde::Serialize;
use tauri::{State, WebviewWindow};

use crate::AppState;
use crate::emby::EmbyError;
use crate::emby::models::{ItemPage, Library, ServerSummary, SignedInUser};
use crate::emby::session::EmbyService;
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
    /// Debug builds only: the Gate 1 scenario drives the player (src/spike.rs).
    pub spike: bool,
}

#[tauri::command]
pub async fn app_info(state: State<'_, AppState>) -> Result<AppInfo, ()> {
    Ok(AppInfo {
        version: env!("CARGO_PKG_VERSION"),
        surface_mode: state.mode,
        libmpv_found: state.library.is_file(),
        spike: cfg!(debug_assertions) && std::env::var_os("VIEWER_SPIKE_MEDIA").is_some(),
    })
}

// Emby (ADR 0004). Results carry user and server summaries, never the token.

#[tauri::command]
pub async fn emby_probe(
    emby: State<'_, EmbyService>,
    url: String,
) -> Result<ServerSummary, EmbyError> {
    emby.probe(&url).await
}

#[tauri::command]
pub async fn emby_sign_in(
    emby: State<'_, EmbyService>,
    url: String,
    username: String,
    password: String,
) -> Result<SignedInUser, EmbyError> {
    emby.sign_in(&url, &username, &password).await
}

#[tauri::command]
pub async fn emby_restore(emby: State<'_, EmbyService>) -> Result<Option<SignedInUser>, EmbyError> {
    emby.restore().await
}

#[tauri::command]
pub async fn emby_sign_out(emby: State<'_, EmbyService>) -> Result<(), EmbyError> {
    emby.sign_out().await
}

#[tauri::command]
pub async fn emby_last_server(emby: State<'_, EmbyService>) -> Result<Option<String>, ()> {
    Ok(emby.last_server_url())
}

#[tauri::command]
pub async fn emby_libraries(emby: State<'_, EmbyService>) -> Result<Vec<Library>, EmbyError> {
    emby.libraries().await
}

#[tauri::command]
pub async fn emby_items(
    emby: State<'_, EmbyService>,
    library_id: String,
    start_index: u32,
    limit: u32,
) -> Result<ItemPage, EmbyError> {
    emby.items(&library_id, start_index, limit).await
}
