//! Typed Emby HTTP client. Authentication travels only in the
//! `X-Emby-Authorization` header, never in URLs.

use std::time::Duration;

use reqwest::{Method, RequestBuilder, Response, StatusCode};
use serde::de::DeserializeOwned;

use super::EmbyError;
use super::images::{self, ImageRequest};
use super::models::{
    AuthenticateUserByName, AuthenticationResult, BaseItemDto, DeviceProfile, PlaybackInfoRequest,
    PlaybackInfoResponse, PlaybackReportBody, PublicSystemInfo, QueryResult, UserDto,
};
use super::server_url::ServerUrl;

pub const CLIENT_NAME: &str = "Viewer";
const CONNECT_TIMEOUT: Duration = Duration::from_secs(5);
const REQUEST_TIMEOUT: Duration = Duration::from_secs(15);

/// How this installation identifies itself to the server (one device per install).
#[derive(Debug, Clone)]
pub struct DeviceIdentity {
    pub name: String,
    pub id: String,
    pub version: String,
}

/// Emby access token. Debug output is redacted so it cannot leak through logs.
#[derive(Clone, PartialEq, Eq)]
pub struct AccessToken(String);

impl AccessToken {
    pub fn new(value: String) -> Self {
        Self(value)
    }

    pub fn expose(&self) -> &str {
        &self.0
    }
}

impl std::fmt::Debug for AccessToken {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str("AccessToken(<redacted>)")
    }
}

#[derive(Debug, Clone)]
pub struct EmbyClient {
    http: reqwest::Client,
    server: ServerUrl,
    device: DeviceIdentity,
    token: Option<AccessToken>,
}

impl EmbyClient {
    pub fn new(server: ServerUrl, device: DeviceIdentity) -> Result<Self, EmbyError> {
        let http = reqwest::Client::builder()
            .connect_timeout(CONNECT_TIMEOUT)
            .timeout(REQUEST_TIMEOUT)
            // An Emby server never needs redirects for API calls; following them
            // could forward the auth header to another host.
            .redirect(reqwest::redirect::Policy::none())
            .user_agent(format!("{CLIENT_NAME}/{}", device.version))
            .build()
            .map_err(|e| EmbyError::Unreachable(e.to_string()))?;
        Ok(Self {
            http,
            server,
            device,
            token: None,
        })
    }

    pub fn with_token(mut self, token: AccessToken) -> Self {
        self.token = Some(token);
        self
    }

    pub fn server(&self) -> &ServerUrl {
        &self.server
    }

    /// `X-Emby-Authorization` value. Quotes and commas are stripped from values so a
    /// device name cannot break the header syntax.
    pub fn authorization_header(&self) -> String {
        let clean = |v: &str| v.replace(['"', ','], "");
        let mut h = format!(
            "Emby Client=\"{CLIENT_NAME}\", Device=\"{}\", DeviceId=\"{}\", Version=\"{}\"",
            clean(&self.device.name),
            clean(&self.device.id),
            clean(&self.device.version),
        );
        if let Some(t) = &self.token {
            h.push_str(&format!(", Token=\"{}\"", clean(t.expose())));
        }
        h
    }

    fn request(&self, method: Method, path: &str) -> RequestBuilder {
        self.request_url(method, &self.server.api(path), &[])
    }

    /// Query values are encoded by `url`; they never carry secrets.
    fn request_url(&self, method: Method, url: &str, query: &[(&str, &str)]) -> RequestBuilder {
        // `parse_with_params` appends `?` even with no parameter.
        let url = match url::Url::parse_with_params(url, query) {
            Ok(u) if !query.is_empty() => u.to_string(),
            _ => url.to_owned(),
        };
        self.http
            .request(method, url)
            .header("X-Emby-Authorization", self.authorization_header())
            .header("Accept", "application/json")
    }

    pub async fn public_info(&self) -> Result<PublicSystemInfo, EmbyError> {
        let res = self
            .request(Method::GET, "/System/Info/Public")
            .send()
            .await?;
        json(check(res, false)?).await
    }

