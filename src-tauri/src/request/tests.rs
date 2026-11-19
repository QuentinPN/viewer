//! Request client tests: the shared fixtures (`contracts/request/fixtures`)
//! and a local fake server recording every request (ADR 0125).

use std::io::{BufRead, BufReader, Read, Write};
use std::net::TcpListener;
use std::sync::{Arc, Mutex};

use reqwest::Method;
use url::Url;

use super::client::{SessionToken, RequestClient};
use super::models::*;
use super::{RequestError, ensure_compatible, normalize_address};
use crate::emby::client::AccessToken;

macro_rules! fixture {
    ($name:literal) => {
        include_str!(concat!(
            "../../../contracts/request/fixtures/",
            $name,
            ".json"
        ))
    };
}

const EMBY_USER: &str = "4a1f0e2b9c8d4e7fa6b5c4d3e2f1a0b9";
const EMBY_TOKEN: &str = "emby-token-0123456789abcdef";

#[derive(Debug, Clone)]
struct Recorded {
    method: String,
    path: String,
    authorization: Option<String>,
    body: String,
}

struct Reply {
    status: u16,
    content_type: &'static str,
    headers: Vec<(&'static str, String)>,
    body: Vec<u8>,
}

impl Reply {
    fn json(status: u16, body: &str) -> Self {
        Self {
            status,
            content_type: "application/json",
            headers: Vec::new(),
            body: body.as_bytes().to_vec(),
        }
    }

    fn empty(status: u16) -> Self {
        Self::json(status, "")
    }

    fn header(mut self, name: &'static str, value: &str) -> Self {
        self.headers.push((name, value.to_owned()));
        self
    }
}

type Route = Box<dyn Fn(&Recorded) -> Reply + Send + Sync>;

/// One-request-per-connection HTTP server on 127.0.0.1 recording every request.
struct FakeServer {
    url: Url,
    requests: Arc<Mutex<Vec<Recorded>>>,
}

impl FakeServer {
    fn start(route: Route) -> Self {
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let url = Url::parse(&format!("http://{}", listener.local_addr().unwrap())).unwrap();
        let requests = Arc::new(Mutex::new(Vec::new()));
        let log = Arc::clone(&requests);
        std::thread::spawn(move || {
            for stream in listener.incoming() {
                let Ok(mut stream) = stream else { break };
                let mut reader = BufReader::new(stream.try_clone().unwrap());
                let mut line = String::new();
                if reader.read_line(&mut line).is_err() || line.is_empty() {
                    continue;
                }
                let mut parts = line.split_whitespace();
                let method = parts.next().unwrap_or_default().to_owned();
                let path = parts.next().unwrap_or_default().to_owned();
                let (mut authorization, mut len) = (None, 0usize);
                loop {
                    let mut h = String::new();
                    reader.read_line(&mut h).unwrap();
                    let h = h.trim_end();
                    if h.is_empty() {
                        break;
                    }
                    let (name, value) = h.split_once(':').unwrap();
                    match name.to_ascii_lowercase().as_str() {
                        "authorization" => authorization = Some(value.trim().to_owned()),
                        "content-length" => len = value.trim().parse().unwrap(),
                        _ => {}
                    }
                }
                let mut body = vec![0; len];
                reader.read_exact(&mut body).unwrap();
                let req = Recorded {
                    method,
                    path,
                    authorization,
                    body: String::from_utf8_lossy(&body).into_owned(),
                };
                let reply = route(&req);
                log.lock().unwrap().push(req);
                let mut head = format!(
                    "HTTP/1.1 {} X\r\nContent-Type: {}\r\nContent-Length: {}\r\nConnection: close\r\n",
                    reply.status,
                    reply.content_type,
                    reply.body.len()
                );
                for (name, value) in &reply.headers {
                    head.push_str(&format!("{name}: {value}\r\n"));
                }
                head.push_str("\r\n");
                let _ = stream.write_all(&[head.as_bytes(), &reply.body].concat());
            }
        });
        Self { url, requests }
    }

    fn requests(&self) -> Vec<Recorded> {
        self.requests.lock().unwrap().clone()
    }

