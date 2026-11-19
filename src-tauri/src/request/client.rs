//! Typed Request HTTP client. `POST /api/auth/emby-token` goes without any
//! `Authorization` header; every other authenticated call carries the session
//! token as `Authorization: Bearer`, never in a URL.

use std::time::Duration;

use reqwest::{Method, RequestBuilder, Response, StatusCode, header};
use serde::de::DeserializeOwned;
use serde::{Deserialize, Serialize};
use url::Url;

use super::RequestError;
use super::models::{ApiVersion, EmbyTokenSession};
use crate::emby::client::{AccessToken, CLIENT_NAME};

const CONNECT_TIMEOUT: Duration = Duration::from_secs(5);
const REQUEST_TIMEOUT: Duration = Duration::from_secs(15);
/// Longest device name the server accepts.
pub const DEVICE_NAME_MAX: usize = 64;
/// Larger image answers are refused (same bound as Emby artwork).
pub const MAX_IMAGE_BYTES: usize = crate::emby::images::MAX_BYTES;
/// Image types served to the WebView; SVG is never passed through.
const IMAGE_TYPES: [&str; 3] = ["image/jpeg", "image/png", "image/webp"];
/// Wait advised when a 429 has no usable `Retry-After`.
const DEFAULT_RETRY_AFTER_S: u64 = 60;
/// A server's `Retry-After` is bounded: it sets a deadline in memory.
const MAX_RETRY_AFTER_S: u64 = 24 * 60 * 60;

/// Request session token. Debug output is redacted so it cannot leak in logs.
#[derive(Clone, PartialEq, Eq, Deserialize)]
#[serde(transparent)]
pub struct SessionToken(String);

impl SessionToken {
    pub fn new(value: String) -> Self {
        Self(value)
    }

    pub fn expose(&self) -> &str {
        &self.0
    }
}

impl std::fmt::Debug for SessionToken {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str("SessionToken(***)")
    }
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct ExchangeBody<'a> {
    user_id: &'a str,
    access_token: &'a str,
    device_name: String,
}

#[derive(Deserialize)]
struct ApiError {
    error: String,
}

#[derive(Debug, Clone)]
pub struct RequestClient {
    http: reqwest::Client,
    /// Address without trailing `/`; API paths start with `/api/`.
    base: String,
}

impl RequestClient {
    pub fn new(base: &Url, version: &str) -> Result<Self, RequestError> {
        let http = reqwest::Client::builder()
            .connect_timeout(CONNECT_TIMEOUT)
            .timeout(REQUEST_TIMEOUT)
            // A redirect could carry the Bearer or the Emby token to another host.
            .redirect(reqwest::redirect::Policy::none())
            .user_agent(format!("{CLIENT_NAME}/{version}"))
            .build()
            .map_err(|_| RequestError::Unreachable)?;
        Ok(Self {
            http,
            base: super::address_text(base),
        })
    }

    fn request(
        &self,
        method: Method,
        path: &str,
        query: &[(&str, String)],
        token: Option<&SessionToken>,
    ) -> RequestBuilder {
        let url = format!("{}{path}", self.base);
        // `parse_with_params` appends `?` even with no parameter.
        let url = match Url::parse_with_params(&url, query) {
            Ok(u) if !query.is_empty() => u.to_string(),
            _ => url,
        };
        let builder = self
            .http
            .request(method, url)
            .header(header::ACCEPT, "application/json");
        match token {
            Some(t) => builder.bearer_auth(t.expose()),
            None => builder,
        }
    }

    pub async fn version(&self) -> Result<ApiVersion, RequestError> {
        let res = self
            .request(Method::GET, "/api/version", &[], None)
            .send()
            .await?;
        json(check(res).await?).await
    }

    /// Trades the Emby identity for a session token. Sent without `Authorization`.
    pub async fn exchange(
        &self,
        user_id: &str,
        emby_token: &AccessToken,
        device_name: &str,
    ) -> Result<EmbyTokenSession, RequestError> {
        let body = ExchangeBody {
            user_id,
            access_token: emby_token.expose(),
            device_name: device_name
                .chars()
                .filter(|c| !c.is_control())
                .take(DEVICE_NAME_MAX)
                .collect(),
        };
        let res = self
            .request(Method::POST, "/api/auth/emby-token", &[], None)
            .json(&body)
            .send()
            .await?;
        json(check(res).await?).await
    }

