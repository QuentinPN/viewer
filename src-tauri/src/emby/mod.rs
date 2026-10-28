//! Emby integration (ADR 0004). The access token stays in Rust: the UI only sees
//! typed summaries, never the token or raw API URLs.

pub mod client;
pub mod credentials;
pub mod images;
pub mod models;
pub mod playback;
pub mod server_url;
pub mod session;
#[cfg(test)]
mod tests;

use serde::Serialize;

/// Failure categories surfaced to the UI. Messages are French and never contain
/// the token or the password.
#[derive(Debug, thiserror::Error)]
pub enum EmbyError {
    #[error("adresse du serveur invalide : {0}")]
    InvalidUrl(String),
    #[error(
        "connexion non chiffrée refusée : une adresse hors réseau local doit utiliser https://"
    )]
    InsecureRemoteUrl,
    #[error("serveur injoignable : {0}")]
    Unreachable(String),
    #[error("le serveur ne répond pas (délai dépassé)")]
    Timeout,
    #[error("nom d'utilisateur ou mot de passe incorrect")]
    InvalidCredentials,
    #[error("accès refusé par le serveur")]
    Forbidden,
    #[error("erreur du serveur (HTTP {0})")]
    Server(u16),
    #[error("réponse inattendue du serveur : {0}")]
    InvalidResponse(String),
    #[error("élément introuvable sur le serveur")]
    NotFound,
    #[error("aucune session ouverte")]
    NotSignedIn,
    #[error("session expirée ou révoquée : reconnectez-vous")]
    SessionExpired,
    #[error("coffre d'identification Windows indisponible : {0}")]
    CredentialStore(String),
    #[error("lecture refusée : {0}")]
    PlaybackRefused(String),
    #[error("aucune version de ce média n'est lisible par cette application")]
    NoCompatibleStream,
    #[error("ce média demande un transcodage par le serveur, pas encore pris en charge ({0})")]
    TranscodeRequired(String),
    /// The player could not start the stream chosen by the server.
    #[error("lecteur indisponible : {0}")]
    Player(String),
}

impl EmbyError {
    pub fn kind(&self) -> &'static str {
        match self {
            Self::InvalidUrl(_) => "invalidUrl",
            Self::InsecureRemoteUrl => "insecureRemoteUrl",
            Self::Unreachable(_) => "unreachable",
            Self::Timeout => "timeout",
            Self::InvalidCredentials => "invalidCredentials",
            Self::Forbidden => "forbidden",
            Self::Server(_) => "server",
            Self::InvalidResponse(_) => "invalidResponse",
            Self::NotFound => "notFound",
            Self::NotSignedIn => "notSignedIn",
            Self::SessionExpired => "sessionExpired",
            Self::CredentialStore(_) => "credentialStore",
            Self::PlaybackRefused(_) => "playbackRefused",
            Self::NoCompatibleStream => "noCompatibleStream",
            Self::TranscodeRequired(_) => "transcodeRequired",
            Self::Player(_) => "player",
        }
    }
}

impl Serialize for EmbyError {
    fn serialize<S: serde::Serializer>(&self, s: S) -> Result<S::Ok, S::Error> {
        use serde::ser::SerializeStruct;
        let mut st = s.serialize_struct("EmbyError", 2)?;
        st.serialize_field("kind", self.kind())?;
        st.serialize_field("message", &self.to_string())?;
        st.end()
    }
}

impl From<reqwest::Error> for EmbyError {
    fn from(e: reqwest::Error) -> Self {
        if e.is_timeout() {
            Self::Timeout
        } else if e.is_decode() {
            Self::InvalidResponse("JSON illisible".into())
        } else if e.is_connect() || e.is_request() {
            // reqwest errors may carry the URL; ours never contains secrets, but keep
            // the message short and free of source chains.
            Self::Unreachable(root_cause(&e))
        } else {
            Self::InvalidResponse(root_cause(&e))
        }
    }
}

fn root_cause(e: &(dyn std::error::Error + 'static)) -> String {
    let mut cur = e;
    while let Some(next) = cur.source() {
        cur = next;
    }
    cur.to_string()
}
