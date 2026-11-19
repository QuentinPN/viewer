//! Request session service (ADR 0125): address in `request.json`, token in
//! the OS credential store per Emby account, single-flight exchange, one retry
//! after a 401, persistent sign-out.

use std::collections::HashMap;
use std::future::Future;
use std::path::PathBuf;
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use reqwest::Method;
use serde::de::DeserializeOwned;
use serde::{Deserialize, Serialize};
use url::Url;

use super::client::{SessionToken, RequestClient};
use super::images::UrImage;
use super::models::{ApiVersion, Viewer, ViewerResponse};
use super::{RequestError, account, address_text, ensure_compatible, normalize_address};
use crate::emby::artwork_cache::ArtworkCache;
use crate::emby::client::AccessToken;
use crate::emby::credentials::CredentialStore;

/// The Emby account a Request session belongs to, with the token to trade.
#[derive(Debug, Clone)]
pub struct Identity {
    pub server_id: String,
    pub user_id: String,
    pub emby_token: AccessToken,
}

impl Identity {
    fn account(&self) -> String {
        account(&self.server_id, &self.user_id)
    }
}

/// `request.json`: not a secret (address and signed-out accounts).
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct Settings {
    #[serde(default)]
    address: Option<String>,
    /// Credential accounts that chose "Se déconnecter".
    #[serde(default)]
    signed_out: Vec<String>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub enum UrState {
    NotConfigured,
    SignedOut,
    TooOld,
    Unreachable,
    Rejected,
    Ready,
}

/// State of the « Demander » tab.
#[derive(Debug, Clone, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct UrStatus {
    pub state: UrState,
    pub address: Option<String>,
    pub viewer: Option<Viewer>,
    pub api_version: Option<String>,
    pub missing: Vec<String>,
    pub message: Option<String>,
}

impl UrStatus {
    fn new(state: UrState, address: Option<String>) -> Self {
        Self {
            state,
            address,
            viewer: None,
            api_version: None,
            missing: Vec::new(),
            message: None,
        }
    }

    fn failed(address: Option<String>, e: RequestError) -> Self {
        let state = match &e {
            RequestError::NotConfigured => UrState::NotConfigured,
            RequestError::SignedOut => UrState::SignedOut,
            RequestError::TooOld { .. } => UrState::TooOld,
            RequestError::Rejected(_)
            | RequestError::RateLimited { .. }
            | RequestError::Forbidden(_) => UrState::Rejected,
            _ => UrState::Unreachable,
        };
        let mut status = Self::new(state, address);
        status.message = Some(e.to_string());
        if let RequestError::TooOld {
            api_version,
            missing,
        } = e
        {
            status.api_version = Some(api_version);
            status.missing = missing;
        }
        status
    }
}

/// A server address checked compatible, with its client.
#[derive(Clone)]
struct Checked {
    address: String,
    client: RequestClient,
    version: ApiVersion,
}

/// Best-effort `POST /api/auth/logout` of a session already deleted locally.
pub struct Revocation {
    client: RequestClient,
    token: SessionToken,
}

impl Revocation {
    pub async fn send(self) {
        if let Err(e) = self
            .client
            .send_empty(Method::POST, "/api/auth/logout", &self.token)
            .await
        {
            log::warn!("Request logout not confirmed: {}", e.kind());
        }
    }
}

pub struct RequestService {
    store: Box<dyn CredentialStore>,
    path: PathBuf,
    settings: Mutex<Settings>,
    device_name: String,
    version: String,
    checked: Mutex<Option<Checked>>,
    /// Single-flight exchange: the server allows 10 failures per IP and 5 minutes,
    /// shared by the household (ADR 0125).
    exchange: tokio::sync::Mutex<()>,
    /// Accounts whose exchange was refused, with the Emby token refused: no new
    /// attempt with that token until "Se connecter" or another address.
    rejected: Mutex<HashMap<String, (AccessToken, String)>>,
    /// After a 429, nothing is sent before this instant.
    blocked_until: Mutex<Option<Instant>>,
    artwork: Option<Arc<ArtworkCache>>,
}

