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
}

#[derive(Debug, Clone, Default, Deserialize)]
#[serde(rename_all = "PascalCase")]
pub struct UserItemData {
    #[serde(default)]
    pub played: bool,
    #[serde(default)]
    pub unplayed_item_count: Option<u32>,
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
