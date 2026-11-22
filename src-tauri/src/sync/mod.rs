//! Linked playback computer ↔ TV: the computer leads, the TV
//! follows, messages go through the Emby session relay (`SendString`).

pub mod envelope;
pub mod leader;
mod service;

use serde::Serialize;

use crate::emby::models::SessionInfoDto;

pub use service::{SyncHandle, devices};

/// `Client` of the Android TV app's sessions.
pub const TV_CLIENT: &str = "Viewer TV";

/// A TV the playback can be sent to. The name comes from the server's list.
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SyncDevice {
    pub session_id: String,
    pub name: String,
}

/// Open TV sessions of the user, other than this device's.
pub fn tv_devices(sessions: &[SessionInfoDto], own_device_id: &str) -> Vec<SyncDevice> {
    sessions
        .iter()
        .filter(|s| {
            s.client.as_deref() == Some(TV_CLIENT)
                && s.supports_remote_control
                && s.device_id.as_deref() != Some(own_device_id)
                && envelope::is_id(&s.id, 1)
        })
        .map(|s| SyncDevice {
            session_id: s.id.clone(),
            name: s
                .device_name
                .clone()
                .filter(|n| !n.trim().is_empty())
                .unwrap_or_else(|| "TV".into()),
        })
        .collect()
}

/// Id of this device's own session, the `from` of what it sends.
pub fn own_session_id(sessions: &[SessionInfoDto], own_device_id: &str) -> Option<String> {
    sessions
        .iter()
        .find(|s| s.device_id.as_deref() == Some(own_device_id) && envelope::is_id(&s.id, 1))
        .map(|s| s.id.clone())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn sessions() -> Vec<SessionInfoDto> {
        // Shape of `GET /Sessions?ControllableByUserId=` on Emby 4.10.2.0 (2026-11-14).
        serde_json::from_str(
            r#"[
            {"Id":"own1","DeviceId":"dev-pc","DeviceName":"PC","Client":"Viewer","SupportsRemoteControl":true},
            {"Id":"tv1","DeviceId":"dev-tv","DeviceName":"Shield salon","Client":"Viewer TV","SupportsRemoteControl":true},
            {"Id":"tv2","DeviceId":"dev-tv2","DeviceName":"Chambre","Client":"Viewer TV","SupportsRemoteControl":false},
            {"Id":"web","DeviceId":"dev-web","DeviceName":"Firefox","Client":"Emby Web","SupportsRemoteControl":true},
            {"Id":"bad id","DeviceId":"dev-x","DeviceName":"X","Client":"Viewer TV","SupportsRemoteControl":true}
        ]"#,
        )
        .unwrap()
    }

    #[test]
    fn devices_are_remote_controllable_tv_sessions() {
        assert_eq!(
            tv_devices(&sessions(), "dev-pc"),
            vec![SyncDevice {
                session_id: "tv1".into(),
                name: "Shield salon".into()
            }]
        );
        // Our own session never lists itself, even under the TV client name.
        assert!(tv_devices(&sessions(), "dev-tv").is_empty());
    }

    #[test]
    fn own_session_is_found_by_device_id() {
        assert_eq!(
            own_session_id(&sessions(), "dev-pc").as_deref(),
            Some("own1")
        );
        assert_eq!(own_session_id(&sessions(), "nope"), None);
    }
}
