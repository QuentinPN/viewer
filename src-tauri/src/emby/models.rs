//! Emby API models, limited to fields this client uses. Shapes verified against the
//! OpenAPI spec served by Emby Server 4.10.1.0 and its actual responses (ADR 0004).

use serde::{Deserialize, Serialize};

/// `GET /System/Info/Public` (no authentication required in practice).
#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "PascalCase")]
pub struct PublicSystemInfo {
    pub server_name: String,
    pub version: String,
    pub id: String,
}

/// `POST /Users/AuthenticateByName` request body.
#[derive(Debug, Serialize)]
#[serde(rename_all = "PascalCase")]
pub struct AuthenticateUserByName<'a> {
    pub username: &'a str,
    pub pw: &'a str,
}

/// `POST /Users/AuthenticateByName` response.
#[derive(Deserialize)]
#[serde(rename_all = "PascalCase")]
pub struct AuthenticationResult {
    pub user: UserDto,
    pub access_token: String,
    pub server_id: String,
}

impl std::fmt::Debug for AuthenticationResult {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("AuthenticationResult")
            .field("user", &self.user)
            .field("access_token", &"<redacted>")
            .field("server_id", &self.server_id)
            .finish()
    }
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "PascalCase")]
pub struct UserDto {
    pub id: String,
    pub name: String,
    #[serde(default)]
    pub policy: Option<UserPolicy>,
}

#[derive(Debug, Clone, Default, Deserialize)]
#[serde(rename_all = "PascalCase")]
pub struct UserPolicy {
    #[serde(default)]
    pub is_administrator: bool,
    #[serde(default)]
    pub is_disabled: bool,
}

/// What the UI learns about a server before signing in.
#[derive(Debug, Clone, Serialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct ServerSummary {
    pub url: String,
    pub name: String,
    pub version: String,
    pub id: String,
    /// False for plain HTTP (allowed on the local network only).
    pub encrypted: bool,
}

/// Signed-in user as exposed to the UI: never the token.
#[derive(Debug, Clone, Serialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct SignedInUser {
    pub server: ServerSummary,
    pub user_id: String,
    pub user_name: String,
    pub is_administrator: bool,
}

/// A session known from `session.json`, shown on the recovery, reauthentication
/// and server screens (ADR 0012). Contains no secret.
#[derive(Debug, Clone, Serialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct SavedServer {
    pub url: String,
    pub id: String,
    pub name: String,
    pub version: String,
    pub encrypted: bool,
    pub user_id: String,
    pub user_name: String,
}

/// What a tile of the pre-connection shelf can do (ADR 0103).
#[derive(Debug, Clone, Copy, Serialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub enum ShelfAccess {
    /// A token is in the credential store: the tile validates it, no password.
    Session,
    /// The server already rejected this session: "Mot de passe requis".
    Rejected,
    /// Signed out on purpose: the password is asked, no rejection was seen.
    SignedOut,
}

/// One tile of the pre-connection shelf. Contains no secret.
#[derive(Debug, Clone, Serialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct ShelfServer {
    pub server: SavedServer,
    pub access: ShelfAccess,
}

/// Outcome of validating the saved session (ADR 0012). Network failures keep the
/// session; only a rejection by the server asks for the password again.
#[derive(Debug, Serialize)]
#[serde(
    tag = "state",
    rename_all = "camelCase",
    rename_all_fields = "camelCase"
)]
pub enum SessionStatus {
    SignedOut,
    Authenticated {
        user: SignedInUser,
    },
    ServerUnreachable {
        server: SavedServer,
        error: super::EmbyError,
    },
    AuthenticationRequired {
        server: SavedServer,
        error: super::EmbyError,
    },
}

/// `QueryResult_BaseItemDto` from `/Users/{Id}/Views` and `/Users/{Id}/Items`.
/// Paging past the end returns `TotalRecordCount: 0` (Emby 4.10.1.0).
#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "PascalCase")]
pub struct QueryResult<T> {
    #[serde(default = "Vec::new")]
    pub items: Vec<T>,
    #[serde(default)]
    pub total_record_count: u32,
}

