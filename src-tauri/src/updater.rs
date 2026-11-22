//! Background updates: in release builds, checks the self-hosted
//! manifest 15 s after launch then every 6 h, downloads a newer installer into
//! memory (its minisign signature is verified against the embedded key) and tells
//! the UI it is ready. Installing launches the NSIS installer in quiet mode, which
//! relaunches the app (`/S /UPDATE /R`); on Windows the process exits right away.

use std::sync::Mutex;

use serde::Serialize;
use tauri::{AppHandle, Emitter, State};
use tauri_plugin_updater::{Error as UpdaterError, Update};

pub const UPDATE_READY_EVENT: &str = "app://update-ready";

/// What the UI is told about a downloaded update.
#[derive(Clone, Debug, PartialEq, Eq, Serialize)]
pub struct UpdateInfo {
    pub version: String,
    /// Markdown release notes, empty when the manifest has none.
    pub notes: String,
}

impl UpdateInfo {
    pub fn new(version: &str, notes: Option<&str>) -> Self {
        Self {
            version: version.trim().to_owned(),
            notes: notes.map(str::trim).unwrap_or_default().to_owned(),
        }
    }
}

/// The downloaded update, kept until it is installed. Generic over the payload so
/// the transitions are testable without a real `Update`.
pub struct Slot<T> {
    ready: Option<(UpdateInfo, T)>,
}

impl<T> Default for Slot<T> {
    fn default() -> Self {
        Self { ready: None }
    }
}

impl<T> Slot<T> {
    /// The download of `version`, if it is the one already kept: a version is
    /// fetched only once.
    pub fn downloaded(&self, version: &str) -> Option<UpdateInfo> {
        self.ready
            .as_ref()
            .filter(|(info, _)| info.version == version.trim())
            .map(|(info, _)| info.clone())
    }

    /// Keeps the newest download; the payload of an older one is dropped.
    pub fn store(&mut self, info: UpdateInfo, payload: T) {
        self.ready = Some((info, payload));
    }

    pub fn info(&self) -> Option<UpdateInfo> {
        self.ready.as_ref().map(|(info, _)| info.clone())
    }

    pub fn payload(&self) -> Option<&T> {
        self.ready.as_ref().map(|(_, payload)| payload)
    }
}

/// Managed state: the update and its verified installer bytes, and the lock that
/// serializes checks (background loop and Settings button never download twice).
#[derive(Default)]
pub struct UpdateState {
    slot: Mutex<Slot<(Update, Vec<u8>)>>,
    checking: tokio::sync::Mutex<()>,
}

impl UpdateState {
    fn lock(&self) -> std::sync::MutexGuard<'_, Slot<(Update, Vec<u8>)>> {
        self.slot.lock().unwrap_or_else(|p| p.into_inner())
    }
}

/// Result of one check, as the Settings section shows it.
#[derive(Clone, Debug, PartialEq, Eq, Serialize)]
#[serde(tag = "status", rename_all = "camelCase")]
pub enum CheckOutcome {
    /// No newer version on the manifest.
    UpToDate,
    /// A newer version is downloaded and verified (also announced by `app://update-ready`).
    Ready { info: UpdateInfo },
    /// Debug builds only: a newer version exists but is not downloaded, so a dev
    /// build never installs a release over itself.
    Available { version: String },
    /// The check or the download failed; `category` as in [`category`].
    Failed { category: String },
}

/// Log category of an updater failure: never the URL, body or message, which can
/// carry the endpoint.
pub fn category(error: &UpdaterError) -> &'static str {
    match error {
        UpdaterError::Reqwest(_) | UpdaterError::Network(_) | UpdaterError::Http(_) => "network",
        UpdaterError::Minisign(_)
        | UpdaterError::SignedVersionMismatch { .. }
        | UpdaterError::MissingSignedVersion => "signature",
        UpdaterError::ReleaseNotFound
        | UpdaterError::TargetNotFound(_)
        | UpdaterError::TargetsNotFound(_)
        | UpdaterError::Serialization(_)
        | UpdaterError::Semver(_)
        | UpdaterError::InvalidUpdaterFormat => "manifest",
        UpdaterError::InsecureTransportProtocol => "transport",
        UpdaterError::Io(_) => "io",
        _ => "other",
    }
}

fn failed(error: &UpdaterError, what: &str) -> CheckOutcome {
    let category = category(error);
    log::info!("update {what} failed: {category}");
    CheckOutcome::Failed {
        category: category.to_owned(),
    }
}

/// One check, shared by the background loop and the Settings button. Checks are
/// serialized: a second caller waits, then finds the version already downloaded.
/// `download` is false in debug builds (see [`CheckOutcome::Available`]).
pub async fn check_once(app: &AppHandle, download: bool) -> CheckOutcome {
    use tauri::Manager;
    use tauri_plugin_updater::UpdaterExt;

    let state = app.state::<UpdateState>();
    let _serial = state.checking.lock().await;
    let update = match app.updater() {
        Ok(updater) => match updater.check().await {
            Ok(Some(update)) => update,
            Ok(None) => return CheckOutcome::UpToDate,
            Err(e) => return failed(&e, "check"),
        },
        Err(e) => return failed(&e, "setup"),
    };
    let downloaded = state.lock().downloaded(&update.version);
    if let Some(info) = downloaded {
        return CheckOutcome::Ready { info };
    }
    if !download {
        return CheckOutcome::Available {
            version: update.version.trim().to_owned(),
        };
    }
    let bytes = match update.download(|_, _| {}, || {}).await {
        Ok(bytes) => bytes,
        Err(e) => return failed(&e, "download"),
    };
    let info = UpdateInfo::new(&update.version, update.body.as_deref());
    log::info!("update {} downloaded", info.version);
    state.lock().store(info.clone(), (update, bytes));
    let _ = app.emit(UPDATE_READY_EVENT, &info);
    CheckOutcome::Ready { info }
}

