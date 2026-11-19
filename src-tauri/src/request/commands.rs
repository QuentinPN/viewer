//! Tauri commands of the « Demander » tab (ADR 0125). Arguments are typed and
//! validated here; models come back with their `ImageRef`s filled.

use reqwest::Method;
use serde::Deserialize;
use tauri::State;

use super::RequestError;
use super::images::decorated;
use super::models::{
    AuthSession, DiscoverResponse, Genre, GenreList, MediaDetails, MediaSearchResults, MediaType,
    NewRequest, PagedMedia, RequestDetail, RequestPage, RequestQuota, RequestStatus, SessionList,
    SortOrder,
};
use super::service::{Identity, UrStatus, RequestService};
use crate::emby::session::EmbyService;

/// Longest search accepted by the server.
const QUERY_MAX: usize = 100;

pub fn identity(emby: &EmbyService) -> Result<Identity, RequestError> {
    let (server_id, user_id, emby_token) = emby.exchange_identity()?;
    Ok(Identity {
        server_id,
        user_id,
        emby_token,
    })
}

/// Filters of « Parcourir » (`GET /api/discover/{type}`).
#[derive(Debug, Clone, Default, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct BrowseFilters {
    #[serde(default)]
    pub page: Option<u32>,
    #[serde(default)]
    pub genres: Vec<i64>,
    #[serde(default)]
    pub year_from: Option<i32>,
    #[serde(default)]
    pub year_to: Option<i32>,
    #[serde(default)]
    pub rating: Option<i32>,
    #[serde(default)]
    pub sort: Option<SortOrder>,
}

impl BrowseFilters {
    pub fn query(&self) -> Vec<(&'static str, String)> {
        let mut q = vec![("page", self.page.unwrap_or(1).max(1).to_string())];
        if !self.genres.is_empty() {
            let ids: Vec<String> = self.genres.iter().map(i64::to_string).collect();
            q.push(("genres", ids.join(",")));
        }
        let numbers = [
            ("yearFrom", self.year_from),
            ("yearTo", self.year_to),
            ("rating", self.rating),
        ];
        q.extend(
            numbers
                .into_iter()
                .filter_map(|(k, v)| v.map(|v| (k, v.to_string()))),
        );
        if let Some(sort) = self.sort {
            q.push(("sort", sort.as_str().to_owned()));
        }
        q
    }
}

/// `?status=a,b`: several statuses in one comma-separated value, never repeated.
pub fn requests_query(status: &[RequestStatus], page: u32) -> Vec<(&'static str, String)> {
    let mut q = vec![
        ("mine", "true".to_owned()),
        ("page", page.max(1).to_string()),
    ];
    if !status.is_empty() {
        let names: Vec<String> = status
            .iter()
            .filter_map(|s| serde_json::to_value(s).ok())
            .filter_map(|v| v.as_str().map(str::to_owned))
            .collect();
        q.push(("status", names.join(",")));
    }
    q
}

/// Opaque session id from `/api/auth/sessions`, safe as a path segment.
fn is_session_id(id: &str) -> bool {
    !id.is_empty()
        && id.len() <= 128
        && id
            .chars()
            .all(|c| c.is_ascii_alphanumeric() || c == '_' || c == '-')
}

#[tauri::command]
pub async fn ur_status(
    emby: State<'_, EmbyService>,
    ur: State<'_, RequestService>,
) -> Result<UrStatus, RequestError> {
    Ok(ur.status(&identity(&emby)?).await)
}

#[tauri::command]
pub async fn ur_set_address(
    emby: State<'_, EmbyService>,
    ur: State<'_, RequestService>,
    address: String,
) -> Result<UrStatus, RequestError> {
    let id = identity(&emby)?;
    ur.set_address(&address)?;
    Ok(ur.status(&id).await)
}

#[tauri::command]
pub async fn ur_sign_in(
    emby: State<'_, EmbyService>,
    ur: State<'_, RequestService>,
) -> Result<UrStatus, RequestError> {
    let id = identity(&emby)?;
    ur.sign_in(&id)?;
    Ok(ur.status(&id).await)
}

#[tauri::command]
pub async fn ur_sign_out(
    emby: State<'_, EmbyService>,
    ur: State<'_, RequestService>,
) -> Result<(), RequestError> {
    ur.sign_out(&identity(&emby)?).await
}

#[tauri::command]
pub async fn ur_discover(
    emby: State<'_, EmbyService>,
    ur: State<'_, RequestService>,
) -> Result<DiscoverResponse, RequestError> {
    let r = ur.get(&identity(&emby)?, "/api/discover", &[]).await?;
    Ok(decorated::<DiscoverResponse>(r))
}