/// One section of the user's home screen as the server composes it
/// (`/Users/{Id}/HomeSections`, a bare array on Emby 4.10.1.0).
#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "PascalCase")]
pub struct HomeSectionDto {
    pub id: String,
    #[serde(default)]
    pub section_type: String,
    /// The "resume" section also lists the next episode of each series in progress.
    #[serde(default)]
    pub include_next_up_in_resume: bool,
}

/// Subset of `BaseItemDto` used by library views and grids. Ids are opaque strings
/// (numeric on Emby 4.10.1.0).
#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "PascalCase")]
pub struct BaseItemDto {
    pub id: String,
    #[serde(default)]
    pub name: String,
    #[serde(rename = "Type", default)]
    pub item_type: String,
    #[serde(default)]
    pub collection_type: Option<String>,
    /// Only when asked in `Fields` (letter index, ADR 0101).
    #[serde(default)]
    pub sort_name: Option<String>,
    #[serde(default)]
    pub production_year: Option<i32>,
    #[serde(default)]
    pub image_tags: std::collections::HashMap<String, String>,
    #[serde(default)]
    pub primary_image_aspect_ratio: Option<f64>,
    #[serde(default)]
    pub run_time_ticks: Option<i64>,
    #[serde(default)]
    pub user_data: Option<UserItemData>,
    // Detail fields: `/Users/{Id}/Items/{Id}` returns them without `Fields`; list
    // endpoints only when asked. `Path` is never read: it is the file path on the server.
    #[serde(default)]
    pub original_title: Option<String>,
    #[serde(default)]
    pub overview: Option<String>,
    #[serde(default)]
    pub taglines: Vec<String>,
    #[serde(default)]
    pub genres: Vec<String>,
    /// `Studios[].Id` is a number on Emby 4.10.1.0, unlike other ids: only names are read.
    #[serde(default)]
    pub studios: Vec<NameDto>,
    #[serde(default)]
    pub people: Vec<PersonDto>,
    /// `[{Name, Id}]` of the collections holding the item (ADR 0121).
    #[serde(default)]
    pub collections: Option<serde_json::Value>,
    /// Trailer files stored with the item (ADR 0121).
    #[serde(default)]
    pub local_trailer_count: Option<u32>,
    #[serde(default)]
    pub official_rating: Option<String>,
    #[serde(default)]
    pub community_rating: Option<f64>,
    #[serde(default)]
    pub critic_rating: Option<f64>,
    /// ISO 8601 in UTC, e.g. `2024-07-04T22:00:00.0000000Z` for a 5 July premiere.
    #[serde(default)]
    pub premiere_date: Option<String>,
    #[serde(default)]
    pub end_date: Option<String>,
    /// Series only: `Ended` or `Continuing`.
    #[serde(default)]
    pub status: Option<String>,
    #[serde(default)]
    pub backdrop_image_tags: Vec<String>,
    #[serde(default)]
    pub index_number: Option<u32>,
    #[serde(default)]
    pub index_number_end: Option<u32>,
    #[serde(default)]
    pub parent_index_number: Option<u32>,
    #[serde(default)]
    pub series_id: Option<String>,
    #[serde(default)]
    pub season_id: Option<String>,
    /// Episodes: the series' artwork, used when the episode has no still.
    #[serde(default)]
    pub series_name: Option<String>,
    #[serde(default)]
    pub parent_thumb_item_id: Option<String>,
    #[serde(default)]
    pub parent_thumb_image_tag: Option<String>,
    #[serde(default)]
    pub parent_backdrop_item_id: Option<String>,
    #[serde(default)]
    pub parent_backdrop_image_tags: Vec<String>,
    /// Episodes: the series' logo and poster, for the home hero.
    #[serde(default)]
    pub parent_logo_item_id: Option<String>,
    #[serde(default)]
    pub parent_logo_image_tag: Option<String>,
    #[serde(default)]
    pub series_primary_image_tag: Option<String>,
    #[serde(default)]
    pub media_sources: Vec<MediaSourceDto>,
    /// `Imdb`, `Tmdb`, `Tvdb`…; the key case varies (`IMDB` on a series, Emby
    /// 4.10.2.0). Sent to the intro services only (ADR 0030).
    #[serde(default)]
    pub provider_ids: std::collections::HashMap<String, String>,
    #[serde(default)]
    pub chapters: Vec<ChapterDto>,
}

