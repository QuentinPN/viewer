//! Request API models, field for field from `contracts/request/openapi.json`.
//! Optional schema fields are `Option` with `#[serde(default)]`.

use serde::{Deserialize, Deserializer, Serialize, Serializer};

use crate::emby::models::ImageRef;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum MediaType {
    Movie,
    Tv,
}

impl MediaType {
    pub fn as_str(self) -> &'static str {
        match self {
            Self::Movie => "movie",
            Self::Tv => "tv",
        }
    }
}

/// Availability of a title or a season (`status` of `MediaSummary`).
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum MediaStatus {
    Available,
    Partial,
    Downloading,
    Requested,
    Pending,
    Declined,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum RequestStatus {
    Pending,
    Approved,
    Declined,
    Cancelled,
    Sent,
    Failed,
    Available,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum Role {
    Member,
    AutoApprove,
    Manager,
    Owner,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum SeriesType {
    Standard,
    Anime,
    Daily,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum EventType {
    Requested,
    Approved,
    Declined,
    Cancelled,
    Sent,
    Failed,
    Retried,
    Available,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum CrewJob {
    Director,
    Writer,
    Creator,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum SortOrder {
    Popularity,
    Rating,
    Release,
    Title,
}

impl SortOrder {
    pub fn as_str(self) -> &'static str {
        match self {
            Self::Popularity => "popularity",
            Self::Rating => "rating",
            Self::Release => "release",
            Self::Title => "title",
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ApiVersion {
    pub api_version: String,
    pub features: Vec<String>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Capabilities {
    pub can_request_movies: bool,
    pub can_request_tv: bool,
    pub can_request4k: bool,
    pub auto_approve_movies: bool,
    pub auto_approve_tv: bool,
    pub auto_approve4k: bool,
    pub can_request_on_behalf: bool,
    pub can_request_quota_exempt: bool,
    pub can_advanced_request: bool,
    pub can_auto_request_watchlist: bool,
    pub can_manage_requests: bool,
    pub can_manage_issues: bool,
    pub can_manage_blocklist: bool,
    pub can_view_stats: bool,
    pub can_manage_users: bool,
    pub can_request: bool,
    pub is_owner: bool,
    pub quota_exempt: bool,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Viewer {
    pub id: String,
    pub name: String,
    pub role: Role,
    pub capabilities: Capabilities,
    pub avatar_url: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Eq, Deserialize)]
pub struct ViewerResponse {
    pub viewer: Viewer,
}

/// Answer of `POST /api/auth/emby-token`. Never sent to the UI: it holds the token.
#[derive(Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct EmbyTokenSession {
    pub token: super::client::SessionToken,
    pub expires_at: String,
    pub viewer: Viewer,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct AuthSession {
    pub id: String,
    pub device_name: Option<String>,
    pub user_agent: Option<String>,
    pub created_at: String,
    pub last_seen_at: String,
    pub expires_at: String,
    pub current: bool,
}

#[derive(Debug, Clone, PartialEq, Eq, Deserialize)]
pub struct SessionList {
    pub sessions: Vec<AuthSession>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Genre {
    pub id: i64,
    pub name: String,
}

#[derive(Debug, Clone, PartialEq, Eq, Deserialize)]
pub struct GenreList {
    pub genres: Vec<Genre>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "by", rename_all = "lowercase")]
pub enum Blocked {
    Title,
    Keyword { keyword: String },
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct MediaSummary {
    pub tmdb_id: i64,
    #[serde(rename = "type")]
    pub media_type: MediaType,
    pub title: String,
    pub year: Option<i32>,
    pub poster_path: Option<String>,
    pub backdrop_path: Option<String>,
    pub overview: String,
    #[serde(default)]
    pub blocked: Option<Blocked>,
    #[serde(default)]
    pub status: Option<MediaStatus>,
    #[serde(default)]
    pub progress: Option<f64>,
    #[serde(default)]
    pub emby_item_id: Option<String>,
    /// Built in Rust on the `requestimg` protocol; never read from the server.
    #[serde(default, skip_deserializing)]
    pub poster: Option<ImageRef>,
    /// Built in Rust on the `requestimg` protocol; never read from the server.
    #[serde(default, skip_deserializing)]
    pub backdrop: Option<ImageRef>,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PagedMedia {
    pub page: u32,
    pub total_pages: u32,
    pub results: Vec<MediaSummary>,
}

#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SeeAllFilters {
    #[serde(default)]
    pub genres: Option<Vec<i64>>,
    #[serde(default)]
    pub year_from: Option<i32>,
    #[serde(default)]
    pub year_to: Option<i32>,
    #[serde(default)]
    pub rating: Option<i32>,
    #[serde(default)]
    pub runtime_min: Option<i32>,
    #[serde(default)]
    pub runtime_max: Option<i32>,
    #[serde(default)]
    pub language: Option<String>,
    #[serde(default)]
    pub provider: Option<i64>,
    #[serde(default)]
    pub sort: Option<SortOrder>,
    #[serde(default)]
    pub company: Option<i64>,
    #[serde(default)]
    pub network: Option<i64>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct SeeAll {
    #[serde(rename = "type")]
    pub media_type: MediaType,
    pub filters: SeeAllFilters,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct DiscoverRow {
    pub id: String,
    pub title: String,
    pub items: Vec<MediaSummary>,
    #[serde(default)]
    pub see_all: Option<SeeAll>,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct DiscoverResponse {
    pub rows: Vec<DiscoverRow>,
    pub layout: Vec<String>,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SearchPerson {
    pub id: i64,
    pub name: String,
    pub profile_path: Option<String>,
    /// Built in Rust on the `requestimg` protocol; never read from the server.
    #[serde(default, skip_deserializing)]
    pub profile: Option<ImageRef>,
    pub known_for: Vec<String>,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct MediaSearchResults {
    pub page: u32,
    pub total_pages: u32,
    pub results: Vec<MediaSummary>,
    pub people: Vec<SearchPerson>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct EpisodeCount {
    pub present: u32,
    pub expected: u32,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct FourK {
    #[serde(default)]
    pub status: Option<MediaStatus>,
    #[serde(default)]
    pub progress: Option<f64>,
    #[serde(default)]
    pub episodes: Option<EpisodeCount>,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Season {
    pub season_number: u32,
    pub episode_count: u32,
    pub air_date: Option<String>,
    #[serde(default)]
    pub episodes: Option<EpisodeCount>,
    #[serde(default)]
    pub four_k: Option<FourK>,
    #[serde(default)]
    pub status: Option<MediaStatus>,
    #[serde(default)]
    pub progress: Option<f64>,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct CastMember {
    pub id: i64,
    pub name: String,
    pub character: String,
    pub profile_path: Option<String>,
    /// Built in Rust on the `requestimg` protocol; never read from the server.
    #[serde(default, skip_deserializing)]
    pub profile: Option<ImageRef>,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct CrewMember {
    pub id: i64,
    pub name: String,
    pub jobs: Vec<CrewJob>,
    pub profile_path: Option<String>,
    /// Built in Rust on the `requestimg` protocol; never read from the server.
    #[serde(default, skip_deserializing)]
    pub profile: Option<ImageRef>,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Collection {
    pub id: i64,
    pub name: String,
    pub poster_path: Option<String>,
    pub backdrop_path: Option<String>,
    /// Built in Rust on the `requestimg` protocol; never read from the server.
    #[serde(default, skip_deserializing)]
    pub poster: Option<ImageRef>,
    /// Built in Rust on the `requestimg` protocol; never read from the server.
    #[serde(default, skip_deserializing)]
    pub backdrop: Option<ImageRef>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct WatchProvider {
    pub id: i64,
    pub name: String,
    pub logo_path: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct WatchProviders {
    pub flatrate: Vec<WatchProvider>,
    pub rent: Vec<WatchProvider>,
    pub buy: Vec<WatchProvider>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct ExternalIds {
    pub imdb: Option<String>,
    pub tvdb: Option<i64>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct LastAired {
    pub season_number: u32,
    pub episode_number: u32,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct MediaDetails {
    pub tmdb_id: i64,
    #[serde(rename = "type")]
    pub media_type: MediaType,
    pub title: String,
    pub year: Option<i32>,
    pub poster_path: Option<String>,
    pub backdrop_path: Option<String>,
    pub overview: String,
    #[serde(default)]
    pub blocked: Option<Blocked>,
    #[serde(default)]
    pub status: Option<MediaStatus>,
    #[serde(default)]
    pub progress: Option<f64>,
    pub emby_item_id: Option<String>,
    pub genres: Vec<Genre>,
    pub runtime: Option<u32>,
    pub seasons: Vec<Season>,
    pub cast: Vec<CastMember>,
    #[serde(default)]
    pub crew: Option<Vec<CrewMember>>,
    #[serde(default)]
    pub collection: Option<Collection>,
    pub trailer_key: Option<String>,
    pub watch_providers: WatchProviders,
    pub recommendations: Vec<MediaSummary>,
    pub similar: Vec<MediaSummary>,
    pub external_ids: ExternalIds,
    #[serde(default)]
    pub four_k: Option<FourK>,
    #[serde(default)]
    pub keywords: Option<Vec<Genre>>,
    #[serde(default)]
    pub original_language: Option<String>,
    #[serde(default)]
    pub last_aired: Option<LastAired>,
    /// Built in Rust on the `requestimg` protocol; never read from the server.
    #[serde(default, skip_deserializing)]
    pub poster: Option<ImageRef>,
    /// Built in Rust on the `requestimg` protocol; never read from the server.
    #[serde(default, skip_deserializing)]
    pub backdrop: Option<ImageRef>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct QuotaUsage {
    pub limit: i64,
    pub days: i64,
    pub used: i64,
    pub remaining: i64,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct RequestQuota {
    pub movies: QuotaUsage,
    pub seasons: QuotaUsage,
    pub exempt: bool,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct RequestPerson {
    pub id: String,
    pub name: String,
    pub avatar_url: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct RequestServer {
    pub id: i64,
    pub name: String,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct RequestView {
    pub id: i64,
    #[serde(rename = "type")]
    pub media_type: MediaType,
    pub tmdb_id: i64,
    pub status: RequestStatus,
    pub seasons: Vec<u32>,
    pub requester: RequestPerson,
    pub created_by: Option<RequestPerson>,
    pub quota_exempt: bool,
    pub decided_by: Option<RequestPerson>,
    pub decline_reason: Option<String>,
    pub quality_profile_id: Option<i64>,
    pub root_folder: Option<String>,
    pub series_type: Option<SeriesType>,
    pub is4k: bool,
    pub server: Option<RequestServer>,
    pub created_at: String,
    pub updated_at: String,
    pub media: Option<MediaSummary>,
    pub failure_reason: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct RequestEvent {
    pub id: i64,
    #[serde(rename = "type")]
    pub event_type: EventType,
    pub actor: Option<RequestPerson>,
    pub detail: Option<String>,
    pub created_at: String,
}

/// `RequestDetail` = every `RequestView` field plus `events`.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct RequestDetail {
    #[serde(flatten)]
    pub request: RequestView,
    pub events: Vec<RequestEvent>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct RequestTotals {
    pub pending: u32,
    pub approved: u32,
    pub declined: u32,
    pub cancelled: u32,
    pub sent: u32,
    pub failed: u32,
    pub available: u32,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct RequestPage {
    pub page: u32,
    pub total_pages: u32,
    pub results: Vec<RequestView>,
    pub totals: RequestTotals,
}

/// `seasons` of a series request: `"all"` or a list of season numbers.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum SeasonsChoice {
    All,
    List(Vec<u32>),
}

impl Serialize for SeasonsChoice {
    fn serialize<S: Serializer>(&self, s: S) -> Result<S::Ok, S::Error> {
        match self {
            Self::All => s.serialize_str("all"),
            Self::List(list) => list.serialize(s),
        }
    }
}

impl<'de> Deserialize<'de> for SeasonsChoice {
    fn deserialize<D: Deserializer<'de>>(d: D) -> Result<Self, D::Error> {
        #[derive(Deserialize)]
        #[serde(untagged)]
        enum Raw {
            Word(String),
            List(Vec<u32>),
        }
        match Raw::deserialize(d)? {
            Raw::Word(w) if w == "all" => Ok(Self::All),
            Raw::Word(w) => Err(serde::de::Error::custom(format!("saisons invalides : {w}"))),
            Raw::List(list) if list.is_empty() => {
                Err(serde::de::Error::custom("aucune saison choisie"))
            }
            Raw::List(list) => Ok(Self::List(list)),
        }
    }
}

/// Body of `POST /api/requests` (member fields only: no 4K, no advanced options).
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct NewRequest {
    #[serde(rename = "type")]
    pub media_type: MediaType,
    pub tmdb_id: i64,
    /// Series only; the server's body schema forbids unknown or null fields.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub seasons: Option<SeasonsChoice>,
}
