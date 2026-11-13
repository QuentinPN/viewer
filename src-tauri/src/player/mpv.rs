//! libmpv implementation of [`PlayerEngine`].
//!
//! Threading model: libmpv's client API is thread-safe except `mpv_wait_event`,
//! which is only ever called from the dedicated event thread owned here. Shutdown
//! stops that thread (flag + `mpv_wakeup`) *before* `mpv_terminate_destroy`, so the
//! handle is never destroyed while the event loop still uses it.

use std::ffi::{CStr, CString, c_char, c_int};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::thread::JoinHandle;
use std::time::{Duration, Instant};

use super::ffi::{self, MpvApi, MpvHandle};
// Also applied by the log file itself; kept here so stderr echoes are clean too.
use super::{
    EndReason, ExternalSubtitle, MediaSource, OPEN_LIMIT, PlaybackRequest, PlayerCommand,
    PlayerDiagnostics, PlayerEngine, PlayerError, PlayerEvent, STALL_LIMIT, Stall, StallWatch,
    SubtitleBackground, SubtitleColor, SubtitlePosition, SubtitleSize, SubtitleStyle, Track,
    TrackKind, TrackPick, TrackSelection, tls_roots,
};
use crate::diagnostics::redact;

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
    /// `ao=null` with video still shown: scripted UI tests must not play sound on
    /// the user's speakers (ADR 0006).
    pub silent: bool,
    /// Where to export the Windows root certificates for HTTPS sources. Without it,
    /// `tls-ca-file` stays empty and every HTTPS source is refused (ADR 0026).
    pub tls_roots: Option<PathBuf>,
    /// Wait for a file to load (ADR 0027). Only tests shorten it.
    pub open_limit: Duration,
}

impl Default for MpvConfig {
    fn default() -> Self {
        Self {
            wid: None,
            headless: false,
            hwdec: "auto-safe".into(),
            silent: false,
            tls_roots: None,
            open_limit: OPEN_LIMIT,
        }
    }
}

struct RawHandle(*mut MpvHandle);
// SAFETY: the mpv client API is documented as thread-safe for every function used
// from multiple threads here; `mpv_wait_event` is confined to the event thread.
unsafe impl Send for RawHandle {}
// SAFETY: see above.
unsafe impl Sync for RawHandle {}

/// FFmpeg HTTP options: after a network error or an early end of stream, waits
/// 0, 1, 3 then 7 s before each new try; the next delay (15 s) is past
/// `reconnect_delay_max`, so it gives up there. HTTP errors such as 401 are not
/// retried (ADR 0022). No redirects: they would carry the auth header to another
/// host (ADR 0025).
const STREAM_LAVF_OPTIONS: &str = "reconnect=1,reconnect_streamed=1,reconnect_on_network_error=1,reconnect_delay_max=7,max_redirects=0";

/// What FFmpeg may open by itself, such as HLS segments: a playlist from the server
/// must not reach local files (ADR 0025).
const DEMUXER_LAVF_OPTIONS: &str = "protocol_whitelist=[http,https,tcp,tls]";

/// Seconds a connection or a read may hang before FFmpeg counts it as failed. mpv
/// reads again after a failure, so a stream that stays dead is ended by
/// [`StallWatch`], not by these options (ADR 0022).
const NETWORK_TIMEOUT: &str = "15";

struct Inner {
    api: MpvApi,
    handle: RawHandle,
    /// Subtitles of the last `load`, added by the event thread on `FILE_LOADED`
    /// (`sub-add` needs a loaded file).
    pending_subtitles: Mutex<Vec<(String, ExternalSubtitle)>>,
    /// Tracks of the last `load`, selected by the event thread on `FILE_LOADED`,
    /// once mpv knows the file's streams (ADR 0035).
    pending_tracks: Mutex<TrackSelection>,
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