    pub async fn get<T: DeserializeOwned>(
        &self,
        path: &str,
        query: &[(&str, String)],
        token: &SessionToken,
    ) -> Result<T, RequestError> {
        let res = self
            .request(Method::GET, path, query, Some(token))
            .send()
            .await?;
        json(check(res).await?).await
    }

    pub async fn send<B: Serialize, T: DeserializeOwned>(
        &self,
        method: Method,
        path: &str,
        body: Option<&B>,
        token: &SessionToken,
    ) -> Result<T, RequestError> {
        let mut req = self.request(method, path, &[], Some(token));
        if let Some(b) = body {
            req = req.json(b);
        }
        json(check(req.send().await?).await?).await
    }

    /// A call answered by `204 No Content`.
    pub async fn send_empty(
        &self,
        method: Method,
        path: &str,
        token: &SessionToken,
    ) -> Result<(), RequestError> {
        let res = self.request(method, path, &[], Some(token)).send().await?;
        check(res).await.map(drop)
    }

    /// TMDB image through the server (`/api/images/{size}/{file}`): bytes and
    /// content type, only JPEG, PNG or WebP, at most `MAX_IMAGE_BYTES`.
    pub async fn image(
        &self,
        size: &str,
        file: &str,
        token: &SessionToken,
    ) -> Result<(Vec<u8>, String), RequestError> {
        let path = format!("/api/images/{size}/{file}");
        let res = check(
            self.request(Method::GET, &path, &[], Some(token))
                .header(header::ACCEPT, "image/*")
                .send()
                .await?,
        )
        .await?;
        let content_type = res
            .headers()
            .get(header::CONTENT_TYPE)
            .and_then(|v| v.to_str().ok())
            .map(|v| v.split(';').next().unwrap_or_default().trim().to_owned())
            .filter(|t| IMAGE_TYPES.contains(&t.as_str()))
            .ok_or(RequestError::InvalidResponse)?;
        if res
            .content_length()
            .is_some_and(|n| n > MAX_IMAGE_BYTES as u64)
        {
            return Err(RequestError::InvalidResponse);
        }
        let bytes = res.bytes().await?;
        if bytes.len() > MAX_IMAGE_BYTES {
            return Err(RequestError::InvalidResponse);
        }
        Ok((bytes.to_vec(), content_type))
    }
}

async fn json<T: DeserializeOwned>(res: Response) -> Result<T, RequestError> {
    let bytes = res.bytes().await?;
    serde_json::from_slice(&bytes).map_err(|_| RequestError::InvalidResponse)
}

/// Success passes through; an error status becomes a typed error carrying the
/// server's `error` text.
async fn check(res: Response) -> Result<Response, RequestError> {
    let status = res.status();
    if status.is_success() {
        return Ok(res);
    }
    let retry_after = res
        .headers()
        .get(header::RETRY_AFTER)
        .and_then(|v| v.to_str().ok())
        .and_then(|v| v.trim().parse::<u64>().ok())
        .map(|s| s.min(MAX_RETRY_AFTER_S));
    let message = res
        .bytes()
        .await
        .ok()
        .and_then(|b| serde_json::from_slice::<ApiError>(&b).ok())
        .map(|e| e.error)
        .filter(|e| !e.trim().is_empty());
    let or = |default: &str| message.clone().unwrap_or_else(|| default.to_owned());
    Err(match status {
        StatusCode::UNAUTHORIZED => {
            RequestError::Rejected(or("connexion refusée par Request"))
        }
        StatusCode::FORBIDDEN => RequestError::Forbidden(or("accès refusé par Request")),
        StatusCode::NOT_FOUND => RequestError::NotFound(or("introuvable sur Request")),
        StatusCode::TOO_MANY_REQUESTS => RequestError::RateLimited {
            retry_after_s: retry_after.unwrap_or(DEFAULT_RETRY_AFTER_S),
        },
        _ => RequestError::Server {
            status: status.as_u16(),
            message: or(&format!("erreur d'Request (HTTP {})", status.as_u16())),
        },
    })
}
