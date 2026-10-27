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