/// Manual check from Settings › Mises à jour. Release builds download like the
/// background loop; debug builds only report a newer version.
#[tauri::command]
pub async fn app_update_check(app: AppHandle) -> CheckOutcome {
    check_once(&app, !cfg!(debug_assertions)).await
}

/// Release builds only: 15 s after launch, then every 6 h.
#[cfg(not(debug_assertions))]
pub fn spawn(app: AppHandle) {
    use std::time::Duration;
    tauri::async_runtime::spawn(async move {
        tokio::time::sleep(Duration::from_secs(15)).await;
        loop {
            check_once(&app, true).await;
            tokio::time::sleep(Duration::from_secs(6 * 60 * 60)).await;
        }
    });
}

#[tauri::command]
pub fn app_update_pending(state: State<'_, UpdateState>) -> Option<UpdateInfo> {
    state.lock().info()
}

/// Runs the installer; on Windows the app exits and the installer relaunches it.
#[tauri::command]
pub fn app_update_install(state: State<'_, UpdateState>) -> Result<(), String> {
    let slot = state.lock();
    let Some((update, bytes)) = slot.payload() else {
        return Err("Aucune mise à jour n'est prête.".into());
    };
    update.install(bytes).map_err(|e| {
        log::warn!("update install failed: {}", category(&e));
        "L'installation n'a pas pu démarrer.".to_owned()
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn info_trims_and_defaults_notes() {
        assert_eq!(
            UpdateInfo::new(" 1.2.0 ", None),
            UpdateInfo {
                version: "1.2.0".into(),
                notes: String::new()
            }
        );
        assert_eq!(UpdateInfo::new("1.2.0", Some("\n- a\n")).notes, "- a");
    }

    #[test]
    fn slot_downloads_each_version_once() {
        let mut slot = Slot::<Vec<u8>>::default();
        assert_eq!(slot.downloaded("1.2.0"), None);
        assert_eq!(slot.info(), None);
        slot.store(UpdateInfo::new("1.2.0", Some("notes")), vec![1]);
        assert_eq!(
            slot.downloaded(" 1.2.0").map(|i| i.notes),
            Some("notes".into())
        );
        assert_eq!(slot.downloaded("1.3.0"), None);
        assert_eq!(slot.info().map(|i| i.version), Some("1.2.0".into()));
        slot.store(UpdateInfo::new("1.3.0", None), vec![2]);
        assert_eq!(slot.payload(), Some(&vec![2]));
    }

    #[test]
    fn payload_serializes_for_the_ui() {
        let json = serde_json::to_value(UpdateInfo::new("1.2.0", Some("- x"))).unwrap();
        assert_eq!(
            json,
            serde_json::json!({ "version": "1.2.0", "notes": "- x" })
        );
    }

    #[test]
    fn outcomes_serialize_tagged_for_the_ui() {
        let json = |o: CheckOutcome| serde_json::to_value(o).unwrap();
        assert_eq!(
            json(CheckOutcome::UpToDate),
            serde_json::json!({ "status": "upToDate" })
        );
        assert_eq!(
            json(CheckOutcome::Ready {
                info: UpdateInfo::new("1.3.0", None)
            }),
            serde_json::json!({ "status": "ready", "info": { "version": "1.3.0", "notes": "" } })
        );
        assert_eq!(
            json(CheckOutcome::Available {
                version: "1.3.0".into()
            }),
            serde_json::json!({ "status": "available", "version": "1.3.0" })
        );
        assert_eq!(
            json(failed(&UpdaterError::Network("x".into()), "check")),
            serde_json::json!({ "status": "failed", "category": "network" })
        );
    }

    #[test]
    fn failures_log_a_category_only() {
        assert_eq!(
            category(&UpdaterError::Network("http://x".into())),
            "network"
        );
        assert_eq!(category(&UpdaterError::ReleaseNotFound), "manifest");
        assert_eq!(category(&UpdaterError::MissingSignedVersion), "signature");
        assert_eq!(category(&UpdaterError::EmptyEndpoints), "other");
    }

    /// The shipped configuration parses, and quiet mode is what makes NSIS relaunch
    /// the app (`/S /UPDATE /R`, plugin 2.14.1 `config.rs`).
    #[test]
    fn shipped_config_is_quiet_and_signed() {
        use tauri_plugin_updater::Config;
        let conf: serde_json::Value =
            serde_json::from_str(include_str!("../tauri.conf.json")).unwrap();
        let config: Config = serde_json::from_value(conf["plugins"]["updater"].clone()).unwrap();
        assert!(config.pubkey.len() > 40);
        assert_eq!(config.endpoints.len(), 1);
        assert!(config.endpoints[0].path().ends_with("/desktop/latest.json"));
        assert!(config.dangerous_insecure_transport_protocol);
        assert_eq!(
            config.windows.map(|w| format!("{:?}", w.install_mode)),
            Some("Quiet".to_owned())
        );
        assert_eq!(conf["bundle"]["createUpdaterArtifacts"], true);
    }
}
