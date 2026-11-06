//! Server address validation (ADR 0004): http(s) only, no credentials, query or
//! fragment, and plain HTTP only towards local network addresses.

use std::net::IpAddr;

use url::{Host, Url};

use super::EmbyError;

/// Normalized Emby server base URL, without the `/emby` API prefix.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ServerUrl {
    url: Url,
}

impl ServerUrl {
    pub fn parse(input: &str) -> Result<Self, EmbyError> {
        let trimmed = input.trim();
        if trimmed.is_empty() {
            return Err(EmbyError::InvalidUrl("adresse vide".into()));
        }
        // Users often type "192.168.1.10:8096" without a scheme. Default to http:
        // the local-network rule below still refuses plain HTTP to public hosts.
        let with_scheme = if trimmed.contains("://") {
            trimmed.to_owned()
        } else {
            format!("http://{trimmed}")
        };
        let mut url = Url::parse(&with_scheme).map_err(|e| EmbyError::InvalidUrl(e.to_string()))?;
        match url.scheme() {
            "http" | "https" => {}
            s => {
                return Err(EmbyError::InvalidUrl(format!(
                    "schéma « {s} » non pris en charge"
                )));
            }
        }
        if !url.username().is_empty() || url.password().is_some() {
            return Err(EmbyError::InvalidUrl(
                "l'adresse ne doit pas contenir d'identifiants".into(),
            ));
        }
        if url.query().is_some() || url.fragment().is_some() {
            return Err(EmbyError::InvalidUrl(
                "l'adresse ne doit contenir ni « ? » ni « # »".into(),
            ));
        }
        if url.host().is_none() {
            return Err(EmbyError::InvalidUrl("hôte manquant".into()));
        }
        // Keep a reverse-proxy prefix, drop a trailing slash and a typed "/emby" or
        // "/web/..." suffix copied from the browser address bar.
        let mut path = url.path().trim_end_matches('/').to_owned();
        if let Some(i) = path.find("/web") {
            if path[i..] == *"/web" || path[i..].starts_with("/web/") {
                path.truncate(i);
            }
        }
        if path.ends_with("/emby") {
            path.truncate(path.len() - "/emby".len());
        }
        url.set_path(if path.is_empty() { "/" } else { &path });

        let server = Self { url };
        if server.url.scheme() == "http" && !server.is_local() {
            return Err(EmbyError::InsecureRemoteUrl);
        }
        Ok(server)
    }

    pub fn is_https(&self) -> bool {
        self.url.scheme() == "https"
    }

    /// True for loopback, private, link-local and well-known local DNS suffixes.
    pub fn is_local(&self) -> bool {
        match self.url.host() {
            Some(Host::Ipv4(ip)) => is_local_ip(IpAddr::V4(ip)),
            Some(Host::Ipv6(ip)) => is_local_ip(IpAddr::V6(ip)),
            Some(Host::Domain(d)) => {
                let d = d.to_ascii_lowercase();
                d == "localhost"
                    || [".localhost", ".local", ".lan", ".home.arpa"]
                        .iter()
                        .any(|s| d.ends_with(s))
            }
            None => false,
        }
    }

    /// Absolute URL of an API path, e.g. `api("/Users/AuthenticateByName")`.
    pub fn api(&self, path: &str) -> String {
        format!("{}/emby{path}", self.as_str())
    }

    /// Base URL without trailing slash, as shown to the user and persisted.
    pub fn as_str(&self) -> &str {
        self.url.as_str().trim_end_matches('/')
    }
}

fn is_local_ip(ip: IpAddr) -> bool {
    match ip {
        IpAddr::V4(v4) => v4.is_loopback() || v4.is_private() || v4.is_link_local(),
        IpAddr::V6(v6) => {
            let first = v6.segments()[0];
            v6.is_loopback()
                || (first & 0xfe00) == 0xfc00 // unique local fc00::/7
                || (first & 0xffc0) == 0xfe80 // link-local fe80::/10
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn ok(input: &str) -> String {
        ServerUrl::parse(input).unwrap().as_str().to_owned()
    }

    #[test]
    fn normalizes_common_inputs() {
        assert_eq!(ok("http://192.168.1.10:8096"), "http://192.168.1.10:8096");
        assert_eq!(ok("192.168.1.10:8096"), "http://192.168.1.10:8096");
        assert_eq!(
            ok(" http://192.168.1.10:8096/ "),
            "http://192.168.1.10:8096"
        );
        assert_eq!(ok("http://emby.local:8096/emby"), "http://emby.local:8096");
        assert_eq!(
            ok("http://192.168.1.10:8096/web/index.html"),
            "http://192.168.1.10:8096"
        );
        assert_eq!(
            ok("https://media.example.com/proxy/"),
            "https://media.example.com/proxy"
        );
        assert_eq!(
            ServerUrl::parse("http://10.0.0.2:8096")
                .unwrap()
                .api("/System/Info/Public"),
            "http://10.0.0.2:8096/emby/System/Info/Public"
        );
    }

    #[test]
    fn plain_http_only_on_local_network() {
        for local in [
            "http://127.0.0.1:8096",
            "http://10.1.2.3",
            "http://172.16.0.1",
            "http://192.168.0.5",
            "http://169.254.1.1",
            "http://[::1]:8096",
            "http://[fd12::1]",
            "http://localhost:8096",
            "http://emby.home.arpa",
            "http://emby.lan",
        ] {
            assert!(ServerUrl::parse(local).is_ok(), "{local}");
        }
        for remote in [
            "http://8.8.8.8:8096",
            "http://media.example.com",
            "http://172.32.0.1",
        ] {
            assert!(
                matches!(ServerUrl::parse(remote), Err(EmbyError::InsecureRemoteUrl)),
                "{remote}"
            );
        }
        assert!(ServerUrl::parse("https://8.8.8.8").is_ok());
    }

    #[test]
    fn rejects_unsafe_or_malformed_urls() {
        for bad in [
            "",
            "ftp://192.168.1.10",
            "file:///C:/x",
            "http://user:pw@192.168.1.10",
            "http://192.168.1.10/?api_key=x",
            "http://192.168.1.10/#x",
            "http://",
        ] {
            assert!(
                matches!(ServerUrl::parse(bad), Err(EmbyError::InvalidUrl(_))),
                "{bad}"
            );
        }
    }
}
