//! Transport-neutral playback abstraction. The UI and the future
//! `PlaybackCoordinator` only see these types; libmpv stays behind `MpvEngine`.

mod ffi;
pub mod mpv;
mod tls_roots;

use std::time::{Duration, Instant};

use serde::{Deserialize, Serialize};

/// What to play. Built by the coordinator, never by the Svelte UI directly.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct PlaybackRequest {
    /// Local file path or http(s) URL. Validated by [`MediaSource::parse`].
    pub source: String,
    /// HTTP headers sent by the player (e.g. auth), so tokens never go in the URL.
    #[serde(default)]
    pub http_headers: Vec<(String, String)>,
    /// Resume position in seconds.
    #[serde(default)]
    pub start_seconds: Option<f64>,
    /// Subtitle files added once the media is loaded (e.g. text tracks a transcode
    /// delivers separately). Validated like `source`.
    #[serde(default)]
    pub subtitles: Vec<ExternalSubtitle>,
}

/// A subtitle file loaded next to the media, with the same HTTP headers.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct ExternalSubtitle {
    pub source: String,
    #[serde(default)]
    pub title: Option<String>,
    #[serde(default)]
    pub lang: Option<String>,
    /// Selected when loaded; otherwise left to the player's own track choice.
    #[serde(default)]
    pub selected: bool,
}

/// A validated source: only local files and http(s) are accepted from the app
/// layer, so mpv-specific protocols (`av://`, `edl://`, `lavfi`…) cannot be injected.
#[derive(Debug, Clone, PartialEq)]
pub enum MediaSource {
    LocalFile(std::path::PathBuf),
    Http(String),
}

impl MediaSource {
    pub fn parse(raw: &str) -> Result<Self, PlayerError> {
        let raw = raw.trim();
        let lower = raw.to_ascii_lowercase();
        if lower.starts_with("http://") || lower.starts_with("https://") {
            if raw.chars().any(char::is_whitespace) {
                return Err(PlayerError::InvalidSource(
                    "URL contenant des espaces".into(),
                ));
            }
            return Ok(Self::Http(raw.to_owned()));
        }
        // Reject anything that looks like a URL scheme other than a drive letter (C:\…).
        if let Some(idx) = raw.find("://")
            && idx > 1
        {
            return Err(PlayerError::InvalidSource("protocole non autorisé".into()));
        }
        let path = std::path::PathBuf::from(raw);
        if !path.is_absolute() {
            return Err(PlayerError::InvalidSource("chemin local non absolu".into()));
        }
        if !path.is_file() {
            return Err(PlayerError::InvalidSource("fichier introuvable".into()));
        }
        Ok(Self::LocalFile(path))
    }

    pub fn is_https(&self) -> bool {
        matches!(self, Self::Http(u) if u.to_ascii_lowercase().starts_with("https://"))
    }

