//! Streaming quality setting: a bitrate cap per Emby server, one for a
//! local-network address and one for a remote one, kept in `playback-quality.json`.
//! Contains no secret.

use std::collections::BTreeMap;
use std::path::{Path, PathBuf};
use std::sync::Mutex;

use serde::{Deserialize, Serialize};

use super::EmbyError;

/// Caps offered in the settings, in bits per second; `None` is "Maximale".
pub const BITRATE_CHOICES: [u64; 7] = [
    40_000_000, 20_000_000, 10_000_000, 8_000_000, 4_000_000, 3_000_000, 1_500_000,
];

/// The caps of one server; `None` sends the default limit.
#[derive(Debug, Clone, Copy, Default, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct QualityCaps {
    #[serde(default)]
    pub local: Option<u64>,
    #[serde(default)]
    pub remote: Option<u64>,
}

impl QualityCaps {
    /// Refuses any cap outside `BITRATE_CHOICES`.
    pub fn validated(self) -> Result<Self, EmbyError> {
        if [self.local, self.remote]
            .into_iter()
            .flatten()
            .all(|b| BITRATE_CHOICES.contains(&b))
        {
            Ok(self)
        } else {
            Err(EmbyError::Settings("débit de lecture non proposé".into()))
        }
    }

    /// Drops caps outside the list, e.g. from a hand-edited or older file.
    fn sanitized(self) -> Self {
        let keep = |b: Option<u64>| b.filter(|b| BITRATE_CHOICES.contains(b));
        Self {
            local: keep(self.local),
            remote: keep(self.remote),
        }
    }

    /// The cap for a server reached through a local (or remote) address.
    pub fn for_address(&self, local: bool) -> Option<u64> {
        if local { self.local } else { self.remote }
    }
}

/// The limit of one negotiation, sent to PlaybackInfo and shown by the player.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct StreamingLimit {
    pub bitrate: u64,
    /// The cap chosen (or the debug override); `None` sends the default limit.
    pub cap: Option<u64>,
    pub local_address: bool,
}

/// What the settings screen shows for the current server.
#[derive(Debug, Clone, Serialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct PlaybackQuality {
    pub local: Option<u64>,
    pub remote: Option<u64>,
    /// Whether the session's address counts as local, so which cap applies.
    pub local_address: bool,
    pub choices: Vec<u64>,
}

#[derive(Debug, Default, Serialize, Deserialize)]
struct QualityFile {
    /// Keyed by Emby server id.
    #[serde(default)]
    servers: BTreeMap<String, QualityCaps>,
}

pub struct QualityStore {
    path: PathBuf,
    file: Mutex<QualityFile>,
}

impl QualityStore {
    /// An unreadable file counts as empty: every cap is "Maximale".
    pub fn load(path: PathBuf) -> Self {
        let mut file = std::fs::read(&path)
            .ok()
            .and_then(|b| serde_json::from_slice::<QualityFile>(&b).ok())
            .unwrap_or_default();
        for caps in file.servers.values_mut() {
            *caps = caps.sanitized();
        }
        Self {
            path,
            file: Mutex::new(file),
        }
    }

    pub fn get(&self, server_id: &str) -> QualityCaps {
        self.lock()
            .servers
            .get(server_id)
            .copied()
            .unwrap_or_default()
    }

    pub fn set(&self, server_id: &str, caps: QualityCaps) -> Result<(), EmbyError> {
        let caps = caps.validated()?;
        let mut file = self.lock();
        if caps == QualityCaps::default() {
            file.servers.remove(server_id);
        } else {
            file.servers.insert(server_id.to_owned(), caps);
        }
        save(&file, &self.path)
    }

    fn lock(&self) -> std::sync::MutexGuard<'_, QualityFile> {
        self.file.lock().unwrap_or_else(|p| p.into_inner())
    }
}

/// Atomic write (temp file + rename), like `session.json`.
fn save(file: &QualityFile, path: &Path) -> Result<(), EmbyError> {
    let io = |e: std::io::Error| EmbyError::Settings(format!("playback-quality.json : {e}"));
    if let Some(dir) = path.parent() {
        std::fs::create_dir_all(dir).map_err(io)?;
    }
    let tmp = path.with_extension("json.tmp");
    let json = serde_json::to_vec_pretty(file).map_err(|e| EmbyError::Settings(e.to_string()))?;
    std::fs::write(&tmp, json).map_err(io)?;
    std::fs::rename(&tmp, path).map_err(io)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn temp_path() -> PathBuf {
        std::env::temp_dir()
            .join(format!("viewer-quality-test-{}", uuid::Uuid::new_v4()))
            .join("playback-quality.json")
    }

    #[test]
    fn quality_caps_accept_only_the_offered_choices() {
        let ok = QualityCaps {
            local: None,
            remote: Some(4_000_000),
        };
        assert_eq!(ok.validated().unwrap(), ok);
        for bad in [0, 1, 3_500_000, 400_000_000, u64::MAX] {
            let caps = QualityCaps {
                local: Some(bad),
                remote: None,
            };
            assert!(caps.validated().is_err(), "{bad}");
        }
    }

    #[test]
    fn quality_caps_pick_the_cap_of_the_address() {
        let caps = QualityCaps {
            local: Some(40_000_000),
            remote: Some(1_500_000),
        };
        assert_eq!(caps.for_address(true), Some(40_000_000));
        assert_eq!(caps.for_address(false), Some(1_500_000));
        assert_eq!(QualityCaps::default().for_address(false), None);
    }

    #[test]
    fn quality_store_round_trips_per_server() {
        let path = temp_path();
        let store = QualityStore::load(path.clone());
        assert_eq!(store.get("a"), QualityCaps::default());
        let caps = QualityCaps {
            local: Some(8_000_000),
            remote: Some(3_000_000),
        };
        store.set("a", caps).unwrap();
        assert!(!path.with_extension("json.tmp").exists());

        let reopened = QualityStore::load(path.clone());
        assert_eq!(reopened.get("a"), caps);
        assert_eq!(reopened.get("b"), QualityCaps::default());

        // Back to "Maximale" twice: the server leaves the file.
        reopened.set("a", QualityCaps::default()).unwrap();
        let text = std::fs::read_to_string(&path).unwrap();
        assert!(!text.contains("\"a\""), "{text}");
        let _ = std::fs::remove_dir_all(path.parent().unwrap());
    }

    #[test]
    fn quality_store_refuses_a_value_off_the_list_and_keeps_the_old_one() {
        let store = QualityStore::load(temp_path());
        let caps = QualityCaps {
            local: Some(4_000_000),
            remote: None,
        };
        store.set("a", caps).unwrap();
        let bad = QualityCaps {
            local: Some(5_000_000),
            remote: None,
        };
        assert!(store.set("a", bad).is_err());
        assert_eq!(store.get("a"), caps);
    }

    #[test]
    fn quality_store_ignores_an_unreadable_file_and_unknown_values() {
        let path = temp_path();
        std::fs::create_dir_all(path.parent().unwrap()).unwrap();
        std::fs::write(&path, b"{ not json").unwrap();
        assert_eq!(
            QualityStore::load(path.clone()).get("a"),
            QualityCaps::default()
        );

        std::fs::write(
            &path,
            br#"{"servers":{"a":{"local":123,"remote":4000000}}}"#,
        )
        .unwrap();
        assert_eq!(
            QualityStore::load(path.clone()).get("a"),
            QualityCaps {
                local: None,
                remote: Some(4_000_000),
            }
        );
        let _ = std::fs::remove_dir_all(path.parent().unwrap());
    }
}
