//! libmpv implementation of [`PlayerEngine`].
//!
//! Threading model: libmpv's client API is thread-safe except `mpv_wait_event`,
//! which is only ever called from the dedicated event thread owned here. Shutdown
//! stops that thread (flag + `mpv_wakeup`) *before* `mpv_terminate_destroy`, so the
//! handle is never destroyed while the event loop still uses it.

use std::ffi::{CStr, CString, c_char, c_int};
use std::path::Path;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::thread::JoinHandle;

use super::ffi::{self, MpvApi, MpvHandle};
use super::{
    EndReason, MediaSource, PlaybackRequest, PlayerCommand, PlayerDiagnostics, PlayerEngine,
    PlayerError, PlayerEvent, Track, TrackKind,
};

pub type EventSink = Box<dyn Fn(PlayerEvent) + Send + Sync>;

#[derive(Debug, Clone)]
pub struct MpvConfig {
    /// Native window handle (HWND on Windows) mpv renders into. `None` + `headless`
    /// is used by tests; `None` alone lets mpv open its own window.
    pub wid: Option<i64>,
    /// `vo=null` / `ao=null`: decode without presenting (CI and engine tests).
    pub headless: bool,
    /// mpv `hwdec` value. `auto-safe` only enables decoders mpv considers reliable.
    pub hwdec: String,
}

impl Default for MpvConfig {
    fn default() -> Self {
        Self {
            wid: None,
            headless: false,
            hwdec: "auto-safe".into(),
        }
    }
}

struct RawHandle(*mut MpvHandle);
// SAFETY: the mpv client API is documented as thread-safe for every function used
// from multiple threads here; `mpv_wait_event` is confined to the event thread.
unsafe impl Send for RawHandle {}
// SAFETY: see above.
unsafe impl Sync for RawHandle {}

struct Inner {
    api: MpvApi,
    handle: RawHandle,
}

impl Inner {
    fn h(&self) -> *mut MpvHandle {
        self.handle.0
    }

    fn check(&self, code: c_int, context: &str) -> Result<(), PlayerError> {
        if code >= 0 {
            return Ok(());
        }
        // SAFETY: mpv_error_string returns a static, NUL-terminated string for any code.
        let message = unsafe { CStr::from_ptr((self.api.error_string)(code)) }
            .to_string_lossy()
            .into_owned();
        Err(PlayerError::Mpv {
            context: context.to_owned(),
            message,
        })
    }

    fn set_option(&self, name: &str, value: &str) -> Result<(), PlayerError> {
        let (n, v) = (cstring(name)?, cstring(value)?);
        // SAFETY: valid handle, NUL-terminated strings that outlive the call.
        let code = unsafe { (self.api.set_option_string)(self.h(), n.as_ptr(), v.as_ptr()) };
        self.check(code, name)
    }

    fn set_property(&self, name: &str, value: &str) -> Result<(), PlayerError> {
        let (n, v) = (cstring(name)?, cstring(value)?);
        // SAFETY: valid handle, NUL-terminated strings that outlive the call.
        let code = unsafe { (self.api.set_property_string)(self.h(), n.as_ptr(), v.as_ptr()) };
        self.check(code, name)
    }

    fn get_property(&self, name: &str) -> Option<String> {
        let n = cstring(name).ok()?;
        // SAFETY: valid handle; result is either NULL or an mpv-allocated string we free.
        unsafe {
            let ptr = (self.api.get_property_string)(self.h(), n.as_ptr());
            if ptr.is_null() {
                return None;
            }
            let value = CStr::from_ptr(ptr).to_string_lossy().into_owned();
            (self.api.free)(ptr.cast());
            Some(value)
        }
    }

    fn command(&self, args: &[&str]) -> Result<(), PlayerError> {
        let owned = args
            .iter()
            .map(|a| cstring(a))
            .collect::<Result<Vec<_>, _>>()?;
        let mut ptrs: Vec<*const c_char> = owned.iter().map(|c| c.as_ptr()).collect();
        ptrs.push(std::ptr::null());
        // SAFETY: NULL-terminated argv of strings kept alive by `owned`.
        let code = unsafe { (self.api.command)(self.h(), ptrs.as_mut_ptr()) };
        self.check(code, args.first().copied().unwrap_or("command"))
    }

