mod commands;
pub mod emby;
pub mod player;
#[cfg(debug_assertions)]
mod spike;
mod video_surface;

use std::path::PathBuf;
use std::sync::{Arc, Mutex};

use tauri::{Emitter, Manager, RunEvent, WindowEvent};

use emby::reporting::Reporter;
use player::mpv::{MpvConfig, MpvEngine};
use player::{PlayerEngine, PlayerError};
use video_surface::SurfaceMode;

pub const PLAYER_EVENT: &str = "player://event";

/// Owns the native player. The engine is created lazily on first playback and
/// torn down on close; teardown always runs off the UI thread (see ADR 0003).
pub struct AppState {
    pub player: Mutex<Option<Arc<MpvEngine>>>,
    pub mode: SurfaceMode,
    pub library: PathBuf,
    pub wid: Option<i64>,
    /// Emby playback reports (ADR 0008), fed with every player event.
    pub reporter: Reporter,
    app: tauri::AppHandle,
    #[cfg(windows)]
    _surface: Option<video_surface::ChildSurface>,
}

impl AppState {
    pub fn engine(&self) -> Result<Arc<MpvEngine>, PlayerError> {
        let app = &self.app;
        let mut guard = self.player.lock().unwrap_or_else(|p| p.into_inner());
        if let Some(engine) = guard.as_ref() {
            return Ok(Arc::clone(engine));
        }
        let config = MpvConfig {
            wid: self.wid,
            // Debug builds only: scripted UI tests (scripts/e2e) stay silent.
            silent: cfg!(debug_assertions) && std::env::var_os("VIEWER_E2E_SILENT").is_some(),
            ..MpvConfig::default()
        };
        let emitter = app.clone();
        let reporter = self.reporter.clone();
        let engine = Arc::new(MpvEngine::new(
            &self.library,
            &config,
            Box::new(move |ev| {
                reporter.player_event(&ev);
                let _ = emitter.emit(PLAYER_EVENT, &ev);
            }),
        )?);
        *guard = Some(Arc::clone(&engine));
        Ok(engine)
    }

    /// Detaches and destroys the engine. Must not be called on the UI thread:
    /// mpv's embedded window teardown messages the parent window.
    pub fn close_player(&self) {
        let engine = self.player.lock().unwrap_or_else(|p| p.into_inner()).take();
        if let Some(engine) = engine {
            engine.shutdown();
            // The event thread is gone, so notify the UI ourselves: whoever closed the
            // player (UI, scenario, window close), the overlay must return to idle.
            self.reporter.player_event(&player::PlayerEvent::Shutdown);
            let _ = self.app.emit(PLAYER_EVENT, player::PlayerEvent::Shutdown);
        }
    }
}

/// libmpv lookup order: `VIEWER_LIBMPV` (debug builds only), next to the executable,
/// then the repository's `vendor/mpv` in debug builds.
fn resolve_libmpv() -> PathBuf {
    #[cfg(debug_assertions)]
    if let Some(p) = std::env::var_os("VIEWER_LIBMPV") {
        return p.into();
    }
    let beside_exe = std::env::current_exe()
        .ok()
        .and_then(|e| e.parent().map(|d| d.join("libmpv-2.dll")))
        .filter(|p| p.is_file());
    #[cfg(debug_assertions)]
    let fallback = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../vendor/mpv/libmpv-2.dll");
    #[cfg(not(debug_assertions))]
    let fallback = PathBuf::from("libmpv-2.dll");
    beside_exe.unwrap_or(fallback)
}

/// Emby session service: token in the OS credential store, metadata in the app
/// config directory (ADR 0004).
fn emby_service(
    app: &tauri::App,
) -> Result<emby::session::EmbyService, Box<dyn std::error::Error>> {
    let path = app.path().app_config_dir()?.join("session.json");
    #[cfg(windows)]
    let store: Box<dyn emby::credentials::CredentialStore> =
        Box::new(emby::credentials::WindowsCredentialStore);
    #[cfg(not(windows))]
    let store: Box<dyn emby::credentials::CredentialStore> =
        Box::new(emby::credentials::MemoryCredentialStore::default());
    let device_name = std::env::var("COMPUTERNAME").unwrap_or_else(|_| "PC Windows".into());
    let service = emby::session::EmbyService::new(
        store,
        path,
        device_name,
        env!("CARGO_PKG_VERSION").to_owned(),
    );
    // Debug builds only: scripted UI tests (scripts/e2e) force a transcode with a
    // low limit, in bits per second.
    let e2e_bitrate = std::env::var("VIEWER_E2E_MAX_BITRATE")
        .ok()
        .and_then(|v| v.parse::<u64>().ok())
        .filter(|_| cfg!(debug_assertions));
    Ok(match e2e_bitrate {
        Some(bitrate) => service.with_max_streaming_bitrate(bitrate),
        None => service,
    })
}

