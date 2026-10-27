//! Sign-in lifecycle: probe, sign in, restore at startup, sign out. Non-secret
//! session metadata lives in `session.json`; the token lives in a `CredentialStore`.

use std::path::{Path, PathBuf};
use std::sync::Mutex;

use serde::{Deserialize, Serialize};

use super::EmbyError;
use super::client::{AccessToken, DeviceIdentity, EmbyClient};
use super::credentials::{self, CredentialStore};
use super::models::{ServerSummary, SignedInUser};
use super::server_url::ServerUrl;

/// Contents of `session.json`. Contains no secret.
#[derive(Debug, Clone, Default, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct SessionFile {
    pub device_id: String,
    /// Prefills the connection form after sign-out or an expired session.
    pub last_server_url: Option<String>,
    pub session: Option<SessionRecord>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct SessionRecord {
    pub server_url: String,
    pub server_id: String,
    pub server_name: String,
    pub server_version: String,
    pub user_id: String,
    pub user_name: String,
}

impl SessionFile {
    /// Loads the file, creating a fresh device id when missing or unreadable.
    pub fn load_or_init(path: &Path) -> Self {
        let mut file = std::fs::read(path)
            .ok()
            .and_then(|b| serde_json::from_slice::<SessionFile>(&b).ok())
            .unwrap_or_default();
        if file.device_id.is_empty() {
            file.device_id = uuid::Uuid::new_v4().simple().to_string();
        }
        file
    }

    /// Atomic write (temp file + rename) so a crash never leaves a half file.
    pub fn save(&self, path: &Path) -> Result<(), EmbyError> {
        let io = |e: std::io::Error| EmbyError::CredentialStore(format!("session.json : {e}"));
        if let Some(dir) = path.parent() {
            std::fs::create_dir_all(dir).map_err(io)?;
        }
        let tmp = path.with_extension("json.tmp");
        let json = serde_json::to_vec_pretty(self)
            .map_err(|e| EmbyError::CredentialStore(e.to_string()))?;
        std::fs::write(&tmp, json).map_err(io)?;
        std::fs::rename(&tmp, path).map_err(io)
    }
}

struct Current {
    client: EmbyClient,
    user: SignedInUser,
}

pub struct EmbyService {
    store: Box<dyn CredentialStore>,
    path: PathBuf,
    file: Mutex<SessionFile>,
    device_name: String,
    version: String,
    current: Mutex<Option<Current>>,
}

impl EmbyService {
    pub fn new(
        store: Box<dyn CredentialStore>,
        path: PathBuf,
        device_name: String,
        version: String,
    ) -> Self {
        let file = SessionFile::load_or_init(&path);
        Self {
            store,
            path,
            file: Mutex::new(file),
            device_name,
            version,
            current: Mutex::new(None),
        }
    }

    fn device(&self) -> DeviceIdentity {
        DeviceIdentity {
            name: self.device_name.clone(),
            id: self.file().device_id,
            version: self.version.clone(),
        }
    }

    fn file(&self) -> SessionFile {
        self.file.lock().unwrap_or_else(|p| p.into_inner()).clone()
    }

    fn update_file(&self, f: impl FnOnce(&mut SessionFile)) -> Result<(), EmbyError> {
        let mut guard = self.file.lock().unwrap_or_else(|p| p.into_inner());
        f(&mut guard);
        guard.save(&self.path)
    }

    fn set_current(&self, current: Option<Current>) {
        *self.current.lock().unwrap_or_else(|p| p.into_inner()) = current;
    }

    pub fn current_user(&self) -> Option<SignedInUser> {
        self.current
            .lock()
            .unwrap_or_else(|p| p.into_inner())
            .as_ref()
            .map(|c| c.user.clone())
    }

    /// Authenticated client for the open session (for library and playback calls).
    pub fn client(&self) -> Result<EmbyClient, EmbyError> {
        self.current
            .lock()
            .unwrap_or_else(|p| p.into_inner())
            .as_ref()
            .map(|c| c.client.clone())
            .ok_or(EmbyError::NotSignedIn)
    }

    pub fn last_server_url(&self) -> Option<String> {
        self.file().last_server_url
    }

