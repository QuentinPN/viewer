//! Sign-in lifecycle: probe, sign in, restore at startup, sign out. Non-secret
//! session metadata lives in `session.json`; the token lives in a `CredentialStore`.

use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Duration;

use serde::{Deserialize, Serialize};

use super::EmbyError;
use super::artwork_cache::{ArtworkCache, ArtworkCacheStats};
use super::client::{AccessToken, DeviceIdentity, EmbyClient, EpisodesQuery, ItemsQuery};
use super::credentials::{self, CredentialStore};
use super::images::{self, ImageRequest, ImageType};
use super::models::{
    BaseItemDto, CastMember, DetailKind, Episode, EpisodeContext, EpisodeLink, EpisodePage,
    HeroEpisode, HeroItem, HeroReason, ImageRef, ItemDetail, ItemPage, ItemsOptions, Library,
    LibraryKind, MediaInfo, MediaItem, MediaSourceDto, MediaStreamDto, PlaybackStopped,
    PlaybackTitle, ResumePoint, SavedServer, SearchResults, Season, ServerSummary, SessionStatus,
    SignedInUser, StreamInfo, UserItemData, VideoInfo, WatchItem, WatchKind, is_usable_genre,
};
use super::playback::{self, PlaybackPlan};
use super::reporting::Report;
use super::server_url::ServerUrl;

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
        }
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
/// Shorter queries are not sent: the server answers nothing to one letter.
const SEARCH_MIN_CHARS: usize = 2;
const SEARCH_MAX_CHARS: usize = 100;
const TICKS_PER_MINUTE: i64 = 600_000_000;
const IMAGE_CONCURRENCY: usize = 6;
/// Sessions kept aside by "Changer de serveur"; the oldest is forgotten beyond.
pub const MAX_SAVED: usize = 8;

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
    /// Bounds concurrent artwork downloads to spare the NAS (ADR 0005).
    image_permits: tokio::sync::Semaphore,
    /// Sent to PlaybackInfo; the server transcodes files above it (ADR 0009).
    max_streaming_bitrate: u64,
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
        Self {
            store,
            path,
            file: Mutex::new(file),
            device_name,
            version,
            current: Mutex::new(None),
            image_permits: tokio::sync::Semaphore::new(IMAGE_CONCURRENCY),
            max_streaming_bitrate: playback::MAX_STREAMING_BITRATE,
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
        self.max_streaming_bitrate = bitrate;
        self
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
        if options
            .genre
            .as_deref()
            .is_some_and(|g| !is_usable_genre(g))
        {
            return Err(EmbyError::InvalidResponse("genre invalide".into()));
        }
        let kind = self.known_library(library_id).await?;
        let (client, user_id) = self.session()?;
        let scope = self.scope();
        let query = ItemsQuery {
            parent_id: library_id,
            item_types: kind.item_types(),
            start_index,
            limit: limit.clamp(1, MAX_PAGE),
            options,
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

    /// Films and series whose title matches `query` (ADR 0011), both searched at
    /// once. A query shorter than two characters once cleaned reaches no server.
    pub async fn search(&self, query: &str) -> Result<SearchResults, EmbyError> {
        let query = clean_search(query);
        if query.chars().count() < SEARCH_MIN_CHARS {
            return Ok(SearchResults::default());
        }
        let (client, user_id) = self.session()?;
        let scope = self.scope();
        let asked = SEARCH_SHOWN as u32 + 1;
        let (movies, series) = tokio::join!(
            client.search(&user_id, &query, "Movie", asked),
            client.search(&user_id, &query, "Series", asked)
        );
        let keep = |dtos: Vec<BaseItemDto>, item_type: &str| {
            let items: Vec<MediaItem> = dtos
                .into_iter()
                .filter(|d| d.item_type == item_type)
                .filter_map(|d| media_item(d, &scope))
                .collect();
            let more = items.len() > SEARCH_SHOWN;
            (items.into_iter().take(SEARCH_SHOWN).collect(), more)
        };
        let (movies, more_movies) = keep(self.expire_on_401(&client, movies)?.items, "Movie");
        let (series, more_series) = keep(self.expire_on_401(&client, series)?.items, "Series");
        Ok(SearchResults {
            query,
            movies,
            series,
            more_movies,
            more_series,
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
        let (client, user_id) = self.session()?;
        let profile = playback::device_profile(self.max_streaming_bitrate);
        let (info, item) = tokio::join!(
            client.playback_info(&user_id, item_id, &profile),
            client.item(&user_id, item_id)
        );
        let response = self.expire_on_401(&client, info)?;
        let mut plan = playback::plan(client.server(), response)?;
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
    pub fn change_server(&self) -> Result<(), EmbyError> {
        self.set_current(None);
        let evicted = self.update_file(|f| {
            f.reauth = None;
            if let Some(r) = f.session.take() {
                f.saved.retain(|s| !s.same_account(&r));
                f.saved.insert(0, r);
            }
            let keep = f.saved.len().min(MAX_SAVED);
            f.saved.split_off(keep)
        })?;
        for r in evicted {
            if let Err(e) = self.store.delete(&r.account()) {
                log::warn!("evicted token not deleted: {}", e.kind());
            }
        }
        Ok(())
    }

    /// Sessions set aside whose token is still in the store.
    pub fn saved_sessions(&self) -> Vec<SavedServer> {
        self.file()
            .saved
            .iter()
            .filter(|r| matches!(self.store.load(&r.account()), Ok(Some(_))))
            .map(SessionRecord::summary)
            .collect()
    }

    /// Brings a set-aside session back, then validates it like at startup.
    pub async fn resume_saved(
        &self,
        server_id: &str,
        user_id: &str,
    ) -> Result<SessionStatus, EmbyError> {
        self.set_current(None);
        let found = self.update_file(|f| {
            let pos = f
                .saved
                .iter()
                .position(|s| s.server_id == server_id && s.user_id == user_id)?;
            let record = f.saved.remove(pos);
            if let Some(previous) = f.session.take() {
                f.saved.insert(0, previous);
            }
            f.reauth = None;
            f.last_server_url = Some(record.server_url.clone());
            f.session = Some(record);
            Some(())
        })?;
        if found.is_none() {
            return Err(EmbyError::NotFound);
        }
        self.restore().await
    }

    /// Revokes the token on the server when reachable, then always forgets it
    /// locally: an offline sign-out must not leave a usable token on disk.
    pub async fn sign_out(&self) -> Result<(), EmbyError> {
        let current = {
            let mut guard = self.lock_current();
            self.epoch.fetch_add(1, Ordering::SeqCst);
            guard.take()
        };
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
        self.update_file(|f| {
            f.session = None;
            f.reauth = None;
        })
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

fn is_id(id: &str) -> bool {
    !id.is_empty() && id.chars().all(|c| c.is_ascii_alphanumeric() || c == '-')
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