    /// Signs in by user name. The password is only sent in the request body.
    pub async fn authenticate(
        &self,
        username: &str,
        password: &str,
    ) -> Result<AuthenticationResult, EmbyError> {
        let res = self
            .request(Method::POST, "/Users/AuthenticateByName")
            .json(&AuthenticateUserByName {
                username,
                pw: password,
            })
            .send()
            .await?;
        let result: AuthenticationResult = json(check(res, false)?).await?;
        if result.access_token.is_empty() {
            return Err(EmbyError::InvalidResponse("jeton absent".into()));
        }
        Ok(result)
    }

    /// Current user; also validates that the stored token is still accepted.
    pub async fn user(&self, user_id: &str) -> Result<UserDto, EmbyError> {
        self.require_token()?;
        let res = self
            .request(Method::GET, &format!("/Users/{}", path_segment(user_id)?))
            .send()
            .await?;
        json(check(res, true)?).await
    }

    /// Revokes the token server-side (`204` on success).
    pub async fn logout(&self) -> Result<(), EmbyError> {
        self.require_token()?;
        let res = self
            .request(Method::POST, "/Sessions/Logout")
            .send()
            .await?;
        check(res, true).map(|_| ())
    }

    /// Libraries visible to the user (`/Users/{Id}/Views`).
    pub async fn views(&self, user_id: &str) -> Result<QueryResult<BaseItemDto>, EmbyError> {
        self.require_token()?;
        let path = format!("/Users/{}/Views", path_segment(user_id)?);
        let res = self.request(Method::GET, &path).send().await?;
        json(check(res, true)?).await
    }

    /// One page of a library, sorted by name. The server ignores an unknown
    /// `ParentId` and returns the whole server: callers pass known library ids only.
    pub async fn items(
        &self,
        user_id: &str,
        query: &ItemsQuery<'_>,
    ) -> Result<QueryResult<BaseItemDto>, EmbyError> {
        self.require_token()?;
        let url = self
            .server
            .api(&format!("/Users/{}/Items", path_segment(user_id)?));
        let (start, limit) = (query.start_index.to_string(), query.limit.to_string());
        let params = [
            ("ParentId", path_segment(query.parent_id)?),
            ("Recursive", "true"),
            ("IncludeItemTypes", query.item_types),
            ("SortBy", "SortName"),
            ("SortOrder", "Ascending"),
            ("StartIndex", &start),
            ("Limit", &limit),
            ("Fields", "PrimaryImageAspectRatio,ProductionYear"),
            ("EnableImageTypes", "Primary"),
            ("ImageTypeLimit", "1"),
            ("EnableUserData", "true"),
        ];
        let res = self.request_url(Method::GET, &url, &params).send().await?;
        json(check(res, true)?).await
    }

    /// Films and episodes started but not finished, most recent first
    /// (`/Users/{Id}/Items/Resume`).
    pub async fn resume_items(
        &self,
        user_id: &str,
        limit: u32,
    ) -> Result<QueryResult<BaseItemDto>, EmbyError> {
        self.require_token()?;
        let url = self
            .server
            .api(&format!("/Users/{}/Items/Resume", path_segment(user_id)?));
        let limit = limit.to_string();
        let params = [
            ("Limit", limit.as_str()),
            ("MediaTypes", "Video"),
            ("IncludeItemTypes", "Movie,Episode"),
            ("Fields", WATCH_FIELDS),
            ("EnableUserData", "true"),
            ("ImageTypeLimit", "1"),
            ("EnableImageTypes", WATCH_IMAGES),
        ];
        let res = self.request_url(Method::GET, &url, &params).send().await?;
        json(check(res, true)?).await
    }

    /// Next episode to watch of each series in progress (`/Shows/NextUp`).
    pub async fn next_up(
        &self,
        user_id: &str,
        limit: u32,
    ) -> Result<QueryResult<BaseItemDto>, EmbyError> {
        self.require_token()?;
        let url = self.server.api("/Shows/NextUp");
        let limit = limit.to_string();
        let params = [
            ("UserId", path_segment(user_id)?),
            ("Limit", limit.as_str()),
            ("Fields", WATCH_FIELDS),
            ("EnableUserData", "true"),
            ("ImageTypeLimit", "1"),
            ("EnableImageTypes", WATCH_IMAGES),
        ];
        let res = self.request_url(Method::GET, &url, &params).send().await?;
        json(check(res, true)?).await
    }

