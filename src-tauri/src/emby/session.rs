//! Sign-in lifecycle: probe, sign in, restore at startup, sign out. Non-secret
//! session metadata lives in `session.json`; the token lives in a `CredentialStore`.

use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Duration;

use serde::{Deserialize, Serialize};

use super::EmbyError;
use super::artwork_cache::{ArtworkCache, ArtworkCacheCleared, ArtworkCacheStats};
use super::chapters::{self, Chapter};
use super::client::{
    AccessToken, DeviceIdentity, EmbyClient, EpisodesQuery, ItemsQuery, ItemsShape,
};
use super::credentials::{self, CredentialStore};
use super::images::{self, ImageRequest, ImageType};
use super::models::{
    BaseItemDto, CastMember, DetailKind, Episode, EpisodeContext, EpisodeLink, EpisodePage,
    HeroEpisode, HeroItem, HeroReason, ImageRef, ItemDetail, ItemPage, ItemsOptions, Library,
    LibraryKind, MediaInfo, MediaItem, MediaSourceDto, MediaStreamDto, PlayMethod, PlaybackStopped,
    PlaybackTitle, Playlist, PlaylistCreated, ResumePoint, SavedServer, SearchResults, Season,
    ServerSummary, SessionStatus, ShelfAccess, ShelfServer, SignedInUser, SortNameEntry,
    StreamInfo, UserItemData, VideoInfo, WatchItem, WatchKind, is_usable_decade, is_usable_genre,
};
use super::playback::{self, PlaybackPlan};
use super::quality::{BITRATE_CHOICES, PlaybackQuality, QualityCaps, QualityStore, StreamingLimit};
use super::reporting::Report;
use super::server_url::ServerUrl;
use super::tracks::{self, TrackPreferences};
use crate::segments::{MediaIds, Segment, SegmentQuery, emby_markers};

/// Contents of `session.json`. Contains no secret.
#[derive(Debug, Clone, Default, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct SessionFile {
    pub device_id: String,
    /// Prefills the connection form after sign-out or an expired session.
    pub last_server_url: Option<String>,
    pub session: Option<SessionRecord>,
    /// Sessions set aside by "Changer de serveur", newest first (ADR 0012). Their
    /// tokens stay in the credential store.
    #[serde(default)]
    pub saved: Vec<SessionRecord>,
    /// Non-secret hint of the last session the server rejected: prefills the
    /// reauthentication screen, even after a restart.
    #[serde(default)]
    pub reauth: Option<SessionRecord>,
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
    /// The server rejected this session before it was put on the shelf: its tile
    /// says "Mot de passe requis" (ADR 0103).
    #[serde(default, skip_serializing_if = "std::ops::Not::not")]
    pub rejected: bool,
}

impl SessionRecord {
    fn of(user: &SignedInUser) -> Self {
        Self {
            server_url: user.server.url.clone(),
            server_id: user.server.id.clone(),
            server_name: user.server.name.clone(),
            server_version: user.server.version.clone(),
            user_id: user.user_id.clone(),
            user_name: user.user_name.clone(),
            rejected: false,
        }
    }

    fn is(&self, server_id: &str, user_id: &str) -> bool {
        self.server_id == server_id && self.user_id == user_id
    }

    fn same_account(&self, other: &SessionRecord) -> bool {
        self.server_id == other.server_id && self.user_id == other.user_id
    }

    fn account(&self) -> String {
        credentials::account(&self.server_id, &self.user_id)
    }

    fn summary(&self) -> SavedServer {
        SavedServer {
            url: self.server_url.clone(),
            id: self.server_id.clone(),
            name: self.server_name.clone(),
            version: self.server_version.clone(),
            encrypted: self.server_url.starts_with("https://"),
            user_id: self.user_id.clone(),
            user_name: self.user_name.clone(),
        }
    }
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

    /// Puts a record at the front of the shelf (`saved`), replacing the same
    /// account, and returns the records pushed out beyond `MAX_SAVED`.
    fn shelve(&mut self, record: SessionRecord) -> Vec<SessionRecord> {
        self.saved.retain(|s| !s.same_account(&record));
        self.saved.insert(0, record);
        let keep = self.saved.len().min(MAX_SAVED);
        self.saved.split_off(keep)
    }