    pub fn as_mpv_arg(&self) -> String {
        match self {
            Self::LocalFile(p) => p.to_string_lossy().into_owned(),
            Self::Http(u) => u.clone(),
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(tag = "type", rename_all = "camelCase")]
pub enum PlayerCommand {
    SetPaused {
        paused: bool,
    },
    TogglePause,
    SeekAbsolute {
        seconds: f64,
    },
    SeekRelative {
        seconds: f64,
    },
    /// `None` disables the track kind (only meaningful for subtitles).
    SetAudioTrack {
        id: Option<i64>,
    },
    SetSubtitleTrack {
        id: Option<i64>,
    },
    SetVolume {
        percent: f64,
    },
    Stop,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct Track {
    pub id: i64,
    pub kind: TrackKind,
    pub title: Option<String>,
    pub lang: Option<String>,
    pub codec: Option<String>,
    pub selected: bool,
    pub external: bool,
    pub default: bool,
}

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub enum TrackKind {
    Video,
    Audio,
    Sub,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(tag = "type", rename_all = "camelCase")]
pub enum PlayerEvent {
    FileLoaded,
    TimePos {
        seconds: f64,
    },
    Duration {
        seconds: f64,
    },
    Paused {
        paused: bool,
    },
    Buffering {
        active: bool,
    },
    Tracks {
        tracks: Vec<Track>,
    },
    VideoReconfigured,
    Seeked,
    EndFile {
        reason: EndReason,
        error: Option<String>,
    },
    Shutdown,
}

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub enum EndReason {
    Eof,
    Stopped,
    Quit,
    Error,
    Other,
}

/// Technical snapshot used for diagnostics (Direct Play badge, hwdec, logs).
#[derive(Debug, Clone, Default, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct PlayerDiagnostics {
    pub mpv_version: Option<String>,
    pub ffmpeg_version: Option<String>,
    pub video_output: Option<String>,
    pub gpu_api: Option<String>,
    pub hwdec_current: Option<String>,
    pub video_codec: Option<String>,
    pub video_width: Option<i64>,
    pub video_height: Option<i64>,
    pub audio_codec: Option<String>,
    /// Active audio output driver (`current-ao`); `None` means no device is open.
    pub audio_output: Option<String>,
    pub container: Option<String>,
    pub osd_width: Option<i64>,
    pub osd_height: Option<i64>,
    pub time_pos: Option<f64>,
    pub duration: Option<f64>,
    pub paused: Option<bool>,
    /// Engine volume in percent (0–130), as set by `SetVolume` (ADR 0017).
    pub volume: Option<f64>,
    pub frame_drop_count: Option<i64>,
    pub decoder_frame_drop_count: Option<i64>,
}

#[derive(Debug, thiserror::Error)]
pub enum PlayerError {
    #[error("libmpv introuvable ou invalide : {0}")]
    LibraryUnavailable(String),
    #[error("version de l'API libmpv non prise en charge : {0}")]
    UnsupportedApi(String),
    #[error("source invalide : {0}")]
    InvalidSource(String),
    #[error("certificats Windows illisibles : {0}")]
    CertificateStore(String),
    #[error("erreur libmpv ({context}) : {message}")]
    Mpv { context: String, message: String },
    #[error("aucune lecture en cours")]
    NotOpen,
}

impl Serialize for PlayerError {
    fn serialize<S: serde::Serializer>(&self, s: S) -> Result<S::Ok, S::Error> {
        s.serialize_str(&self.to_string())
    }
}

/// How long a loaded file may stay unpaused with its position frozen before the
/// playback counts as interrupted (ADR 0022).
pub const STALL_LIMIT: Duration = Duration::from_secs(30);

/// Pure watch over the player events: the position must move while playing. Time is
/// passed in. FFmpeg retries at each read of the demuxer, so a stream that stays
/// dead never reaches an end of file by itself (ADR 0022).
#[derive(Debug)]
pub struct StallWatch {
    limit: Duration,
    armed: bool,
    paused: bool,
    position: Option<f64>,
    last_progress: Instant,
}

impl StallWatch {
    pub fn new(limit: Duration, now: Instant) -> Self {
        Self {
            limit,
            armed: false,
            paused: false,
            position: None,
            last_progress: now,
        }
    }

    pub fn on_event(&mut self, event: &PlayerEvent, now: Instant) {
        match event {
            PlayerEvent::FileLoaded => {
                self.armed = true;
                self.position = None;
                self.last_progress = now;
            }
            PlayerEvent::TimePos { seconds } if self.position != Some(*seconds) => {
                self.position = Some(*seconds);
                self.last_progress = now;
            }
            PlayerEvent::Paused { paused } => {
                self.paused = *paused;
                self.last_progress = now;
            }
            PlayerEvent::EndFile { .. } | PlayerEvent::Shutdown => self.armed = false,
            _ => {}
        }
    }

    /// True once when the limit is reached; silent until the next file loads.
    pub fn expired(&mut self, now: Instant) -> bool {
        let expired =
            self.armed && !self.paused && now.duration_since(self.last_progress) >= self.limit;
        if expired {
            self.armed = false;
        }
        expired
    }
}

/// Native playback engine contract. Implementations must release every native
/// resource on `shutdown` and on drop, including after errors.
pub trait PlayerEngine: Send + Sync {
    fn load(&self, request: &PlaybackRequest, source: &MediaSource) -> Result<(), PlayerError>;
    fn command(&self, command: &PlayerCommand) -> Result<(), PlayerError>;
    fn tracks(&self) -> Result<Vec<Track>, PlayerError>;
    fn diagnostics(&self) -> PlayerDiagnostics;
    fn shutdown(&self);
}

#[cfg(test)]
mod tests {
    use super::*;

    const LIMIT: Duration = Duration::from_secs(30);

    fn at(start: Instant, seconds: u64) -> Instant {
        start + Duration::from_secs(seconds)
    }

    fn loaded(start: Instant) -> StallWatch {
        let mut watch = StallWatch::new(LIMIT, start);
        watch.on_event(&PlayerEvent::FileLoaded, start);
        watch
    }

    #[test]
    fn stall_expires_once_when_the_position_freezes() {
        let start = Instant::now();
        let mut watch = loaded(start);
        watch.on_event(&PlayerEvent::TimePos { seconds: 1.0 }, at(start, 10));
        assert!(!watch.expired(at(start, 39)));
        assert!(watch.expired(at(start, 40)));
        assert!(!watch.expired(at(start, 80)), "reported once");
    }

    #[test]
    fn stall_ignores_a_repeated_position() {
        let start = Instant::now();
        let mut watch = loaded(start);
        watch.on_event(&PlayerEvent::TimePos { seconds: 1.0 }, at(start, 10));
        watch.on_event(&PlayerEvent::TimePos { seconds: 1.0 }, at(start, 35));
        assert!(watch.expired(at(start, 40)));
    }

    #[test]
    fn stall_waits_for_a_file_and_stops_with_it() {
        let start = Instant::now();
        let mut watch = StallWatch::new(LIMIT, start);
        assert!(!watch.expired(at(start, 60)), "nothing loaded yet");
        watch.on_event(&PlayerEvent::FileLoaded, at(start, 60));
        assert!(!watch.expired(at(start, 89)), "loading does not count");
        watch.on_event(
            &PlayerEvent::EndFile {
                reason: EndReason::Eof,
                error: None,
            },
            at(start, 70),
        );
        assert!(!watch.expired(at(start, 200)), "end of file");
        watch.on_event(&PlayerEvent::FileLoaded, at(start, 200));
        assert!(watch.expired(at(start, 230)), "next file watched again");
    }

    #[test]
    fn stall_does_not_count_a_user_pause() {
        let start = Instant::now();
        let mut watch = loaded(start);
        watch.on_event(&PlayerEvent::Paused { paused: true }, at(start, 10));
        assert!(!watch.expired(at(start, 500)));
        watch.on_event(&PlayerEvent::Paused { paused: false }, at(start, 500));
        assert!(!watch.expired(at(start, 529)), "counted from the unpause");
        assert!(watch.expired(at(start, 530)));
    }

    #[test]
    fn accepts_http_and_https() {
        assert_eq!(
            MediaSource::parse("https://emby.local:8920/emby/Videos/1/stream").unwrap(),
            MediaSource::Http("https://emby.local:8920/emby/Videos/1/stream".into())
        );
        assert!(matches!(
            MediaSource::parse("HTTP://x/y"),
            Ok(MediaSource::Http(_))
        ));
    }

    #[test]
    fn rejects_mpv_protocols_and_relative_paths() {
        for bad in [
            "av://lavfi:testsrc",
            "edl://a;b",
            "file:///C:/x.mkv",
            "ytdl://x",
            "relative.mkv",
            "",
        ] {
            assert!(MediaSource::parse(bad).is_err(), "{bad} should be rejected");
        }
    }

    #[test]
    fn rejects_missing_local_file() {
        let missing = std::env::temp_dir().join("viewer-does-not-exist.mkv");
        assert!(MediaSource::parse(&missing.to_string_lossy()).is_err());
    }

    #[test]
    fn accepts_existing_local_file() {
        let file = std::env::temp_dir().join("viewer-source-test.bin");
        std::fs::write(&file, b"x").unwrap();
        assert_eq!(
            MediaSource::parse(&file.to_string_lossy()).unwrap(),
            MediaSource::LocalFile(file.clone())
        );
        std::fs::remove_file(file).ok();
    }

    #[test]
    fn command_json_contract() {
        let cmd: PlayerCommand =
            serde_json::from_str(r#"{"type":"setSubtitleTrack","id":null}"#).unwrap();
        assert_eq!(cmd, PlayerCommand::SetSubtitleTrack { id: None });
        let cmd: PlayerCommand =
            serde_json::from_str(r#"{"type":"seekAbsolute","seconds":12.5}"#).unwrap();
        assert_eq!(cmd, PlayerCommand::SeekAbsolute { seconds: 12.5 });
    }
}