    fn observe(&self, id: u64, name: &str, format: c_int) -> Result<(), PlayerError> {
        let n = cstring(name)?;
        // SAFETY: valid handle and NUL-terminated name.
        let code = unsafe { (self.api.observe_property)(self.h(), id, n.as_ptr(), format) };
        self.check(code, name)
    }
}

fn cstring(s: &str) -> Result<CString, PlayerError> {
    CString::new(s).map_err(|_| PlayerError::InvalidSource("caractère NUL".into()))
}

pub struct MpvEngine {
    inner: Arc<Inner>,
    stop: Arc<AtomicBool>,
    event_thread: Mutex<Option<JoinHandle<()>>>,
    destroyed: AtomicBool,
}

const OBS_TIME_POS: u64 = 1;
const OBS_DURATION: u64 = 2;
const OBS_PAUSE: u64 = 3;
const OBS_CACHE: u64 = 4;
const OBS_TRACKS: u64 = 5;

impl MpvEngine {
    /// Loads libmpv, configures and initialises a player instance, and starts the
    /// event thread forwarding [`PlayerEvent`]s to `sink`.
    pub fn new(library: &Path, config: &MpvConfig, sink: EventSink) -> Result<Self, PlayerError> {
        // SAFETY: the library path is chosen by the app (bundled next to the
        // executable or an explicit developer override), not by remote input.
        let api = unsafe { MpvApi::load(library) }
            .map_err(|e| PlayerError::LibraryUnavailable(format!("{}: {e}", library.display())))?;

        // SAFETY: plain getter without arguments.
        let version = unsafe { (api.client_api_version)() };
        if version >> 16 != ffi::REQUIRED_API_MAJOR {
            return Err(PlayerError::UnsupportedApi(format!(
                "{}.{}",
                version >> 16,
                version & 0xffff
            )));
        }

        // SAFETY: mpv_create has no preconditions; NULL means out of memory.
        let raw = unsafe { (api.create)() };
        if raw.is_null() {
            return Err(PlayerError::Mpv {
                context: "mpv_create".into(),
                message: "NULL".into(),
            });
        }
        let inner = Inner {
            api,
            handle: RawHandle(raw),
        };

        if let Err(e) = Self::configure(&inner, config) {
            // SAFETY: handle was created above and is not shared with any thread yet.
            unsafe { (inner.api.terminate_destroy)(inner.h()) };
            return Err(e);
        }

        let inner = Arc::new(inner);
        let stop = Arc::new(AtomicBool::new(false));
        let thread = {
            let inner = Arc::clone(&inner);
            let stop = Arc::clone(&stop);
            std::thread::Builder::new()
                .name("mpv-events".into())
                .spawn(move || event_loop(&inner, &stop, &sink))
                .map_err(|e| PlayerError::Mpv {
                    context: "thread".into(),
                    message: e.to_string(),
                })?
        };

        Ok(Self {
            inner,
            stop,
            event_thread: Mutex::new(Some(thread)),
            destroyed: AtomicBool::new(false),
        })
    }

