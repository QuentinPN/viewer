//! UI preferences across the switch to the Go/Wails app. WebView2 keeps
//! localStorage per origin (`http://tauri.localhost` here, `http://wails.localhost`
//! there), so the frontend hands the app's keys to `ui_settings_export` at each
//! launch, and the Wails build reads them back with `ui_settings_import` when its
//! own storage holds none (src/lib/native/uiSettings.ts). The file lives in the
//! app config folder, so the debug build keeps its own.

use std::collections::{BTreeMap, HashMap};
use std::fs;
use std::io;
use std::path::Path;

use serde::Serialize;
use tauri::{AppHandle, Manager};

/// The file in the app config folder.
pub const FILE_NAME: &str = "ui-settings.json";
/// The app's localStorage keys with a fixed name (src/lib/native/uiSettings.ts).
pub const KEYS: [&str; 5] = [
    "viewer.player.volume",
    "viewer.player.skip",
    "viewer.player.subtitles",
    "viewer.player.nightMode",
    "viewer.ui.seasonalLogo",
];
/// The prefixes of the app's per server / user keys (src/lib/native/uiSettings.ts).
pub const PREFIXES: [&str; 4] = [
    "viewer.home-rows.v1:",
    "viewer.library-options.v1:",
    "viewer.search-history.v1:",
    "viewer.version.v1:",
];
pub const MAX_ENTRIES: usize = 200;
/// UTF-8 bytes of one value.
pub const MAX_VALUE_BYTES: usize = 64 * 1024;
/// UTF-8 bytes of every key and value together.
pub const MAX_TOTAL_BYTES: usize = 512 * 1024;

#[derive(Debug, PartialEq, Eq, thiserror::Error)]
pub enum UiSettingsError {
    #[error("dossier des réglages indisponible sur ce poste")]
    Unavailable,
    #[error("réglages refusés : plus de {MAX_ENTRIES} entrées")]
    TooManyEntries,
    #[error("réglages refusés : clé non autorisée")]
    KeyNotAllowed,
    #[error("réglages refusés : valeur de plus de 64 Kio")]
    ValueTooLarge,
    #[error("réglages refusés : plus de 512 Kio au total")]
    TooLarge,
    #[error("réglages non enregistrés : {0}")]
    Write(String),
    #[error("réglages illisibles : {0}")]
    Read(String),
}

/// A bare string, like `DiagnosticsError`.
impl Serialize for UiSettingsError {
    fn serialize<S: serde::Serializer>(&self, s: S) -> Result<S::Ok, S::Error> {
        s.serialize_str(&self.to_string())
    }
}

/// Whether `key` is one of the app's UI preferences.
pub fn is_allowed_key(key: &str) -> bool {
    KEYS.contains(&key)
        || PREFIXES
            .iter()
            .any(|p| key.len() > p.len() && key.starts_with(p))
}

/// The limits, checked in key order so Rust and Go refuse the same way.
pub fn validate(entries: &BTreeMap<String, String>) -> Result<(), UiSettingsError> {
    if entries.len() > MAX_ENTRIES {
        return Err(UiSettingsError::TooManyEntries);
    }
    let mut total = 0usize;
    for (key, value) in entries {
        if !is_allowed_key(key) {
            return Err(UiSettingsError::KeyNotAllowed);
        }
        if value.len() > MAX_VALUE_BYTES {
            return Err(UiSettingsError::ValueTooLarge);
        }
        total += key.len() + value.len();
    }
    if total > MAX_TOTAL_BYTES {
        return Err(UiSettingsError::TooLarge);
    }
    Ok(())
}

/// Validates then writes `dir/ui-settings.json` atomically (temp file + rename),
/// replacing the previous export.
pub fn export(dir: &Path, entries: HashMap<String, String>) -> Result<(), UiSettingsError> {
    let entries: BTreeMap<String, String> = entries.into_iter().collect();
    validate(&entries)?;
    let io = |e: io::Error| UiSettingsError::Write(e.kind().to_string());
    fs::create_dir_all(dir).map_err(io)?;
    let json = serde_json::to_vec(&entries).map_err(|_| UiSettingsError::Write("JSON".into()))?;
    let tmp = dir.join(format!("{FILE_NAME}.tmp"));
    fs::write(&tmp, json).map_err(io)?;
    fs::rename(&tmp, dir.join(FILE_NAME)).map_err(io)
}

/// Reads `dir/ui-settings.json`: `None` when there is no export, the same limits
/// as `export` otherwise. The file is left in place.
pub fn import(dir: &Path) -> Result<Option<HashMap<String, String>>, UiSettingsError> {
    let bytes = match fs::read(dir.join(FILE_NAME)) {
        Ok(bytes) => bytes,
        Err(e) if e.kind() == io::ErrorKind::NotFound => return Ok(None),
        Err(e) => return Err(UiSettingsError::Read(e.kind().to_string())),
    };
    let entries: BTreeMap<String, String> = serde_json::from_slice(&bytes)
        .map_err(|_| UiSettingsError::Read("JSON invalide".into()))?;
    validate(&entries)?;
    Ok(Some(entries.into_iter().collect()))
}

/// Saves the app's UI preferences for the Go/Wails build.
#[tauri::command]
pub async fn ui_settings_export(
    app: AppHandle,
    entries: HashMap<String, String>,
) -> Result<(), UiSettingsError> {
    let dir = app
        .path()
        .app_config_dir()
        .map_err(|_| UiSettingsError::Unavailable)?;
    export(&dir, entries)
}