fn lock<T>(m: &Mutex<T>) -> std::sync::MutexGuard<'_, T> {
    m.lock().unwrap_or_else(|p| p.into_inner())
}

impl RequestService {
    pub fn new(
        store: Box<dyn CredentialStore>,
        path: PathBuf,
        device_name: String,
        version: String,
    ) -> Self {
        let mut settings: Settings = std::fs::read(&path)
            .ok()
            .and_then(|b| serde_json::from_slice(&b).ok())
            .unwrap_or_default();
        // A hand-edited file never bypasses the address policy.
        settings.address = settings
            .address
            .and_then(|a| normalize_address(&a).ok())
            .map(|u| address_text(&u));
        Self {
            store,
            path,
            settings: Mutex::new(settings),
            device_name,
            version,
            checked: Mutex::new(None),
            exchange: tokio::sync::Mutex::new(()),
            rejected: Mutex::new(HashMap::new()),
            blocked_until: Mutex::new(None),
            artwork: None,
        }
    }

    pub fn with_artwork_cache(mut self, cache: Option<Arc<ArtworkCache>>) -> Self {
        self.artwork = cache;
        self
    }

    fn address(&self) -> Option<String> {
        lock(&self.settings).address.clone()
    }

    fn update_settings(&self, f: impl FnOnce(&mut Settings)) -> Result<(), RequestError> {
        let mut guard = lock(&self.settings);
        let mut next = guard.clone();
        f(&mut next);
        let io = |e: std::io::Error| RequestError::Settings(format!("request.json : {e}"));
        if let Some(dir) = self.path.parent() {
            std::fs::create_dir_all(dir).map_err(io)?;
        }
        let bytes = serde_json::to_vec_pretty(&next)
            .map_err(|e| RequestError::Settings(e.to_string()))?;
        let temp = self.path.with_extension("json.tmp");
        std::fs::write(&temp, bytes).map_err(io)?;
        std::fs::rename(&temp, &self.path).map_err(io)?;
        *guard = next;
        Ok(())
    }

    fn is_signed_out(&self, account: &str) -> bool {
        lock(&self.settings).signed_out.iter().any(|a| a == account)
    }

    /// The stored token, only if it was issued by the current address: a token of
    /// a previous address is never sent to another host.
    fn load(&self, account: &str) -> Result<Option<SessionToken>, RequestError> {
        let Some(stored) = self.store.load(account)? else {
            return Ok(None);
        };
        let address = self.address();
        Ok(stored
            .expose()
            .split_once(' ')
            .filter(|(issuer, _)| address.as_deref() == Some(*issuer))
            .map(|(_, token)| SessionToken::new(token.to_owned())))
    }

    /// Kept as `{address} {token}` (a token has no space).
    fn save(&self, account: &str, token: &SessionToken) -> Result<(), RequestError> {
        let address = self.address().ok_or(RequestError::NotConfigured)?;
        let value = format!("{address} {}", token.expose());
        Ok(self.store.save(account, &AccessToken::new(value))?)
    }

    /// Fails fast while a 429 of the exchange is running: only the exchange counts
    /// against the shared failure limit, a 429 elsewhere concerns that call only.
    fn check_blocked(&self) -> Result<(), RequestError> {
        let mut guard = lock(&self.blocked_until);
        match *guard {
            Some(until) if until > Instant::now() => Err(RequestError::RateLimited {
                retry_after_s: until.saturating_duration_since(Instant::now()).as_secs() + 1,
            }),
            Some(_) => {
                *guard = None;
                Ok(())
            }
            None => Ok(()),
        }
    }

    fn note<T>(&self, result: Result<T, RequestError>) -> Result<T, RequestError> {
        if let Err(RequestError::RateLimited { retry_after_s }) = &result {
            *lock(&self.blocked_until) = Some(Instant::now() + Duration::from_secs(*retry_after_s));
        }
        result
    }