    fn configure(inner: &Inner, config: &MpvConfig) -> Result<(), PlayerError> {
        // Hardening: no user config, scripts, ytdl hook or built-in key/mouse bindings.
        // All input goes through the app UI so focus stays with the WebView.
        for (k, v) in [
            ("config", "no"),
            ("load-scripts", "no"),
            ("ytdl", "no"),
            ("osc", "no"),
            ("input-default-bindings", "no"),
            ("input-vo-keyboard", "no"),
            ("input-cursor", "no"),
            ("cursor-autohide", "no"),
            ("terminal", "no"),
            ("idle", "yes"),
            ("keep-open", "yes"),
            ("hwdec", config.hwdec.as_str()),
        ] {
            inner.set_option(k, v)?;
        }
        if config.headless {
            inner.set_option("vo", "null")?;
            inner.set_option("ao", "null")?;
        }
        if let Some(wid) = config.wid {
            inner.set_option("wid", &wid.to_string())?;
            inner.set_option("force-window", "yes")?;
        }
        let level = cstring("warn")?;
        // SAFETY: valid handle and NUL-terminated level string.
        let code = unsafe { (inner.api.request_log_messages)(inner.h(), level.as_ptr()) };
        inner.check(code, "request_log_messages")?;

        // SAFETY: handle is configured and not yet initialised.
        let code = unsafe { (inner.api.initialize)(inner.h()) };
        inner.check(code, "mpv_initialize")?;

        inner.observe(OBS_TIME_POS, "time-pos", ffi::MPV_FORMAT_DOUBLE)?;
        inner.observe(OBS_DURATION, "duration", ffi::MPV_FORMAT_DOUBLE)?;
        inner.observe(OBS_PAUSE, "pause", ffi::MPV_FORMAT_FLAG)?;
        inner.observe(OBS_CACHE, "paused-for-cache", ffi::MPV_FORMAT_FLAG)?;
        inner.observe(OBS_TRACKS, "track-list", ffi::MPV_FORMAT_STRING)?;
        Ok(())
    }

    /// Raw property read for diagnostics and tests. Not exposed over IPC.
    pub fn property(&self, name: &str) -> Option<String> {
        self.inner.get_property(name)
    }

    /// Escape hatch for engine tests that need mpv-only sources (e.g. lavfi).
    #[cfg(test)]
    fn load_raw(&self, target: &str) -> Result<(), PlayerError> {
        self.inner.command(&["loadfile", target, "replace"])
    }

    #[cfg(test)]
    fn command_raw(&self, args: &[&str]) -> Result<(), PlayerError> {
        self.inner.command(args)
    }
}

impl PlayerEngine for MpvEngine {
    fn load(&self, request: &PlaybackRequest, source: &MediaSource) -> Result<(), PlayerError> {
        let inner = &self.inner;
        inner.command(&["change-list", "http-header-fields", "clr", ""])?;
        for (name, value) in &request.http_headers {
            if name.contains([':', '\r', '\n']) || value.contains(['\r', '\n']) {
                return Err(PlayerError::InvalidSource("en-tête HTTP invalide".into()));
            }
            inner.command(&[
                "change-list",
                "http-header-fields",
                "append",
                &format!("{name}: {value}"),
            ])?;
        }
        match request.start_seconds {
            Some(s) if s.is_finite() && s > 0.0 => {
                inner.set_property("start", &format!("{s:.3}"))?
            }
            _ => inner.set_property("start", "none")?,
        }
        inner.set_property("pause", "no")?;
        inner.command(&["loadfile", &source.as_mpv_arg(), "replace"])
    }

    fn command(&self, command: &PlayerCommand) -> Result<(), PlayerError> {
        let inner = &self.inner;
        match command {
            PlayerCommand::SetPaused { paused } => {
                inner.set_property("pause", if *paused { "yes" } else { "no" })
            }
            PlayerCommand::TogglePause => inner.command(&["cycle", "pause"]),
            PlayerCommand::SeekAbsolute { seconds } => {
                inner.command(&["seek", &finite(*seconds)?.to_string(), "absolute+exact"])
            }
            PlayerCommand::SeekRelative { seconds } => {
                inner.command(&["seek", &finite(*seconds)?.to_string(), "relative+exact"])
            }
            PlayerCommand::SetAudioTrack { id } => inner.set_property("aid", &track_value(*id)),
            PlayerCommand::SetSubtitleTrack { id } => inner.set_property("sid", &track_value(*id)),
            PlayerCommand::SetVolume { percent } => {
                inner.set_property("volume", &finite(percent.clamp(0.0, 130.0))?.to_string())
            }
            PlayerCommand::Stop => inner.command(&["stop"]),
        }
    }