/// The UI preferences the Tauri build saved, if any.
#[tauri::command]
pub async fn ui_settings_import(
    app: AppHandle,
) -> Result<Option<HashMap<String, String>>, UiSettingsError> {
    let dir = app
        .path()
        .app_config_dir()
        .map_err(|_| UiSettingsError::Unavailable)?;
    import(&dir)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::path::PathBuf;

    fn temp_dir() -> PathBuf {
        std::env::temp_dir().join(format!("viewer-ui-settings-{}", uuid::Uuid::new_v4()))
    }

    fn map(pairs: &[(&str, &str)]) -> HashMap<String, String> {
        pairs
            .iter()
            .map(|(k, v)| ((*k).to_owned(), (*v).to_owned()))
            .collect()
    }

    #[test]
    fn export_then_import_round_trips() {
        let dir = temp_dir();
        let entries = map(&[
            ("viewer.player.volume", r#"{"level":40,"muted":false}"#),
            ("viewer.version.v1:s:u:item", "30502"),
            ("viewer.search-history.v1:s:u", r#"["dune","« été »"]"#),
        ]);
        export(&dir, entries.clone()).unwrap();
        assert_eq!(import(&dir).unwrap(), Some(entries));
        assert!(!dir.join(format!("{FILE_NAME}.tmp")).exists());
        // Read only: a second import gets the same entries.
        assert!(import(&dir).unwrap().is_some());
        // A new export replaces the previous one.
        export(&dir, map(&[("viewer.player.skip", "{}")])).unwrap();
        assert_eq!(
            import(&dir).unwrap(),
            Some(map(&[("viewer.player.skip", "{}")]))
        );
        fs::remove_dir_all(&dir).unwrap();
    }

    #[test]
    fn import_without_export_is_none() {
        assert_eq!(import(&temp_dir()).unwrap(), None);
    }

    #[test]
    fn allowed_keys() {
        for key in [
            "viewer.player.volume",
            "viewer.player.nightMode",
            "viewer.ui.seasonalLogo",
            "viewer.home-rows.v1:s:u",
            "viewer.library-options.v1:s:u:l",
        ] {
            assert!(is_allowed_key(key), "{key}");
        }
        for key in ["", "token", "viewer.version.v1:", "viewer.player.volumeX"] {
            assert!(!is_allowed_key(key), "{key}");
        }
    }

    #[test]
    fn export_refuses_what_breaks_the_limits() {
        let dir = temp_dir();
        let refused = |entries: HashMap<String, String>| export(&dir, entries).unwrap_err();
        assert_eq!(
            refused(map(&[("token", "x")])),
            UiSettingsError::KeyNotAllowed
        );
        let many = (0..=MAX_ENTRIES)
            .map(|i| (format!("viewer.version.v1:s:u:{i}"), "v".to_owned()))
            .collect();
        assert_eq!(refused(many), UiSettingsError::TooManyEntries);
        // UTF-8 bytes, not characters.
        let big = "é".repeat(MAX_VALUE_BYTES / 2 + 1);
        assert_eq!(
            refused(map(&[("viewer.player.volume", &big)])),
            UiSettingsError::ValueTooLarge
        );
        let chunk = "x".repeat(MAX_VALUE_BYTES);
        let total = (0..8)
            .map(|i| (format!("viewer.library-options.v1:s:u:{i}"), chunk.clone()))
            .collect();
        assert_eq!(refused(total), UiSettingsError::TooLarge);
        assert!(!dir.exists(), "nothing written when refused");
    }

    #[test]
    fn import_refuses_a_bad_file() {
        let dir = temp_dir();
        fs::create_dir_all(&dir).unwrap();
        for body in ["{oops", "null", r#"{"a":1}"#] {
            fs::write(dir.join(FILE_NAME), body).unwrap();
            assert_eq!(
                import(&dir).unwrap_err(),
                UiSettingsError::Read("JSON invalide".into()),
                "{body}"
            );
        }
        fs::write(dir.join(FILE_NAME), r#"{"token":"x"}"#).unwrap();
        assert_eq!(import(&dir).unwrap_err(), UiSettingsError::KeyNotAllowed);
        fs::remove_dir_all(&dir).unwrap();
    }

    #[test]
    fn export_writes_compact_sorted_json() {
        let dir = temp_dir();
        export(
            &dir,
            map(&[
                ("viewer.player.skip", "<&>"),
                ("viewer.home-rows.v1:s:u", "{}"),
            ]),
        )
        .unwrap();
        assert_eq!(
            fs::read_to_string(dir.join(FILE_NAME)).unwrap(),
            r#"{"viewer.home-rows.v1:s:u":"{}","viewer.player.skip":"<&>"}"#
        );
        fs::remove_dir_all(&dir).unwrap();
    }

    #[test]
    fn errors_serialize_as_bare_strings() {
        assert_eq!(
            serde_json::to_string(&UiSettingsError::TooManyEntries).unwrap(),
            r#""réglages refusés : plus de 200 entrées""#
        );
        assert_eq!(
            serde_json::to_string(&UiSettingsError::Write("access denied".into())).unwrap(),
            r#""réglages non enregistrés : access denied""#
        );
    }
}
