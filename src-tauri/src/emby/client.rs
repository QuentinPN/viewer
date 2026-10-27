//! Typed Emby HTTP client. Authentication travels only in the
//! `X-Emby-Authorization` header, never in URLs.

use std::time::Duration;

use reqwest::{Method, RequestBuilder, Response, StatusCode};
use serde::de::DeserializeOwned;

use super::EmbyError;
use super::models::{AuthenticateUserByName, AuthenticationResult, PublicSystemInfo, UserDto};
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
        self.http
            .request(method, self.server.api(path))
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

    fn require_token(&self) -> Result<(), EmbyError> {
        self.token
            .as_ref()
            .map(|_| ())
            .ok_or(EmbyError::NotSignedIn)
    }
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