    fn client(&self) -> RequestClient {
        RequestClient::new(&self.url, "9.9.9").unwrap()
    }
}

fn block_on<F: std::future::Future>(f: F) -> F::Output {
    tauri::async_runtime::block_on(f)
}

fn token(value: &str) -> SessionToken {
    SessionToken::new(value.to_owned())
}

#[test]
fn parses_every_fixture() {
    fn parse<T: serde::de::DeserializeOwned>(name: &str, text: &str) -> T {
        serde_json::from_str(text).unwrap_or_else(|e| panic!("{name}: {e}"))
    }
    let ok: ApiVersion = parse("version-ok", fixture!("version-ok"));
    assert_eq!(ok.api_version, "1.0.0");
    let _: ApiVersion = parse("version-old", fixture!("version-old"));
    let session: EmbyTokenSession = parse("emby-token", fixture!("emby-token"));
    assert_eq!(session.viewer.role, Role::Member);
    assert!(session.viewer.capabilities.can_request_movies);
    let sessions: SessionList = parse("sessions", fixture!("sessions"));
    assert_eq!(sessions.sessions.len(), 3);
    assert!(sessions.sessions[0].current);
    let discover: DiscoverResponse = parse("discover", fixture!("discover"));
    assert!(!discover.rows.is_empty());
    let _: PagedMedia = parse("paged-media", fixture!("paged-media"));
    let _: MediaSearchResults = parse("search", fixture!("search"));
    let movie: MediaDetails = parse("media-movie", fixture!("media-movie"));
    assert_eq!(movie.media_type, MediaType::Movie);
    assert_eq!(movie.emby_item_id, None);
    assert_eq!(movie.status, None);
    let tv: MediaDetails = parse("media-tv", fixture!("media-tv"));
    assert_eq!(tv.status, Some(MediaStatus::Partial));
    assert_eq!(tv.seasons.len(), 6);
    assert_eq!(tv.seasons[1].status, Some(MediaStatus::Available));
    assert_eq!(tv.seasons[5].air_date, None);
    let _: RequestQuota = parse("quota", fixture!("quota"));
    let _: RequestView = parse("request-view", fixture!("request-view"));
    let page: RequestPage = parse("request-page", fixture!("request-page"));
    assert!(!page.results.is_empty());
    let detail: RequestDetail = parse("request-detail", fixture!("request-detail"));
    assert_eq!(detail.request.status, RequestStatus::Approved);
    assert_eq!(detail.events.len(), 3);
    let err: serde_json::Value = parse("error", fixture!("error"));
    assert_eq!(err["error"], "Quota de films atteint");
}

#[test]
fn normalizes_address() {
    let text = |s: &str| normalize_address(s).map(|u| super::address_text(&u));
    assert_eq!(
        text(" request.exemple.fr/ ").as_deref(),
        Ok("https://request.exemple.fr")
    );
    assert_eq!(
        text("https://request.exemple.fr:8445/").as_deref(),
        Ok("https://request.exemple.fr:8445")
    );
    assert_eq!(
        text("request.exemple.fr:8445").as_deref(),
        Ok("https://request.exemple.fr:8445")
    );
    for local in [
        "http://127.0.0.1:3000",
        "http://10.0.0.5",
        "http://172.20.1.1",
        "http://192.168.1.14:8445",
        "http://nas.local",
        "http://localhost:5173",
    ] {
        assert_eq!(text(local).as_deref(), Ok(local), "{local}");
    }
    for bad in [
        "ftp://x",
        "http://request.exemple.fr",
        "http://8.8.8.8",
        "http://172.32.0.1",
        "https://user:pw@request.exemple.fr",
        "https://request.exemple.fr/?a=b",
        "",
        "   ",
        "request exemple.fr",
    ] {
        assert_eq!(text(bad), Err(RequestError::InvalidAddress), "{bad}");
    }
}

#[test]
fn rejects_old_server() {
    let old: ApiVersion = serde_json::from_str(fixture!("version-old")).unwrap();
    match ensure_compatible(&old) {
        Err(RequestError::TooOld {
            api_version,
            missing,
        }) => {
            assert_eq!(api_version, "1.0.0");
            // `version-old` only has `api.version`: all four required features miss.
            assert_eq!(
                missing,
                [
                    "auth.emby-token",
                    "auth.bearer",
                    "auth.sessions",
                    "media.item-id"
                ]
            );
        }
        other => panic!("{other:?}"),
    }
    let ok: ApiVersion = serde_json::from_str(fixture!("version-ok")).unwrap();
    assert_eq!(ensure_compatible(&ok), Ok(()));
    let next = ApiVersion {
        api_version: "2.0.0".into(),
        ..ok
    };
    assert!(matches!(
        ensure_compatible(&next),
        Err(RequestError::TooOld { missing, .. }) if missing.is_empty()
    ));
}

#[test]
fn exchange_sends_no_authorization() {
    let server = FakeServer::start(Box::new(|_| Reply::json(200, fixture!("emby-token"))));
    let long_name = format!("{} (Windows)", "P".repeat(80));
    let session = block_on(server.client().exchange(
        EMBY_USER,
        &AccessToken::new(EMBY_TOKEN.into()),
        &long_name,
    ))
    .unwrap();
    assert_eq!(session.viewer.name, "Camille");
    let reqs = server.requests();
    assert_eq!(reqs.len(), 1);
    assert_eq!(reqs[0].method, "POST");
    assert_eq!(reqs[0].path, "/api/auth/emby-token");
    assert_eq!(reqs[0].authorization, None);
    let body: serde_json::Value = serde_json::from_str(&reqs[0].body).unwrap();
    assert_eq!(body["userId"], EMBY_USER);
    assert_eq!(body["accessToken"], EMBY_TOKEN);
    assert_eq!(body["deviceName"], "P".repeat(64));
    assert_eq!(body.as_object().unwrap().len(), 3);
}

#[test]
fn bearer_on_other_calls() {
    let server = FakeServer::start(Box::new(|r| match r.path.split('?').next().unwrap() {
        "/api/version" => Reply::json(200, fixture!("version-ok")),
        "/api/discover" => Reply::json(200, fixture!("discover")),
        "/api/requests" if r.method == "POST" => Reply::json(201, fixture!("request-detail")),
        "/api/requests" => Reply::json(200, fixture!("request-page")),
        "/api/auth/logout" => Reply::empty(204),
        "/api/images/w342/abc.jpg" => Reply {
            status: 200,
            content_type: "image/jpeg",
            headers: Vec::new(),
            body: vec![0xFF, 0xD8, 0xFF, 1, 2],
        },
        _ => Reply::empty(404),
    }));
    let client = server.client();
    let t = token("ur-session-token");
    let version = block_on(client.version()).unwrap();
    assert_eq!(version.api_version, "1.0.0");
    let _: DiscoverResponse = block_on(client.get("/api/discover", &[], &t)).unwrap();
    let _: RequestPage = block_on(client.get(
        "/api/requests",
        &[("status", "approved,sent".into()), ("mine", "true".into())],
        &t,
    ))
    .unwrap();
    let new = NewRequest {
        media_type: MediaType::Tv,
        tmdb_id: 94605,
        seasons: Some(SeasonsChoice::List(vec![3])),
    };
    let _: RequestDetail =
        block_on(client.send(Method::POST, "/api/requests", Some(&new), &t)).unwrap();
    block_on(client.send_empty(Method::POST, "/api/auth/logout", &t)).unwrap();
    let (bytes, content_type) = block_on(client.image("w342", "abc.jpg", &t)).unwrap();
    assert_eq!(content_type, "image/jpeg");
    assert_eq!(bytes, [0xFF, 0xD8, 0xFF, 1, 2]);

    let reqs = server.requests();
    assert_eq!(reqs[0].authorization, None, "version is public");
    for r in &reqs[1..] {
        assert_eq!(
            r.authorization.as_deref(),
            Some("Bearer ur-session-token"),
            "{}",
            r.path
        );
        assert!(!r.path.contains("ur-session-token"));
    }
    assert_eq!(
        reqs[2].path,
        "/api/requests?status=approved%2Csent&mine=true"
    );
    assert_eq!(
        reqs[3].body,
        r#"{"type":"tv","tmdbId":94605,"seasons":[3]}"#
    );
}

#[test]
fn new_request_body_follows_the_contract() {
    let movie = NewRequest {
        media_type: MediaType::Movie,
        tmdb_id: 438631,
        seasons: None,
    };
    assert_eq!(
        serde_json::to_string(&movie).unwrap(),
        r#"{"type":"movie","tmdbId":438631}"#
    );
    let all = NewRequest {
        media_type: MediaType::Tv,
        tmdb_id: 1,
        seasons: Some(SeasonsChoice::All),
    };
    assert_eq!(
        serde_json::to_string(&all).unwrap(),
        r#"{"type":"tv","tmdbId":1,"seasons":"all"}"#
    );
    let from_ui: NewRequest =
        serde_json::from_str(r#"{"type":"tv","tmdbId":1,"seasons":[1,2]}"#).unwrap();
    assert_eq!(from_ui.seasons, Some(SeasonsChoice::List(vec![1, 2])));
    assert!(
        serde_json::from_str::<NewRequest>(r#"{"type":"tv","tmdbId":1,"seasons":[]}"#).is_err()
    );
}

#[test]
fn maps_error_body() {
    let server = FakeServer::start(Box::new(|r| match r.path.as_str() {
        "/api/requests" => Reply::json(403, fixture!("error")),
        "/api/media/movie/1" => Reply::json(404, r#"{"error":"Titre introuvable"}"#),
        "/api/discover" => Reply::json(502, r#"{"error":"TMDB indisponible"}"#),
        "/api/search" => Reply::empty(500),
        _ => Reply::json(401, r#"{"error":"Session expirée"}"#),
    }));
    let client = server.client();
    let t = token("t");
    let new = NewRequest {
        media_type: MediaType::Movie,
        tmdb_id: 1,
        seasons: None,
    };
    let err =
        block_on(client.send::<_, RequestDetail>(Method::POST, "/api/requests", Some(&new), &t))
            .unwrap_err();
    assert_eq!(
        err,
        RequestError::Forbidden("Quota de films atteint".into())
    );
    assert_eq!(err.to_string(), "Quota de films atteint");
    let json = serde_json::to_value(&err).unwrap();
    assert_eq!(
        json,
        serde_json::json!({ "kind": "forbidden", "message": "Quota de films atteint" })
    );
    assert_eq!(
        block_on(client.get::<MediaDetails>("/api/media/movie/1", &[], &t)).unwrap_err(),
        RequestError::NotFound("Titre introuvable".into())
    );
    assert_eq!(
        block_on(client.get::<DiscoverResponse>("/api/discover", &[], &t)).unwrap_err(),
        RequestError::Server {
            status: 502,
            message: "TMDB indisponible".into()
        }
    );
    assert!(matches!(
        block_on(client.get::<PagedMedia>("/api/search", &[], &t)).unwrap_err(),
        RequestError::Server { status: 500, .. }
    ));
    assert_eq!(
        block_on(client.get::<SessionList>("/api/auth/sessions", &[], &t)).unwrap_err(),
        RequestError::Rejected("Session expirée".into())
    );
}

#[test]
fn rate_limited_exposes_retry_after() {
    let server = FakeServer::start(Box::new(|_| {
        Reply::json(429, r#"{"error":"Trop de tentatives"}"#).header("Retry-After", "120")
    }));
    let err = block_on(server.client().exchange(
        EMBY_USER,
        &AccessToken::new(EMBY_TOKEN.into()),
        "PC (Windows)",
    ))
    .err()
    .unwrap();
    assert_eq!(err, RequestError::RateLimited { retry_after_s: 120 });
    assert_eq!(
        err.to_string(),
        "trop de tentatives de connexion : réessaie dans 2 min"
    );
    // No automatic retry.
    assert_eq!(server.requests().len(), 1);
}

#[test]
fn unreachable_server_is_typed() {
    let listener = TcpListener::bind("127.0.0.1:0").unwrap();
    let url = Url::parse(&format!("http://{}", listener.local_addr().unwrap())).unwrap();
    drop(listener);
    let client = RequestClient::new(&url, "9.9.9").unwrap();
    assert_eq!(
        block_on(client.version()).unwrap_err(),
        RequestError::Unreachable
    );
}

#[test]
fn image_refuses_svg_and_html() {
    let server = FakeServer::start(Box::new(|r| Reply {
        status: 200,
        content_type: if r.path.ends_with(".svg") {
            "image/svg+xml"
        } else {
            "text/html"
        },
        headers: Vec::new(),
        body: b"<svg/>".to_vec(),
    }));
    let client = server.client();
    for file in ["a.svg", "a.jpg"] {
        assert_eq!(
            block_on(client.image("w185", file, &token("t"))).unwrap_err(),
            RequestError::InvalidResponse
        );
    }
}

#[test]
fn token_never_debug_printed() {
    let t = token("ur-secret-session-value");
    assert_eq!(format!("{t:?}"), "SessionToken(***)");
    let session: EmbyTokenSession = serde_json::from_str(fixture!("emby-token")).unwrap();
    assert!(!format!("{:?}", session.token).contains("EXEMPLE"));
}