    /// Every record still naming this address.
    fn uses_url(&self, url: &str) -> bool {
        self.session
            .iter()
            .chain(self.reauth.iter())
            .chain(self.saved.iter())
            .any(|r| r.server_url == url)
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

/// Largest page the UI may ask for.
pub const MAX_PAGE: u32 = 200;
/// Cards per home row: about two window widths.
const HOME_ROW: u32 = 16;
/// Most titles of the home hero (ADR 0015).
const HERO_SIZE: usize = 5;
/// Search results shown per type (ADR 0011); one more is asked to know if more exist.
pub const SEARCH_SHOWN: usize = 24;
/// Episodes asked per search (ADR 0020): Emby also matches the series name, so
/// the episodes of a matching series come first and are dropped here.
pub const SEARCH_EPISODES_ASKED: u32 = 100;
/// Shorter queries are not sent: the server answers nothing to one letter.
const SEARCH_MIN_CHARS: usize = 2;
const SEARCH_MAX_CHARS: usize = 100;
const TICKS_PER_MINUTE: i64 = 600_000_000;
const IMAGE_CONCURRENCY: usize = 6;
/// Sessions kept aside by "Changer de serveur"; the oldest is forgotten beyond.
pub const MAX_SAVED: usize = 8;
/// Playlists the card menu offers (ADR 0100).
const PLAYLISTS_MAX: u32 = 200;
/// Longest playlist name accepted, in characters.
pub const PLAYLIST_NAME_MAX: usize = 200;

struct Current {
    client: EmbyClient,
    user: SignedInUser,
    /// Last `/Views` answer: item queries only accept these ids as parent.
    libraries: Vec<Library>,
}

pub struct EmbyService {
    store: Box<dyn CredentialStore>,
    path: PathBuf,
    file: Mutex<SessionFile>,
    device_name: String,
    version: String,
    current: Mutex<Option<Current>>,
    /// Bounds concurrent artwork downloads to spare the server (ADR 0005).
    image_permits: tokio::sync::Semaphore,
    /// Debug limit of the scenarios (ADR 0009); wins over the setting.
    bitrate_override: Option<u64>,
    /// Bitrate caps per server (ADR 0037).
    quality: QualityStore,
    /// Bumped (under the `current` lock) by every sign-in, sign-out, server change
    /// and resume: a restore started before commits nothing (ADR 0012).
    epoch: AtomicU64,
    /// Connect and request bounds of new clients; `None` keeps the client defaults.
    timeouts: Option<(Duration, Duration)>,
    /// Client of the last session ended by a 401: its other requests in flight
    /// still answer `SessionExpired`, not `Cancelled`.
    expired: Mutex<Option<EmbyClient>>,
    /// Artwork kept on disk across launches (ADR 0019); `None` fetches every time.
    artwork: Option<Arc<ArtworkCache>>,
}

impl EmbyService {
    pub fn new(
        store: Box<dyn CredentialStore>,
        path: PathBuf,
        device_name: String,
        version: String,
    ) -> Self {
        let file = SessionFile::load_or_init(&path);
        let quality = QualityStore::load(path.with_file_name("playback-quality.json"));
        Self {
            store,
            path,
            file: Mutex::new(file),
            device_name,
            version,
            current: Mutex::new(None),
            image_permits: tokio::sync::Semaphore::new(IMAGE_CONCURRENCY),
            bitrate_override: None,
            quality,
            epoch: AtomicU64::new(0),
            timeouts: None,
            expired: Mutex::new(None),
            artwork: None,
        }
    }

    pub fn with_artwork_cache(mut self, cache: ArtworkCache) -> Self {
        self.artwork = Some(Arc::new(cache));
        self
    }

    pub async fn artwork_stats(&self) -> Option<ArtworkCacheStats> {
        let cache = Arc::clone(self.artwork.as_ref()?);
        tauri::async_runtime::spawn_blocking(move || cache.stats())
            .await
            .ok()
    }

    /// Empties the artwork cache of every server (ADR 0021); `None` without a cache.
    /// Returns what was removed. Images are downloaded again when next shown.
    pub async fn clear_artwork(&self) -> Option<ArtworkCacheCleared> {
        let cache = Arc::clone(self.artwork.as_ref()?);
        tauri::async_runtime::spawn_blocking(move || {
            let (entries, bytes) = cache.clear();
            log::info!("artwork cache cleared: {entries} images, {bytes} bytes");
            ArtworkCacheCleared { entries, bytes }
        })
        .await
        .ok()
    }

    /// Short bounds, so tests can simulate a server that never answers.
    pub fn with_timeouts(mut self, connect: Duration, request: Duration) -> Self {
        self.timeouts = Some((connect, request));
        self
    }

    fn new_client(&self, url: &str) -> Result<EmbyClient, EmbyError> {
        let server = ServerUrl::parse(url)?;
        match self.timeouts {
            Some((connect, request)) => {
                EmbyClient::with_timeouts(server, self.device(), connect, request)
            }
            None => EmbyClient::new(server, self.device()),
        }
    }

    fn epoch(&self) -> u64 {
        self.epoch.load(Ordering::SeqCst)
    }

    /// Lower streaming limit, e.g. to make the server transcode in tests.
    pub fn with_max_streaming_bitrate(mut self, bitrate: u64) -> Self {
        self.bitrate_override = Some(bitrate);
        self
    }

    /// Bitrate caps of the current server (ADR 0037).
    pub fn playback_quality(&self) -> Result<PlaybackQuality, EmbyError> {
        let (server_id, local_address) = self.quality_scope()?;
        let caps = self.quality.get(&server_id);
        Ok(PlaybackQuality {
            local: caps.local,
            remote: caps.remote,
            local_address,
            choices: BITRATE_CHOICES.to_vec(),
        })
    }

    /// Keeps the caps of the current server; a value off the list is refused.
    pub fn set_playback_quality(&self, caps: QualityCaps) -> Result<PlaybackQuality, EmbyError> {
        let (server_id, _) = self.quality_scope()?;
        self.quality.set(&server_id, caps)?;
        self.playback_quality()
    }

    fn quality_scope(&self) -> Result<(String, bool), EmbyError> {
        self.lock_current()
            .as_ref()
            .map(|c| (c.user.server.id.clone(), c.client.server().is_local()))
            .ok_or(EmbyError::NotSignedIn)
    }

    /// The debug override, then the server's cap for the kind of address of the
    /// session, then the default limit (ADR 0037).
    fn streaming_limit(&self, client: &EmbyClient) -> StreamingLimit {
        let local_address = client.server().is_local();
        let cap = self
            .bitrate_override
            .or_else(|| self.quality.get(&self.scope()).for_address(local_address));
        StreamingLimit {
            bitrate: cap.unwrap_or(playback::MAX_STREAMING_BITRATE),
            cap,
            local_address,
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

    fn update_file<R>(&self, f: impl FnOnce(&mut SessionFile) -> R) -> Result<R, EmbyError> {
        let mut guard = self.file.lock().unwrap_or_else(|p| p.into_inner());
        let result = f(&mut guard);
        guard.save(&self.path)?;
        Ok(result)
    }

    fn lock_current(&self) -> std::sync::MutexGuard<'_, Option<Current>> {
        self.current.lock().unwrap_or_else(|p| p.into_inner())
    }

    /// Replaces the open session and starts a new epoch.
    fn set_current(&self, current: Option<Current>) {
        let mut guard = self.lock_current();
        self.epoch.fetch_add(1, Ordering::SeqCst);
        *guard = current;
    }

    /// Server id of the open session, or empty: scopes image URLs (ADR 0012).
    fn scope(&self) -> String {
        self.lock_current()
            .as_ref()
            .map(|c| c.user.server.id.clone())
            .unwrap_or_default()
    }

    /// The server rejected this session: its token is deleted, and `session.json`
    /// keeps a non-secret hint to prefill the reauthentication screen.
    fn forget_rejected(&self, record: &SessionRecord) {
        if let Err(e) = self.store.delete(&record.account()) {
            log::warn!("rejected token not deleted: {}", e.kind());
        }
        let updated = self.update_file(|f| {
            if f.session.as_ref().is_some_and(|s| s.same_account(record)) {
                f.session = None;
            }
            f.reauth = Some(record.clone());
        });
        if let Err(e) = updated {
            log::warn!("session.json not updated: {}", e.kind());
        }
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

    fn session(&self) -> Result<(EmbyClient, String), EmbyError> {
        self.current
            .lock()
            .unwrap_or_else(|p| p.into_inner())
            .as_ref()
            .map(|c| (c.client.clone(), c.user.user_id.clone()))
            .ok_or(EmbyError::NotSignedIn)
    }

    /// A token rejected while browsing is forgotten like at startup, so the next
    /// launch does not retry it. Only the session whose token was refused ends: a
    /// late 401 from a previous session answers `Cancelled` (ADR 0012).
    pub(super) fn expire_on_401<T>(
        &self,
        client: &EmbyClient,
        result: Result<T, EmbyError>,
    ) -> Result<T, EmbyError> {
        if let Err(EmbyError::SessionExpired) = &result {
            let mut guard = self.lock_current();
            let mut expired = self.expired.lock().unwrap_or_else(|p| p.into_inner());
            if !guard.as_ref().is_some_and(|c| c.client.same_token(client)) {
                // Already ended by a parallel request of the same session, or a
                // late answer for an older session.
                let same = expired.as_ref().is_some_and(|e| e.same_token(client));
                return if same {
                    result
                } else {
                    Err(EmbyError::Cancelled)
                };
            }
            self.epoch.fetch_add(1, Ordering::SeqCst);
            if let Some(c) = guard.take() {
                *expired = Some(c.client);
                drop((guard, expired));
                self.forget_rejected(&SessionRecord::of(&c.user));
            }
        }
        result
    }

    /// Film and series libraries of the user, in server order. Other collection
    /// types (music, photos, live TV…) are not browsable yet and are left out.
    pub async fn libraries(&self) -> Result<Vec<Library>, EmbyError> {
        let (client, user_id) = self.session()?;
        let scope = self.scope();
        let views = self.expire_on_401(&client, client.views(&user_id).await)?;
        let libraries: Vec<Library> = views
            .items
            .into_iter()
            .filter_map(|v| {
                let kind = LibraryKind::from_collection_type(v.collection_type.as_deref())?;
                let image = images::image_ref(
                    &scope,
                    &v.id,
                    ImageType::Primary,
                    v.image_tags.get("Primary").map(String::as_str),
                    images::LIBRARY_WIDTHS,
                );
                Some(Library {
                    id: v.id,
                    name: v.name,
                    kind,
                    image,
                })
            })
            .collect();
        if let Some(c) = self
            .current
            .lock()
            .unwrap_or_else(|p| p.into_inner())
            .as_mut()
        {
            c.libraries = libraries.clone();
        }
        Ok(libraries)
    }

    fn library_kind(&self, library_id: &str) -> Option<LibraryKind> {
        self.current
            .lock()
            .unwrap_or_else(|p| p.into_inner())
            .as_ref()?
            .libraries
            .iter()
            .find(|l| l.id == library_id)
            .map(|l| l.kind)
    }

    /// Kind of a library of the session; the list is loaded when unknown, and
    /// an id outside it is refused (Emby would answer with the whole server).
    async fn known_library(&self, library_id: &str) -> Result<LibraryKind, EmbyError> {
        match self.library_kind(library_id) {
            Some(k) => Ok(k),
            None => {
                self.libraries().await?;
                self.library_kind(library_id).ok_or(EmbyError::NotFound)
            }
        }
    }

    /// One page of films or series of a library, sorted and filtered as asked
    /// (ADR 0013). `limit` is clamped to `1..=MAX_PAGE`.
    pub async fn items(
        &self,
        library_id: &str,
        start_index: u32,
        limit: u32,
        options: &ItemsOptions,
    ) -> Result<ItemPage, EmbyError> {
        check_options(options)?;
        let kind = self.known_library(library_id).await?;
        let (client, user_id) = self.session()?;
        let scope = self.scope();
        let query = ItemsQuery {
            parent_id: library_id,
            item_types: kind.item_types(),
            start_index,
            limit: limit.clamp(1, MAX_PAGE),
            options,
            shape: ItemsShape::Page,
        };
        let page = self.expire_on_401(&client, client.items(&user_id, &query).await)?;
        let returned = u32::try_from(page.items.len()).unwrap_or(u32::MAX);
        Ok(ItemPage {
            next_index: start_index.saturating_add(returned),
            items: page
                .items
                .into_iter()
                .filter_map(|d| media_item(d, &scope))
                .collect(),
            start_index,
            total: page.total_record_count,
        })
    }

    /// Every title of a library grid in its order, with its sort name: the
    /// letter rail jumps from it (ADR 0101). Unlike pages, nothing is dropped, so
    /// positions match the server's.
    pub async fn sort_names(
        &self,
        library_id: &str,
        options: &ItemsOptions,
    ) -> Result<Vec<SortNameEntry>, EmbyError> {
        check_options(options)?;
        let kind = self.known_library(library_id).await?;
        let (client, user_id) = self.session()?;
        let query = ItemsQuery {
            parent_id: library_id,
            item_types: kind.item_types(),
            start_index: 0,
            limit: 0,
            options,
            shape: ItemsShape::SortNames,
        };
        let page = self.expire_on_401(&client, client.items(&user_id, &query).await)?;
        Ok(page
            .items
            .into_iter()
            .map(|d| SortNameEntry {
                name: d
                    .sort_name
                    .filter(|n| !n.trim().is_empty())
                    .unwrap_or(d.name),
                id: d.id,
            })
            .collect())
    }

    /// One title drawn by the server among those the grid shows (ADR 0101),
    /// `None` when none matches.
    pub async fn random_item(
        &self,
        library_id: &str,
        options: &ItemsOptions,
    ) -> Result<Option<MediaItem>, EmbyError> {
        check_options(options)?;
        let kind = self.known_library(library_id).await?;
        let (client, user_id) = self.session()?;
        let scope = self.scope();
        let query = ItemsQuery {
            parent_id: library_id,
            item_types: kind.item_types(),
            start_index: 0,
            limit: 1,
            options,
            shape: ItemsShape::Random,
        };
        let page = self.expire_on_401(&client, client.items(&user_id, &query).await)?;
        Ok(page.items.into_iter().find_map(|d| media_item(d, &scope)))
    }

    /// Production years of a library, newest first, without repeats (decade
    /// filter, ADR 0101).
    pub async fn production_years(&self, library_id: &str) -> Result<Vec<i32>, EmbyError> {
        let kind = self.known_library(library_id).await?;
        let (client, user_id) = self.session()?;
        let page = self.expire_on_401(
            &client,
            client
                .production_years(&user_id, library_id, kind.item_types())
                .await,
        )?;
        let mut years: Vec<i32> = page
            .items
            .into_iter()
            .filter_map(|d| d.production_year)
            .filter(|y| (1880..=2199).contains(y))
            .collect();
        years.sort_unstable_by(|a, b| b.cmp(a));
        years.dedup();
        Ok(years)
    }

    /// Genres offered as a filter of a library grid, by name. Names the server
    /// could not filter on (`|` is its separator) are left out.
    pub async fn genres(&self, library_id: &str) -> Result<Vec<String>, EmbyError> {
        let kind = self.known_library(library_id).await?;
        let (client, user_id) = self.session()?;
        let page = self.expire_on_401(
            &client,
            client.genres(&user_id, library_id, kind.item_types()).await,
        )?;
        let mut names: Vec<String> = page
            .items
            .into_iter()
            .map(|d| d.name.trim().to_owned())
            .filter(|n| is_usable_genre(n))
            .collect();
        names.dedup();
        Ok(names)
    }

    /// Home row "Reprendre": films and episodes started but not finished.
    pub async fn continue_watching(&self) -> Result<Vec<WatchItem>, EmbyError> {
        let (client, user_id) = self.session()?;
        let scope = self.scope();
        let res = self.expire_on_401(&client, client.resume_items(&user_id, HOME_ROW).await)?;
        Ok(res
            .items
            .into_iter()
            .filter_map(|d| watch_item(d, &scope))
            .collect())
    }

    /// Home hero (ADR 0015): up to five titles with a backdrop, the ones to resume
    /// first, then films and series added recently and not watched; one title per
    /// film or series. A series plays its first episode not watched yet.
    pub async fn hero(&self) -> Result<Vec<HeroItem>, EmbyError> {
        let (client, user_id) = self.session()?;
        let scope = self.scope();
        let (resumed, latest) = tokio::join!(
            client.hero_resume(&user_id, HOME_ROW),
            client.hero_latest(&user_id, 2 * HERO_SIZE as u32)
        );
        let resumed = self.expire_on_401(&client, resumed)?.items;
        let latest = self.expire_on_401(&client, latest)?.items;
        let mut hero: Vec<HeroItem> = Vec::new();
        let candidates = resumed
            .into_iter()
            .filter_map(|d| resumed_hero(d, &scope))
            .chain(latest.into_iter().filter_map(|d| latest_hero(d, &scope)));
        for item in candidates {
            if hero.len() == HERO_SIZE {
                break;
            }
            if hero.iter().all(|h| h.detail.id != item.detail.id) {
                hero.push(item);
            }
        }
        // A handful of series at most: one request each, in turn.
        for item in &mut hero {
            if item.play_id.is_some() || item.detail.item_type != "Series" {
                continue;
            }
            let page = self.expire_on_401(
                &client,
                client
                    .first_unplayed_episode(&user_id, &item.detail.id)
                    .await,
            )?;
            if let Some(dto) = page
                .items
                .into_iter()
                .find(|e| e.item_type == "Episode" && is_id(&e.id))
            {
                let user_data = dto.user_data.clone().unwrap_or_default();
                item.resume = resume(&user_data, dto.run_time_ticks);
                item.episode = Some(hero_episode(&dto));
                item.play_id = Some(dto.id);
            }
        }
        Ok(hero)
    }

    /// Home row "À suivre": the next episode of each series in progress.
    pub async fn next_up(&self) -> Result<Vec<WatchItem>, EmbyError> {
        let (client, user_id) = self.session()?;
        let scope = self.scope();
        // Emby 4.10.1.0 answers an empty `/Shows/NextUp` once an episode is watched,
        // and lists the next one in its "resume" home section instead (ADR 0010).
        let (shows, section) = tokio::join!(
            client.next_up(&user_id, HOME_ROW),
            self.resume_section(&client, &user_id)
        );
        let mut items = self.expire_on_401(&client, shows)?.items;
        for dto in section? {
            if items.iter().all(|i| i.id != dto.id) {
                items.push(dto);
            }
        }
        Ok(items
            .into_iter()
            .filter(|i| i.item_type == "Episode")
            .filter(|i| {
                i.user_data
                    .as_ref()
                    .is_none_or(|u| !u.played && u.playback_position_ticks == 0)
            })
            .filter_map(|d| watch_item(d, &scope))
            .take(HOME_ROW as usize)
            .collect())
    }

    /// Items of the server's "resume" home section when it includes Next Up; none
    /// when the server has no home sections (404) or no such section.
    async fn resume_section(
        &self,
        client: &EmbyClient,
        user_id: &str,
    ) -> Result<Vec<BaseItemDto>, EmbyError> {
        let sections = match client.home_sections(user_id).await {
            Err(EmbyError::NotFound) => return Ok(Vec::new()),
            other => self.expire_on_401(client, other)?,
        };
        let Some(section) = sections
            .into_iter()
            .find(|s| s.section_type == "resume" && s.include_next_up_in_resume && is_id(&s.id))
        else {
            return Ok(Vec::new());
        };
        let page = self.expire_on_401(
            client,
            client.section_items(user_id, &section.id, HOME_ROW).await,
        )?;
        Ok(page.items)
    }

    /// Films, series and episodes whose title matches `query` (ADR 0011, 0020),
    /// all searched at once. A query shorter than two characters once cleaned
    /// reaches no server.
    pub async fn search(&self, query: &str) -> Result<SearchResults, EmbyError> {
        let query = clean_search(query);
        if query.chars().count() < SEARCH_MIN_CHARS {
            return Ok(SearchResults::default());
        }
        let (client, user_id) = self.session()?;
        let scope = self.scope();
        let asked = SEARCH_SHOWN as u32 + 1;
        let (movies, series, episodes) = tokio::join!(
            client.search(&user_id, &query, "Movie", asked),
            client.search(&user_id, &query, "Series", asked),
            client.search(&user_id, &query, "Episode", SEARCH_EPISODES_ASKED)
        );
        fn shown<T>(items: Vec<T>) -> (Vec<T>, bool) {
            let more = items.len() > SEARCH_SHOWN;
            (items.into_iter().take(SEARCH_SHOWN).collect(), more)
        }
        let keep = |dtos: Vec<BaseItemDto>, item_type: &str| {
            shown(
                dtos.into_iter()
                    .filter(|d| d.item_type == item_type)
                    .filter_map(|d| media_item(d, &scope))
                    .collect(),
            )
        };
        let (movies, more_movies) = keep(self.expire_on_401(&client, movies)?.items, "Movie");
        let (series, more_series) = keep(self.expire_on_401(&client, series)?.items, "Series");
        // Only episodes whose own title matches: a full page may hide others.
        let episodes = self.expire_on_401(&client, episodes)?.items;
        let page_full = episodes.len() >= SEARCH_EPISODES_ASKED as usize;
        let (episodes, more) = shown(
            episodes
                .into_iter()
                .filter(|d| d.item_type == "Episode" && title_matches(&d.name, &query))
                .filter_map(|d| watch_item(d, &scope))
                .collect(),
        );
        let more_episodes = more || page_full;
        Ok(SearchResults {
            query,
            movies,
            series,
            episodes,
            more_movies,
            more_series,
            more_episodes,
        })
    }

    /// Home row "Ajoutés récemment" of a film or series library: films and series
    /// (a series stands for its new episodes), newest first.
    pub async fn latest(&self, library_id: &str) -> Result<Vec<MediaItem>, EmbyError> {
        let kind = match self.library_kind(library_id) {
            Some(k) => k,
            None => {
                self.libraries().await?;
                self.library_kind(library_id).ok_or(EmbyError::NotFound)?
            }
        };
        let (client, user_id) = self.session()?;
        let scope = self.scope();
        let items =
            self.expire_on_401(&client, client.latest(&user_id, library_id, HOME_ROW).await)?;
        Ok(items
            .into_iter()
            .filter(|i| i.item_type == kind.item_types())
            .filter_map(|d| media_item(d, &scope))
            .collect())
    }

    /// Home row "Favoris" (ADR 0014): favorite films and series, by title.
    pub async fn favorites(&self) -> Result<Vec<MediaItem>, EmbyError> {
        let (client, user_id) = self.session()?;
        let scope = self.scope();
        let page = self.expire_on_401(&client, client.favorites(&user_id, HOME_ROW).await)?;
        Ok(page
            .items
            .into_iter()
            .filter(|i| matches!(i.item_type.as_str(), "Movie" | "Series"))
            .filter_map(|d| media_item(d, &scope))
            .collect())
    }

    /// Adds a film or series to the user's favorites, or removes it; returns the
    /// state the server reports.
    pub async fn set_favorite(&self, item_id: &str, favorite: bool) -> Result<bool, EmbyError> {
        if !is_id(item_id) {
            return Err(EmbyError::InvalidResponse("identifiant invalide".into()));
        }
        let (client, user_id) = self.session()?;
        let data = self.expire_on_401(
            &client,
            client.set_favorite(&user_id, item_id, favorite).await,
        )?;
        Ok(data.is_favorite)
    }

    /// Marks a film, episode, season or series played or unplayed (ADR 0096), then
    /// reads back what the server holds. For a season or series, whether Emby
    /// applies it to the episodes is not documented: callers read them again.
    pub async fn set_played(
        &self,
        item_id: &str,
        played: bool,
    ) -> Result<PlaybackStopped, EmbyError> {
        if !is_id(item_id) {
            return Err(EmbyError::InvalidResponse("identifiant invalide".into()));
        }
        let (client, user_id) = self.session()?;
        let date = played.then(super::client::date_played_now);
        let data = self.expire_on_401(
            &client,
            client.set_played(&user_id, item_id, date.as_deref()).await,
        )?;
        Ok(self.state_after_change(item_id, data).await)
    }

    /// Takes a film or episode out of "Reprendre" (ADR 0096); the resume point
    /// is the server's business afterwards, read back as for `set_played`.
    pub async fn hide_from_resume(&self, item_id: &str) -> Result<PlaybackStopped, EmbyError> {
        if !is_id(item_id) {
            return Err(EmbyError::InvalidResponse("identifiant invalide".into()));
        }
        let (client, user_id) = self.session()?;
        let data = self.expire_on_401(
            &client,
            client.hide_from_resume(&user_id, item_id, true).await,
        )?;
        Ok(self.state_after_change(item_id, data).await)
    }

    /// Next episode to play of a series, as the home hero picks it: the first one
    /// not watched yet, in the series' order (ADR 0100). `None` when all are.
    pub async fn series_next_episode(
        &self,
        series_id: &str,
    ) -> Result<Option<WatchItem>, EmbyError> {
        if !is_id(series_id) {
            return Err(EmbyError::InvalidResponse("identifiant invalide".into()));
        }
        let (client, user_id) = self.session()?;
        let scope = self.scope();
        let page = self.expire_on_401(
            &client,
            client.first_unplayed_episode(&user_id, series_id).await,
        )?;
        Ok(page
            .items
            .into_iter()
            .filter(|e| e.item_type == "Episode")
            .find_map(|d| watch_item(d, &scope)))
    }

    /// Playlists of the user, by name (ADR 0100).
    pub async fn playlists(&self) -> Result<Vec<Playlist>, EmbyError> {
        let (client, user_id) = self.session()?;
        let page = self.expire_on_401(&client, client.playlists(&user_id, PLAYLISTS_MAX).await)?;
        Ok(page
            .items
            .into_iter()
            .filter(|p| p.item_type == "Playlist" && is_id(&p.id))
            .map(|p| Playlist {
                name: text(Some(p.name)).unwrap_or_else(|| "Sans titre".into()),
                id: p.id,
            })
            .collect())
    }

    /// Appends a film or episode to one of the user's playlists (ADR 0100);
    /// returns how many items the server says it added.
    pub async fn add_to_playlist(
        &self,
        playlist_id: &str,
        item_id: &str,
    ) -> Result<u32, EmbyError> {
        if !is_id(playlist_id) || !is_id(item_id) {
            return Err(EmbyError::InvalidResponse("identifiant invalide".into()));
        }
        let (client, user_id) = self.session()?;
        let res = self.expire_on_401(
            &client,
            client.add_to_playlist(&user_id, playlist_id, item_id).await,
        )?;
        Ok(res.item_added_count)
    }

    /// Creates a video playlist named `name` holding the item (ADR 0100).
    pub async fn create_playlist(
        &self,
        name: &str,
        item_id: &str,
    ) -> Result<PlaylistCreated, EmbyError> {
        let name = playlist_name(name)
            .ok_or_else(|| EmbyError::InvalidResponse("nom de playlist invalide".into()))?;
        if !is_id(item_id) {
            return Err(EmbyError::InvalidResponse("identifiant invalide".into()));
        }
        let (client, user_id) = self.session()?;
        let res = self.expire_on_401(
            &client,
            client.create_playlist(&user_id, name, item_id).await,
        )?;
        if !is_id(&res.id) {
            return Err(EmbyError::InvalidResponse("identifiant invalide".into()));
        }
        Ok(PlaylistCreated {
            id: res.id,
            name: text(Some(res.name)).unwrap_or_else(|| name.to_owned()),
            added: res.item_added_count,
        })
    }

    /// State of an item the server just changed: read again for the resume
    /// fraction, else taken from the user data it answered (the change is made).
    async fn state_after_change(&self, item_id: &str, data: UserItemData) -> PlaybackStopped {
        match self.playback_state(item_id).await {
            Ok(state) => state,
            Err(e) => {
                log::warn!("state after a watched change not read: {}", e.kind());
                PlaybackStopped {
                    item_id: item_id.to_owned(),
                    played: data.played,
                    resume: resume(&data, None),
                }
            }
        }
    }

    /// Detail page of a film, series or episode. Other item types are refused.
    /// An episode also gets its neighbours in the series (ADR 0016).
    pub async fn item_detail(&self, item_id: &str) -> Result<ItemDetail, EmbyError> {
        let (client, user_id) = self.session()?;
        let dto = self.expire_on_401(&client, client.item(&user_id, item_id).await)?;
        if dto.id != item_id {
            return Err(EmbyError::InvalidResponse(
                "le serveur a renvoyé un autre élément".into(),
            ));
        }
        let mut detail = item_detail(dto, &self.scope())?;
        if let Some(ep) = detail.episode.as_mut() {
            let around = self.expire_on_401(
                &client,
                client
                    .adjacent_episodes(&user_id, &ep.series_id, item_id)
                    .await,
            )?;
            (ep.previous, ep.next) = neighbours(around.items, &ep.series_id, item_id);
        }
        Ok(detail)
    }

    /// Cache key of an item's segments: one per server, user and privacy setting.
    pub fn segments_key(&self, item_id: &str, external: bool) -> Result<String, EmbyError> {
        let (client, user_id) = self.session()?;
        Ok(format!(
            "{}|{user_id}|{item_id}|{external}",
            client.server().as_str()
        ))
    }

    /// Emby's markers of an item and what the external sources are asked about it
    /// (ADR 0030): a film by its ids, an episode by its series' ids and numbers.
    /// `None` when the item has no usable id.
    pub async fn segment_query(
        &self,
        item_id: &str,
    ) -> Result<(Vec<Segment>, Option<SegmentQuery>), EmbyError> {
        let (client, user_id) = self.session()?;
        let dto = self.expire_on_401(&client, client.item(&user_id, item_id).await)?;
        if dto.id != item_id {
            return Err(EmbyError::InvalidResponse(
                "le serveur a renvoyé un autre élément".into(),
            ));
        }
        let markers = emby_markers(&dto.chapters);
        let duration_ms = dto
            .run_time_ticks
            .filter(|&t| t > 0)
            .map(|t| (t / 10_000) as u64);
        let query = match dto.item_type.as_str() {
            "Movie" => Some((MediaIds::from_provider_ids(&dto.provider_ids), None)),
            "Episode" => match (
                dto.series_id.as_deref().filter(|id| is_id(id)),
                dto.parent_index_number,
                dto.index_number,
            ) {
                (Some(series_id), Some(season), Some(episode)) => {
                    match client.item(&user_id, series_id).await {
                        Ok(series) if series.id == series_id => Some((
                            MediaIds::from_provider_ids(&series.provider_ids),
                            Some((season, episode)),
                        )),
                        Err(EmbyError::SessionExpired) => {
                            return self.expire_on_401(&client, Err(EmbyError::SessionExpired));
                        }
                        // The series is only needed for its ids: no query, no error.
                        _ => None,
                    }
                }
                _ => None,
            },
            _ => None,
        };
        let query = query
            .filter(|(ids, _)| !ids.is_empty())
            .map(|(ids, episode)| SegmentQuery {
                ids,
                episode,
                duration_ms,
            });
        Ok((markers, query))
    }

    /// Chapters of a film or episode for the player (ADR 0034), from the item's
    /// `Chapters`; none when it has fewer than two.
    pub async fn chapters(&self, item_id: &str) -> Result<Vec<Chapter>, EmbyError> {
        let (client, user_id) = self.session()?;
        let dto = self.expire_on_401(&client, client.item(&user_id, item_id).await)?;
        if dto.id != item_id {
            return Err(EmbyError::InvalidResponse(
                "le serveur a renvoyé un autre élément".into(),
            ));
        }
        Ok(chapters::chapters(&dto.chapters, dto.run_time_ticks))
    }

    /// Seasons of a series, in server order; unusable entries are dropped.
    pub async fn seasons(&self, series_id: &str) -> Result<Vec<Season>, EmbyError> {
        let (client, user_id) = self.session()?;
        let res = self.expire_on_401(&client, client.seasons(&user_id, series_id).await)?;
        Ok(res
            .items
            .into_iter()
            .filter(|s| is_id(&s.id) && s.series_id.as_deref().is_none_or(|id| id == series_id))
            .map(|s| {
                let user_data = s.user_data.unwrap_or_default();
                Season {
                    id: s.id,
                    name: s.name,
                    index: s.index_number,
                    unplayed_count: user_data.unplayed_item_count,
                    played: user_data.played,
                }
            })
            .collect())
    }

    /// Negotiates playback of a film or episode (ADR 0007). Returns the plan and the
    /// headers the player must send, authentication included; the URL holds no token.
    /// With `resume`, the plan starts at the position the server holds now (ADR 0008).
    /// The item is read alongside PlaybackInfo for the player's title (ADR 0017); without
    /// `resume`, failing to read it only leaves the title out.
    pub async fn prepare_playback(
        &self,
        item_id: &str,
        resume: bool,
    ) -> Result<(PlaybackPlan, Vec<(String, String)>), EmbyError> {
        self.prepare_source(item_id, None, resume, false, &TrackPreferences::default())
            .await
    }

    /// Same as `prepare_playback`, for when the player could not open the direct
    /// source: Direct Play and Direct Stream are refused to the server so it
    /// transcodes (ADR 0023). A server that still offers the file as is gives
    /// `NoCompatibleStream`, so the fallback never loops on the same source.
    pub async fn prepare_transcode(
        &self,
        item_id: &str,
        resume: bool,
    ) -> Result<(PlaybackPlan, Vec<(String, String)>), EmbyError> {
        self.prepare_source(item_id, None, resume, true, &TrackPreferences::default())
            .await
    }

    /// `prepare_playback` or `prepare_transcode` for one version of an item with
    /// several (ADR 0036); `None` lets the server pick, as before.
    pub async fn prepare_version(
        &self,
        item_id: &str,
        media_source_id: Option<&str>,
        resume: bool,
        force_transcode: bool,
    ) -> Result<(PlaybackPlan, Vec<(String, String)>), EmbyError> {
        self.prepare_source(
            item_id,
            media_source_id,
            resume,
            force_transcode,
            &TrackPreferences::default(),
        )
        .await
    }

    /// `prepare_playback` or, with `force_transcode`, `prepare_transcode`, with the
    /// audio and subtitles picked from the user's preferences (ADR 0035).
    pub async fn prepare(
        &self,
        item_id: &str,
        resume: bool,
        force_transcode: bool,
        prefs: &TrackPreferences,
    ) -> Result<(PlaybackPlan, Vec<(String, String)>), EmbyError> {
        self.prepare_source(item_id, None, resume, force_transcode, prefs)
            .await
    }

    /// `prepare` for one version of the item, or the server's pick with `None`
    /// (ADR 0035, 0036).
    pub async fn prepare_source(
        &self,
        item_id: &str,
        media_source_id: Option<&str>,
        resume: bool,
        force_transcode: bool,
        prefs: &TrackPreferences,
    ) -> Result<(PlaybackPlan, Vec<(String, String)>), EmbyError> {
        if media_source_id.is_some_and(|id| !is_source_id(id)) {
            return Err(EmbyError::InvalidResponse("version invalide".into()));
        }
        let (client, user_id) = self.session()?;
        let limit = self.streaming_limit(&client);
        let profile = playback::device_profile(limit.bitrate);
        let (info, item) = tokio::join!(
            client.playback_info(
                &user_id,
                item_id,
                &profile,
                force_transcode,
                media_source_id,
                None
            ),
            client.item(&user_id, item_id)
        );
        let response = self.expire_on_401(&client, info)?;
        let mut plan = playback::plan(client.server(), response, media_source_id)?;
        if force_transcode && plan.method != PlayMethod::Transcode {
            return Err(EmbyError::NoCompatibleStream);
        }
        let choice = tracks::apply(&mut plan, prefs);
        let other_audio = choice
            .audio
            .filter(|&a| Some(a) != plan.default_audio_stream_index);
        if let Some(audio) = other_audio.filter(|_| plan.method == PlayMethod::Transcode) {
            // A transcode carries the audio the server picked; it takes another one
            // only with the source named (ADR 0035).
            let source = plan.media_source_id.as_str();
            let info = client
                .playback_info(
                    &user_id,
                    item_id,
                    &profile,
                    force_transcode,
                    Some(source),
                    Some(audio),
                )
                .await;
            let response = self.expire_on_401(&client, info)?;
            let mut again = playback::plan(client.server(), response, Some(source))?;
            if again.method == PlayMethod::Transcode
                && again.media_source_id == plan.media_source_id
            {
                tracks::apply(&mut again, prefs);
                plan = again;
            }
        }
        if force_transcode {
            // The server only knows we refused; the real reason is the player's.
            plan.transcode_reasons = Some("échec de la lecture directe".into());
        }
        let item = match self.expire_on_401(&client, item) {
            Ok(dto) if dto.id == item_id => Some(dto),
            Ok(_) if resume => {
                return Err(EmbyError::InvalidResponse(
                    "le serveur a renvoyé un autre élément".into(),
                ));
            }
            Err(e) if resume || matches!(e, EmbyError::SessionExpired) => return Err(e),
            _ => None,
        };
        plan.title = item.as_ref().and_then(playback_title);
        plan.bitrate_limit = Some(limit);
        if let Some(dto) = item.filter(|_| resume) {
            let runtime = plan.run_time_ticks.or(dto.run_time_ticks);
            plan.start_ticks = dto
                .user_data
                .filter(|u| !u.played)
                .map(|u| u.playback_position_ticks)
                .filter(|&t| t > 0 && runtime.is_none_or(|r| t < r))
                .unwrap_or(0);
        }
        let mut headers = plan.headers.clone();
        headers.extend(client.stream_auth_headers()?);
        Ok((plan, headers))
    }

    /// Sends one playback report (ADR 0008).
    pub async fn report_playback(&self, report: &Report) -> Result<(), EmbyError> {
        let client = self.client()?;
        self.expire_on_401(
            &client,
            client
                .report_playback(report.kind.path(), &report.body)
                .await,
        )
    }

    /// Sends a lost stop report again, only while `owner` is still the open session:
    /// it must never reach another account or server (ADR 0024).
    pub async fn resend_stop(&self, owner: &EmbyClient, report: &Report) -> Result<(), EmbyError> {
        if !self.client()?.same_token(owner) {
            return Err(EmbyError::Cancelled);
        }
        // The server ignores the position of a stop once it closed the playback itself,
        // or with `Failed`; a progress report carries it and reopens the playback.
        self.report_playback(&report.late_progress()).await?;
        self.report_playback(report).await
    }

    /// Watched status and resume point the server holds for an item.
    pub async fn playback_state(&self, item_id: &str) -> Result<PlaybackStopped, EmbyError> {
        let (client, user_id) = self.session()?;
        let dto = self.expire_on_401(&client, client.item(&user_id, item_id).await)?;
        if dto.id != item_id {
            return Err(EmbyError::InvalidResponse(
                "le serveur a renvoyé un autre élément".into(),
            ));
        }
        let user_data = dto.user_data.unwrap_or_default();
        Ok(PlaybackStopped {
            item_id: dto.id,
            played: user_data.played,
            resume: resume(&user_data, dto.run_time_ticks),
        })
    }

    /// One page of episodes of a season, `limit` clamped to `1..=MAX_PAGE`. A season
    /// that belongs to another series is reported as not found (see `episodes`).
    /// Specials aired during the season come within it, with their own `SeasonId`
    /// and `season_index` 0 (Emby 4.10.1.0): they are kept.
    pub async fn episodes(
        &self,
        series_id: &str,
        season_id: &str,
        start_index: u32,
        limit: u32,
    ) -> Result<EpisodePage, EmbyError> {
        let (client, user_id) = self.session()?;
        let scope = self.scope();
        let query = EpisodesQuery {
            series_id,
            season_id,
            start_index,
            limit: limit.clamp(1, MAX_PAGE),
        };
        let page = self.expire_on_401(&client, client.episodes(&user_id, &query).await)?;
        let foreign = page
            .items
            .iter()
            .any(|e| e.series_id.as_deref() != Some(series_id));
        if foreign {
            return Err(EmbyError::NotFound);
        }
        let returned = u32::try_from(page.items.len()).unwrap_or(u32::MAX);
        Ok(EpisodePage {
            next_index: start_index.saturating_add(returned),
            items: page
                .items
                .into_iter()
                .filter_map(|d| episode(d, &scope))
                .collect(),
            start_index,
            total: page.total_record_count,
        })
    }

    /// Artwork for the `emby-img` protocol, fetched with the open session.
    /// Artwork of the open session's server: from the disk cache when it has it, else
    /// downloaded (at most `IMAGE_CONCURRENCY` at once) and kept (ADR 0019).
    pub async fn image(&self, req: &ImageRequest) -> Result<(String, Vec<u8>), EmbyError> {
        // Session and server first: the cache never answers for another session.
        self.session()?;
        if req.server_id != self.scope() {
            return Err(EmbyError::Forbidden);
        }
        if let Some(cache) = &self.artwork {
            let (cache, key) = (Arc::clone(cache), req.clone());
            if let Ok(Some(hit)) =
                tauri::async_runtime::spawn_blocking(move || cache.get(&key)).await
            {
                return Ok(hit);
            }
        }
        let _permit = self
            .image_permits
            .acquire()
            .await
            .map_err(|_| EmbyError::NotSignedIn)?;
        // Read again after the wait: the session may have ended meanwhile.
        let (client, _) = self.session()?;
        if req.server_id != self.scope() {
            return Err(EmbyError::Forbidden);
        }
        let (content_type, bytes) = self.expire_on_401(&client, client.image(req).await)?;
        if let Some(cache) = &self.artwork {
            let (cache, key, data) = (Arc::clone(cache), req.clone(), bytes.clone());
            let kept = tauri::async_runtime::spawn_blocking(move || cache.put(&key, &data)).await;
            if let Ok(Err(e)) = kept {
                log::debug!("artwork {} not cached: {:?}", req.item_id, e.kind());
            }
        }
        Ok((content_type, bytes))
    }

    pub fn last_server_url(&self) -> Option<String> {
        self.file().last_server_url
    }

    /// Validates the address and reads the server's public identity.
    /// The open session's server as it answers now (Réglages › Serveur).
    pub async fn server_info(&self) -> Result<ServerSummary, EmbyError> {
        let client = self.client()?;
        summary(&client).await
    }

    pub async fn probe(&self, url: &str) -> Result<ServerSummary, EmbyError> {
        summary(&self.new_client(url)?).await
    }

    pub async fn sign_in(
        &self,
        url: &str,
        username: &str,
        password: &str,
    ) -> Result<SignedInUser, EmbyError> {
        let client = self.new_client(url)?;
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
        let record = SessionRecord::of(&user);
        self.update_file(|f| {
            f.last_server_url = Some(record.server_url.clone());
            f.saved.retain(|s| !s.same_account(&record));
            f.reauth = None;
            f.session = Some(record);
        })?;
        self.set_current(Some(Current {
            client: client.with_token(token),
            user: user.clone(),
            libraries: Vec::new(),
        }));
        Ok(user)
    }

    /// Validates the saved session (ADR 0012), at startup and whenever the UI
    /// suspects the network. Only a rejection by the server forgets the token:
    /// timeouts, 5xx and unreachable servers keep everything for a later retry.
    /// `Err(Cancelled)`: a sign-in, sign-out or server change happened meanwhile.
    pub async fn restore(&self) -> Result<SessionStatus, EmbyError> {
        let epoch = self.epoch();
        let file = self.file();
        let Some(record) = file.session else {
            return Ok(match file.reauth {
                Some(hint) => SessionStatus::AuthenticationRequired {
                    server: hint.summary(),
                    error: EmbyError::SessionExpired,
                },
                None => SessionStatus::SignedOut,
            });
        };
        let Some(token) = self.store.load(&record.account())? else {
            // The token was removed outside the app: same as a rejected one.
            self.forget_rejected(&record);
            return Ok(SessionStatus::AuthenticationRequired {
                server: record.summary(),
                error: EmbyError::SessionExpired,
            });
        };
        let outcome = self.validate(&record, token).await;
        let mut guard = self.lock_current();
        if self.epoch() != epoch {
            return Err(EmbyError::Cancelled);
        }
        match outcome {
            Ok((client, user)) => {
                // Re-validating the open session keeps its library list.
                let libraries = guard
                    .take()
                    .filter(|c| SessionRecord::of(&c.user).same_account(&record))
                    .map(|c| c.libraries)
                    .unwrap_or_default();
                *guard = Some(Current {
                    client,
                    user: user.clone(),
                    libraries,
                });
                Ok(SessionStatus::Authenticated { user })
            }
            Err(error @ (EmbyError::SessionExpired | EmbyError::AccountUnavailable)) => {
                self.epoch.fetch_add(1, Ordering::SeqCst);
                *guard = None;
                drop(guard);
                self.forget_rejected(&record);
                Ok(SessionStatus::AuthenticationRequired {
                    server: record.summary(),
                    error,
                })
            }
            Err(error) => Ok(SessionStatus::ServerUnreachable {
                server: record.summary(),
                error,
            }),
        }
    }

    /// Public probe **without** the token, then `GET /Users/{Id}` with it: the token
    /// is never sent to a server whose id is not the saved one.
    async fn validate(
        &self,
        record: &SessionRecord,
        token: AccessToken,
    ) -> Result<(EmbyClient, SignedInUser), EmbyError> {
        let client = self.new_client(&record.server_url)?;
        let server = summary(&client).await?;
        if server.id != record.server_id {
            return Err(EmbyError::ServerMismatch);
        }
        let client = client.with_token(token);
        let dto = match client.user(&record.user_id).await {
            Err(EmbyError::Forbidden | EmbyError::NotFound) => {
                return Err(EmbyError::AccountUnavailable);
            }
            other => other?,
        };
        let policy = dto.policy.unwrap_or_default();
        if policy.is_disabled {
            return Err(EmbyError::AccountUnavailable);
        }
        if dto.id != record.user_id {
            return Err(EmbyError::InvalidResponse(
                "le serveur a renvoyé un autre utilisateur".into(),
            ));
        }
        let user = SignedInUser {
            server,
            user_id: dto.id,
            user_name: dto.name,
            is_administrator: policy.is_administrator,
        };
        Ok((client, user))
    }

    /// "Changer de serveur": closes the session without revoking it and sets it
    /// aside so the user can come back to it (ADR 0012).
    /// A rejected session (the reauthentication hint) stays on the shelf with
    /// "Mot de passe requis" (ADR 0103).
    pub fn change_server(&self) -> Result<(), EmbyError> {
        self.set_current(None);
        let evicted = self.update_file(|f| {
            let mut evicted = Vec::new();
            if let Some(mut hint) = f.reauth.take() {
                hint.rejected = true;
                evicted.extend(f.shelve(hint));
            }
            if let Some(r) = f.session.take() {
                evicted.extend(f.shelve(r));
            }
            evicted
        })?;
        self.delete_evicted(evicted);
        Ok(())
    }

    fn delete_evicted(&self, evicted: Vec<SessionRecord>) {
        for r in evicted {
            if let Err(e) = self.store.delete(&r.account()) {
                log::warn!("evicted token not deleted: {}", e.kind());
            }
        }
    }

    fn has_token(&self, record: &SessionRecord) -> bool {
        matches!(self.store.load(&record.account()), Ok(Some(_)))
    }

    /// Tiles of the pre-connection shelf (ADR 0103), newest first, one per
    /// server and user: the session left by a failed check (never the open
    /// one), the rejected session, then the shelf.
    pub fn shelf(&self) -> Vec<ShelfServer> {
        let file = self.file();
        let open = self.current_user().map(|u| SessionRecord::of(&u));
        let rejected = file.reauth.map(|mut r| {
            r.rejected = true;
            r
        });
        let mut tiles: Vec<ShelfServer> = Vec::new();
        let mut seen: Vec<SessionRecord> = Vec::new();
        let records = file
            .session
            .into_iter()
            .filter(|r| !open.as_ref().is_some_and(|o| o.same_account(r)))
            .chain(rejected)
            .chain(file.saved);
        for record in records {
            if seen.iter().any(|s| s.same_account(&record)) {
                continue;
            }
            let access = if self.has_token(&record) {
                ShelfAccess::Session
            } else if record.rejected {
                ShelfAccess::Rejected
            } else {
                ShelfAccess::SignedOut
            };
            tiles.push(ShelfServer {
                server: record.summary(),
                access,
            });
            seen.push(record);
        }
        tiles
    }

    /// "Oublier ce serveur" (ADR 0103): the record (address, user name) leaves
    /// `session.json` and the token leaves the credential store. Nothing changes
    /// on the server; the returned revocation is a best effort the caller may
    /// run later, it never holds the removal back.
    pub fn forget_server(
        &self,
        server_id: &str,
        user_id: &str,
    ) -> Result<Option<Revocation>, EmbyError> {
        let file = self.file();
        let Some(record) = file
            .session
            .iter()
            .chain(file.reauth.iter())
            .chain(file.saved.iter())
            .find(|r| r.is(server_id, user_id))
            .cloned()
        else {
            return Err(EmbyError::NotFound);
        };
        if self
            .current_user()
            .is_some_and(|u| SessionRecord::of(&u).same_account(&record))
        {
            self.set_current(None);
        }
        // The token first: a vault failure leaves everything as it was.
        let token = self.store.load(&record.account()).ok().flatten();
        self.store.delete(&record.account())?;
        self.update_file(|f| {
            if f.session.as_ref().is_some_and(|r| r.is(server_id, user_id)) {
                f.session = None;
            }
            if f.reauth.as_ref().is_some_and(|r| r.is(server_id, user_id)) {
                f.reauth = None;
            }
            f.saved.retain(|r| !r.is(server_id, user_id));
            if f.last_server_url.as_deref() == Some(record.server_url.as_str())
                && !f.uses_url(&record.server_url)
            {
                f.last_server_url = None;
            }
        })?;
        let Some(token) = token else {
            return Ok(None);
        };
        Ok(self
            .new_client(&record.server_url)
            .ok()
            .map(|client| Revocation {
                client,
                server_id: record.server_id,
                token,
            }))
    }

    /// Sessions set aside whose token is still in the store.
    pub fn saved_sessions(&self) -> Vec<SavedServer> {
        self.file()
            .saved
            .iter()
            .filter(|r| self.has_token(r))
            .map(SessionRecord::summary)
            .collect()
    }

    /// Brings a set-aside session back, then validates it like at startup. The
    /// session left by a failed check (vault error at startup) is accepted too.
    pub async fn resume_saved(
        &self,
        server_id: &str,
        user_id: &str,
    ) -> Result<SessionStatus, EmbyError> {
        self.set_current(None);
        let (found, evicted) = self.update_file(|f| {
            let mut evicted = Vec::new();
            if f.session.as_ref().is_some_and(|s| s.is(server_id, user_id)) {
                return (true, evicted);
            }
            let Some(pos) = f.saved.iter().position(|s| s.is(server_id, user_id)) else {
                return (false, evicted);
            };
            let mut record = f.saved.remove(pos);
            record.rejected = false;
            if let Some(mut hint) = f.reauth.take() {
                hint.rejected = true;
                evicted.extend(f.shelve(hint));
            }
            if let Some(previous) = f.session.take() {
                evicted.extend(f.shelve(previous));
            }
            f.last_server_url = Some(record.server_url.clone());
            f.session = Some(record);
            (true, evicted)
        })?;
        self.delete_evicted(evicted);
        if !found {
            return Err(EmbyError::NotFound);
        }
        self.restore().await
    }

    /// Revokes the token on the server when reachable, then always forgets it
    /// locally: an offline sign-out must not leave a usable token on disk. The
    /// address and user name stay on the shelf, without token (ADR 0103).
    pub async fn sign_out(&self) -> Result<(), EmbyError> {
        let current = {
            let mut guard = self.lock_current();
            self.epoch.fetch_add(1, Ordering::SeqCst);
            guard.take()
        };
        let record = self.file().session;
        if let Some(c) = &current
            && let Err(e) = c.client.logout().await
        {
            log::warn!("Emby logout not confirmed by the server: {}", e.kind());
        }
        if let Some(r) = &record {
            self.store
                .delete(&credentials::account(&r.server_id, &r.user_id))?;
        }
        let evicted = self.update_file(|f| {
            f.session = None;
            f.reauth = None;
            match record {
                Some(r) => f.shelve(SessionRecord {
                    rejected: false,
                    ..r
                }),
                None => Vec::new(),
            }
        })?;
        self.delete_evicted(evicted);
        Ok(())
    }
}

/// Best-effort revocation of a forgotten session (ADR 0103): the public probe
/// goes **without** the token, which is only sent to the server with the saved
/// id, like a restore (ADR 0012).
pub struct Revocation {
    client: EmbyClient,
    server_id: String,
    token: AccessToken,
}

impl Revocation {
    pub async fn send(self) {
        let outcome = match summary(&self.client).await {
            Ok(server) if server.id == self.server_id => {
                self.client.with_token(self.token).logout().await
            }
            Ok(_) => Err(EmbyError::ServerMismatch),
            Err(e) => Err(e),
        };
        if let Err(e) = outcome {
            log::info!("forgotten session not revoked on the server: {}", e.kind());
        }
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

/// Grid card for a film or series; items with an unusable id are dropped.
/// Options from the UI the server could not filter on are refused before any
/// request.
fn check_options(options: &ItemsOptions) -> Result<(), EmbyError> {
    if options
        .genre
        .as_deref()
        .is_some_and(|g| !is_usable_genre(g))
    {
        return Err(EmbyError::InvalidResponse("genre invalide".into()));
    }
    if options.decade.is_some_and(|d| !is_usable_decade(d)) {
        return Err(EmbyError::InvalidResponse("décennie invalide".into()));
    }
    Ok(())
}

fn media_item(dto: BaseItemDto, server: &str) -> Option<MediaItem> {
    let poster = images::image_ref(
        server,
        &dto.id,
        ImageType::Primary,
        dto.image_tags.get("Primary").map(String::as_str),
        images::POSTER_WIDTHS,
    );
    if !is_id(&dto.id) {
        return None;
    }
    let user_data = dto.user_data.unwrap_or_default();
    Some(MediaItem {
        runtime_minutes: minutes(dto.run_time_ticks.filter(|_| dto.item_type == "Movie")),
        id: dto.id,
        name: dto.name,
        item_type: dto.item_type,
        year: dto.production_year,
        poster,
        aspect_ratio: dto
            .primary_image_aspect_ratio
            .filter(|r| r.is_finite() && *r > 0.0),
        played: user_data.played,
        unplayed_count: user_data.unplayed_item_count,
        favorite: user_data.is_favorite,
    })
}

/// Hero title of a resumed film or episode; an episode stands for its series,
/// whose backdrop and logo it shows. Items without a backdrop are dropped.
fn resumed_hero(dto: BaseItemDto, server: &str) -> Option<HeroItem> {
    if !is_id(&dto.id) {
        return None;
    }
    let user_data = dto.user_data.clone().unwrap_or_default();
    let resume = resume(&user_data, dto.run_time_ticks);
    match dto.item_type.as_str() {
        "Movie" => {
            let mut item = latest_hero(dto, server)?;
            item.reason = HeroReason::Resume;
            item.resume = resume;
            Some(item)
        }
        "Episode" => {
            let series_id = dto.series_id.clone().filter(|id| is_id(id))?;
            let backdrop = images::image_ref(
                server,
                dto.parent_backdrop_item_id.as_deref()?,
                ImageType::Backdrop,
                dto.parent_backdrop_image_tags.first().map(String::as_str),
                images::BACKDROP_WIDTHS,
            )?;
            let logo = dto.parent_logo_item_id.as_deref().and_then(|id| {
                images::image_ref(
                    server,
                    id,
                    ImageType::Logo,
                    dto.parent_logo_image_tag.as_deref(),
                    images::LOGO_WIDTHS,
                )
            });
            let poster = images::image_ref(
                server,
                &series_id,
                ImageType::Primary,
                dto.series_primary_image_tag.as_deref(),
                images::POSTER_WIDTHS,
            );
            Some(HeroItem {
                detail: MediaItem {
                    id: series_id,
                    name: text(dto.series_name.clone()).unwrap_or_default(),
                    item_type: "Series".into(),
                    year: None,
                    runtime_minutes: None,
                    poster,
                    aspect_ratio: None,
                    played: false,
                    unplayed_count: None,
                    favorite: false,
                },
                reason: HeroReason::Resume,
                backdrop,
                logo,
                overview: text(dto.overview.clone()),
                episode: Some(hero_episode(&dto)),
                play_id: Some(dto.id),
                resume,
            })
        }
        _ => None,
    }
}

/// Hero title of a film or series from its own artwork. A film plays itself; a
/// series gets its episode later. Items without a backdrop are dropped.
fn latest_hero(dto: BaseItemDto, server: &str) -> Option<HeroItem> {
    let is_movie = match dto.item_type.as_str() {
        "Movie" => true,
        "Series" => false,
        _ => return None,
    };
    let backdrop = images::image_ref(
        server,
        &dto.id,
        ImageType::Backdrop,
        dto.backdrop_image_tags.first().map(String::as_str),
        images::BACKDROP_WIDTHS,
    )?;
    let logo = images::image_ref(
        server,
        &dto.id,
        ImageType::Logo,
        dto.image_tags.get("Logo").map(String::as_str),
        images::LOGO_WIDTHS,
    );
    let overview = text(dto.overview.clone());
    let user_data = dto.user_data.clone().unwrap_or_default();
    let resume = resume(&user_data, dto.run_time_ticks).filter(|_| is_movie);
    let detail = media_item(dto, server)?;
    Some(HeroItem {
        play_id: is_movie.then(|| detail.id.clone()),
        detail,
        reason: HeroReason::Latest,
        backdrop,
        logo,
        overview,
        episode: None,
        resume,
    })
}

fn hero_episode(dto: &BaseItemDto) -> HeroEpisode {
    HeroEpisode {
        name: dto.name.trim().to_owned(),
        season_index: dto.parent_index_number,
        index: dto.index_number,
    }
}

/// Card of the "Reprendre" and "À suivre" rows; other item types are dropped.
fn watch_item(dto: BaseItemDto, server: &str) -> Option<WatchItem> {
    if !is_id(&dto.id) {
        return None;
    }
    let kind = match dto.item_type.as_str() {
        "Movie" => WatchKind::Movie,
        "Episode" => WatchKind::Episode,
        _ => return None,
    };
    let own = |t: ImageType| {
        images::image_ref(
            server,
            &dto.id,
            t,
            dto.image_tags.get(t.as_str()).map(String::as_str),
            images::THUMB_WIDTHS,
        )
    };
    let image = match kind {
        WatchKind::Episode => own(ImageType::Primary)
            .or_else(|| {
                images::image_ref(
                    server,
                    dto.parent_thumb_item_id.as_deref()?,
                    ImageType::Thumb,
                    dto.parent_thumb_image_tag.as_deref(),
                    images::THUMB_WIDTHS,
                )
            })
            .or_else(|| {
                images::image_ref(
                    server,
                    dto.parent_backdrop_item_id.as_deref()?,
                    ImageType::Backdrop,
                    dto.parent_backdrop_image_tags.first().map(String::as_str),
                    images::THUMB_WIDTHS,
                )
            }),
        WatchKind::Movie => own(ImageType::Thumb).or_else(|| {
            images::image_ref(
                server,
                &dto.id,
                ImageType::Backdrop,
                dto.backdrop_image_tags.first().map(String::as_str),
                images::THUMB_WIDTHS,
            )
        }),
    };
    let user_data = dto.user_data.unwrap_or_default();
    Some(WatchItem {
        resume: resume(&user_data, dto.run_time_ticks),
        runtime_minutes: minutes(dto.run_time_ticks),
        series_name: match kind {
            WatchKind::Episode => text(dto.series_name),
            WatchKind::Movie => None,
        },
        season_index: dto.parent_index_number,
        index: dto.index_number,
        id: dto.id,
        name: dto.name.trim().to_owned(),
        kind,
        image,
        played: user_data.played,
        favorite: user_data.is_favorite,
    })
}

/// Search text as sent to the server: no control characters, single spaces, at
/// most `SEARCH_MAX_CHARS` characters.
pub fn clean_search(query: &str) -> String {
    query
        .chars()
        .filter(|c| c.is_whitespace() || !c.is_control())
        .collect::<String>()
        .split_whitespace()
        .collect::<Vec<_>>()
        .join(" ")
        .chars()
        .take(SEARCH_MAX_CHARS)
        .collect::<String>()
        .trim_end()
        .to_string()
}

/// Whether `query` starts a word of `title`, ignoring case and accents, as Emby
/// matches titles (ADR 0011). Used on episodes, which the server also finds by
/// their series name (ADR 0020).
pub fn title_matches(title: &str, query: &str) -> bool {
    let title = fold(title);
    let query = fold(query);
    if query.is_empty() {
        return false;
    }
    (0..title.len())
        .any(|i| (i == 0 || !title[i - 1].is_alphanumeric()) && title[i..].starts_with(&query))
}

/// Lower case without the accents of Latin letters: `É` and `é` become `e`.
fn fold(text: &str) -> Vec<char> {
    const ACCENTED: [(&str, char); 19] = [
        ("àáâãäåāăą", 'a'),
        ("çćĉċč", 'c'),
        ("ďđ", 'd'),
        ("èéêëēĕėęě", 'e'),
        ("ĝğġģ", 'g'),
        ("ĥħ", 'h'),
        ("ìíîïĩīĭįı", 'i'),
        ("ĵ", 'j'),
        ("ķ", 'k'),
        ("ĺļľŀł", 'l'),
        ("ñńņňŉ", 'n'),
        ("òóôõöøōŏő", 'o'),
        ("ŕŗř", 'r'),
        ("śŝşšſ", 's'),
        ("ţťŧ", 't'),
        ("ùúûüũūŭůűų", 'u'),
        ("ŵ", 'w'),
        ("ýÿŷ", 'y'),
        ("źżž", 'z'),
    ];
    text.chars()
        .flat_map(char::to_lowercase)
        .map(|c| {
            ACCENTED
                .iter()
                .find(|(set, _)| set.contains(c))
                .map_or(c, |&(_, plain)| plain)
        })
        .collect()
}

fn is_id(id: &str) -> bool {
    !id.is_empty() && id.chars().all(|c| c.is_ascii_alphanumeric() || c == '-')
}

/// Media source ids seen: `mediasource_30207` for an item's own file, an item id
/// for a merged version (ADR 0036).
fn is_source_id(id: &str) -> bool {
    !id.is_empty()
        && id.len() <= 128
        && id
            .chars()
            .all(|c| c.is_ascii_alphanumeric() || c == '-' || c == '_')
}

const TICKS_PER_SECOND: i64 = 10_000_000;
/// Enough names for one line of credits; the full cast is not shown.
const MAX_CAST: usize = 12;

fn minutes(ticks: Option<i64>) -> Option<u32> {
    ticks
        .and_then(|t| u32::try_from(t / TICKS_PER_MINUTE).ok())
        .filter(|&m| m > 0)
}

/// Resume point of a started, unfinished item.
fn resume(user_data: &UserItemData, runtime_ticks: Option<i64>) -> Option<ResumePoint> {
    let position = user_data.playback_position_ticks;
    if user_data.played || position <= 0 {
        return None;
    }
    Some(ResumePoint {
        position_seconds: u32::try_from(position / TICKS_PER_SECOND).ok()?,
        fraction: runtime_ticks
            .filter(|&r| r > 0)
            .map(|r| (position as f64 / r as f64).clamp(0.0, 1.0)),
    })
}

/// Title of a film or episode for the player (ADR 0017).
fn playback_title(dto: &BaseItemDto) -> Option<PlaybackTitle> {
    let episode = dto.item_type == "Episode";
    Some(PlaybackTitle {
        name: text(Some(dto.name.clone()))?,
        series_name: text(dto.series_name.clone().filter(|_| episode)),
        season_index: dto.parent_index_number.filter(|_| episode),
        index: dto.index_number.filter(|_| episode),
        index_end: dto.index_number_end.filter(|_| episode),
    })
}

/// Trimmed, non-empty text.
/// A playlist name the user typed, trimmed: not empty, not too long, no control
/// character.
fn playlist_name(name: &str) -> Option<&str> {
    let name = name.trim();
    (!name.is_empty()
        && name.chars().count() <= PLAYLIST_NAME_MAX
        && !name.chars().any(char::is_control))
    .then_some(name)
}

fn text(value: Option<String>) -> Option<String> {
    value.map(|v| v.trim().to_owned()).filter(|v| !v.is_empty())
}

fn year_of(date: Option<&str>) -> Option<i32> {
    date?.get(..4)?.parse().ok()
}

/// Display range of a video stream: `HDR 10` or `Dolby Vision Profile 8.1 (…)`.
fn video_range(s: &MediaStreamDto) -> Option<String> {
    let range = text(s.video_range.clone())?;
    let detail =
        text(s.extended_video_sub_type_description.clone()).filter(|d| d != "None" && *d != range);
    Some(match (range.as_str(), detail) {
        ("DolbyVision", Some(d)) => format!("Dolby Vision {d}"),
        ("DolbyVision", None) => "Dolby Vision".into(),
        (_, _) => range,
    })
}

fn media_info(source: MediaSourceDto) -> MediaInfo {
    let stream = |s: &MediaStreamDto| StreamInfo {
        language: text(s.language.clone()),
        codec: text(s.codec.clone()),
        title: text(s.title.clone()),
        channels: s.channels,
        is_default: s.is_default,
        is_forced: s.is_forced,
        is_external: s.is_external,
    };
    let of_type = |t: &str| {
        source
            .media_streams
            .iter()
            .filter(|s| s.stream_type == t)
            .map(stream)
            .collect::<Vec<_>>()
    };
    MediaInfo {
        id: source.id.filter(|id| is_source_id(id)),
        video: source
            .media_streams
            .iter()
            .find(|s| s.stream_type == "Video")
            .map(|s| VideoInfo {
                codec: text(s.codec.clone()),
                width: s.width,
                height: s.height,
                bit_depth: s.bit_depth,
                range: video_range(s),
            }),
        audio: of_type("Audio"),
        subtitles: of_type("Subtitle"),
        name: text(source.name),
        container: text(source.container),
        size_bytes: source.size,
        bitrate: source.bitrate,
    }
}

/// Episodes just before and after `episode_id` in the server's order, from the
/// same series. None when the episode is missing from the answer.
fn neighbours(
    items: Vec<BaseItemDto>,
    series_id: &str,
    episode_id: &str,
) -> (Option<EpisodeLink>, Option<EpisodeLink>) {
    let items: Vec<_> = items
        .into_iter()
        .filter(|e| is_id(&e.id) && e.series_id.as_deref() == Some(series_id))
        .collect();
    let Some(at) = items.iter().position(|e| e.id == episode_id) else {
        return (None, None);
    };
    let link = |e: &BaseItemDto| EpisodeLink {
        id: e.id.clone(),
        name: e.name.trim().to_owned(),
        season_index: e.parent_index_number,
        index: e.index_number,
        index_end: e.index_number_end,
    };
    (
        at.checked_sub(1).map(|i| link(&items[i])),
        items.get(at + 1).map(link),
    )
}

/// Artwork of an episode page: the series poster, its own image (else the series
/// backdrop) and the series logo.
fn episode_images(
    dto: &BaseItemDto,
    series_id: &str,
    server: &str,
) -> (Option<ImageRef>, Option<ImageRef>, Option<ImageRef>) {
    let still = images::image_ref(
        server,
        &dto.id,
        ImageType::Primary,
        dto.image_tags.get("Primary").map(String::as_str),
        images::BACKDROP_WIDTHS,
    );
    let backdrop = still.or_else(|| {
        images::image_ref(
            server,
            dto.parent_backdrop_item_id.as_deref()?,
            ImageType::Backdrop,
            dto.parent_backdrop_image_tags.first().map(String::as_str),
            images::BACKDROP_WIDTHS,
        )
    });
    let poster = images::image_ref(
        server,
        series_id,
        ImageType::Primary,
        dto.series_primary_image_tag.as_deref(),
        images::POSTER_WIDTHS,
    );
    let logo = dto.parent_logo_item_id.as_deref().and_then(|id| {
        images::image_ref(
            server,
            id,
            ImageType::Logo,
            dto.parent_logo_image_tag.as_deref(),
            images::LOGO_WIDTHS,
        )
    });
    (poster, backdrop, logo)
}

fn item_detail(dto: BaseItemDto, server: &str) -> Result<ItemDetail, EmbyError> {
    let kind = match dto.item_type.as_str() {
        "Movie" => DetailKind::Movie,
        "Series" => DetailKind::Series,
        "Episode" => DetailKind::Episode,
        other => {
            return Err(EmbyError::InvalidResponse(format!(
                "type d'élément non pris en charge : {other}"
            )));
        }
    };
    if !is_id(&dto.id) {
        return Err(EmbyError::InvalidResponse("identifiant invalide".into()));
    }
    let image = |t: ImageType, widths| {
        let tag = match t {
            ImageType::Backdrop => dto.backdrop_image_tags.first(),
            _ => dto.image_tags.get(t.as_str()),
        };
        images::image_ref(server, &dto.id, t, tag.map(String::as_str), widths)
    };
    let episode = if kind == DetailKind::Episode {
        let series_id = dto
            .series_id
            .clone()
            .filter(|id| is_id(id))
            .ok_or_else(|| EmbyError::InvalidResponse("épisode sans série".into()))?;
        Some(EpisodeContext {
            series_name: text(dto.series_name.clone()).unwrap_or_default(),
            season_id: dto.season_id.clone().filter(|id| is_id(id)),
            season_index: dto.parent_index_number,
            index: dto.index_number,
            index_end: dto.index_number_end,
            previous: None,
            next: None,
            series_id,
        })
    } else {
        None
    };
    let (poster, backdrop, logo) = match &episode {
        Some(ep) => episode_images(&dto, &ep.series_id, server),
        None => (
            image(ImageType::Primary, images::POSTER_WIDTHS),
            image(ImageType::Backdrop, images::BACKDROP_WIDTHS),
            image(ImageType::Logo, images::LOGO_WIDTHS),
        ),
    };
    let user_data = dto.user_data.unwrap_or_default();
    let is_series = kind == DetailKind::Series;
    let people_of = |t: &'static str| {
        dto.people
            .iter()
            .filter(move |p| p.person_type == t && !p.name.trim().is_empty())
    };
    let name = dto.name.trim().to_owned();
    Ok(ItemDetail {
        directors: people_of("Director").map(|p| p.name.clone()).collect(),
        writers: people_of("Writer").map(|p| p.name.clone()).collect(),
        // An episode lists its guests: the regular cast belongs to the series.
        cast: people_of(if episode.is_some() {
            "GuestStar"
        } else {
            "Actor"
        })
        .take(MAX_CAST)
        .map(|p| CastMember {
            name: p.name.clone(),
            role: text(p.role.clone()),
        })
        .collect(),
        original_title: text(dto.original_title).filter(|t| *t != name),
        year: dto.production_year,
        end_year: year_of(dto.end_date.as_deref()).filter(|_| is_series),
        continuing: dto
            .status
            .as_deref()
            .filter(|_| is_series)
            .and_then(|s| match s {
                "Continuing" => Some(true),
                "Ended" => Some(false),
                _ => None,
            }),
        overview: text(dto.overview),
        tagline: dto.taglines.into_iter().find_map(|t| text(Some(t))),
        genres: dto.genres,
        studios: dto
            .studios
            .into_iter()
            .filter_map(|s| text(Some(s.name)))
            .filter(|_| episode.is_none())
            .collect(),
        official_rating: text(dto.official_rating),
        community_rating: dto
            .community_rating
            .filter(|r| r.is_finite() && *r > 0.0)
            .map(|r| (r * 10.0).round() / 10.0),
        critic_rating: dto
            .critic_rating
            .filter(|r| r.is_finite() && (0.0..=100.0).contains(r))
            .map(|r| r.round() as u32),
        // Films and episodes are played from their page; a series is not.
        runtime_minutes: minutes(dto.run_time_ticks).filter(|_| !is_series),
        premiere_date: text(dto.premiere_date),
        resume: if is_series {
            None
        } else {
            resume(&user_data, dto.run_time_ticks)
        },
        played: user_data.played,
        favorite: user_data.is_favorite,
        unplayed_count: user_data.unplayed_item_count.filter(|_| is_series),
        media: if is_series {
            Vec::new()
        } else {
            dto.media_sources.into_iter().map(media_info).collect()
        },
        episode,
        id: dto.id,
        kind,
        name,
        poster,
        backdrop,
        logo,
    })
}

fn episode(dto: BaseItemDto, server: &str) -> Option<Episode> {
    if !is_id(&dto.id) {
        return None;
    }
    let thumb = images::image_ref(
        server,
        &dto.id,
        ImageType::Primary,
        dto.image_tags.get("Primary").map(String::as_str),
        images::THUMB_WIDTHS,
    );
    let user_data = dto.user_data.unwrap_or_default();
    Some(Episode {
        resume: resume(&user_data, dto.run_time_ticks),
        played: user_data.played,
        runtime_minutes: minutes(dto.run_time_ticks),
        id: dto.id,
        name: dto.name.trim().to_owned(),
        index: dto.index_number,
        index_end: dto.index_number_end,
        season_index: dto.parent_index_number,
        overview: text(dto.overview),
        premiere_date: text(dto.premiere_date),
        thumb,
    })
}
