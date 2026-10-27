mod commands;
pub mod emby;
pub mod player;
#[cfg(debug_assertions)]
mod spike;
mod video_surface;

use std::path::PathBuf;
use std::sync::{Arc, Mutex};

use tauri::{Emitter, Manager, RunEvent, WindowEvent};

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
            ..MpvConfig::default()
        };
        let emitter = app.clone();
        let engine = Arc::new(MpvEngine::new(
            &self.library,
            &config,
            Box::new(move |ev| {
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
    Ok(emby::session::EmbyService::new(
        store,
        path,
        device_name,
        env!("CARGO_PKG_VERSION").to_owned(),
    ))
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    let app = tauri::Builder::default()
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
                        window.state::<AppState>().close_player();
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
        ])
        .build(tauri::generate_context!())
        .expect("error while building Viewer");

    app.run(|_app, event| {
        if let RunEvent::Exit = event {
            log::info!("Viewer exiting");
        }
    });
}