    fn tracks(&self) -> Result<Vec<Track>, PlayerError> {
        let json = self
            .inner
            .get_property("track-list")
            .unwrap_or_else(|| "[]".into());
        parse_tracks(&json)
    }

    fn diagnostics(&self) -> PlayerDiagnostics {
        let s = |n: &str| self.inner.get_property(n).filter(|v| !v.is_empty());
        let i = |n: &str| s(n).and_then(|v| v.parse::<i64>().ok());
        let f = |n: &str| s(n).and_then(|v| v.parse::<f64>().ok());
        PlayerDiagnostics {
            mpv_version: s("mpv-version"),
            ffmpeg_version: s("ffmpeg-version"),
            video_output: s("current-vo"),
            gpu_api: s("current-gpu-context"),
            hwdec_current: s("hwdec-current"),
            video_codec: s("video-codec"),
            video_width: i("width"),
            video_height: i("height"),
            audio_codec: s("audio-codec-name"),
            audio_output: s("current-ao"),
            container: s("file-format"),
            osd_width: i("osd-width"),
            osd_height: i("osd-height"),
            time_pos: f("time-pos"),
            duration: f("duration"),
            paused: s("pause").map(|v| v == "yes"),
            frame_drop_count: i("frame-drop-count"),
            decoder_frame_drop_count: i("decoder-frame-drop-count"),
        }
    }

    fn shutdown(&self) {
        if self.destroyed.swap(true, Ordering::SeqCst) {
            return;
        }
        self.stop.store(true, Ordering::SeqCst);
        // SAFETY: handle is still alive; wakeup only interrupts mpv_wait_event.
        unsafe { (self.inner.api.wakeup)(self.inner.h()) };
        if let Some(t) = self
            .event_thread
            .lock()
            .unwrap_or_else(|p| p.into_inner())
            .take()
        {
            let _ = t.join();
        }
        // SAFETY: the event thread has exited, so nothing else waits on the handle,
        // and `destroyed` guarantees this runs once. Further calls are prevented
        // because every public entry point is on `self`, which is being torn down.
        unsafe { (self.inner.api.terminate_destroy)(self.inner.h()) };
    }
}

impl Drop for MpvEngine {
    fn drop(&mut self) {
        self.shutdown();
    }
}

fn finite(v: f64) -> Result<f64, PlayerError> {
    if v.is_finite() {
        Ok(v)
    } else {
        Err(PlayerError::InvalidSource("valeur non finie".into()))
    }
}

fn track_value(id: Option<i64>) -> String {
    id.map_or_else(|| "no".to_owned(), |v| v.to_string())
}

fn parse_tracks(json: &str) -> Result<Vec<Track>, PlayerError> {
    let value: serde_json::Value = serde_json::from_str(json).map_err(|e| PlayerError::Mpv {
        context: "track-list".into(),
        message: e.to_string(),
    })?;
    let items = value.as_array().cloned().unwrap_or_default();
    Ok(items
        .iter()
        .filter_map(|t| {
            let kind = match t.get("type")?.as_str()? {
                "video" => TrackKind::Video,
                "audio" => TrackKind::Audio,
                "sub" => TrackKind::Sub,
                _ => return None,
            };
            let text = |k: &str| t.get(k).and_then(|v| v.as_str()).map(str::to_owned);
            let flag = |k: &str| {
                t.get(k)
                    .and_then(serde_json::Value::as_bool)
                    .unwrap_or(false)
            };
            Some(Track {
                id: t.get("id")?.as_i64()?,
                kind,
                title: text("title"),
                lang: text("lang"),
                codec: text("codec"),
                selected: flag("selected"),
                external: flag("external"),
                default: flag("default"),
            })
        })
        .collect())
}