    /// Runs a command on mpv's worker threads; the outcome comes back as a
    /// `MPV_EVENT_COMMAND_REPLY` carrying `reply`.
    fn command_async(&self, reply: u64, args: &[&str]) -> Result<(), PlayerError> {
        let owned = args
            .iter()
            .map(|a| cstring(a))
            .collect::<Result<Vec<_>, _>>()?;
        let mut ptrs: Vec<*const c_char> = owned.iter().map(|c| c.as_ptr()).collect();
        ptrs.push(std::ptr::null());
        // SAFETY: NULL-terminated argv; mpv copies the arguments before returning.
        let code = unsafe { (self.api.command_async)(self.h(), reply, ptrs.as_mut_ptr()) };
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
    tls_roots: Option<PathBuf>,
    event_thread: Mutex<Option<JoinHandle<()>>>,
    destroyed: AtomicBool,
}

const OBS_TIME_POS: u64 = 1;
const OBS_DURATION: u64 = 2;
const OBS_PAUSE: u64 = 3;
const OBS_CACHE: u64 = 4;
const OBS_TRACKS: u64 = 5;
const OBS_EOF: u64 = 6;
/// Reply id of the asynchronous `sub-add` commands.
const REPLY_SUBTITLE: u64 = 1;
/// Reply id of the `stop` sent when a stream stalls.
const REPLY_STALL_STOP: u64 = 2;

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
            pending_subtitles: Mutex::new(Vec::new()),
            pending_tracks: Mutex::new(TrackSelection::default()),
        };

        if let Err(e) = Self::configure(&inner, config) {
            // SAFETY: handle was created above and is not shared with any thread yet.
            unsafe { (inner.api.terminate_destroy)(inner.h()) };
            return Err(e);
        }

        let inner = Arc::new(inner);
        let stop = Arc::new(AtomicBool::new(false));
        let open_limit = config.open_limit;
        let thread = {
            let inner = Arc::clone(&inner);
            let stop = Arc::clone(&stop);
            std::thread::Builder::new()
                .name("mpv-events".into())
                .spawn(move || event_loop(&inner, &stop, &sink, open_limit))
                .map_err(|e| PlayerError::Mpv {
                    context: "thread".into(),
                    message: e.to_string(),
                })?
        };

        Ok(Self {
            inner,
            stop,
            tls_roots: config.tls_roots.clone(),
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
            // HTTP through FFmpeg rather than the curl stream, whose retries are
            // immediate and give up within the same millisecond: FFmpeg reopens
            // the stream after a short network outage (ADR 0022).
            ("curl-enabled", "no"),
            ("stream-lavf-o", STREAM_LAVF_OPTIONS),
            ("network-timeout", NETWORK_TIMEOUT),
            ("demuxer-lavf-o", DEMUXER_LAVF_OPTIONS),
            ("tls-verify", "yes"),
        ] {
            inner.set_option(k, v)?;
        }
        if config.headless {
            inner.set_option("vo", "null")?;
        }
        if config.headless || config.silent {
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
        // With `keep-open`, mpv stays on the last frame at the end of the file and
        // sends no `end-file`: `eof-reached` is the end, including a stream cut
        // short by the network once the cache is drained (ADR 0012).
        inner.observe(OBS_EOF, "eof-reached", ffi::MPV_FORMAT_FLAG)?;
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
}

impl PlayerEngine for MpvEngine {
    fn load(&self, request: &PlaybackRequest, source: &MediaSource) -> Result<(), PlayerError> {
        let inner = &self.inner;
        if source.is_https()
            && let Some(path) = &self.tls_roots
        {
            tls_roots::refresh(path).map_err(|e| PlayerError::CertificateStore(e.to_string()))?;
            inner.set_property("tls-ca-file", &path.to_string_lossy())?;
        }
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
        let subtitles = request
            .subtitles
            .iter()
            .map(|s| {
                let target = MediaSource::parse(&s.source)?.as_mpv_arg();
                Ok((target, s.clone()))
            })
            .collect::<Result<Vec<_>, PlayerError>>()?;
        *inner
            .pending_subtitles
            .lock()
            .unwrap_or_else(|p| p.into_inner()) = subtitles;
        for (name, value) in subtitle_options(&request.subtitle_style) {
            inner.set_property(name, value)?;
        }
        // `aid` and `sid` are options too: a track picked during the last file
        // would otherwise carry over to this one.
        inner.set_property("aid", "auto")?;
        let off = request.tracks.subtitle == TrackPick::Off;
        inner.set_property("sid", if off { "no" } else { "auto" })?;
        *inner
            .pending_tracks
            .lock()
            .unwrap_or_else(|p| p.into_inner()) = request.tracks;
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
            PlayerCommand::SetSpeed { speed } => {
                inner.set_property("speed", &finite(speed.clamp(0.25, 4.0))?.to_string())
            }
            PlayerCommand::SetNightMode { on } => inner.set_property("af", night_filter(*on)),
            PlayerCommand::SetSubtitleDelay { seconds } => {
                inner.set_property("sub-delay", &delay_value(*seconds)?)
            }
            PlayerCommand::SetAudioDelay { seconds } => {
                inner.set_property("audio-delay", &delay_value(*seconds)?)
            }
            PlayerCommand::SetVideoFit { fit } => {
                for (name, value) in fit.mpv_properties() {
                    inner.set_property(name, value)?;
                }
                Ok(())
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
            volume: f("volume"),
            frame_drop_count: i("frame-drop-count"),
            decoder_frame_drop_count: i("decoder-frame-drop-count"),
            chapter_count: i("chapters"),
            subtitle_options: SUBTITLE_OPTIONS
                .iter()
                .filter_map(|&n| Some((n.to_owned(), s(n)?)))
                .collect(),
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

/// Night mode (ADR 0072), as on the TV: FFmpeg's `acompressor` through mpv's
/// `lavfi`, labelled `@night`. Peaks above about -24 dBFS are compressed 4:1,
/// then a makeup gain of about +6 dB lifts speech; no look-ahead, so no latency.
const NIGHT_FILTER: &str =
    "@night:lavfi=[acompressor=threshold=0.063:ratio=4:attack=20:release=250:makeup=2]";

/// The `af` value for night mode on or off: it is the chain's only filter.
fn night_filter(on: bool) -> &'static str {
    if on { NIGHT_FILTER } else { "" }
}

/// Past ten seconds either way, the file itself is out of sync (ADR 0062).
const DELAY_MAX_SECONDS: f64 = 10.0;

fn delay_value(seconds: f64) -> Result<String, PlayerError> {
    Ok(finite(seconds)?
        .clamp(-DELAY_MAX_SECONDS, DELAY_MAX_SECONDS)
        .to_string())
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

/// mpv options set for each file from the subtitle style (ADR 0035).
const SUBTITLE_OPTIONS: [&str; 7] = [
    "sub-scale",
    "sub-color",
    "sub-border-style",
    "sub-back-color",
    "sub-pos",
    "sub-align-y",
    "sub-ass-override",
];

/// Values of [`SUBTITLE_OPTIONS`], in order. They apply to text subtitles; ASS and
/// SSA keep their own styles (`sub-ass-override=no`, so not even `sub-scale`)
/// unless the user overrides them.
fn subtitle_options(style: &SubtitleStyle) -> [(&'static str, &'static str); 7] {
    let scale = match style.size {
        SubtitleSize::Small => "0.8",
        SubtitleSize::Normal => "1",
        SubtitleSize::Large => "1.25",
        SubtitleSize::Larger => "1.5",
    };
    let color = match style.color {
        SubtitleColor::White => "#FFFFFF",
        SubtitleColor::Yellow => "#FFE14D",
    };
    let border = match style.background {
        SubtitleBackground::Outline => "outline-and-shadow",
        SubtitleBackground::Box => "background-box",
    };
    // `sub-pos` is the bottom edge in percent of the height; 100 keeps mpv's margin.
    let (pos, align) = match style.position {
        SubtitlePosition::Bottom => ("100", "bottom"),
        SubtitlePosition::Raised => ("85", "bottom"),
        SubtitlePosition::Top => ("100", "top"),
    };
    let ass = if style.override_ass { "force" } else { "no" };
    [
        ("sub-scale", scale),
        ("sub-color", color),
        ("sub-border-style", border),
        // The box colour, 70 % opaque black (#AARRGGBB). The outline style draws no
        // shadow (`sub-shadow-offset` stays 0), so it ignores it.
        ("sub-back-color", "#B3000000"),
        ("sub-pos", pos),
        ("sub-align-y", align),
        ("sub-ass-override", ass),
    ]
}

/// mpv track id of the stream with FFmpeg index `index` in a `track-list`, among
/// the file's own tracks of that kind (`"audio"` or `"sub"`).
fn track_with_ff_index(json: &str, kind: &str, index: i64) -> Option<i64> {
    let tracks: serde_json::Value = serde_json::from_str(json).ok()?;
    tracks.as_array()?.iter().find_map(|t| {
        let own = t.get("external").and_then(serde_json::Value::as_bool) != Some(true);
        (own && t.get("type")?.as_str()? == kind && t.get("ff-index")?.as_i64()? == index)
            .then(|| t.get("id")?.as_i64())
            .flatten()
    })
}

/// Selects the tracks asked by the last `load`. A stream the file does not have
/// leaves mpv's own choice.
fn select_pending_tracks(inner: &Inner) {
    let tracks = std::mem::take(
        &mut *inner
            .pending_tracks
            .lock()
            .unwrap_or_else(|p| p.into_inner()),
    );
    for (pick, kind, property) in [
        (tracks.audio, "audio", "aid"),
        (tracks.subtitle, "sub", "sid"),
    ] {
        let TrackPick::Stream { index } = pick else {
            continue;
        };
        let list = inner.get_property("track-list").unwrap_or_default();
        match track_with_ff_index(&list, kind, index) {
            Some(id) => {
                if let Err(e) = inner.set_property(property, &id.to_string()) {
                    log::warn!("piste {kind} {index} non choisie : {e}");
                }
            }
            None => log::warn!("piste {kind} {index} absente du fichier"),
        }
    }
}

/// `sub-add` for the subtitles of the file that just loaded. A failure leaves the
/// media playing without that track.
fn add_pending_subtitles(inner: &Inner) {
    let pending = std::mem::take(
        &mut *inner
            .pending_subtitles
            .lock()
            .unwrap_or_else(|p| p.into_inner()),
    );
    // Asynchronous: Emby may take a minute to extract a subtitle from the file, and
    // a synchronous sub-add would stall playback meanwhile (ADR 0009).
    for (target, sub) in pending {
        let flag = if sub.selected { "select" } else { "auto" };
        let title = sub.title.as_deref().unwrap_or("");
        let lang = sub.lang.as_deref().unwrap_or("");
        if let Err(e) =
            inner.command_async(REPLY_SUBTITLE, &["sub-add", &target, flag, title, lang])
        {
            log::warn!(
                "sous-titres externes non chargés : {}",
                redact(&e.to_string())
            );
        }
    }
}

/// Feeds the watch, then the sink.
fn emit(stall: &mut StallWatch, sink: &EventSink, event: PlayerEvent) {
    stall.on_event(&event, Instant::now());
    sink(event);
}

/// The playback ends on an error, which follows the path of any failure (fallback or
/// message during an opening; stop report with the last position, "Lecture
/// interrompue" afterwards), and mpv stops its retries.
fn end_stalled(inner: &Inner, sink: &EventSink, stall: Stall) {
    let detail = match stall {
        Stall::Opening => {
            log::warn!("flux non ouvert dans le délai : ouverture abandonnée");
            "le flux ne s'ouvre pas"
        }
        Stall::Playing => {
            log::warn!(
                "position figée depuis {} s : lecture interrompue",
                STALL_LIMIT.as_secs()
            );
            "le flux ne répond plus"
        }
    };
    // Asynchronous: this thread must keep draining events while mpv cancels the read.
    // Before the event: the UI's fallback loads a file in reaction, and this stop must
    // reach mpv first or it would cancel that load.
    if let Err(e) = inner.command_async(REPLY_STALL_STOP, &["stop"]) {
        log::warn!("arrêt après blocage du flux : {e}");
    }
    sink(PlayerEvent::EndFile {
        reason: EndReason::Error,
        error: Some(detail.into()),
    });
}

fn event_loop(inner: &Inner, stop: &AtomicBool, sink: &EventSink, open_limit: Duration) {
    let mut stall = StallWatch::new(STALL_LIMIT, open_limit, Instant::now());
    while !stop.load(Ordering::SeqCst) {
        if let Some(kind) = stall.expired(Instant::now()) {
            end_stalled(inner, sink, kind);
        }
        // SAFETY: only this thread calls mpv_wait_event; the returned event is
        // valid until the next call, and we copy everything out before looping.
        let event = unsafe { &*(inner.api.wait_event)(inner.h(), 0.5) };
        match event.event_id {
            ffi::MPV_EVENT_NONE => {}
            ffi::MPV_EVENT_SHUTDOWN => {
                emit(&mut stall, sink, PlayerEvent::Shutdown);
                break;
            }
            ffi::MPV_EVENT_START_FILE => stall.on_load_started(Instant::now()),
            ffi::MPV_EVENT_FILE_LOADED => {
                select_pending_tracks(inner);
                add_pending_subtitles(inner);
                emit(&mut stall, sink, PlayerEvent::FileLoaded);
            }
            ffi::MPV_EVENT_COMMAND_REPLY
                if event.reply_userdata == REPLY_SUBTITLE && event.error < 0 =>
            {
                // SAFETY: mpv_error_string returns a static string for any code.
                let message = unsafe { CStr::from_ptr((inner.api.error_string)(event.error)) };
                log::warn!(
                    "sous-titres externes non chargés : {}",
                    message.to_string_lossy()
                );
            }
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
                // Once FFmpeg gives up on an HLS list, mpv tries each segment as a file:
                // the first failure ends it (ADR 0027). Before the event, as in `end_stalled`.
                if reason == EndReason::Error
                    && let Err(e) = inner.command_async(REPLY_STALL_STOP, &["stop"])
                {
                    log::warn!("arrêt après un échec du flux : {e}");
                }
                emit(&mut stall, sink, PlayerEvent::EndFile { reason, error });
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
                    emit(&mut stall, sink, ev);
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
            (OBS_EOF, ffi::MPV_FORMAT_FLAG) => {
                (*prop.data.cast::<c_int>() != 0).then_some(PlayerEvent::EndFile {
                    reason: EndReason::Eof,
                    error: None,
                })
            }
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
    fn delays_are_held_within_ten_seconds() {
        assert_eq!(delay_value(-0.3).unwrap(), "-0.3");
        assert_eq!(delay_value(42.0).unwrap(), "10");
        assert_eq!(delay_value(-42.0).unwrap(), "-10");
        assert!(delay_value(f64::NAN).is_err());
    }

    #[test]
    fn night_filter_is_the_compressor_or_nothing() {
        assert!(night_filter(true).contains("acompressor"));
        assert_eq!(night_filter(false), "");
    }

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
                "Opening https://emby/emby/Videos/1/stream?static=true&api_key=abc123&x=1 failed"
            ),
            "Opening https://emby/emby/Videos/1/stream?static=true&api_key=<redacted>&x=1 failed"
        );
        assert_eq!(redact("no secret here"), "no secret here");
        assert_eq!(redact("X-Emby-Token: abc"), "X-Emby-Token: <redacted>");
        assert_eq!(redact("x?ApiKey=abc&y=1"), "x?ApiKey=<redacted>&y=1");
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

    /// Local HTTP server for engine tests: answers with `route(path)` and records
    /// each request as "<path> <token>".
    fn serve(
        route: impl Fn(&str) -> String + Send + 'static,
    ) -> (u16, std::sync::Arc<Mutex<Vec<String>>>) {
        let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
        let port = listener.local_addr().unwrap().port();
        let seen = std::sync::Arc::new(Mutex::new(Vec::new()));
        let log = seen.clone();
        std::thread::spawn(move || {
            for mut stream in listener.incoming().flatten() {
                let mut head = Vec::new();
                let mut buf = [0u8; 1024];
                while !head.windows(4).any(|w| w == b"\r\n\r\n") {
                    match std::io::Read::read(&mut stream, &mut buf) {
                        Ok(n) if n > 0 => head.extend_from_slice(&buf[..n]),
                        _ => break,
                    }
                }
                let head = String::from_utf8_lossy(&head).into_owned();
                let path = head.split(' ').nth(1).unwrap_or_default().to_owned();
                let token = head
                    .lines()
                    .find_map(|l| l.strip_prefix("X-Emby-Token: "))
                    .unwrap_or("-");
                log.lock().unwrap().push(format!("{path} {token}"));
                let _ = std::io::Write::write_all(&mut stream, route(&path).as_bytes());
            }
        });
        (port, seen)
    }

    fn http_response(status: &str, extra_header: &str, body: &str) -> String {
        format!(
            "HTTP/1.1 {status}\r\n{extra_header}Content-Length: {}\r\nConnection: close\r\n\r\n{body}",
            body.len()
        )
    }

    #[test]
    fn https_sources_get_the_windows_roots_and_fail_closed() {
        let Some(lib) = libmpv_path() else {
            eprintln!("SKIPPED: libmpv not found (set VIEWER_LIBMPV)");
            return;
        };
        let dir = std::env::temp_dir().join(format!("viewer-roots-é-{}", std::process::id()));
        let path = dir.join("tls-roots.pem");
        let engine_with = |tls_roots: Option<PathBuf>| {
            MpvEngine::new(
                &lib,
                &MpvConfig {
                    headless: true,
                    tls_roots,
                    ..MpvConfig::default()
                },
                Box::new(|_| {}),
            )
            .expect("engine")
        };
        let load = |engine: &MpvEngine, url: &str| {
            let request = PlaybackRequest {
                source: url.to_owned(),
                http_headers: Vec::new(),
                start_seconds: None,
                subtitles: Vec::new(),
                ..Default::default()
            };
            engine.load(&request, &MediaSource::parse(url).unwrap())
        };

        let engine = engine_with(Some(path.clone()));
        load(&engine, "http://127.0.0.1:1/video").expect("http");
        assert!(!path.exists(), "no export for a plain HTTP source");
        assert_eq!(engine.property("tls-ca-file").as_deref(), Some(""));

        load(&engine, "HTTPS://127.0.0.1:1/video").expect("https");
        let pem = std::fs::read_to_string(&path).unwrap();
        assert!(pem.starts_with("-----BEGIN CERTIFICATE-----\n"));
        assert_eq!(
            engine.property("tls-ca-file").as_deref(),
            Some(path.to_str().unwrap())
        );
        engine.shutdown();

        // No path configured: nothing to trust, OpenSSL refuses.
        let engine = engine_with(None);
        load(&engine, "https://127.0.0.1:1/video").expect("https");
        assert_eq!(engine.property("tls-ca-file").as_deref(), Some(""));
        engine.shutdown();

        // The export fails: nothing is loaded.
        let engine = engine_with(Some(path.join("not-a-directory").join("roots.pem")));
        let error = load(&engine, "https://127.0.0.1:1/video").expect_err("export failure");
        assert!(matches!(error, PlayerError::CertificateStore(_)));
        assert_eq!(engine.property("path"), None);
        engine.shutdown();

        std::fs::remove_dir_all(&dir).unwrap();
    }

    #[test]
    fn finds_the_track_of_an_emby_stream_by_ff_index() {
        let json = r#"[{"id":1,"type":"video","ff-index":0},
            {"id":1,"type":"audio","ff-index":1},{"id":2,"type":"audio","ff-index":2},
            {"id":1,"type":"sub","ff-index":3},{"id":2,"type":"sub","external":true,"ff-index":4},
            {"id":3,"type":"sub","ff-index":4}]"#;
        assert_eq!(track_with_ff_index(json, "audio", 2), Some(2));
        assert_eq!(
            track_with_ff_index(json, "sub", 4),
            Some(3),
            "not the added file"
        );
        assert_eq!(track_with_ff_index(json, "sub", 1), None, "an audio index");
        assert_eq!(track_with_ff_index("not json", "audio", 1), None);
    }

    /// Preferences of ADR 0035 reach libmpv: style options, subtitles off, and the
    /// audio stream picked by its index in the file.
    #[test]
    fn applies_subtitle_style_and_track_preferences() {
        let Some(lib) = libmpv_path() else {
            eprintln!("SKIPPED: libmpv not found (set VIEWER_LIBMPV)");
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
            Box::new(move |e| {
                let _ = tx.lock().unwrap().send(e);
            }),
        )
        .expect("engine");
        let style = SubtitleStyle {
            size: SubtitleSize::Larger,
            color: SubtitleColor::Yellow,
            background: SubtitleBackground::Box,
            position: SubtitlePosition::Raised,
            override_ass: true,
        };
        let request = PlaybackRequest {
            source: "http://127.0.0.1:1/video".into(),
            tracks: TrackSelection {
                audio: TrackPick::Stream { index: 2 },
                subtitle: TrackPick::Off,
            },
            subtitle_style: style,
            ..Default::default()
        };
        engine
            .load(&request, &MediaSource::parse(&request.source).unwrap())
            .expect("load");
        // Every option name exists in this libmpv and reads back as set.
        let options = engine.diagnostics().subtitle_options;
        let read: Vec<(&str, &str)> = options
            .iter()
            .map(|(k, v)| (k.as_str(), v.as_str()))
            .collect();
        assert_eq!(
            read,
            [
                ("sub-scale", "1.500000"),
                ("sub-color", "#FFFFE14D"),
                ("sub-border-style", "background-box"),
                ("sub-back-color", "#B3000000"),
                ("sub-pos", "85.000000"),
                ("sub-align-y", "bottom"),
                ("sub-ass-override", "force"),
            ]
        );
        assert_eq!(engine.property("sid").as_deref(), Some("no"));

        // The second audio stream of the file, by its index; the defaults come back.
        *engine.inner.pending_tracks.lock().unwrap() = request.tracks;
        engine
            .load_raw("av://lavfi:testsrc2=size=160x120:duration=30[out0];sine=f=440[out1];sine=f=880[out2]")
            .expect("loadfile");
        assert!(
            wait_for(&rx, Duration::from_secs(10), |e| *e
                == PlayerEvent::FileLoaded),
            "file loaded"
        );
        assert_eq!(
            engine.property("current-tracks/audio/ff-index").as_deref(),
            Some("2")
        );
        let defaults = PlaybackRequest {
            source: "http://127.0.0.1:1/video".into(),
            ..Default::default()
        };
        engine
            .load(&defaults, &MediaSource::parse(&defaults.source).unwrap())
            .expect("load");
        assert_eq!(engine.property("options/aid").as_deref(), Some("auto"));
        assert_eq!(engine.property("options/sid").as_deref(), Some("auto"));
        assert_eq!(engine.property("sub-ass-override").as_deref(), Some("no"));
        assert_eq!(engine.property("sub-color").as_deref(), Some("#FFFFFFFF"));
        engine.shutdown();
    }

    /// Loads `url` with a token in a headless engine; returns every event up to the
    /// final end of file. `None` when libmpv is not available.
    fn play_with_token(url: &str) -> Option<Vec<PlayerEvent>> {
        let Some(lib) = libmpv_path() else {
            eprintln!("SKIPPED: libmpv not found (set VIEWER_LIBMPV)");
            return None;
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
        let request = PlaybackRequest {
            source: url.to_owned(),
            http_headers: vec![("X-Emby-Token".to_owned(), "TEST-TOKEN".to_owned())],
            start_seconds: None,
            subtitles: Vec::new(),
            ..Default::default()
        };
        engine
            .load(&request, &MediaSource::parse(url).unwrap())
            .expect("load");
        let mut events = Vec::new();
        let deadline = Instant::now() + Duration::from_secs(30);
        while let Some(left) = deadline.checked_duration_since(Instant::now()) {
            let Ok(ev) = rx.recv_timeout(left) else { break };
            let end = matches!(
                ev,
                PlayerEvent::EndFile {
                    reason: EndReason::Error | EndReason::Eof,
                    ..
                }
            );
            events.push(ev);
            if end {
                return Some(events);
            }
        }
        panic!("no end of file: {events:?}");
    }

    /// Engine loading an HLS list of `segments` entries that all answer HTTP 500.
    /// `None` when libmpv is not available.
    #[allow(clippy::type_complexity)]
    fn load_failing_segments(
        segments: usize,
        open_limit: Duration,
    ) -> Option<(
        MpvEngine,
        mpsc::Receiver<PlayerEvent>,
        std::sync::Arc<Mutex<Vec<String>>>,
    )> {
        let Some(lib) = libmpv_path() else {
            eprintln!("SKIPPED: libmpv not found (set VIEWER_LIBMPV)");
            return None;
        };
        let mut playlist = String::from(
            "#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:6\n#EXT-X-PLAYLIST-TYPE:VOD\n",
        );
        for n in 0..segments {
            playlist.push_str(&format!("#EXTINF:6.0,\nseg/{n}.ts\n"));
        }
        playlist.push_str("#EXT-X-ENDLIST\n");
        let (port, seen) = serve(move |path| {
            if path.ends_with(".m3u8") {
                http_response(
                    "200 OK",
                    "Content-Type: application/vnd.apple.mpegurl\r\n",
                    &playlist,
                )
            } else {
                http_response("500 Internal Server Error", "", "Stale file handle")
            }
        });
        let (tx, rx) = mpsc::channel();
        let tx = Mutex::new(tx);
        let engine = MpvEngine::new(
            &lib,
            &MpvConfig {
                headless: true,
                open_limit,
                ..MpvConfig::default()
            },
            Box::new(move |ev| {
                let _ = tx.lock().unwrap().send(ev);
            }),
        )
        .expect("engine");
        let url = format!("http://127.0.0.1:{port}/main.m3u8");
        let request = PlaybackRequest {
            source: url.clone(),
            http_headers: Vec::new(),
            start_seconds: None,
            subtitles: Vec::new(),
            ..Default::default()
        };
        engine
            .load(&request, &MediaSource::parse(&url).unwrap())
            .expect("load");
        Some((engine, rx, seen))
    }

    fn segment_requests(seen: &Mutex<Vec<String>>) -> usize {
        seen.lock()
            .unwrap()
            .iter()
            .filter(|l| l.contains(".ts"))
            .count()
    }

    /// ADR 0027: once FFmpeg gives up on the list, mpv reads it as a playlist of files.
    /// The first failure ends it: no second wave of requests, no series of errors.
    #[test]
    fn a_failed_hls_list_is_not_walked_as_a_playlist() {
        const SEGMENTS: usize = 100;
        let Some((engine, rx, seen)) = load_failing_segments(SEGMENTS, Duration::from_secs(30))
        else {
            return;
        };
        // mpv ends the list itself with "Other" (a redirect) when it reads it as a playlist.
        let is_failure = |ev: &PlayerEvent| {
            matches!(
                ev,
                PlayerEvent::EndFile {
                    reason: EndReason::Error,
                    ..
                }
            )
        };
        assert!(wait_for(&rx, Duration::from_secs(25), is_failure));
        std::thread::sleep(Duration::from_secs(2));
        let settled = segment_requests(&seen);
        let later: Vec<_> = rx.try_iter().collect();
        assert!(!later.iter().any(is_failure), "{later:?}");
        std::thread::sleep(Duration::from_secs(1));
        assert_eq!(segment_requests(&seen), settled);
        assert!(settled < 3 * SEGMENTS, "{settled} segment requests");
        engine.shutdown();
    }

    /// ADR 0027: FFmpeg skips HLS segments that fail, one after the other, and neither
    /// loads the file nor ends it. The limit ends the opening and stops the requests.
    #[test]
    fn failing_hls_segments_end_at_the_opening_limit() {
        let started = Instant::now();
        let Some((_engine, rx, seen)) = load_failing_segments(50_000, Duration::from_secs(2))
        else {
            return;
        };

        let mut events = Vec::new();
        while let Ok(ev) = rx.recv_timeout(Duration::from_secs(20)) {
            let end = matches!(ev, PlayerEvent::EndFile { .. });
            events.push(ev);
            if end {
                break;
            }
        }
        assert_eq!(
            events.last(),
            Some(&PlayerEvent::EndFile {
                reason: EndReason::Error,
                error: Some("le flux ne s'ouvre pas".into()),
            }),
            "{events:?}"
        );
        assert!(!events.contains(&PlayerEvent::FileLoaded), "{events:?}");
        let elapsed = started.elapsed();
        assert!(elapsed >= Duration::from_secs(2), "{elapsed:?}");
        assert!(elapsed < Duration::from_secs(5), "{elapsed:?}");
        let segments = || segment_requests(&seen);
        assert!(segments() > 1, "the demuxer went on to the next segments");

        // The real end of file follows, then no more requests.
        assert!(wait_for(&rx, Duration::from_secs(5), |ev| matches!(
            ev,
            PlayerEvent::EndFile {
                reason: EndReason::Stopped,
                ..
            }
        )));
        let after_stop = segments();
        std::thread::sleep(Duration::from_millis(500));
        assert_eq!(segments(), after_stop);
    }

    /// ADR 0025: the token stops at the server the request was meant for.
    #[test]
    fn redirect_does_not_carry_the_token_to_another_host() {
        let (other, other_seen) = serve(|_| http_response("404 Not Found", "", ""));
        let (port, seen) = serve(move |_| {
            let location = format!("Location: http://127.0.0.1:{other}/leak\r\n");
            http_response("302 Found", &location, "")
        });
        let url = format!("http://127.0.0.1:{port}/stream");
        let Some(events) = play_with_token(&url) else {
            return;
        };
        assert!(!events.contains(&PlayerEvent::FileLoaded), "{events:?}");
        assert_eq!(*seen.lock().unwrap(), ["/stream TEST-TOKEN"]);
        assert!(other_seen.lock().unwrap().is_empty());
    }

    /// ADR 0025: a playlist from the server does not open local files. Without the
    /// protocol whitelist, FFmpeg reads and plays this one.
    #[test]
    fn hls_playlist_cannot_open_a_local_file() {
        let wav = std::env::temp_dir().join(format!("viewer-test-{}.wav", std::process::id()));
        let samples = vec![128u8; 8000];
        let mut bytes = b"RIFF".to_vec();
        bytes.extend_from_slice(&(36u32 + 8000).to_le_bytes());
        bytes.extend_from_slice(b"WAVEfmt ");
        bytes.extend_from_slice(&16u32.to_le_bytes());
        bytes.extend_from_slice(&1u16.to_le_bytes());
        bytes.extend_from_slice(&1u16.to_le_bytes());
        bytes.extend_from_slice(&8000u32.to_le_bytes());
        bytes.extend_from_slice(&8000u32.to_le_bytes());
        bytes.extend_from_slice(&1u16.to_le_bytes());
        bytes.extend_from_slice(&8u16.to_le_bytes());
        bytes.extend_from_slice(b"data");
        bytes.extend_from_slice(&8000u32.to_le_bytes());
        bytes.extend_from_slice(&samples);
        std::fs::write(&wav, bytes).unwrap();

        let segment = format!("file:{}", wav.to_string_lossy().replace('\\', "/"));
        let (port, _) = serve(move |path| {
            if path != "/index.m3u8" {
                return http_response("404 Not Found", "", "");
            }
            let playlist = format!(
                "#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:10\n#EXTINF:1,\n{segment}\n#EXT-X-ENDLIST\n"
            );
            http_response(
                "200 OK",
                "Content-Type: application/vnd.apple.mpegurl\r\n",
                &playlist,
            )
        });
        let events = play_with_token(&format!("http://127.0.0.1:{port}/index.m3u8"));
        let _ = std::fs::remove_file(&wav);
        let Some(events) = events else { return };
        assert!(!events.contains(&PlayerEvent::FileLoaded), "{events:?}");
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
        assert_eq!(
            engine.property("stream-lavf-o").as_deref(),
            Some(STREAM_LAVF_OPTIONS),
            "reconnect options kept by mpv"
        );
        assert!(
            engine
                .property("demuxer-lavf-o")
                .is_some_and(|o| o.contains("protocol_whitelist")),
            "protocol whitelist kept by mpv"
        );
        assert_eq!(engine.property("tls-verify").as_deref(), Some("yes"));
        assert_eq!(engine.property("curl-enabled").as_deref(), Some("no"));
        assert_eq!(
            engine
                .property("network-timeout")
                .map(|t| t.parse::<f64>().ok()),
            Some(NETWORK_TIMEOUT.parse::<f64>().ok())
        );

        // External subtitle queued like `load` does, added on FILE_LOADED.
        let srt = std::env::temp_dir().join(format!("viewer-test-{}.srt", std::process::id()));
        std::fs::write(
            &srt,
            "1\n00:00:00,000 --> 00:00:30,000\nSous-titre de test\n",
        )
        .unwrap();
        *engine.inner.pending_subtitles.lock().unwrap() = vec![(
            srt.to_string_lossy().into_owned(),
            ExternalSubtitle {
                source: String::new(),
                title: Some("Complets".into()),
                lang: Some("fre".into()),
                selected: true,
            },
        )];
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

        let subs: Vec<_> = engine
            .tracks()
            .unwrap()
            .into_iter()
            .filter(|t| t.kind == TrackKind::Sub)
            .collect();
        assert_eq!(subs.len(), 1, "external subtitle listed");
        assert!(subs[0].external && subs[0].selected);
        assert_eq!(subs[0].title.as_deref(), Some("Complets"));
        assert_eq!(subs[0].lang.as_deref(), Some("fre"));
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

        // `keep-open` keeps the last frame: the end still comes as an `Eof`.
        engine
            .command(&PlayerCommand::SeekAbsolute { seconds: 29.0 })
            .unwrap();
        engine
            .command(&PlayerCommand::SetPaused { paused: false })
            .unwrap();
        assert!(
            wait_for(&rx, Duration::from_secs(10), |e| matches!(
                e,
                PlayerEvent::EndFile {
                    reason: EndReason::Eof,
                    ..
                }
            )),
            "end of file reported with keep-open"
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