/// `ChapterInfo`: `MarkerType` is `Chapter`, `IntroStart`, `IntroEnd` or
/// `CreditsStart` (ADR 0030). `Chapter` ones are the player's chapters (ADR 0034).
#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "PascalCase")]
pub struct ChapterDto {
    #[serde(default)]
    pub start_position_ticks: i64,
    #[serde(default)]
    pub name: Option<String>,
    #[serde(default)]
    pub marker_type: Option<String>,
}

#[derive(Debug, Clone, Default, Deserialize)]
#[serde(rename_all = "PascalCase")]
pub struct UserItemData {
    #[serde(default)]
    pub played: bool,
    #[serde(default)]
    pub unplayed_item_count: Option<u32>,
    #[serde(default)]
    pub playback_position_ticks: i64,
    #[serde(default)]
    pub is_favorite: bool,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "PascalCase")]
pub struct NameDto {
    #[serde(default)]
    pub name: String,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "PascalCase")]
pub struct PersonDto {
    #[serde(default)]
    pub name: String,
    #[serde(default)]
    pub role: Option<String>,
    /// `Actor`, `Director`, `Writer`, `Producer`, `GuestStar`…
    #[serde(rename = "Type", default)]
    pub person_type: String,
    /// Documented as a string; read as any value so that a number would not
    /// make the whole item unreadable, as `Studios[].Id` (ADR 0121).
    #[serde(default)]
    pub id: Option<serde_json::Value>,
    /// Tag of the person's photo, the `Primary` image of the person item.
    #[serde(default)]
    pub primary_image_tag: Option<String>,
}

/// One file (version) of an item. `Path` is left out on purpose.
#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "PascalCase")]
pub struct MediaSourceDto {
    /// What PlaybackInfo's `MediaSourceId` names (ADR 0036).
    #[serde(default)]
    pub id: Option<String>,
    #[serde(default)]
    pub name: Option<String>,
    #[serde(default)]
    pub container: Option<String>,
    #[serde(default)]
    pub size: Option<u64>,
    #[serde(default)]
    pub bitrate: Option<u64>,
    #[serde(default)]
    pub media_streams: Vec<MediaStreamDto>,
}

#[derive(Debug, Clone, PartialEq, Deserialize)]
#[serde(rename_all = "PascalCase")]
pub struct MediaStreamDto {
    /// `Video`, `Audio`, `Subtitle`, `EmbeddedImage`, `Attachment`…
    #[serde(rename = "Type", default)]
    pub stream_type: String,
    #[serde(default)]
    pub index: Option<i32>,
    /// PlaybackInfo only: how a subtitle reaches the client (`Embed`, `External`,
    /// `Encode`, `Hls`).
    #[serde(default)]
    pub delivery_method: Option<String>,
    /// PlaybackInfo only, with `External`: relative URL holding the token in `api_key`.
    #[serde(default)]
    pub delivery_url: Option<String>,
    #[serde(default)]
    pub codec: Option<String>,
    /// ISO 639 as stored in the file: `fre`, `fra`, `fr`, `und`… all seen on one server.
    #[serde(default)]
    pub language: Option<String>,
    #[serde(default)]
    pub title: Option<String>,
    #[serde(default)]
    pub is_default: bool,
    #[serde(default)]
    pub is_forced: bool,
    #[serde(default)]
    pub is_external: bool,
    #[serde(default)]
    pub channels: Option<u32>,
    #[serde(default)]
    pub width: Option<u32>,
    #[serde(default)]
    pub height: Option<u32>,
    #[serde(default)]
    pub bit_depth: Option<u32>,
    /// `SDR`, `HDR 10`, `DolbyVision`.
    #[serde(default)]
    pub video_range: Option<String>,
    /// `HDR 10`, `Profile 8.1 (HDR10 compatible)`, `None`.
    #[serde(default)]
    pub extended_video_sub_type_description: Option<String>,
}

/// Library kinds this client browses. Other collection types are not listed yet.
#[derive(Debug, Clone, Copy, Serialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub enum LibraryKind {
    Movies,
    TvShows,
}