#[tauri::command]
pub async fn ur_browse(
    emby: State<'_, EmbyService>,
    ur: State<'_, RequestService>,
    media_type: MediaType,
    filters: BrowseFilters,
) -> Result<PagedMedia, RequestError> {
    let path = format!("/api/discover/{}", media_type.as_str());
    let r = ur.get(&identity(&emby)?, &path, &filters.query()).await?;
    Ok(decorated::<PagedMedia>(r))
}

#[tauri::command]
pub async fn ur_genres(
    emby: State<'_, EmbyService>,
    ur: State<'_, RequestService>,
    media_type: MediaType,
) -> Result<Vec<Genre>, RequestError> {
    let path = format!("/api/genres/{}", media_type.as_str());
    let list: GenreList = ur.get(&identity(&emby)?, &path, &[]).await?;
    Ok(list.genres)
}

#[tauri::command]
pub async fn ur_search(
    emby: State<'_, EmbyService>,
    ur: State<'_, RequestService>,
    query: String,
    page: Option<u32>,
) -> Result<MediaSearchResults, RequestError> {
    let q: String = query.trim().chars().take(QUERY_MAX).collect();
    if q.is_empty() {
        return Ok(MediaSearchResults {
            page: 1,
            total_pages: 0,
            results: Vec::new(),
            people: Vec::new(),
        });
    }
    let params = [("q", q), ("page", page.unwrap_or(1).max(1).to_string())];
    let r = ur.get(&identity(&emby)?, "/api/search", &params).await?;
    Ok(decorated::<MediaSearchResults>(r))
}

#[tauri::command]
pub async fn ur_media(
    emby: State<'_, EmbyService>,
    ur: State<'_, RequestService>,
    media_type: MediaType,
    tmdb_id: u64,
) -> Result<MediaDetails, RequestError> {
    let path = format!("/api/media/{}/{tmdb_id}", media_type.as_str());
    let r = ur.get(&identity(&emby)?, &path, &[]).await?;
    Ok(decorated::<MediaDetails>(r))
}

#[tauri::command]
pub async fn ur_quota(
    emby: State<'_, EmbyService>,
    ur: State<'_, RequestService>,
) -> Result<RequestQuota, RequestError> {
    ur.get(&identity(&emby)?, "/api/requests/quota", &[]).await
}

#[tauri::command]
pub async fn ur_request(
    emby: State<'_, EmbyService>,
    ur: State<'_, RequestService>,
    request: NewRequest,
) -> Result<RequestDetail, RequestError> {
    let r = ur
        .send(
            &identity(&emby)?,
            Method::POST,
            "/api/requests",
            Some(&request),
        )
        .await?;
    Ok(decorated::<RequestDetail>(r))
}

#[tauri::command]
pub async fn ur_my_requests(
    emby: State<'_, EmbyService>,
    ur: State<'_, RequestService>,
    status: Option<Vec<RequestStatus>>,
    page: u32,
) -> Result<RequestPage, RequestError> {
    let query = requests_query(status.as_deref().unwrap_or_default(), page);
    let r = ur.get(&identity(&emby)?, "/api/requests", &query).await?;
    Ok(decorated::<RequestPage>(r))
}

#[tauri::command]
pub async fn ur_request_detail(
    emby: State<'_, EmbyService>,
    ur: State<'_, RequestService>,
    id: u64,
) -> Result<RequestDetail, RequestError> {
    let path = format!("/api/requests/{id}");
    let r = ur.get(&identity(&emby)?, &path, &[]).await?;
    Ok(decorated::<RequestDetail>(r))
}

#[tauri::command]
pub async fn ur_cancel(
    emby: State<'_, EmbyService>,
    ur: State<'_, RequestService>,
    id: u64,
) -> Result<RequestDetail, RequestError> {
    let path = format!("/api/requests/{id}/cancel");
    let r = ur
        .send::<(), _>(&identity(&emby)?, Method::POST, &path, None)
        .await?;
    Ok(decorated::<RequestDetail>(r))
}

#[tauri::command]
pub async fn ur_sessions(
    emby: State<'_, EmbyService>,
    ur: State<'_, RequestService>,
) -> Result<Vec<AuthSession>, RequestError> {
    let list: SessionList = ur.get(&identity(&emby)?, "/api/auth/sessions", &[]).await?;
    Ok(list.sessions)
}

#[tauri::command]
pub async fn ur_revoke_session(
    emby: State<'_, EmbyService>,
    ur: State<'_, RequestService>,
    id: String,
) -> Result<(), RequestError> {
    if !is_session_id(&id) {
        return Err(RequestError::NotFound("appareil introuvable".into()));
    }
    let path = format!("/api/auth/sessions/{id}");
    ur.send_empty(&identity(&emby)?, Method::DELETE, &path)
        .await
}
