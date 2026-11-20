//! Local log file and crash diagnostics (ADR 0033).
//!
//! A small `log` backend writes every record to `viewer.log` in the app log
//! directory, rotated by size, after [`redact`] removed tokens, passwords and
//! authorization headers. A panic hook writes the panic and its backtrace there
//! before the default hook runs. Nothing leaves the PC.

use std::fs::{self, File, OpenOptions};
use std::io::{self, Write};
use std::path::{Path, PathBuf};
use std::sync::Mutex;
use std::time::{SystemTime, UNIX_EPOCH};

use log::{LevelFilter, Log, Metadata, Record};
use serde::Serialize;
use tauri::State;

/// Current file name; archives are `viewer.1.log` (newest) to `viewer.{n}.log`.
pub const LOG_FILE: &str = "viewer.log";
/// Size at which the current file is archived.
pub const MAX_FILE_BYTES: u64 = 2 * 1024 * 1024;
/// Files kept in all, the current one included: at most 6 MiB on disk.
pub const KEPT_FILES: usize = 3;
/// A single record is cut beyond this, so one message cannot fill a file.
const MAX_RECORD_BYTES: usize = 64 * 1024;
const REDACTED: &str = "<redacted>";

/// Keys whose value is a secret, longest first so `access_token` wins over `token`.
const SECRET_KEYS: &[&str] = &[
    "x-emby-authorization",
    "x-mediabrowser-token",
    "authorization",
    "x-emby-token",
    "access_token",
    "accesstoken",
    "password",
    "api_key",
    "apikey",
    "passwd",
    "token",
    "pw",
];
/// Authorization headers hold spaces, commas and quotes: redacted to the end of the line.
const WHOLE_LINE_KEYS: &[&str] = &["x-emby-authorization", "authorization"];

/// Removes secret values from `text`, line by line: `key=value`, `key: value` and
/// JSON `"key":"value"` for the keys of [`SECRET_KEYS`] (any case), and the
/// `user:password@` part of URLs.
pub fn redact(text: &str) -> String {
    text.split_inclusive('\n')
        .map(|line| redact_userinfo(&redact_keys(line)))
        .collect()
}

fn is_word(b: u8) -> bool {
    b.is_ascii_alphanumeric() || b == b'_'
}

fn redact_keys(line: &str) -> String {
    // ASCII lowercasing keeps byte offsets, and every offset below is an ASCII byte
    // of a key or a delimiter, so slicing the original line is safe.
    let lower = line.to_ascii_lowercase();
    let bytes = lower.as_bytes();
    let line_end = line.trim_end_matches(['\r', '\n']).len();
    let mut out = String::with_capacity(line.len());
    let mut copied = 0;
    let mut i = 0;
    while i < line_end {
        let Some((key, value_start)) = secret_at(bytes, i, line_end) else {
            i += 1;
            continue;
        };
        let (start, end) = value_span(bytes, value_start, line_end, WHOLE_LINE_KEYS.contains(&key));
        if end > start {
            out.push_str(&line[copied..start]);
            out.push_str(REDACTED);
            copied = end;
        }
        i = end.max(value_start);
    }
    out.push_str(&line[copied..]);
    out
}

/// The key starting at `i` and where its value starts, when `i` starts a secret key
/// followed by `=` or `:` (after an optional closing quote and spaces).
fn secret_at(bytes: &[u8], i: usize, end: usize) -> Option<(&'static str, usize)> {
    if i > 0 && is_word(bytes[i - 1]) {
        return None;
    }
    let key = SECRET_KEYS.iter().find(|k| {
        let after = i + k.len();
        after <= end && bytes[i..after] == *k.as_bytes() && (after == end || !is_word(bytes[after]))
    })?;
    let mut j = i + key.len();
    if j < end && (bytes[j] == b'"' || bytes[j] == b'\'') {
        j += 1;
    }
    while j < end && bytes[j] == b' ' {
        j += 1;
    }
    if j >= end || (bytes[j] != b'=' && bytes[j] != b':') {
        return None;
    }
    j += 1;
    while j < end && bytes[j] == b' ' {
        j += 1;
    }
    Some((key, j))
}