impl LibraryKind {
    pub fn from_collection_type(value: Option<&str>) -> Option<Self> {
        match value {
            Some("movies") => Some(Self::Movies),
            Some("tvshows") => Some(Self::TvShows),
            _ => None,
        }
    }

    /// `IncludeItemTypes` for the grid: films or series, not episodes or folders.
    pub fn item_types(self) -> &'static str {
        match self {
            Self::Movies => "Movie",
            Self::TvShows => "Series",
        }
    }
}

/// Order of a library grid (ADR 0013).
#[derive(Debug, Clone, Copy, Default, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub enum SortKey {
    #[default]
    Name,
    DateAdded,
    Year,
    Rating,
}

impl SortKey {
    /// `SortBy`; the title breaks ties so pages stay stable.
    pub fn sort_by(self) -> &'static str {
        match self {
            Self::Name => "SortName",
            Self::DateAdded => "DateCreated,SortName",
            Self::Year => "ProductionYear,SortName",
            Self::Rating => "CommunityRating,SortName",
        }
    }
}

/// Watched state kept in a library grid (ADR 0013).
#[derive(Debug, Clone, Copy, Default, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub enum WatchFilter {
    #[default]
    All,
    Unplayed,
    Played,
    InProgress,
}

/// Sort and filters of a library grid, as the UI chooses them (ADR 0013).
#[derive(Debug, Clone, Default, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase", default)]
pub struct ItemsOptions {
    pub sort: SortKey,
    pub descending: bool,
    pub watch: WatchFilter,
    pub favorites: bool,
    pub genre: Option<String>,
    /// First year of a decade (`1990`), or every year (ADR 0101).
    pub decade: Option<i32>,
}

/// A decade the server can filter on: a multiple of ten in a sane range.
pub fn is_usable_decade(decade: i32) -> bool {
    decade % 10 == 0 && (1880..=2190).contains(&decade)
}

/// One title of a library in grid order, for the letter index (ADR 0101).
#[derive(Debug, Clone, Serialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct SortNameEntry {
    pub id: String,
    /// `SortName`, or `Name` when the server sends none.
    pub name: String,
}

/// Longest genre name sent to the server.
pub const MAX_GENRE_CHARS: usize = 100;

/// A genre the server can filter on: `Genres` is `|`-separated on Emby.
pub fn is_usable_genre(name: &str) -> bool {
    !name.trim().is_empty()
        && name.chars().count() <= MAX_GENRE_CHARS
        && !name.chars().any(|c| c == '|' || c.is_control())
}

impl ItemsOptions {
    pub fn sort_order(&self) -> &'static str {
        if self.descending {
            "Descending"
        } else {
            "Ascending"
        }
    }

    /// `Filters`, empty when nothing is filtered.
    pub fn filters(&self) -> String {
        let watch = match self.watch {
            WatchFilter::All => None,
            WatchFilter::Unplayed => Some("IsUnplayed"),
            WatchFilter::Played => Some("IsPlayed"),
            WatchFilter::InProgress => Some("IsResumable"),
        };
        watch
            .into_iter()
            .chain(self.favorites.then_some("IsFavorite"))
            .collect::<Vec<_>>()
            .join(",")
    }

    /// `Years` of the decade, `None` without one or with an unusable one.
    pub fn years(&self) -> Option<String> {
        let decade = self.decade.filter(|d| is_usable_decade(*d))?;
        Some(
            (decade..decade + 10)
                .map(|y| y.to_string())
                .collect::<Vec<_>>()
                .join(","),
        )
    }
}

/// Image ready for `<img>`: URLs point at the local `emby-img` protocol (ADR 0005).
#[derive(Debug, Clone, Serialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct ImageRef {
    pub src: String,
    pub srcset: String,
}

#[derive(Debug, Clone, Serialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct Library {
    pub id: String,
    pub name: String,
    pub kind: LibraryKind,
    pub image: Option<ImageRef>,
}