/// Strips credentials mpv may echo in log lines (query-string tokens).
fn redact(line: &str) -> String {
    let mut out = String::with_capacity(line.len());
    let mut rest = line;
    while let Some((pos, key_len)) = find_secret_key(rest) {
        let value_start = pos + key_len;
        out.push_str(&rest[..value_start]);
        out.push_str("<redacted>");
        let tail = &rest[value_start..];
        // Authorization headers hold spaces/commas: redact to end of line.
        let whole_line = rest[pos..]
            .to_ascii_lowercase()
            .starts_with("x-emby-authorization");
        let end = if whole_line {
            tail.len()
        } else {
            tail.find(['&', ' ', '"', '\'', ',']).unwrap_or(tail.len())
        };
        rest = &tail[end..];
    }
    out.push_str(rest);
    out
}

/// Returns (byte offset, key length) of the first secret-bearing key. Keys are ASCII,
/// so offsets in the lowercased copy are valid in the original string.
fn find_secret_key(s: &str) -> Option<(usize, usize)> {
    let lower = s.to_ascii_lowercase();
    [
        "api_key=",
        "x-emby-token=",
        "accesstoken=",
        "x-emby-token: ",
        "x-emby-authorization: ",
    ]
    .iter()
    .filter_map(|k| lower.find(k).map(|p| (p, k.len())))
    .min()
}

fn event_loop(inner: &Inner, stop: &AtomicBool, sink: &EventSink) {
    while !stop.load(Ordering::SeqCst) {
        // SAFETY: only this thread calls mpv_wait_event; the returned event is
        // valid until the next call, and we copy everything out before looping.
        let event = unsafe { &*(inner.api.wait_event)(inner.h(), 0.5) };
        match event.event_id {
            ffi::MPV_EVENT_NONE => {}
            ffi::MPV_EVENT_SHUTDOWN => {
                sink(PlayerEvent::Shutdown);
                break;
            }
            ffi::MPV_EVENT_FILE_LOADED => sink(PlayerEvent::FileLoaded),
            ffi::MPV_EVENT_VIDEO_RECONFIG => sink(PlayerEvent::VideoReconfigured),
            ffi::MPV_EVENT_PLAYBACK_RESTART => sink(PlayerEvent::Seeked),
            ffi::MPV_EVENT_END_FILE => {
                // SAFETY: data points to mpv_event_end_file for this event id.
                let data = unsafe { &*event.data.cast::<ffi::MpvEventEndFile>() };
                let reason = match data.reason {
                    ffi::MPV_END_FILE_REASON_EOF => EndReason::Eof,
                    ffi::MPV_END_FILE_REASON_STOP => EndReason::Stopped,
                    ffi::MPV_END_FILE_REASON_QUIT => EndReason::Quit,
                    ffi::MPV_END_FILE_REASON_ERROR => EndReason::Error,
                    _ => EndReason::Other,
                };
                let error = (data.error < 0).then(|| {
                    // SAFETY: static string for any error code.
                    unsafe { CStr::from_ptr((inner.api.error_string)(data.error)) }
                        .to_string_lossy()
                        .into_owned()
                });
                sink(PlayerEvent::EndFile { reason, error });
            }
            ffi::MPV_EVENT_LOG_MESSAGE => {
                // SAFETY: data points to mpv_event_log_message for this event id.
                let msg = unsafe { &*event.data.cast::<ffi::MpvEventLogMessage>() };
                // SAFETY: prefix/text are NUL-terminated strings owned by the event.
                let (prefix, text) = unsafe {
                    (
                        CStr::from_ptr(msg.prefix).to_string_lossy(),
                        CStr::from_ptr(msg.text).to_string_lossy(),
                    )
                };
                log::warn!(target: "mpv", "[{prefix}] {}", redact(text.trim_end()));
            }
            ffi::MPV_EVENT_PROPERTY_CHANGE => {
                // SAFETY: data points to mpv_event_property for this event id.
                let prop = unsafe { &*event.data.cast::<ffi::MpvEventProperty>() };
                if let Some(ev) = map_property(event.reply_userdata, prop) {
                    sink(ev);
                }
            }
            _ => {}
        }
    }
}