    /// Latest additions of a library, newest first (`/Users/{Id}/Items/Latest`).
    /// Episodes come grouped as their series; the answer is a bare array. Emby
    /// 4.10.1.0 answers 500 to an unknown `ParentId`: callers pass known ids only.
    pub async fn latest(
        &self,
        user_id: &str,
        parent_id: &str,
        limit: u32,
    ) -> Result<Vec<BaseItemDto>, EmbyError> {
        self.require_token()?;
        let url = self
            .server
            .api(&format!("/Users/{}/Items/Latest", path_segment(user_id)?));
        let limit = limit.to_string();
        let params = [
            ("ParentId", path_segment(parent_id)?),
            ("Limit", limit.as_str()),
            ("Fields", "PrimaryImageAspectRatio,ProductionYear"),
            ("EnableUserData", "true"),
            ("ImageTypeLimit", "1"),
            ("EnableImageTypes", "Primary"),
        ];
        let res = self.request_url(Method::GET, &url, &params).send().await?;
        json(check(res, true)?).await
    }

    /// Full item as the user sees it (`/Users/{Id}/Items/{Id}`): overview, people,
    /// media sources and user data come without `Fields`. Unknown id: 404.
    pub async fn item(&self, user_id: &str, item_id: &str) -> Result<BaseItemDto, EmbyError> {
        self.require_token()?;
        let path = format!(
            "/Users/{}/Items/{}",
            path_segment(user_id)?,
            path_segment(item_id)?
        );
        let res = self.request(Method::GET, &path).send().await?;
        json(check(res, true)?).await
    }

    /// Seasons of a series, in order (`0` = specials). Unknown series: 404.
    pub async fn seasons(
        &self,
        user_id: &str,
        series_id: &str,
    ) -> Result<QueryResult<BaseItemDto>, EmbyError> {
        self.require_token()?;
        let url = self
            .server
            .api(&format!("/Shows/{}/Seasons", path_segment(series_id)?));
        let params = [
            ("UserId", path_segment(user_id)?),
            ("EnableUserData", "true"),
            ("EnableImageTypes", "Primary"),
            ("ImageTypeLimit", "1"),
        ];
        let res = self.request_url(Method::GET, &url, &params).send().await?;
        json(check(res, true)?).await
    }

    /// One page of episodes of a season. Emby 4.10.1.0 trusts `SeasonId` over the
    /// series id: a season of another series returns that series' episodes, so
    /// callers must check `SeriesId` on the answer.
    pub async fn episodes(
        &self,
        user_id: &str,
        query: &EpisodesQuery<'_>,
    ) -> Result<QueryResult<BaseItemDto>, EmbyError> {
        self.require_token()?;
        let url = self.server.api(&format!(
            "/Shows/{}/Episodes",
            path_segment(query.series_id)?
        ));
        let (start, limit) = (query.start_index.to_string(), query.limit.to_string());
        let params = [
            ("UserId", path_segment(user_id)?),
            ("SeasonId", path_segment(query.season_id)?),
            ("StartIndex", &start),
            ("Limit", &limit),
            // Without it episodes come without their overview.
            ("Fields", "Overview"),
            ("EnableUserData", "true"),
            ("EnableImageTypes", "Primary"),
            ("ImageTypeLimit", "1"),
        ];
        let res = self.request_url(Method::GET, &url, &params).send().await?;
        json(check(res, true)?).await
    }

    /// Playback negotiation (ADR 0007): the server answers with its sources and how
    /// each can be played for this profile. Unknown id: 404.
    pub async fn playback_info(
        &self,
        user_id: &str,
        item_id: &str,
        profile: &DeviceProfile,
    ) -> Result<PlaybackInfoResponse, EmbyError> {
        self.require_token()?;
        let url = self
            .server
            .api(&format!("/Items/{}/PlaybackInfo", path_segment(item_id)?));
        let user_id = path_segment(user_id)?;
        let body = PlaybackInfoRequest {
            user_id,
            max_streaming_bitrate: profile.max_streaming_bitrate,
            device_profile: profile,
            is_playback: true,
            auto_open_live_stream: false,
        };
        let res = self
            .request_url(Method::POST, &url, &[("UserId", user_id)])
            .json(&body)
            .send()
            .await?;
        json(check(res, true)?).await
    }