#[derive(Debug, Clone, Serialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct MediaItem {
    pub id: String,
    pub name: String,
    /// `"Movie"` or `"Series"`.
    pub item_type: String,
    pub year: Option<i32>,
    pub runtime_minutes: Option<u32>,
    pub poster: Option<ImageRef>,
    /// Width / height of the poster, when the server knows it.
    pub aspect_ratio: Option<f64>,
    pub played: bool,
    /// Series only: episodes not yet watched.
    pub unplayed_count: Option<u32>,
    /// Labels the card menu's favourite action (ADR 0100).
    pub favorite: bool,
}

/// Kinds of item the home rows "Reprendre" and "À suivre" play.
#[derive(Debug, Clone, Copy, Serialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub enum WatchKind {
    Movie,
    Episode,
}

/// A film or episode of the home rows "Reprendre" and "À suivre" (ADR 0010): the
/// card plays it, so it carries what the label needs and a 16:9 image.
#[derive(Debug, Clone, Serialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct WatchItem {
    pub id: String,
    pub name: String,
    pub kind: WatchKind,
    /// Episodes only.
    pub series_name: Option<String>,
    pub season_index: Option<u32>,
    pub index: Option<u32>,
    /// Episode still, else the series or film thumb, else its backdrop.
    pub image: Option<ImageRef>,
    pub runtime_minutes: Option<u32>,
    pub resume: Option<ResumePoint>,
    /// Label the card menu's played and favourite actions (ADR 0100).
    pub played: bool,
    pub favorite: bool,
}

/// Answer of a search (ADR 0011, 0020): films, series and episodes whose title
/// matches `query`.
#[derive(Debug, Clone, Serialize, PartialEq, Default)]
#[serde(rename_all = "camelCase")]
pub struct SearchResults {
    /// The query as sent to the server, after cleaning; empty when too short.
    pub query: String,
    pub movies: Vec<MediaItem>,
    pub series: Vec<MediaItem>,
    /// 16:9 cards, as on the home page, with their series and numbers.
    pub episodes: Vec<WatchItem>,
    /// More films than shown: the server's total is not reliable with `SearchTerm`.
    pub more_movies: bool,
    pub more_series: bool,
    pub more_episodes: bool,
}

#[derive(Debug, Clone, Serialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct ItemPage {
    pub items: Vec<MediaItem>,
    pub start_index: u32,
    /// Where the next page starts: `start_index` plus what the server returned,
    /// even if some unusable items were dropped from `items`.
    pub next_index: u32,
    pub total: u32,
}

/// One title of the home hero (ADR 0015): a wide backdrop, what the main button
/// plays, and the film or series that "Plus d'infos" opens.
#[derive(Debug, Clone, Serialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct HeroItem {
    /// Film or series of the detail page; for a resumed episode, its series.
    pub detail: MediaItem,
    pub reason: HeroReason,
    pub backdrop: ImageRef,
    pub logo: Option<ImageRef>,
    pub overview: Option<String>,
    /// Film or episode the main button plays; `None` when nothing is left to watch.
    pub play_id: Option<String>,
    /// When the main button plays an episode.
    pub episode: Option<HeroEpisode>,
    pub resume: Option<ResumePoint>,
}

#[derive(Debug, Clone, Copy, Serialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub enum HeroReason {
    /// Started but not finished ("Reprendre").
    Resume,
    /// Recently added, not watched.
    Latest,
}

#[derive(Debug, Clone, Serialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct HeroEpisode {
    pub name: String,
    pub season_index: Option<u32>,
    pub index: Option<u32>,
}

/// Kinds of item a detail page shows. Episodes open from their series (ADR 0016).
#[derive(Debug, Clone, Copy, Serialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub enum DetailKind {
    Movie,
    Series,
    Episode,
}

/// Where an episode sits in its series, and its neighbours (ADR 0016).
#[derive(Debug, Clone, Serialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct EpisodeContext {
    pub series_id: String,
    pub series_name: String,
    pub season_id: Option<String>,
    /// `0` is the specials season.
    pub season_index: Option<u32>,
    pub index: Option<u32>,
    pub index_end: Option<u32>,
    /// Previous episode of the series, across seasons, in server order.
    pub previous: Option<EpisodeLink>,
    pub next: Option<EpisodeLink>,
}