/// Span (start, exclusive end) of the secret value found at `start`: the inside of
/// a quoted value, the rest of the line for authorization headers, else up to the
/// next delimiter.
fn value_span(bytes: &[u8], start: usize, end: usize, whole_line: bool) -> (usize, usize) {
    if whole_line {
        return (start, end);
    }
    if let Some(&q @ (b'"' | b'\'')) = bytes.get(start).filter(|_| start < end) {
        let mut j = start + 1;
        while j < end && bytes[j] != q {
            j += if bytes[j] == b'\\' { 2 } else { 1 };
        }
        return (start + 1, j.min(end));
    }
    let mut j = start;
    while j < end && !b"&\"', ;\t)]}".contains(&bytes[j]) {
        j += 1;
    }
    (start, j)
}

/// `scheme://user:password@host` becomes `scheme://<redacted>@host`.
fn redact_userinfo(line: &str) -> String {
    let mut out = String::with_capacity(line.len());
    let mut rest = line;
    while let Some(pos) = rest.find("://") {
        let auth_start = pos + 3;
        out.push_str(&rest[..auth_start]);
        let tail = &rest[auth_start..];
        let authority_end = tail
            .find(|c: char| matches!(c, '/' | '?' | '#') || c.is_whitespace())
            .unwrap_or(tail.len());
        match tail[..authority_end].rfind('@') {
            Some(at) if tail[..at].contains(':') => {
                out.push_str(REDACTED);
                rest = &tail[at..];
            }
            _ => rest = tail,
        }
    }
    out.push_str(rest);
    out
}

/// A log file archived by size: when a line would take the current file beyond
/// `max_bytes`, `name.log` becomes `name.1.log`, which becomes `name.2.log`, and so
/// on; the oldest beyond `kept` files is deleted.
pub struct RotatingFile {
    dir: PathBuf,
    max_bytes: u64,
    kept: usize,
    file: Option<File>,
    size: u64,
}

impl RotatingFile {
    pub fn open(dir: &Path, max_bytes: u64, kept: usize) -> io::Result<Self> {
        fs::create_dir_all(dir)?;
        let mut file = Self {
            dir: dir.to_owned(),
            max_bytes,
            kept: kept.max(1),
            file: None,
            size: 0,
        };
        file.reopen(false)?;
        Ok(file)
    }

    pub fn path(&self, index: usize) -> PathBuf {
        match index {
            0 => self.dir.join(LOG_FILE),
            n => self.dir.join(format!("viewer.{n}.log")),
        }
    }

    fn reopen(&mut self, truncate: bool) -> io::Result<()> {
        let file = OpenOptions::new()
            .create(true)
            .append(!truncate)
            .write(true)
            .truncate(truncate)
            .open(self.path(0))?;
        self.size = file.metadata()?.len();
        self.file = Some(file);
        Ok(())
    }

    fn rotate(&mut self) -> io::Result<()> {
        // Windows cannot rename an open file.
        self.file = None;
        match fs::remove_file(self.path(self.kept - 1)) {
            Err(e) if e.kind() != io::ErrorKind::NotFound => return Err(e),
            _ => {}
        }
        for n in (0..self.kept - 1).rev() {
            match fs::rename(self.path(n), self.path(n + 1)) {
                Err(e) if e.kind() != io::ErrorKind::NotFound => return Err(e),
                _ => {}
            }
        }
        self.reopen(true)
    }

    /// Appends `line` and a newline, archiving the current file first when needed.
    pub fn write_line(&mut self, line: &str) -> io::Result<()> {
        let len = line.len() as u64 + 1;
        if self.size > 0
            && self.size + len > self.max_bytes
            && self.rotate().is_err()
            && self.file.is_none()
        {
            // Keep logging into the current file rather than losing records.
            self.reopen(false)?;
        }
        let file = self
            .file
            .as_mut()
            .ok_or_else(|| io::Error::other("log file closed"))?;
        file.write_all(line.as_bytes())?;
        file.write_all(b"\n")?;
        self.size += len;
        Ok(())
    }

    pub fn flush(&mut self) -> io::Result<()> {
        match self.file.as_mut() {
            Some(f) => f.flush(),
            None => Ok(()),
        }
    }
}

/// Level for a record's target: the app's own records at `own`, dependencies
/// (tao, wry, reqwest…) at warnings and errors only.
fn level_for(target: &str, own: LevelFilter) -> LevelFilter {
    if target.starts_with("viewer") || target == "mpv" || target == "panic" {
        own
    } else {
        LevelFilter::Warn.min(own)
    }
}