    /// Client of the configured address, its API version checked on first use and
    /// kept until the address changes.
    async fn client(&self) -> Result<Checked, RequestError> {
        let address = self.address().ok_or(RequestError::NotConfigured)?;
        if let Some(c) = lock(&self.checked)
            .as_ref()
            .filter(|c| c.address == address)
        {
            return Ok(c.clone());
        }
        let url = Url::parse(&address).map_err(|_| RequestError::InvalidAddress)?;
        let client = RequestClient::new(&url, &self.version)?;
        let version = client.version().await?;
        ensure_compatible(&version)?;
        let checked = Checked {
            address,
            client,
            version,
        };
        let mut guard = lock(&self.checked);
        if self.address().as_deref() == Some(checked.address.as_str()) {
            *guard = Some(checked.clone());
        }
        Ok(checked)
    }

    /// The stored token, or a new one from a single-flight exchange. `failed` is
    /// the token a 401 refused: it is replaced only if still the stored one.
    async fn token(
        &self,
        client: &RequestClient,
        id: &Identity,
        failed: Option<&SessionToken>,
    ) -> Result<SessionToken, RequestError> {
        let key = id.account();
        // First: a token left behind by a failed vault delete is never used.
        if self.is_signed_out(&key) {
            return Err(RequestError::SignedOut);
        }
        if failed.is_none()
            && let Some(t) = self.load(&key)?
        {
            return Ok(t);
        }
        let _flight = self.exchange.lock().await;
        if self.is_signed_out(&key) {
            return Err(RequestError::SignedOut);
        }
        let stored = self.load(&key)?;
        match (&stored, failed) {
            (Some(t), Some(f)) if t != f => return Ok(t.clone()),
            (Some(t), None) => return Ok(t.clone()),
            (Some(_), Some(_)) => self.store.delete(&key)?,
            (None, _) => {}
        }
        if let Some((refused, message)) = lock(&self.rejected).get(&key)
            && refused == &id.emby_token
        {
            return Err(RequestError::Rejected(message.clone()));
        }
        self.check_blocked()?;
        let name = format!("{} (Windows)", self.device_name);
        let session = match self.note(client.exchange(&id.user_id, &id.emby_token, &name).await) {
            Err(RequestError::Rejected(message)) => {
                self.latch_rejected(id, &message);
                return Err(RequestError::Rejected(message));
            }
            other => other?,
        };
        self.save(&key, &session.token)?;
        Ok(session.token)
    }

    fn latch_rejected(&self, id: &Identity, message: &str) {
        lock(&self.rejected).insert(id.account(), (id.emby_token.clone(), message.to_owned()));
    }

    /// Runs `f` with a session token: stored or exchanged; on a 401 the token is
    /// replaced once and `f` replayed; a second 401 ends in `Rejected`.
    pub async fn call<T, F, Fut>(&self, id: &Identity, f: F) -> Result<T, RequestError>
    where
        F: Fn(RequestClient, SessionToken) -> Fut,
        Fut: Future<Output = Result<T, RequestError>>,
    {
        if self.is_signed_out(&id.account()) {
            return Err(RequestError::SignedOut);
        }
        let client = self.client().await?.client;
        let first = self.token(&client, id, None).await?;
        match f(client.clone(), first.clone()).await {
            Err(RequestError::Rejected(_)) => {}
            other => return other,
        }
        let second = self.token(&client, id, Some(&first)).await?;
        match f(client, second.clone()).await {
            Err(RequestError::Rejected(message)) => {
                let _flight = self.exchange.lock().await;
                if self.load(&id.account())?.as_ref() == Some(&second) {
                    self.store.delete(&id.account())?;
                }
                self.latch_rejected(id, &message);
                Err(RequestError::Rejected(message))
            }
            other => other,
        }
    }

    pub async fn get<T: DeserializeOwned>(
        &self,
        id: &Identity,
        path: &str,
        query: &[(&str, String)],
    ) -> Result<T, RequestError> {
        self.call(id, |c, t| async move { c.get(path, query, &t).await })
            .await
    }

    pub async fn send<B: Serialize, T: DeserializeOwned>(
        &self,
        id: &Identity,
        method: Method,
        path: &str,
        body: Option<&B>,
    ) -> Result<T, RequestError> {
        self.call(id, |c, t| {
            let method = method.clone();
            async move { c.send(method, path, body, &t).await }
        })
        .await
    }

    pub async fn send_empty(
        &self,
        id: &Identity,
        method: Method,
        path: &str,
    ) -> Result<(), RequestError> {
        self.call(id, |c, t| {
            let method = method.clone();
            async move { c.send_empty(method, path, &t).await }
        })
        .await
    }