#[derive(Debug, Clone, Serialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct EpisodeLink {
    pub id: String,
    pub name: String,
    pub season_index: Option<u32>,
    pub index: Option<u32>,
    pub index_end: Option<u32>,
}

/// Where playback stopped, for an item started but not finished.
#[derive(Debug, Clone, Serialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct ResumePoint {
    pub position_seconds: u32,
    /// `0.0..=1.0` of the runtime, when the runtime is known.
    pub fraction: Option<f64>,
}

#[derive(Debug, Clone, Serialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct CastMember {
    pub name: String,
    pub role: Option<String>,
    /// The person item, for its page (ADR 0121); `None` without a usable id.
    pub id: Option<String>,
    pub photo: Option<ImageRef>,
}

#[derive(Debug, Clone, Serialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct VideoInfo {
    pub codec: Option<String>,
    pub width: Option<u32>,
    pub height: Option<u32>,
    pub bit_depth: Option<u32>,
    /// `SDR`, `HDR 10`, `Dolby Vision Profile 8.1 (HDR10 compatible)`…
    pub range: Option<String>,
}

/// Audio or subtitle track as described by the server, before any playback decision.
#[derive(Debug, Clone, Serialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct StreamInfo {
    pub language: Option<String>,
    pub codec: Option<String>,
    pub title: Option<String>,
    /// Audio only.
    pub channels: Option<u32>,
    pub is_default: bool,
    pub is_forced: bool,
    pub is_external: bool,
}

/// One version of the item: technical information only. Whether it plays directly
/// is decided later from PlaybackInfo, never from these fields.
#[derive(Debug, Clone, Serialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct MediaInfo {
    /// Passed back to `emby_play` to play this version (ADR 0036); `None` when the
    /// server gave no usable id.
    pub id: Option<String>,
    pub name: Option<String>,
    pub container: Option<String>,
    pub size_bytes: Option<u64>,
    pub bitrate: Option<u64>,
    pub video: Option<VideoInfo>,
    pub audio: Vec<StreamInfo>,
    pub subtitles: Vec<StreamInfo>,
}

#[derive(Debug, Clone, Serialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct ItemDetail {
    pub id: String,
    pub kind: DetailKind,
    pub name: String,
    /// Only when it differs from `name`.
    pub original_title: Option<String>,
    pub year: Option<i32>,
    /// Series only, from `EndDate`.
    pub end_year: Option<i32>,
    /// Series only: `Some(true)` while new episodes are expected.
    pub continuing: Option<bool>,
    pub overview: Option<String>,
    pub tagline: Option<String>,
    pub genres: Vec<String>,
    pub studios: Vec<String>,
    pub directors: Vec<String>,
    pub writers: Vec<String>,
    /// Actors; guest stars for an episode.
    pub cast: Vec<CastMember>,
    pub official_rating: Option<String>,
    /// Out of 10, one decimal.
    pub community_rating: Option<f64>,
    /// Out of 100.
    pub critic_rating: Option<u32>,
    /// Movie and episode only.
    pub runtime_minutes: Option<u32>,
    pub premiere_date: Option<String>,
    /// The series poster for an episode.
    pub poster: Option<ImageRef>,
    /// The episode's own image for an episode, else the series backdrop.
    pub backdrop: Option<ImageRef>,
    /// The series logo for an episode.
    pub logo: Option<ImageRef>,
    pub played: bool,
    /// In the user's favorites (ADR 0014).
    pub favorite: bool,
    pub resume: Option<ResumePoint>,
    /// Series only.
    pub unplayed_count: Option<u32>,
    /// Movie and episode only; empty for a series.
    pub media: Vec<MediaInfo>,
    /// Episode only.
    pub episode: Option<EpisodeContext>,
    /// Collections holding the film or series (ADR 0121), at most three.
    pub collections: Vec<super::titles::CollectionRef>,
    /// A trailer file is stored with the item (ADR 0121).
    pub has_local_trailer: bool,
}

#[derive(Debug, Clone, Serialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct Season {
    pub id: String,
    pub name: String,
    /// `0` is the specials season on Emby.
    pub index: Option<u32>,
    pub unplayed_count: Option<u32>,
    pub played: bool,
}