/// `2026-11-07T10:11:12.345Z` (UTC), from a duration since the Unix epoch.
fn timestamp(since_epoch: std::time::Duration) -> String {
    let secs = since_epoch.as_secs();
    let (days, rem) = (secs / 86_400, secs % 86_400);
    // Civil date from days since 1970-01-01 (H. Hinnant's algorithm).
    let z = days as i64 + 719_468;
    let era = z.div_euclid(146_097);
    let doe = z.rem_euclid(146_097);
    let yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let day = doy - (153 * mp + 2) / 5 + 1;
    let month = if mp < 10 { mp + 3 } else { mp - 9 };
    let year = yoe + era * 400 + i64::from(month <= 2);
    format!(
        "{year:04}-{month:02}-{day:02}T{:02}:{:02}:{:02}.{:03}Z",
        rem / 3600,
        rem / 60 % 60,
        rem % 60,
        since_epoch.subsec_millis()
    )
}

/// One redacted line per record: time, level, target and message, cut at
/// [`MAX_RECORD_BYTES`].
fn format_record(time: &str, level: log::Level, target: &str, message: &str) -> String {
    let mut line = redact(&format!("{time} {level:<5} {target}: {message}"));
    if line.len() > MAX_RECORD_BYTES {
        let mut cut = MAX_RECORD_BYTES;
        while !line.is_char_boundary(cut) {
            cut -= 1;
        }
        line.truncate(cut);
        line.push_str(" [cut]");
    }
    line
}

struct FileLogger {
    file: Mutex<RotatingFile>,
    level: LevelFilter,
    echo: bool,
}

impl Log for FileLogger {
    fn enabled(&self, metadata: &Metadata) -> bool {
        metadata.level() <= level_for(metadata.target(), self.level)
    }

    fn log(&self, record: &Record) {
        if !self.enabled(record.metadata()) {
            return;
        }
        let since_epoch = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap_or_default();
        let line = format_record(
            &timestamp(since_epoch),
            record.level(),
            record.target(),
            &record.args().to_string(),
        );
        if self.echo {
            eprintln!("{line}");
        }
        // A panic raised while this thread held the lock must not deadlock the hook.
        let mut file = if std::thread::panicking() {
            match self.file.try_lock() {
                Ok(g) => g,
                Err(std::sync::TryLockError::Poisoned(p)) => p.into_inner(),
                Err(std::sync::TryLockError::WouldBlock) => return,
            }
        } else {
            self.file.lock().unwrap_or_else(|p| p.into_inner())
        };
        if file.write_line(&line).is_err() && self.echo {
            eprintln!("log file not written");
        }
    }

    fn flush(&self) {
        if let Ok(mut file) = self.file.try_lock() {
            let _ = file.flush();
        }
    }
}

/// Folder of the log files, managed by the app for [`diagnostics_open_log_dir`].
pub struct LogDir(pub Option<PathBuf>);

/// Installs the file logger in `dir` and the panic hook. Debug builds log at debug
/// level and echo to stderr; release builds log at info level.
pub fn init(dir: Option<&Path>) {
    let (level, echo) = if cfg!(debug_assertions) {
        (LevelFilter::Debug, true)
    } else {
        (LevelFilter::Info, false)
    };
    install_panic_hook();
    let file = match dir.map(|d| RotatingFile::open(d, MAX_FILE_BYTES, KEPT_FILES)) {
        Some(Ok(file)) => file,
        Some(Err(e)) => {
            eprintln!("log file not opened: {e}");
            return;
        }
        None => return,
    };
    let logger = FileLogger {
        file: Mutex::new(file),
        level,
        echo,
    };
    // Lives as long as the process: `set_logger` takes a static reference.
    if log::set_logger(Box::leak(Box::new(logger))).is_ok() {
        log::set_max_level(level);
        log::info!(
            "Viewer {} started ({} build)",
            env!("CARGO_PKG_VERSION"),
            if cfg!(debug_assertions) {
                "debug"
            } else {
                "release"
            }
        );
    }
}

/// Writes every panic to the log (message, place, thread, backtrace), then runs
/// the previous hook.
fn install_panic_hook() {
    let previous = std::panic::take_hook();
    std::panic::set_hook(Box::new(move |info| {
        let payload = info.payload();
        let message = payload
            .downcast_ref::<&str>()
            .copied()
            .or_else(|| payload.downcast_ref::<String>().map(String::as_str))
            .unwrap_or("<non-text payload>");
        let place = info.location().map_or_else(
            || "unknown place".to_owned(),
            |l| format!("{}:{}:{}", l.file(), l.line(), l.column()),
        );
        let thread = std::thread::current();
        let backtrace = std::backtrace::Backtrace::force_capture();
        log::error!(
            target: "panic",
            "thread '{}' panicked at {place}: {message}\n{backtrace}",
            thread.name().unwrap_or("<unnamed>")
        );
        log::logger().flush();
        previous(info);
    }));
}