    /// State of the tab: address, sign-out, compatibility, then the viewer.
    pub async fn status(&self, id: &Identity) -> UrStatus {
        let address = self.address();
        if address.is_none() {
            return UrStatus::new(UrState::NotConfigured, None);
        }
        if self.is_signed_out(&id.account()) {
            return UrStatus::new(UrState::SignedOut, address);
        }
        match self.get::<ViewerResponse>(id, "/api/auth/me", &[]).await {
            Ok(me) => UrStatus {
                viewer: Some(me.viewer),
                api_version: lock(&self.checked)
                    .as_ref()
                    .map(|c| c.version.api_version.clone()),
                ..UrStatus::new(UrState::Ready, address)
            },
            Err(e) => UrStatus::failed(address, e),
        }
    }

    /// Saves a new address; every cached check and refusal is dropped.
    pub fn set_address(&self, input: &str) -> Result<(), RequestError> {
        let url = normalize_address(input)?;
        self.update_settings(|s| s.address = Some(address_text(&url)))?;
        *lock(&self.checked) = None;
        lock(&self.rejected).clear();
        *lock(&self.blocked_until) = None;
        Ok(())
    }

    /// "Se connecter": allows the exchange again for this account.
    pub fn sign_in(&self, id: &Identity) -> Result<(), RequestError> {
        let key = id.account();
        self.update_settings(|s| s.signed_out.retain(|a| a != &key))?;
        lock(&self.rejected).remove(&key);
        Ok(())
    }

    /// "Se déconnecter": persistent flag, token deleted, then revoked on the
    /// server at best (an unreachable server never keeps the token on disk).
    pub async fn sign_out(&self, id: &Identity) -> Result<(), RequestError> {
        let key = id.account();
        let token = {
            let _flight = self.exchange.lock().await;
            self.update_settings(|s| {
                if !s.signed_out.contains(&key) {
                    s.signed_out.push(key.clone());
                }
            })?;
            let token = self.load(&key).ok().flatten();
            self.store.delete(&key)?;
            token
        };
        if let Some(revocation) = self.revocation(token) {
            revocation.send().await;
        }
        Ok(())
    }

    /// Revocation of the stored session of an Emby account about to be signed
    /// out or forgotten (the Emby service deletes the token itself).
    pub fn revocation_for(&self, server_id: &str, user_id: &str) -> Option<Revocation> {
        self.revocation(self.load(&account(server_id, user_id)).ok().flatten())
    }

    fn revocation(&self, token: Option<SessionToken>) -> Option<Revocation> {
        let token = token?;
        let url = Url::parse(&self.address()?).ok()?;
        let client = RequestClient::new(&url, &self.version).ok()?;
        Some(Revocation { client, token })
    }

    /// Artwork for the `requestimg` protocol: from the disk cache, else fetched with
    /// the Bearer (same 401 handling as any call) and kept.
    pub async fn image(
        &self,
        id: &Identity,
        req: &UrImage,
    ) -> Result<(String, Vec<u8>), RequestError> {
        if self.address().is_none() {
            return Err(RequestError::NotConfigured);
        }
        if self.is_signed_out(&id.account()) {
            return Err(RequestError::SignedOut);
        }
        let key = req.cache_key();
        if let Some(cache) = &self.artwork {
            let (cache, key) = (Arc::clone(cache), key.clone());
            if let Ok(Some(hit)) =
                tauri::async_runtime::spawn_blocking(move || cache.get(&key)).await
            {
                return Ok(hit);
            }
        }
        let (bytes, content_type) = self
            .call(
                id,
                |c, t| async move { c.image(req.size, &req.file, &t).await },
            )
            .await?;
        if let Some(cache) = &self.artwork {
            let (cache, data) = (Arc::clone(cache), bytes.clone());
            let kept = tauri::async_runtime::spawn_blocking(move || cache.put(&key, &data)).await;
            if let Ok(Err(e)) = kept {
                log::debug!("Request artwork not cached: {:?}", e.kind());
            }
        }
        Ok((content_type, bytes))
    }
}
