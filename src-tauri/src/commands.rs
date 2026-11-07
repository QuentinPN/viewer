//! Typed IPC surface. Commands are `async` so they run off the UI thread: libmpv
//! teardown with an embedded window must never block the window's message loop.

use std::sync::atomic::{AtomicBool, Ordering};

use serde::Serialize;
use tauri::{State, WebviewWindow};

use crate::AppState;
use crate::emby::EmbyError;
use crate::emby::artwork_cache::{ArtworkCacheCleared, ArtworkCacheStats};
use crate::emby::chapters::Chapter;
use crate::emby::models::{
    EpisodePage, HeroItem, ItemDetail, ItemPage, ItemsOptions, Library, MediaItem, PlaybackStart,
    SavedServer, SearchResults, Season, ServerSummary, SessionStatus, SignedInUser, WatchItem,
};
use crate::emby::reporting::ActivePlayback;
use crate::emby::session::EmbyService;
use crate::player::{
    MediaSource, PlaybackRequest, PlayerCommand, PlayerDiagnostics, PlayerEngine, PlayerError,
    PlayerEvent, Track,
};
use crate::segments::{Segment, SegmentService};
use crate::video_surface::SurfaceMode;

#[tauri::command]
pub async fn player_open(
    state: State<'_, AppState>,
    request: PlaybackRequest,
) -> Result<(), PlayerError> {
    // Test bench of the Gate 1 prototype: the WebView picks any URL and headers.
    // Emby playback goes through `emby_play`, which picks them in Rust.
    if !cfg!(debug_assertions) {
        return Err(PlayerError::InvalidSource(
            "prototype indisponible dans cette version".into(),
        ));
    }
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
    // tao keeps WS_MAXIMIZE when a maximized window goes fullscreen: the client
    // area stays at the work area, and the transparent strip left at the bottom
    // shows the taskbar. Fullscreen starts from a restored window, and leaving
    // it maximizes the window again.
    static WAS_MAXIMIZED: AtomicBool = AtomicBool::new(false);
    let err = |e: tauri::Error| e.to_string();
    if fullscreen && !window.is_fullscreen().map_err(err)? {
        let maximized = window.is_maximized().map_err(err)?;
        WAS_MAXIMIZED.store(maximized, Ordering::Relaxed);
        if maximized {
            window.unmaximize().map_err(err)?;
        }
    }
    window.set_fullscreen(fullscreen).map_err(err)?;
    if !fullscreen && WAS_MAXIMIZED.swap(false, Ordering::Relaxed) {
        window.maximize().map_err(err)?;
    }
    window.is_fullscreen().map_err(err)
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
pub async fn emby_restore(emby: State<'_, EmbyService>) -> Result<SessionStatus, EmbyError> {
    emby.restore().await
}

/// Sets the open session aside without revoking it (ADR 0012).
#[tauri::command]
pub async fn emby_change_server(emby: State<'_, EmbyService>) -> Result<(), EmbyError> {
    emby.change_server()
}

#[tauri::command]
pub async fn emby_saved_sessions(
    emby: State<'_, EmbyService>,
) -> Result<Vec<SavedServer>, EmbyError> {
    Ok(emby.saved_sessions())
}

#[tauri::command]
pub async fn emby_resume_saved(
    emby: State<'_, EmbyService>,
    server_id: String,
    user_id: String,
) -> Result<SessionStatus, EmbyError> {
    emby.resume_saved(&server_id, &user_id).await
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
    options: Option<ItemsOptions>,
) -> Result<ItemPage, EmbyError> {
    emby.items(
        &library_id,
        start_index,
        limit,
        &options.unwrap_or_default(),
    )
    .await
}

/// Artwork cache figures (ADR 0019), `None` when the app has no cache directory.
#[tauri::command]
pub async fn artwork_cache_stats(
    emby: State<'_, EmbyService>,
) -> Result<Option<ArtworkCacheStats>, EmbyError> {
    Ok(emby.artwork_stats().await)
}

/// Empties the artwork cache (ADR 0021): images and bytes removed, `None` when the
/// app has no cache directory.
#[tauri::command]
pub async fn artwork_cache_clear(
    emby: State<'_, EmbyService>,
) -> Result<Option<ArtworkCacheCleared>, EmbyError> {
    Ok(emby.clear_artwork().await)
}

#[tauri::command]
pub async fn emby_genres(
    emby: State<'_, EmbyService>,
    library_id: String,
) -> Result<Vec<String>, EmbyError> {
    emby.genres(&library_id).await
}

#[tauri::command]
pub async fn emby_continue_watching(
    emby: State<'_, EmbyService>,
) -> Result<Vec<WatchItem>, EmbyError> {
    emby.continue_watching().await
}

#[tauri::command]
pub async fn emby_next_up(emby: State<'_, EmbyService>) -> Result<Vec<WatchItem>, EmbyError> {
    emby.next_up().await
}

#[tauri::command]
pub async fn emby_search(
    emby: State<'_, EmbyService>,
    query: String,
) -> Result<SearchResults, EmbyError> {
    emby.search(&query).await
}

#[tauri::command]
pub async fn emby_latest(
    emby: State<'_, EmbyService>,
    library_id: String,
) -> Result<Vec<MediaItem>, EmbyError> {
    emby.latest(&library_id).await
}

#[tauri::command]
pub async fn emby_hero(emby: State<'_, EmbyService>) -> Result<Vec<HeroItem>, EmbyError> {
    emby.hero().await
}

#[tauri::command]
pub async fn emby_favorites(emby: State<'_, EmbyService>) -> Result<Vec<MediaItem>, EmbyError> {
    emby.favorites().await
}

/// Returns the favorite state the server reports after the change.
#[tauri::command]
pub async fn emby_set_favorite(
    emby: State<'_, EmbyService>,
    item_id: String,
    favorite: bool,
) -> Result<bool, EmbyError> {
    emby.set_favorite(&item_id, favorite).await
}

#[tauri::command]
pub async fn emby_item_detail(
    emby: State<'_, EmbyService>,
    item_id: String,
) -> Result<ItemDetail, EmbyError> {
    emby.item_detail(&item_id).await
}

/// Intro and credits of an item (ADR 0030). `external: false` keeps everything on
/// the Emby server. A failure of an external source is silent: fewer segments.
#[tauri::command]
pub async fn emby_segments(
    emby: State<'_, EmbyService>,
    segments: State<'_, SegmentService>,
    item_id: String,
    external: bool,
) -> Result<Vec<Segment>, EmbyError> {
    let key = emby.segments_key(&item_id, external)?;
    if let Some(found) = segments.cached(&key) {
        return Ok(found);
    }
    let (markers, query) = emby.segment_query(&item_id).await?;
    Ok(segments
        .lookup(key, markers, query.as_ref(), external)
        .await)
}

/// Chapters of a film or episode, in seconds of the file (ADR 0034).
#[tauri::command]
pub async fn emby_chapters(
    emby: State<'_, EmbyService>,
    item_id: String,
) -> Result<Vec<Chapter>, EmbyError> {
    emby.chapters(&item_id).await
}

#[tauri::command]
pub async fn emby_seasons(
    emby: State<'_, EmbyService>,
    series_id: String,
) -> Result<Vec<Season>, EmbyError> {
    emby.seasons(&series_id).await
}

#[tauri::command]
pub async fn emby_episodes(
    emby: State<'_, EmbyService>,
    series_id: String,
    season_id: String,
    start_index: u32,
    limit: u32,
) -> Result<EpisodePage, EmbyError> {
    emby.episodes(&series_id, &season_id, start_index, limit)
        .await
}

/// Plays a film or episode from the server (ADR 0007): PlaybackInfo, choice of the
/// source, then the player. The UI gets the method only, never the URL or token.
/// With `resume`, starts where the server says the user stopped; the playback is
/// then reported to the server (ADR 0008).
#[tauri::command]
pub async fn emby_play(
    state: State<'_, AppState>,
    emby: State<'_, EmbyService>,
    item_id: String,
    resume: bool,
    transcode_fallback: Option<bool>,
) -> Result<PlaybackStart, EmbyError> {
    state.reporter.resend_stop().await;
    let (plan, http_headers) = if transcode_fallback == Some(true) {
        emby.prepare_transcode(&item_id, resume).await?
    } else {
        emby.prepare_playback(&item_id, resume).await?
    };
    let start_seconds = (plan.start_ticks > 0).then(|| plan.start_ticks as f64 / 10_000_000.0);
    state.reporter.begin(ActivePlayback::new(&item_id, &plan));
    let request = PlaybackRequest {
        source: plan.url,
        http_headers,
        start_seconds,
        subtitles: plan.subtitles,
    };
    let player = |e: PlayerError| {
        // Nothing was loaded: drop the announced playback, nothing is reported.
        state.reporter.player_event(&PlayerEvent::Shutdown);
        EmbyError::Player(e.to_string())
    };
    let source = MediaSource::parse(&request.source).map_err(player)?;
    state
        .engine()
        .map_err(player)?
        .load(&request, &source)
        .map_err(player)?;
    Ok(PlaybackStart {
        item_id,
        resume,
        method: plan.method,
        container: plan.container,
        transcode_reasons: plan.transcode_reasons,
        title: plan.title,
    })
}