    /// Validates the address and reads the server's public identity.
    pub async fn probe(&self, url: &str) -> Result<ServerSummary, EmbyError> {
        let server = ServerUrl::parse(url)?;
        let client = EmbyClient::new(server, self.device())?;
        summary(&client).await
    }

    pub async fn sign_in(
        &self,
        url: &str,
        username: &str,
        password: &str,
    ) -> Result<SignedInUser, EmbyError> {
        let server = ServerUrl::parse(url)?;
        let client = EmbyClient::new(server, self.device())?;
        let server = summary(&client).await?;
        let auth = client.authenticate(username.trim(), password).await?;
        if auth.server_id != server.id {
            return Err(EmbyError::InvalidResponse(
                "l'identifiant du serveur a changé pendant la connexion".into(),
            ));
        }
        let token = AccessToken::new(auth.access_token);
        let user = SignedInUser {
            server,
            user_id: auth.user.id,
            user_name: auth.user.name,
            is_administrator: auth.user.policy.unwrap_or_default().is_administrator,
        };
        self.store.save(
            &credentials::account(&user.server.id, &user.user_id),
            &token,
        )?;
        let record = SessionRecord {
            server_url: user.server.url.clone(),
            server_id: user.server.id.clone(),
            server_name: user.server.name.clone(),
            server_version: user.server.version.clone(),
            user_id: user.user_id.clone(),
            user_name: user.user_name.clone(),
        };
        self.update_file(|f| {
            f.last_server_url = Some(record.server_url.clone());
            f.session = Some(record);
        })?;
        self.set_current(Some(Current {
            client: client.with_token(token),
            user: user.clone(),
        }));
        Ok(user)
    }

    /// Reopens the saved session at startup. `Ok(None)`: nothing saved. A rejected
    /// token is deleted and reported as `SessionExpired`; an unreachable server
    /// keeps the session so the user can retry.
    pub async fn restore(&self) -> Result<Option<SignedInUser>, EmbyError> {
        let Some(record) = self.file().session else {
            return Ok(None);
        };
        let account = credentials::account(&record.server_id, &record.user_id);
        let Some(token) = self.store.load(&account)? else {
            self.update_file(|f| f.session = None)?;
            return Ok(None);
        };
        let server = ServerUrl::parse(&record.server_url)?;
        let client = EmbyClient::new(server, self.device())?.with_token(token);
        let server = summary(&client).await?;
        if server.id != record.server_id {
            return Err(EmbyError::InvalidResponse(
                "un autre serveur répond à cette adresse".into(),
            ));
        }
        let dto = match client.user(&record.user_id).await {
            Err(EmbyError::SessionExpired) => {
                self.store.delete(&account)?;
                self.update_file(|f| f.session = None)?;
                return Err(EmbyError::SessionExpired);
            }
            other => other?,
        };
        let user = SignedInUser {
            server,
            user_id: dto.id,
            user_name: dto.name,
            is_administrator: dto.policy.unwrap_or_default().is_administrator,
        };
        self.set_current(Some(Current {
            client,
            user: user.clone(),
        }));
        Ok(Some(user))
    }

    /// Revokes the token on the server when reachable, then always forgets it
    /// locally: an offline sign-out must not leave a usable token on disk.
    pub async fn sign_out(&self) -> Result<(), EmbyError> {
        let current = self
            .current
            .lock()
            .unwrap_or_else(|p| p.into_inner())
            .take();
        let record = self.file().session;
        if let Some(c) = &current {
            if let Err(e) = c.client.logout().await {
                log::warn!("Emby logout not confirmed by the server: {}", e.kind());
            }
        }
        if let Some(r) = record {
            self.store
                .delete(&credentials::account(&r.server_id, &r.user_id))?;
        }
        self.update_file(|f| f.session = None)
    }
}

async fn summary(client: &EmbyClient) -> Result<ServerSummary, EmbyError> {
    let info = client.public_info().await?;
    Ok(ServerSummary {
        url: client.server().as_str().to_owned(),
        name: info.server_name,
        version: info.version,
        id: info.id,
        encrypted: client.server().is_https(),
    })
}
