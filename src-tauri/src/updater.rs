//! Background updates (ADR 0112): in release builds, checks the self-hosted
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
    /// A version is fetched only if it is not the one already downloaded.
    pub fn needs_download(&self, version: &str) -> bool {
        self.ready
            .as_ref()
            .is_none_or(|(info, _)| info.version != version.trim())
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

/// Managed state: the update and its verified installer bytes.
#[derive(Default)]
pub struct UpdateState(Mutex<Slot<(Update, Vec<u8>)>>);

impl UpdateState {
    fn lock(&self) -> std::sync::MutexGuard<'_, Slot<(Update, Vec<u8>)>> {
        self.0.lock().unwrap_or_else(|p| p.into_inner())
    }
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

/// One check (release builds schedule it, see `spawn`): downloads a newer, not yet downloaded update and tells the UI.
#[cfg_attr(debug_assertions, allow(dead_code))]
pub async fn check_once(app: &AppHandle) {
    use tauri::Manager;
    use tauri_plugin_updater::UpdaterExt;

    let update = match app.updater() {
        Ok(updater) => match updater.check().await {
            Ok(Some(update)) => update,
            Ok(None) => return,
            Err(e) => {
                log::info!("update check failed: {}", category(&e));
                return;
            }
        },
        Err(e) => {
            log::warn!("updater unavailable: {}", category(&e));
            return;
        }
    };
    let state = app.state::<UpdateState>();
    if !state.lock().needs_download(&update.version) {
        return;
    }
    let bytes = match update.download(|_, _| {}, || {}).await {
        Ok(bytes) => bytes,
        Err(e) => {
            log::info!("update download failed: {}", category(&e));
            return;
        }
    };
    let info = UpdateInfo::new(&update.version, update.body.as_deref());
    log::info!("update {} downloaded", info.version);
    state.lock().store(info.clone(), (update, bytes));
    let _ = app.emit(UPDATE_READY_EVENT, &info);
}

/// Release builds only: 15 s after launch, then every 6 h.
#[cfg(not(debug_assertions))]
pub fn spawn(app: AppHandle) {
    use std::time::Duration;
    tauri::async_runtime::spawn(async move {
        tokio::time::sleep(Duration::from_secs(15)).await;
        loop {
            check_once(&app).await;
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
        assert!(slot.needs_download("1.2.0"));
        assert_eq!(slot.info(), None);
        slot.store(UpdateInfo::new("1.2.0", Some("notes")), vec![1]);
        assert!(!slot.needs_download("1.2.0"));
        assert!(slot.needs_download("1.3.0"));
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