#[derive(Debug, Clone, Serialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct Episode {
    pub id: String,
    pub name: String,
    pub index: Option<u32>,
    /// Last number of a double episode (`IndexNumberEnd`).
    pub index_end: Option<u32>,
    pub season_index: Option<u32>,
    pub overview: Option<String>,
    pub runtime_minutes: Option<u32>,
    pub premiere_date: Option<String>,
    /// 16:9 still, when the server has one.
    pub thumb: Option<ImageRef>,
    pub played: bool,
    pub resume: Option<ResumePoint>,
}

#[derive(Debug, Clone, Serialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct EpisodePage {
    pub items: Vec<Episode>,
    pub start_index: u32,
    pub next_index: u32,
    pub total: u32,
}

/// `POST /Items/{Id}/PlaybackInfo` request body (ADR 0007).
#[derive(Debug, Serialize)]
#[serde(rename_all = "PascalCase")]
pub struct PlaybackInfoRequest<'a> {
    pub user_id: &'a str,
    pub max_streaming_bitrate: u64,
    pub device_profile: &'a DeviceProfile,
    /// The answer is used to start playback, not only to inspect the item.
    pub is_playback: bool,
    pub auto_open_live_stream: bool,
    /// Both false asks for a transcode: the fallback when the player could not open
    /// what the server offered for direct playback (ADR 0023).
    pub enable_direct_play: bool,
    pub enable_direct_stream: bool,
    /// The audio a transcode must carry (ADR 0035). Emby 4.10.1.0 ignores
    /// `AudioStreamIndex` without `MediaSourceId`.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub media_source_id: Option<&'a str>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub audio_stream_index: Option<i32>,
}

/// What the player can play, as Emby's `DeviceProfile` describes it. Comma
/// separated lists, as the server expects.
#[derive(Debug, Serialize)]
#[serde(rename_all = "PascalCase")]
pub struct DeviceProfile {
    pub name: &'static str,
    pub max_streaming_bitrate: u64,
    pub direct_play_profiles: Vec<DirectPlayProfile>,
    pub transcoding_profiles: Vec<TranscodingProfile>,
    pub subtitle_profiles: Vec<SubtitleProfile>,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "PascalCase")]
pub struct DirectPlayProfile {
    #[serde(rename = "Type")]
    pub kind: &'static str,
    pub container: &'static str,
    pub video_codec: &'static str,
    pub audio_codec: &'static str,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "PascalCase")]
pub struct TranscodingProfile {
    #[serde(rename = "Type")]
    pub kind: &'static str,
    pub container: &'static str,
    pub protocol: &'static str,
    pub video_codec: &'static str,
    pub audio_codec: &'static str,
    pub context: &'static str,
    pub max_audio_channels: &'static str,
    pub break_on_non_key_frames: bool,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "PascalCase")]
pub struct SubtitleProfile {
    pub format: &'static str,
    /// `Embed`, `External`, `Encode`, `Hls`.
    pub method: &'static str,
}

/// `POST /Items/{Id}/PlaybackInfo` response.
#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "PascalCase")]
pub struct PlaybackInfoResponse {
    #[serde(default)]
    pub media_sources: Vec<MediaSourceInfo>,
    #[serde(default)]
    pub play_session_id: Option<String>,
    /// `NotAllowed`, `NoCompatibleStream`, `RateLimitExceeded`.
    #[serde(default)]
    pub error_code: Option<String>,
}

/// One playable source with the server's decision. `Path` is left out on purpose;
/// the stream URLs carry the token in `api_key` (removed before use).
#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "PascalCase")]
pub struct MediaSourceInfo {
    pub id: String,
    /// `File`, `Http`…
    #[serde(default)]
    pub protocol: Option<String>,
    #[serde(default)]
    pub container: Option<String>,
    #[serde(default)]
    pub run_time_ticks: Option<i64>,
    #[serde(default)]
    pub supports_direct_play: bool,
    #[serde(default)]
    pub supports_direct_stream: bool,
    #[serde(default)]
    pub supports_transcoding: bool,
    #[serde(default)]
    pub direct_stream_url: Option<String>,
    #[serde(default)]
    pub transcoding_url: Option<String>,
    /// `hls` for a transcode.
    #[serde(default)]
    pub transcoding_sub_protocol: Option<String>,
    #[serde(default)]
    pub transcoding_container: Option<String>,
    /// Headers the stream needs, in addition to authentication.
    #[serde(default)]
    pub required_http_headers: Option<std::collections::HashMap<String, String>>,
    /// Audio the server picked from the user's preferences (its `Configuration`).
    #[serde(default)]
    pub default_audio_stream_index: Option<i32>,
    /// Subtitle the server picked from the user's preferences.
    #[serde(default)]
    pub default_subtitle_stream_index: Option<i32>,
    #[serde(default)]
    pub media_streams: Vec<MediaStreamDto>,
}