fn map_property(id: u64, prop: &ffi::MpvEventProperty) -> Option<PlayerEvent> {
    if prop.data.is_null() {
        return None; // property unavailable (e.g. no file loaded)
    }
    // SAFETY: for each observed id we requested a specific format, and mpv only
    // delivers data in that format (checked again against `prop.format`).
    unsafe {
        match (id, prop.format) {
            (OBS_TIME_POS, ffi::MPV_FORMAT_DOUBLE) => Some(PlayerEvent::TimePos {
                seconds: *prop.data.cast::<f64>(),
            }),
            (OBS_DURATION, ffi::MPV_FORMAT_DOUBLE) => Some(PlayerEvent::Duration {
                seconds: *prop.data.cast::<f64>(),
            }),
            (OBS_PAUSE, ffi::MPV_FORMAT_FLAG) => Some(PlayerEvent::Paused {
                paused: *prop.data.cast::<c_int>() != 0,
            }),
            (OBS_CACHE, ffi::MPV_FORMAT_FLAG) => Some(PlayerEvent::Buffering {
                active: *prop.data.cast::<c_int>() != 0,
            }),
            (OBS_TRACKS, ffi::MPV_FORMAT_STRING) => {
                let s = CStr::from_ptr(*prop.data.cast::<*const c_char>()).to_string_lossy();
                parse_tracks(&s)
                    .ok()
                    .map(|tracks| PlayerEvent::Tracks { tracks })
            }
            _ => None,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::mpsc;
    use std::time::{Duration, Instant};

    #[test]
    fn parses_mpv_track_list_json() {
        let json = r#"[{"id":1,"type":"video","codec":"h264","selected":true},
            {"id":1,"type":"audio","lang":"jpn","title":"Stéréo","codec":"aac","selected":true,"default":true},
            {"id":2,"type":"audio","lang":"fre","codec":"ac3","selected":false},
            {"id":1,"type":"sub","lang":"fre","codec":"ass","external":false,"selected":true}]"#;
        let tracks = parse_tracks(json).unwrap();
        assert_eq!(tracks.len(), 4);
        assert_eq!(tracks[1].title.as_deref(), Some("Stéréo"));
        assert!(tracks[1].default && tracks[1].selected);
        assert_eq!(tracks[3].kind, TrackKind::Sub);
    }

    #[test]
    fn redacts_tokens_in_logs() {
        assert_eq!(
            redact(
                "Opening https://nas/emby/Videos/1/stream?static=true&api_key=abc123&x=1 failed"
            ),
            "Opening https://nas/emby/Videos/1/stream?static=true&api_key=<redacted>&x=1 failed"
        );
        assert_eq!(redact("no secret here"), "no secret here");
        assert_eq!(redact("X-Emby-Token: abc"), "X-Emby-Token: <redacted>");
    }

    #[test]
    fn rejects_non_finite_values() {
        assert!(finite(f64::NAN).is_err());
        assert!(finite(f64::INFINITY).is_err());
        assert_eq!(track_value(None), "no");
        assert_eq!(track_value(Some(2)), "2");
    }

    /// Path to libmpv for engine tests: `VIEWER_LIBMPV` or `vendor/mpv/libmpv-2.dll`.
    fn libmpv_path() -> Option<std::path::PathBuf> {
        let path = std::env::var_os("VIEWER_LIBMPV")
            .map(Into::into)
            .unwrap_or_else(|| {
                std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("../vendor/mpv/libmpv-2.dll")
            });
        path.is_file().then_some(path)
    }

    fn wait_for(
        rx: &mpsc::Receiver<PlayerEvent>,
        timeout: Duration,
        pred: impl Fn(&PlayerEvent) -> bool,
    ) -> bool {
        let deadline = Instant::now() + timeout;
        while let Some(left) = deadline.checked_duration_since(Instant::now()) {
            match rx.recv_timeout(left) {
                Ok(ev) if pred(&ev) => return true,
                Ok(_) => {}
                Err(_) => return false,
            }
        }
        false
    }

    /// Real libmpv, headless: load, tracks, seek, external subtitle, pause, teardown.
    /// Skipped (with a message) when libmpv is not available, e.g. on non-Windows CI.
    #[test]
    fn headless_engine_lifecycle() {
        let Some(lib) = libmpv_path() else {
            // CI sets VIEWER_REQUIRE_LIBMPV=1 so a missing library fails instead of skipping.
            assert!(
                std::env::var_os("VIEWER_REQUIRE_LIBMPV").is_none(),
                "libmpv required but not found"
            );
            eprintln!("SKIPPED headless_engine_lifecycle: libmpv not found (set VIEWER_LIBMPV)");
            return;
        };
        let (tx, rx) = mpsc::channel();
        let tx = Mutex::new(tx);
        let engine = MpvEngine::new(
            &lib,
            &MpvConfig {
                headless: true,
                ..MpvConfig::default()
            },
            Box::new(move |ev| {
                let _ = tx.lock().unwrap().send(ev);
            }),
        )
        .expect("engine");

        engine
            .load_raw("av://lavfi:testsrc2=size=320x240:rate=25:duration=30")
            .expect("loadfile");
        assert!(
            wait_for(&rx, Duration::from_secs(10), |e| *e
                == PlayerEvent::FileLoaded),
            "file loaded"
        );
        assert!(
            wait_for(
                &rx,
                Duration::from_secs(10),
                |e| matches!(e, PlayerEvent::TimePos { seconds } if *seconds > 0.5)
            ),
            "playback advances"
        );

        let diag = engine.diagnostics();
        assert_eq!(diag.video_width, Some(320));
        assert!(diag.mpv_version.is_some());

        engine
            .command(&PlayerCommand::SeekAbsolute { seconds: 20.0 })
            .unwrap();
        assert!(
            wait_for(
                &rx,
                Duration::from_secs(10),
                |e| matches!(e, PlayerEvent::TimePos { seconds } if *seconds >= 19.9)
            ),
            "seek reaches target"
        );

        let srt = std::env::temp_dir().join(format!("viewer-test-{}.srt", std::process::id()));
        std::fs::write(
            &srt,
            "1\n00:00:00,000 --> 00:00:30,000\nSous-titre de test\n",
        )
        .unwrap();
        engine
            .command_raw(&["sub-add", &srt.to_string_lossy(), "select"])
            .unwrap();
        let subs: Vec<_> = engine
            .tracks()
            .unwrap()
            .into_iter()
            .filter(|t| t.kind == TrackKind::Sub)
            .collect();
        assert_eq!(subs.len(), 1, "external subtitle listed");
        assert!(subs[0].external && subs[0].selected);
        engine
            .command(&PlayerCommand::SetSubtitleTrack { id: None })
            .unwrap();
        assert_eq!(engine.property("sid").as_deref(), Some("no"));

        engine
            .command(&PlayerCommand::SetPaused { paused: true })
            .unwrap();
        assert!(
            wait_for(&rx, Duration::from_secs(5), |e| *e
                == PlayerEvent::Paused { paused: true }),
            "paused"
        );

        engine.command(&PlayerCommand::Stop).unwrap();
        assert!(
            wait_for(&rx, Duration::from_secs(5), |e| matches!(
                e,
                PlayerEvent::EndFile {
                    reason: EndReason::Stopped,
                    ..
                }
            )),
            "stop ends file"
        );
        let started = Instant::now();
        engine.shutdown();
        engine.shutdown(); // idempotent
        assert!(
            started.elapsed() < Duration::from_secs(5),
            "teardown is prompt"
        );
        std::fs::remove_file(srt).ok();
    }

    #[test]
    fn missing_library_is_reported() {
        let err = MpvEngine::new(
            Path::new("Z:/nope/libmpv-2.dll"),
            &MpvConfig::default(),
            Box::new(|_| {}),
        )
        .err()
        .expect("must fail");
        assert!(matches!(err, PlayerError::LibraryUnavailable(_)));
    }
}