#[derive(Debug, thiserror::Error)]
pub enum DiagnosticsError {
    #[error("dossier des journaux indisponible sur ce poste")]
    Unavailable,
    #[error("dossier des journaux non ouvert : {0}")]
    Open(String),
}

impl Serialize for DiagnosticsError {
    fn serialize<S: serde::Serializer>(&self, s: S) -> Result<S::Ok, S::Error> {
        s.serialize_str(&self.to_string())
    }
}

/// Opens the log folder in the Windows file explorer. Takes no argument: the
/// WebView cannot choose what is opened.
#[tauri::command]
pub async fn diagnostics_open_log_dir(dir: State<'_, LogDir>) -> Result<(), DiagnosticsError> {
    let dir = dir.0.as_deref().ok_or(DiagnosticsError::Unavailable)?;
    fs::create_dir_all(dir).map_err(|e| DiagnosticsError::Open(e.kind().to_string()))?;
    open_in_explorer(dir.as_os_str()).map_err(|e| DiagnosticsError::Open(e.kind().to_string()))
}

/// Hands `target` (a folder, or a web address opened in the default browser) to
/// the Windows shell. Callers build `target` themselves: never from the WebView as is.
#[cfg(windows)]
pub(crate) fn open_in_explorer(target: &std::ffi::OsStr) -> io::Result<()> {
    // Full path: no lookup of `explorer` in the current directory or PATH.
    let windows =
        std::env::var_os("SystemRoot").map_or_else(|| PathBuf::from(r"C:\Windows"), PathBuf::from);
    // explorer.exe exits with 1 even on success: only the launch is checked.
    std::process::Command::new(windows.join("explorer.exe"))
        .arg(target)
        .spawn()
        .map(drop)
}