    /// Playback report (ADR 0008) to `/Sessions/Playing`, `…/Progress` or
    /// `…/Stopped`. Emby 4.10.1.0 answers 204.
    pub async fn report_playback(
        &self,
        path: &str,
        body: &PlaybackReportBody,
    ) -> Result<(), EmbyError> {
        self.require_token()?;
        path_segment(&body.item_id)?;
        let res = self.request(Method::POST, path).json(body).send().await?;
        check(res, true).map(drop)
    }

    /// Authentication for the player's HTTP requests: a header, never the URL.
    pub fn stream_auth_headers(&self) -> Result<Vec<(String, String)>, EmbyError> {
        let token = self.token.as_ref().ok_or(EmbyError::NotSignedIn)?;
        Ok(vec![("X-Emby-Token".to_owned(), token.expose().to_owned())])
    }

    /// Artwork bytes and content type for the `emby-img` protocol (ADR 0005).
    pub async fn image(&self, req: &ImageRequest) -> Result<(String, Vec<u8>), EmbyError> {
        let url = self.server.api(&req.api_path());
        let width = req.max_width.to_string();
        let params = [
            ("tag", req.tag.as_str()),
            ("maxWidth", &width),
            ("quality", "90"),
        ];
        let res = check(
            self.request_url(Method::GET, &url, &params).send().await?,
            true,
        )?;
        let content_type = res
            .headers()
            .get(reqwest::header::CONTENT_TYPE)
            .and_then(|v| v.to_str().ok())
            .unwrap_or_default()
            .to_owned();
        if !content_type.starts_with("image/") {
            return Err(EmbyError::InvalidResponse("ce n'est pas une image".into()));
        }
        if res
            .content_length()
            .is_some_and(|n| n > images::MAX_BYTES as u64)
        {
            return Err(EmbyError::InvalidResponse("image trop lourde".into()));
        }
        let bytes = res.bytes().await?;
        if bytes.len() > images::MAX_BYTES {
            return Err(EmbyError::InvalidResponse("image trop lourde".into()));
        }
        Ok((content_type, bytes.to_vec()))
    }

    fn require_token(&self) -> Result<(), EmbyError> {
        self.token
            .as_ref()
            .map(|_| ())
            .ok_or(EmbyError::NotSignedIn)
    }
}

/// "Reprendre" and "À suivre" rows: series name, runtime and the parent artwork
/// come by default; the images feed the 16:9 card.
const WATCH_FIELDS: &str = "PrimaryImageAspectRatio";
const WATCH_IMAGES: &str = "Primary,Backdrop,Thumb";

/// Parameters of one library page.
#[derive(Debug, Clone)]
pub struct ItemsQuery<'a> {
    pub parent_id: &'a str,
    pub item_types: &'a str,
    pub start_index: u32,
    pub limit: u32,
}

/// Parameters of one page of episodes.
#[derive(Debug, Clone)]
pub struct EpisodesQuery<'a> {
    pub series_id: &'a str,
    pub season_id: &'a str,
    pub start_index: u32,
    pub limit: u32,
}

/// Maps HTTP status to error categories. `authenticated` distinguishes an expired
/// token (401 on a token call) from a wrong password (401 on sign-in).
fn check(res: Response, authenticated: bool) -> Result<Response, EmbyError> {
    let status = res.status();
    if status.is_success() {
        return Ok(res);
    }
    Err(match status {
        StatusCode::UNAUTHORIZED if authenticated => EmbyError::SessionExpired,
        StatusCode::UNAUTHORIZED => EmbyError::InvalidCredentials,
        StatusCode::FORBIDDEN => EmbyError::Forbidden,
        s if s.is_redirection() => {
            EmbyError::InvalidResponse(format!("redirection HTTP {} non suivie", s.as_u16()))
        }
        StatusCode::NOT_FOUND => EmbyError::NotFound,
        s => EmbyError::Server(s.as_u16()),
    })
}

async fn json<T: DeserializeOwned>(res: Response) -> Result<T, EmbyError> {
    let bytes = res.bytes().await?;
    serde_json::from_slice(&bytes).map_err(|e| EmbyError::InvalidResponse(e.to_string()))
}

/// Ids come from the server, but are still checked before going into a path.
fn path_segment(id: &str) -> Result<&str, EmbyError> {
    if !id.is_empty() && id.chars().all(|c| c.is_ascii_alphanumeric() || c == '-') {
        Ok(id)
    } else {
        Err(EmbyError::InvalidResponse("identifiant invalide".into()))
    }
}