/// How the server delivers the media.
#[derive(Debug, Clone, Copy, Serialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub enum PlayMethod {
    DirectPlay,
    DirectStream,
    Transcode,
}

impl PlayMethod {
    /// Value of `PlayMethod` in Emby's playback reports.
    pub fn as_emby(self) -> &'static str {
        match self {
            Self::DirectPlay => "DirectPlay",
            Self::DirectStream => "DirectStream",
            Self::Transcode => "Transcode",
        }
    }
}

/// What the UI learns about a started playback: no URL, no token.
#[derive(Debug, Clone, Serialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct PlaybackStart {
    pub item_id: String,
    /// The version played (ADR 0036): a fallback or a resume plays the same one.
    pub media_source_id: String,
    /// The start asked for: a fallback keeps "Reprendre" or "Lire depuis le début".
    pub resume: bool,
    pub method: PlayMethod,
    pub container: Option<String>,
    /// Why the server transcodes, in French.
    pub transcode_reasons: Option<String>,
    /// What the player shows at the top (ADR 0017); `None` when the item could not be read.
    pub title: Option<PlaybackTitle>,
    /// Bitrate cap sent to the server (ADR 0037); `None` for "Maximale".
    pub bitrate_cap: Option<u64>,
    /// Whether the session's address counts as local, so which cap applied.
    pub local_address: bool,
}

/// Name of what plays and, for an episode, where it sits in its series (ADR 0017).
#[derive(Debug, Clone, Serialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct PlaybackTitle {
    pub name: String,
    pub series_name: Option<String>,
    /// `0` is the specials season.
    pub season_index: Option<u32>,
    pub index: Option<u32>,
    pub index_end: Option<u32>,
}

/// Body of `POST /Sessions/Playing`, `/Sessions/Playing/Progress` and
/// `/Sessions/Playing/Stopped` (ADR 0008). Fields a report does not use are omitted.
#[derive(Debug, Clone, Serialize, PartialEq)]
#[serde(rename_all = "PascalCase")]
pub struct PlaybackReportBody {
    pub item_id: String,
    pub media_source_id: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub play_session_id: Option<String>,
    pub position_ticks: i64,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub run_time_ticks: Option<i64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub is_paused: Option<bool>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub can_seek: Option<bool>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub play_method: Option<&'static str>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub event_name: Option<&'static str>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub failed: Option<bool>,
}

/// Server state of an item after a playback stopped, sent to the UI so the detail
/// page shows the new resume point or watched status without reloading.
#[derive(Debug, Clone, Serialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct PlaybackStopped {
    pub item_id: String,
    pub played: bool,
    pub resume: Option<ResumePoint>,
}

/// `AddToPlaylistResult` of `POST /Playlists/{Id}/Items`.
#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "PascalCase")]
pub struct AddToPlaylistResultDto {
    #[serde(default)]
    pub item_added_count: u32,
}

/// `PlaylistCreationResult` of `POST /Playlists`.
#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "PascalCase")]
pub struct PlaylistCreationResultDto {
    pub id: String,
    #[serde(default)]
    pub name: String,
    #[serde(default)]
    pub item_added_count: u32,
}

/// A playlist of the user, offered by the card menu (ADR 0100).
#[derive(Debug, Clone, Serialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct Playlist {
    pub id: String,
    pub name: String,
}

/// A playlist just created, and how many items the server says it holds.
#[derive(Debug, Clone, Serialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct PlaylistCreated {
    pub id: String,
    pub name: String,
    pub added: u32,
}