#[cfg(not(windows))]
pub(crate) fn open_in_explorer(_target: &std::ffi::OsStr) -> io::Result<()> {
    Err(io::ErrorKind::Unsupported.into())
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::time::Duration;

    #[test]
    fn redacts_query_and_header_secrets() {
        assert_eq!(
            redact("GET https://emby/Videos/1/stream?static=true&api_key=abc123&x=1 failed"),
            "GET https://emby/Videos/1/stream?static=true&api_key=<redacted>&x=1 failed"
        );
        assert_eq!(redact("x?ApiKey=abc&y=1"), "x?ApiKey=<redacted>&y=1");
        assert_eq!(redact("X-Emby-Token: abc"), "X-Emby-Token: <redacted>");
        assert_eq!(
            redact("x-emby-token=abc def"),
            "x-emby-token=<redacted> def"
        );
        assert_eq!(
            redact(r#"X-Emby-Authorization: MediaBrowser Client="Viewer", Token="abc""#),
            "X-Emby-Authorization: <redacted>"
        );
        assert_eq!(
            redact("Authorization: Bearer abc.def"),
            "Authorization: <redacted>"
        );
        assert_eq!(
            redact("headers=X-MediaBrowser-Token: abc,Accept: */*"),
            "headers=X-MediaBrowser-Token: <redacted>,Accept: */*"
        );
    }

    #[test]
    fn redacts_json_and_quoted_secrets() {
        assert_eq!(
            redact(r#"{"Username":"quentin","Pw":"s3cr\"et","x":1}"#),
            r#"{"Username":"quentin","Pw":"<redacted>","x":1}"#
        );
        assert_eq!(
            redact(r#"{"AccessToken": "abc", "User": {}}"#),
            r#"{"AccessToken": "<redacted>", "User": {}}"#
        );
        assert_eq!(redact("password='hunter 2' ok"), "password='<redacted>' ok");
        assert_eq!(redact("access_token=abc;"), "access_token=<redacted>;");
        assert_eq!(redact("Token=\"abc"), "Token=\"<redacted>");
    }

    #[test]
    fn redacts_url_credentials() {
        assert_eq!(
            redact("open http://user:pa%40ss@nas:8080/x?y and https://plain.host/a@b"),
            "open http://<redacted>@nas:8080/x?y and https://plain.host/a@b"
        );
        assert_eq!(redact("http://host:8096/emby"), "http://host:8096/emby");
    }

    #[test]
    fn redacts_every_line_of_a_message() {
        assert_eq!(
            redact("a\nAuthorization: x y\r\napi_key=k\n"),
            "a\nAuthorization: <redacted>\r\napi_key=<redacted>\n"
        );
    }

    #[test]
    fn leaves_ordinary_text_alone() {
        for text in [
            "no secret here",
            "rejected token not deleted: keyring",
            "tokens=3 passwords counted",
            "stop report given up",
            "token",
            "api_key=",
            "Durée : 1:23:45, café ☕ token",
        ] {
            assert_eq!(redact(text), text);
        }
        assert_eq!(redact("prix=5 € token=é"), "prix=5 € token=<redacted>");
    }

    #[test]
    fn records_are_redacted_and_bounded() {
        let line = format_record("T", log::Level::Warn, "viewer_lib::emby", "api_key=k");
        assert_eq!(line, "T WARN  viewer_lib::emby: api_key=<redacted>");
        let long = format_record("T", log::Level::Info, "t", &"é".repeat(MAX_RECORD_BYTES));
        assert!(long.len() <= MAX_RECORD_BYTES + 6 && long.ends_with(" [cut]"));
    }

    #[test]
    fn dependencies_log_warnings_only() {
        assert_eq!(
            level_for("viewer_lib::player", LevelFilter::Debug),
            LevelFilter::Debug
        );
        assert_eq!(level_for("mpv", LevelFilter::Info), LevelFilter::Info);
        assert_eq!(
            level_for("reqwest::connect", LevelFilter::Debug),
            LevelFilter::Warn
        );
        assert_eq!(level_for("tao", LevelFilter::Error), LevelFilter::Error);
    }

    #[test]
    fn formats_utc_timestamps() {
        assert_eq!(timestamp(Duration::ZERO), "1970-01-01T00:00:00.000Z");
        // 2026-11-07T09:05:03.250Z
        assert_eq!(
            timestamp(Duration::from_millis(1_794_042_303_250)),
            "2026-11-07T09:05:03.250Z"
        );
        // Leap day.
        assert_eq!(
            timestamp(Duration::from_secs(951_782_400)),
            "2000-02-29T00:00:00.000Z"
        );
    }

    fn temp_dir() -> PathBuf {
        std::env::temp_dir().join(format!("viewer-logs-{}", uuid::Uuid::new_v4()))
    }

    #[test]
    fn rotates_by_size_and_keeps_a_bounded_number_of_files() {
        let dir = temp_dir();
        let mut file = RotatingFile::open(&dir, 100, 3).unwrap();
        for n in 0..20 {
            file.write_line(&format!("{n:02} {}", "x".repeat(36)))
                .unwrap();
        }
        file.flush().unwrap();
        let mut names: Vec<_> = fs::read_dir(&dir)
            .unwrap()
            .map(|e| e.unwrap().file_name().into_string().unwrap())
            .collect();
        names.sort();
        assert_eq!(
            names,
            ["viewer.1.log", "viewer.2.log", "viewer.log"]
        );
        for i in 0..3 {
            assert!(fs::metadata(file.path(i)).unwrap().len() <= 100);
        }
        // 40-byte lines, two per file: the newest in the current file.
        let current = fs::read_to_string(file.path(0)).unwrap();
        assert!(current.starts_with("18 ") && current.contains("\n19 "));
        assert!(fs::read_to_string(file.path(2)).unwrap().starts_with("14 "));
        drop(file);
        fs::remove_dir_all(&dir).unwrap();
    }

    #[test]
    fn appends_to_the_existing_file_and_counts_its_size() {
        let dir = temp_dir();
        let mut file = RotatingFile::open(&dir, 100, 2).unwrap();
        file.write_line(&"a".repeat(59)).unwrap();
        drop(file);
        let mut file = RotatingFile::open(&dir, 100, 2).unwrap();
        file.write_line(&"b".repeat(59)).unwrap();
        file.flush().unwrap();
        assert_eq!(
            fs::read_to_string(file.path(1)).unwrap(),
            format!("{}\n", "a".repeat(59))
        );
        assert_eq!(
            fs::read_to_string(file.path(0)).unwrap(),
            format!("{}\n", "b".repeat(59))
        );
        drop(file);
        fs::remove_dir_all(&dir).unwrap();
    }

    #[test]
    fn a_line_longer_than_the_limit_is_still_written() {
        let dir = temp_dir();
        let mut file = RotatingFile::open(&dir, 10, 2).unwrap();
        file.write_line(&"z".repeat(50)).unwrap();
        file.flush().unwrap();
        assert_eq!(fs::metadata(file.path(0)).unwrap().len(), 51);
        drop(file);
        fs::remove_dir_all(&dir).unwrap();
    }
}