/// Serves `emby-img` requests (ADR 0005): only validated artwork paths, fetched
/// with the session's auth header so the token never reaches the WebView.
async fn emby_image(
    app: &tauri::AppHandle,
    request: &tauri::http::Request<Vec<u8>>,
) -> tauri::http::Response<Vec<u8>> {
    use tauri::http::{Response, StatusCode, header};
    let reply = |status: StatusCode| {
        Response::builder()
            .status(status)
            .body(Vec::new())
            .unwrap_or_default()
    };
    let uri = request.uri();
    let Some(req) = emby::images::ImageRequest::parse(uri.path(), uri.query()) else {
        return reply(StatusCode::BAD_REQUEST);
    };
    match app.state::<emby::session::EmbyService>().image(&req).await {
        Ok((content_type, bytes)) => Response::builder()
            .header(header::CONTENT_TYPE, content_type)
            // The URL carries the image tag, so a given URL never changes content.
            .header(header::CACHE_CONTROL, "max-age=31536000, immutable")
            .body(bytes)
            .unwrap_or_default(),
        Err(e) => {
            log::debug!("artwork {} not served: {}", req.item_id, e.kind());
            reply(match e {
                emby::EmbyError::NotSignedIn | emby::EmbyError::SessionExpired => {
                    StatusCode::UNAUTHORIZED
                }
                emby::EmbyError::NotFound => StatusCode::NOT_FOUND,
                _ => StatusCode::BAD_GATEWAY,
            })
        }
    }
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
/// Debug builds only: `VIEWER_E2E_OFFSCREEN` opens the main window off-screen and
/// without activating it, so scripted UI tests (`scripts/e2e`) do not take the
/// user's foreground window or cover their desktop.
fn context() -> tauri::Context<tauri::Wry> {
    #[allow(unused_mut)]
    let mut context = tauri::generate_context!();
    #[cfg(debug_assertions)]
    if std::env::var_os("VIEWER_E2E_OFFSCREEN").is_some() {
        for window in &mut context.config_mut().app.windows {
            window.focus = false;
            window.x = Some(-20000.0);
            window.y = Some(-20000.0);
        }
    }
    context
}

pub fn run() {
    let app = tauri::Builder::default()
        .register_asynchronous_uri_scheme_protocol(
            emby::images::SCHEME,
            |ctx, request, responder| {
                let app = ctx.app_handle().clone();
                tauri::async_runtime::spawn(async move {
                    responder.respond(emby_image(&app, &request).await);
                });
            },
        )
        .setup(|app| {
            let window = app
                .get_webview_window("main")
                .ok_or("main window missing")?;
            let mode = SurfaceMode::from_env();

            #[cfg(windows)]
            let (surface, wid) = {
                let hwnd = window.hwnd()?.0;
                match mode {
                    SurfaceMode::Child => {
                        let s = video_surface::ChildSurface::create(hwnd)
                            .ok_or("video surface creation failed")?;
                        let wid = s.wid();
                        (Some(s), Some(wid))
                    }
                    SurfaceMode::Parent => (None, Some(hwnd as i64)),
                    SurfaceMode::Separate => (None, None),
                }
            };
            #[cfg(not(windows))]
            let wid = None;

            app.manage(emby_service(app)?);

            app.manage(AppState {
                player: Mutex::new(None),
                mode,
                library: resolve_libmpv(),
                wid,
                reporter: Reporter::spawn(app.handle().clone()),
                app: app.handle().clone(),
                #[cfg(windows)]
                _surface: surface,
            });

            #[cfg(debug_assertions)]
            spike::maybe_start(app.handle().clone());
            Ok(())
        })
        .on_window_event(|window, event| match event {
            #[cfg(windows)]
            WindowEvent::Resized(_) => {
                if let Some(s) = window.state::<AppState>()._surface.as_ref() {
                    s.fit_parent();
                }
            }
            WindowEvent::CloseRequested { api, .. } => {
                // Tear mpv down off the UI thread, so the embedded video window can be
                // destroyed while the UI thread keeps pumping messages. Then destroy the
                // window rather than calling `exit`: exiting with the WebView2 still
                // alive logs "Failed to unregister class Chrome_WidgetWin_0" (1412).
                let state = window.state::<AppState>();
                if state
                    .player
                    .lock()
                    .unwrap_or_else(|p| p.into_inner())
                    .is_some()
                {
                    api.prevent_close();
                    let window = window.clone();
                    std::thread::spawn(move || {
                        let state = window.state::<AppState>();
                        state.close_player();
                        // Let the stop report reach the server before the process ends.
                        if !state.reporter.flush(std::time::Duration::from_secs(3)) {
                            log::warn!("playback stop report not confirmed before exit");
                        }
                        if let Err(e) = window.destroy() {
                            log::warn!("window destroy failed: {e}");
                            window.app_handle().exit(0);
                        }
                    });
                }
            }
            _ => {}
        })
        .invoke_handler(tauri::generate_handler![
            commands::player_open,
            commands::player_command,
            commands::player_tracks,
            commands::player_diagnostics,
            commands::player_close,
            commands::window_set_fullscreen,
            commands::app_info,
            commands::emby_probe,
            commands::emby_sign_in,
            commands::emby_restore,
            commands::emby_sign_out,
            commands::emby_last_server,
            commands::emby_libraries,
            commands::emby_items,
            commands::emby_continue_watching,
            commands::emby_next_up,
            commands::emby_latest,
            commands::emby_search,
            commands::emby_item_detail,
            commands::emby_seasons,
            commands::emby_episodes,
            commands::emby_play,
        ])
        .build(context())
        .expect("error while building Viewer");

    app.run(|_app, event| {
        if let RunEvent::Exit = event {
            log::info!("Viewer exiting");
        }
    });
}
