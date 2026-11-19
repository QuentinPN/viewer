//! Request integration (ADR 0125): the « Demander » tab. Every call is made
//! here, in Rust; the UI never sees a token nor builds an API or image URL.

pub mod client;
pub mod commands;
pub mod images;
pub mod models;
pub mod service;
#[cfg(test)]
mod tests;

use std::net::IpAddr;

use serde::Serialize;
use url::{Host, Url};

use models::ApiVersion;

/// Features the client relies on; a server missing one is "trop ancien".
pub const REQUIRED_FEATURES: [&str; 4] = [
    "auth.emby-token",
    "auth.bearer",
    "auth.sessions",
    "media.item-id",
];
/// Credential account of the Request session of one Emby account:
/// `request/{embyServerId}/{embyUserId}`.
pub fn account(server_id: &str, user_id: &str) -> String {
    format!(
        "request/{}",
        crate::emby::credentials::account(server_id, user_id)
    )
}

/// API major version this client speaks.
const API_MAJOR: &str = "1";

/// Failures surfaced to the UI as `{ kind, message }`, like `EmbyError`. Messages
/// are French; for an HTTP error, the server's own `error` text is kept as is.
#[derive(Debug, Clone, PartialEq, Eq, thiserror::Error)]
pub enum RequestError {
    #[error("aucune adresse Request n'est enregistrée")]
    NotConfigured,
    #[error(
        "adresse Request invalide : utilise https:// (http:// seulement sur le réseau local)"
    )]
    InvalidAddress,
    #[error("Request est injoignable")]
    Unreachable,
    #[error("Request est trop ancien (API {api_version}) pour cette version d'Viewer")]
    TooOld {
        api_version: String,
        missing: Vec<String>,
    },
    /// The server refused the Emby identity or the session token.
    #[error("{0}")]
    Rejected(String),
    #[error("trop de tentatives de connexion : réessaie dans {} min", minutes(*.retry_after_s))]
    RateLimited { retry_after_s: u64 },
    #[error("{0}")]
    Forbidden(String),
    #[error("{0}")]
    NotFound(String),
    #[error("{message}")]
    Server { status: u16, message: String },
    #[error("réponse inattendue d'Request")]
    InvalidResponse,
    #[error("aucune session Emby ouverte")]
    EmbySignedOut,
    /// "Se déconnecter" was chosen: nothing reconnects until "Se connecter".
    #[error("déconnecté d'Request")]
    SignedOut,
    #[error("coffre d'identification Windows indisponible : {0}")]
    CredentialStore(String),
    #[error("réglage non enregistré : {0}")]
    Settings(String),
}

fn minutes(seconds: u64) -> u64 {
    seconds.div_ceil(60).max(1)
}

impl RequestError {
    pub fn kind(&self) -> &'static str {
        match self {
            Self::NotConfigured => "notConfigured",
            Self::InvalidAddress => "invalidAddress",
            Self::Unreachable => "unreachable",
            Self::TooOld { .. } => "tooOld",
            Self::Rejected(_) => "rejected",
            Self::RateLimited { .. } => "rateLimited",
            Self::Forbidden(_) => "forbidden",
            Self::NotFound(_) => "notFound",
            Self::Server { .. } => "server",
            Self::InvalidResponse => "invalidResponse",
            Self::EmbySignedOut => "embySignedOut",
            Self::SignedOut => "signedOut",
            Self::CredentialStore(_) => "credentialStore",
            Self::Settings(_) => "settings",
        }
    }
}

impl Serialize for RequestError {
    fn serialize<S: serde::Serializer>(&self, s: S) -> Result<S::Ok, S::Error> {
        use serde::ser::SerializeStruct;
        let mut st = s.serialize_struct("RequestError", 2)?;
        st.serialize_field("kind", self.kind())?;
        st.serialize_field("message", &self.to_string())?;
        st.end()
    }
}

impl From<reqwest::Error> for RequestError {
    fn from(e: reqwest::Error) -> Self {
        // Never keep reqwest's text: it may carry the URL.
        if e.is_decode() || e.is_body() {
            Self::InvalidResponse
        } else {
            Self::Unreachable
        }
    }
}

impl From<crate::emby::EmbyError> for RequestError {
    fn from(e: crate::emby::EmbyError) -> Self {
        match e {
            crate::emby::EmbyError::CredentialStore(m) => Self::CredentialStore(m),
            crate::emby::EmbyError::NotSignedIn => Self::EmbySignedOut,
            other => Self::CredentialStore(other.to_string()),
        }
    }
}

/// Cleans a typed address: spaces and trailing `/` removed, `https://` when no
/// scheme is given. `http://` only for loopback and private networks, so the
/// Emby token never crosses the Internet in clear text.
pub fn normalize_address(input: &str) -> Result<Url, RequestError> {
    let trimmed = input.trim().trim_end_matches('/');
    if trimmed.is_empty() || trimmed.chars().any(char::is_whitespace) {
        return Err(RequestError::InvalidAddress);
    }
    let with_scheme = if trimmed.contains("://") {
        trimmed.to_owned()
    } else {
        format!("https://{trimmed}")
    };
    let url = Url::parse(&with_scheme).map_err(|_| RequestError::InvalidAddress)?;
    if !url.username().is_empty()
        || url.password().is_some()
        || url.query().is_some()
        || url.fragment().is_some()
    {
        return Err(RequestError::InvalidAddress);
    }
    let host = url.host().ok_or(RequestError::InvalidAddress)?;
    match url.scheme() {
        "https" => Ok(url),
        "http" if is_local(&host) => Ok(url),
        _ => Err(RequestError::InvalidAddress),
    }
}

/// The address as stored and shown: no trailing `/`.
pub fn address_text(url: &Url) -> String {
    url.as_str().trim_end_matches('/').to_owned()
}

/// True for an `http://` address (shown with the « non chiffré » warning).
pub fn is_unencrypted(url: &Url) -> bool {
    url.scheme() == "http"
}

fn is_local(host: &Host<&str>) -> bool {
    match host {
        Host::Ipv4(ip) => ip.is_loopback() || ip.is_private(),
        Host::Ipv6(ip) => IpAddr::V6(*ip).is_loopback(),
        Host::Domain(d) => {
            let d = d.to_ascii_lowercase();
            d == "localhost" || d.ends_with(".local")
        }
    }
}

/// API major `1` with every feature of [`REQUIRED_FEATURES`].
pub fn ensure_compatible(v: &ApiVersion) -> Result<(), RequestError> {
    let missing: Vec<String> = REQUIRED_FEATURES
        .iter()
        .filter(|f| !v.features.iter().any(|have| have == *f))
        .map(|f| (*f).to_owned())
        .collect();
    let major = v.api_version.split('.').next().unwrap_or_default();
    if major != API_MAJOR || !missing.is_empty() {
        return Err(RequestError::TooOld {
            api_version: v.api_version.clone(),
            missing,
        });
    }
    Ok(())
}
