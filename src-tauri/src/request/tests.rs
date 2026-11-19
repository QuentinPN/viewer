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

// ---------------------------------------------------------------------------
// Service: single-flight exchange, retry after 401, sign-out, images (task 2).
// ---------------------------------------------------------------------------

use std::sync::atomic::{AtomicUsize, Ordering};

use serde::Serialize;

use super::commands::{BrowseFilters, requests_query};
use super::images::{Decorate, UrImage, image_ref};
use super::service::{Identity, UrState, RequestService};
use crate::emby::credentials::{CredentialStore, MemoryCredentialStore};

const EMBY_SERVER: &str = "d969e3d9465b49ec8f73a5f1eab2e86f";
const FIXTURE_TOKEN: &str = "ur_sess_EXEMPLE_NON_SECRET_0000000000000000";

fn identity(user_id: &str) -> Identity {
    Identity {
        server_id: EMBY_SERVER.into(),
        user_id: user_id.into(),
        emby_token: AccessToken::new(EMBY_TOKEN.into()),
    }
}

fn stored(store: &MemoryCredentialStore, user_id: &str) -> Option<String> {
    store
        .load(&super::account(EMBY_SERVER, user_id))
        .unwrap()
        .map(|t| t.expose().to_owned())
}

fn seed(store: &MemoryCredentialStore, user_id: &str, value: &str) {
    store
        .save(
            &super::account(EMBY_SERVER, user_id),
            &AccessToken::new(value.into()),
        )
        .unwrap();
}

fn bearer(r: &Recorded) -> Option<&str> {
    r.authorization.as_deref()?.strip_prefix("Bearer ")
}

/// Fake Request: exchange `n` answers token `tok-n`; a Bearer call answers
/// 401 unless `accept` takes its token.
fn request_like(
    accept: impl Fn(&str) -> bool + Send + Sync + 'static,
) -> (FakeServer, Arc<AtomicUsize>) {
    let exchanges = Arc::new(AtomicUsize::new(0));
    let count = Arc::clone(&exchanges);
    let viewer = serde_json::json!({
        "viewer": serde_json::from_str::<serde_json::Value>(fixture!("emby-token")).unwrap()["viewer"]
    })
    .to_string();
    let server = FakeServer::start(Box::new(move |r| {
        let path = r.path.split('?').next().unwrap_or_default();
        match path {
            "/api/version" => return Reply::json(200, fixture!("version-ok")),
            "/api/auth/emby-token" => {
                let n = count.fetch_add(1, Ordering::SeqCst) + 1;
                let body = fixture!("emby-token").replace(FIXTURE_TOKEN, &format!("tok-{n}"));
                return Reply::json(200, &body);
            }
            _ => {}
        }
        if !bearer(r).is_some_and(&accept) {
            return Reply::json(401, r#"{"error":"Session invalide"}"#);
        }
        match path {
            "/api/auth/me" => Reply::json(200, &viewer),
            "/api/auth/logout" => Reply::empty(204),
            "/api/discover" => Reply::json(200, fixture!("discover")),
            "/api/images/w342/abc.jpg" => Reply {
                status: 200,
                content_type: "image/jpeg",
                headers: Vec::new(),
                body: vec![0xFF, 0xD8, 0xFF, 0xE0, 7],
            },
            _ => Reply::empty(404),
        }
    }));
    (server, exchanges)
}

fn temp_settings() -> std::path::PathBuf {
    std::env::temp_dir()
        .join(format!("viewer-ur-test-{}", uuid::Uuid::new_v4()))
        .join("request.json")
}

fn service(store: &Arc<MemoryCredentialStore>, address: Option<&Url>) -> RequestService {
    let service = RequestService::new(
        Box::new(Arc::clone(store)),
        temp_settings(),
        "PC-TEST".into(),
        "9.9.9".into(),
    );
    if let Some(url) = address {
        service.set_address(url.as_str()).unwrap();
    }
    service
}

fn discover(
    service: &RequestService,
    id: &Identity,
) -> Result<DiscoverResponse, RequestError> {
    block_on(service.get(id, "/api/discover", &[]))
}

#[test]
fn concurrent_calls_exchange_once() {
    let (server, exchanges) = request_like(|_| true);
    let store = Arc::new(MemoryCredentialStore::default());
    let ur = service(&store, Some(&server.url));
    let id = identity(EMBY_USER);
    let results = block_on(futures_util::future::join_all(
        (0..8).map(|_| ur.get::<DiscoverResponse>(&id, "/api/discover", &[])),
    ));
    assert!(results.iter().all(Result::is_ok), "{results:?}");
    assert_eq!(exchanges.load(Ordering::SeqCst), 1);
    assert_eq!(stored(&store, EMBY_USER).as_deref(), Some("tok-1"));
    let reqs = server.requests();
    let exchange = reqs
        .iter()
        .find(|r| r.path == "/api/auth/emby-token")
        .unwrap();
    let body: serde_json::Value = serde_json::from_str(&exchange.body).unwrap();
    assert_eq!(body["deviceName"], "PC-TEST (Windows)");
}

#[test]
fn concurrent_401s_exchange_once() {
    let (server, exchanges) = request_like(|t| t != "stale");
    let store = Arc::new(MemoryCredentialStore::default());
    seed(&store, EMBY_USER, "stale");
    let ur = service(&store, Some(&server.url));
    let id = identity(EMBY_USER);
    let results = block_on(futures_util::future::join_all(
        (0..8).map(|_| ur.get::<DiscoverResponse>(&id, "/api/discover", &[])),
    ));
    assert!(results.iter().all(Result::is_ok), "{results:?}");
    assert_eq!(exchanges.load(Ordering::SeqCst), 1);
    let stale = server
        .requests()
        .iter()
        .filter(|r| bearer(r) == Some("stale"))
        .count();
    // Several calls really were refused at once: the test is not vacuous.
    assert!(stale >= 2, "only {stale} stale requests");
    assert_eq!(stored(&store, EMBY_USER).as_deref(), Some("tok-1"));
}

#[test]
fn retries_once_after_401() {
    let (server, exchanges) = request_like(|t| t != "stale");
    let store = Arc::new(MemoryCredentialStore::default());
    seed(&store, EMBY_USER, "stale");
    let ur = service(&store, Some(&server.url));
    discover(&ur, &identity(EMBY_USER)).unwrap();
    assert_eq!(exchanges.load(Ordering::SeqCst), 1);
    assert_eq!(stored(&store, EMBY_USER).as_deref(), Some("tok-1"));
    let reqs = server.requests();
    let calls: Vec<_> = reqs.iter().filter(|r| r.path == "/api/discover").collect();
    assert_eq!(calls.len(), 2);
    assert_eq!(bearer(calls[0]), Some("stale"));
    assert_eq!(bearer(calls[1]), Some("tok-1"));
}

#[test]
fn rejected_after_single_retry() {
    let (server, exchanges) = request_like(|_| false);
    let store = Arc::new(MemoryCredentialStore::default());
    let ur = service(&store, Some(&server.url));
    let id = identity(EMBY_USER);
    assert_eq!(
        discover(&ur, &id).unwrap_err(),
        RequestError::Rejected("Session invalide".into())
    );
    assert_eq!(exchanges.load(Ordering::SeqCst), 2);
    assert_eq!(stored(&store, EMBY_USER), None);
    // No loop: later calls fail at once until "Se connecter".
    assert!(matches!(
        discover(&ur, &id),
        Err(RequestError::Rejected(_))
    ));
    let status = block_on(ur.status(&id));
    assert_eq!(status.state, UrState::Rejected);
    assert_eq!(exchanges.load(Ordering::SeqCst), 2);
    ur.sign_in(&id).unwrap();
    let _ = discover(&ur, &id);
    assert_eq!(exchanges.load(Ordering::SeqCst), 4);
}

#[test]
fn refused_exchange_is_not_retried() {
    let server = FakeServer::start(Box::new(|r| match r.path.as_str() {
        "/api/version" => Reply::json(200, fixture!("version-ok")),
        _ => Reply::json(401, r#"{"error":"Jeton Emby refusé"}"#),
    }));
    let store = Arc::new(MemoryCredentialStore::default());
    let ur = service(&store, Some(&server.url));
    let id = identity(EMBY_USER);
    for _ in 0..3 {
        assert_eq!(
            discover(&ur, &id).unwrap_err(),
            RequestError::Rejected("Jeton Emby refusé".into())
        );
    }
    let exchanges = server
        .requests()
        .iter()
        .filter(|r| r.path == "/api/auth/emby-token")
        .count();
    assert_eq!(exchanges, 1);
    // A new Emby session (another token) may try again.
    let renewed = Identity {
        emby_token: AccessToken::new("new-emby-token".into()),
        ..id
    };
    let _ = discover(&ur, &renewed);
    assert_eq!(server.requests().len(), 3);
}

#[test]
fn rate_limit_blocks_further_calls() {
    let server = FakeServer::start(Box::new(|r| match r.path.as_str() {
        "/api/version" => Reply::json(200, fixture!("version-ok")),
        _ => Reply::json(429, r#"{"error":"Trop de tentatives"}"#).header("Retry-After", "300"),
    }));
    let store = Arc::new(MemoryCredentialStore::default());
    let ur = service(&store, Some(&server.url));
    let id = identity(EMBY_USER);
    assert_eq!(
        discover(&ur, &id).unwrap_err(),
        RequestError::RateLimited { retry_after_s: 300 }
    );
    let sent = server.requests().len();
    assert!(matches!(
        discover(&ur, &id),
        Err(RequestError::RateLimited { retry_after_s }) if retry_after_s <= 300
    ));
    assert_eq!(server.requests().len(), sent);
}

#[test]
fn sign_out_does_not_auto_reconnect() {
    let (server, exchanges) = request_like(|_| true);
    let store = Arc::new(MemoryCredentialStore::default());
    let path = temp_settings();
    let make = || {
        RequestService::new(
            Box::new(Arc::clone(&store)),
            path.clone(),
            "PC-TEST".into(),
            "9.9.9".into(),
        )
    };
    let ur = make();
    ur.set_address(server.url.as_str()).unwrap();
    let id = identity(EMBY_USER);
    assert_eq!(block_on(ur.status(&id)).state, UrState::Ready);
    block_on(ur.sign_out(&id)).unwrap();
    assert_eq!(stored(&store, EMBY_USER), None);
    assert_eq!(discover(&ur, &id).unwrap_err(), RequestError::SignedOut);
    // The flag survives a restart.
    let restarted = make();
    let status = block_on(restarted.status(&id));
    assert_eq!(status.state, UrState::SignedOut);
    assert_eq!(
        status.address.as_deref(),
        Some(super::address_text(&server.url).as_str())
    );
    let img = UrImage::parse("/w342/abc.jpg").unwrap();
    assert_eq!(
        block_on(restarted.image(&id, &img)).unwrap_err(),
        RequestError::SignedOut
    );
    assert_eq!(exchanges.load(Ordering::SeqCst), 1);
    // Another Emby account is not signed out.
    assert_eq!(
        block_on(restarted.status(&identity("otheruser"))).state,
        UrState::Ready
    );
    restarted.sign_in(&id).unwrap();
    let status = block_on(restarted.status(&id));
    assert_eq!(status.state, UrState::Ready);
    assert_eq!(status.viewer.unwrap().name, "Camille");
    assert_eq!(status.api_version.as_deref(), Some("1.0.0"));
    assert_eq!(exchanges.load(Ordering::SeqCst), 3);
}

#[test]
fn sign_out_revokes_and_deletes() {
    let (server, _) = request_like(|_| true);
    let store = Arc::new(MemoryCredentialStore::default());
    let ur = service(&store, Some(&server.url));
    let id = identity(EMBY_USER);
    discover(&ur, &id).unwrap();
    block_on(ur.sign_out(&id)).unwrap();
    let reqs = server.requests();
    let logout = reqs.last().unwrap();
    assert_eq!(
        (logout.method.as_str(), logout.path.as_str()),
        ("POST", "/api/auth/logout")
    );
    assert_eq!(bearer(logout), Some("tok-1"));
    assert_eq!(stored(&store, EMBY_USER), None);

    // Unreachable server: the token is deleted all the same.
    let listener = TcpListener::bind("127.0.0.1:0").unwrap();
    let dead = Url::parse(&format!("http://{}", listener.local_addr().unwrap())).unwrap();
    drop(listener);
    let ur = service(&store, Some(&dead));
    seed(&store, EMBY_USER, "kept-token");
    block_on(ur.sign_out(&id)).unwrap();
    assert_eq!(stored(&store, EMBY_USER), None);
}

#[test]
fn token_is_scoped_to_emby_account() {
    let (server, exchanges) = request_like(|_| true);
    let store = Arc::new(MemoryCredentialStore::default());
    let ur = service(&store, Some(&server.url));
    discover(&ur, &identity("usera")).unwrap();
    discover(&ur, &identity("userb")).unwrap();
    discover(&ur, &identity("usera")).unwrap();
    assert_eq!(exchanges.load(Ordering::SeqCst), 2);
    assert_eq!(
        super::account(EMBY_SERVER, "usera"),
        format!("request/{EMBY_SERVER}/usera")
    );
    assert_eq!(stored(&store, "usera").as_deref(), Some("tok-1"));
    assert_eq!(stored(&store, "userb").as_deref(), Some("tok-2"));
}

#[test]
fn emby_forget_drops_request_token() {
    use crate::emby::session::{EmbyService, SessionFile, SessionRecord};
    for forget in [true, false] {
        let store = Arc::new(MemoryCredentialStore::default());
        let path = temp_settings().with_file_name("session.json");
        let record = SessionRecord {
            server_url: "http://127.0.0.1:9".into(),
            server_id: EMBY_SERVER.into(),
            server_name: "ServeurTest".into(),
            server_version: "4.10.1.0".into(),
            user_id: EMBY_USER.into(),
            user_name: "testeur".into(),
            rejected: false,
        };
        SessionFile {
            session: Some(record),
            ..SessionFile::default()
        }
        .save(&path)
        .unwrap();
        store
            .save(
                &crate::emby::credentials::account(EMBY_SERVER, EMBY_USER),
                &AccessToken::new(EMBY_TOKEN.into()),
            )
            .unwrap();
        seed(&store, EMBY_USER, "ur-token");
        seed(&store, "otheruser", "other-ur-token");
        let emby = EmbyService::new(
            Box::new(Arc::clone(&store)),
            path,
            "PC-TEST".into(),
            "9.9.9".into(),
        );
        if forget {
            emby.forget_server(EMBY_SERVER, EMBY_USER).unwrap();
        } else {
            block_on(emby.sign_out()).unwrap();
        }
        assert_eq!(stored(&store, EMBY_USER), None, "forget={forget}");
        assert_eq!(
            stored(&store, "otheruser").as_deref(),
            Some("other-ur-token")
        );
    }
}

#[test]
fn status_not_configured() {
    let store = Arc::new(MemoryCredentialStore::default());
    let ur = service(&store, None);
    let id = identity(EMBY_USER);
    let status = block_on(ur.status(&id));
    assert_eq!(status.state, UrState::NotConfigured);
    assert_eq!(
        serde_json::to_value(&status).unwrap(),
        serde_json::json!({
            "state": "notConfigured",
            "address": null,
            "viewer": null,
            "apiVersion": null,
            "missing": [],
            "message": null
        })
    );
    assert_eq!(
        discover(&ur, &id).unwrap_err(),
        RequestError::NotConfigured
    );
    assert_eq!(
        ur.set_address("http://request.exemple.fr"),
        Err(RequestError::InvalidAddress)
    );

    // An old server: « trop ancien » with the missing features, no exchange.
    let old = FakeServer::start(Box::new(|_| Reply::json(200, fixture!("version-old"))));
    ur.set_address(old.url.as_str()).unwrap();
    let status = block_on(ur.status(&id));
    assert_eq!(status.state, UrState::TooOld);
    assert_eq!(status.api_version.as_deref(), Some("1.0.0"));
    assert_eq!(status.missing.len(), 4);
    assert_eq!(old.requests().len(), 1);
}

#[test]
fn image_path_validation() {
    let ok = UrImage::parse("/w342/abc_D-1.jpg").unwrap();
    assert_eq!((ok.size, ok.file.as_str()), ("w342", "abc_D-1.jpg"));
    assert!(UrImage::parse("/w1280/x.webp").is_some());
    for bad in [
        "/w342/../x.jpg",
        "../x.jpg",
        "/w9999/a.jpg",
        "/original/a.jpg",
        "/w342/a.svg",
        "/w342/a.jpg/x",
        "/w342/a%2Fb.jpg",
        "/w342/.jpg",
        "/w342/a.JPG.exe",
        "/w342",
    ] {
        assert_eq!(UrImage::parse(bad), None, "{bad}");
    }
    let long = format!("/w342/{}.jpg", "a".repeat(65));
    assert_eq!(UrImage::parse(&long), None);

    let r = image_ref(Some("/abc.jpg"), &["w185", "w342"]).unwrap();
    assert_eq!(r.src, "http://requestimg.localhost/w185/abc.jpg");
    assert_eq!(
        r.srcset,
        "http://requestimg.localhost/w185/abc.jpg 185w, http://requestimg.localhost/w342/abc.jpg 342w"
    );
    assert_eq!(image_ref(Some("/a.svg"), &["w185"]), None);
    assert_eq!(image_ref(Some("abc.jpg"), &["w185"]), None);
    assert_eq!(image_ref(Some("/../a.jpg"), &["w185"]), None);
    assert_eq!(image_ref(None, &["w185"]), None);
}

#[test]
fn image_is_fetched_with_bearer_and_cached() {
    let (server, _) = request_like(|t| t != "stale");
    let store = Arc::new(MemoryCredentialStore::default());
    seed(&store, EMBY_USER, "stale");
    let root = std::env::temp_dir().join(format!("viewer-ur-artwork-{}", uuid::Uuid::new_v4()));
    let cache = Arc::new(crate::emby::artwork_cache::ArtworkCache::new(root, 1 << 20));
    let ur = service(&store, Some(&server.url)).with_artwork_cache(Some(cache));
    let id = identity(EMBY_USER);
    let img = UrImage::parse("/w342/abc.jpg").unwrap();
    let (content_type, bytes) = block_on(ur.image(&id, &img)).unwrap();
    assert_eq!(content_type, "image/jpeg");
    assert_eq!(bytes, [0xFF, 0xD8, 0xFF, 0xE0, 7]);
    let fetched = server.requests().len();
    // Second time from the disk cache: nothing sent.
    assert_eq!(block_on(ur.image(&id, &img)).unwrap().1, bytes);
    assert_eq!(server.requests().len(), fetched);
    let images: Vec<_> = server
        .requests()
        .into_iter()
        .filter(|r| r.path.starts_with("/api/images/"))
        .collect();
    assert_eq!(images.len(), 2, "401 then replay");
    assert_eq!(bearer(&images[1]), Some("tok-1"));
}

/// Walks the JSON sent to the UI: every non-null `posterPath`, `backdropPath` or
/// `profilePath` comes with its `poster`, `backdrop` or `profile` ImageRef.
fn assert_images(value: &serde_json::Value, at: &str) {
    match value {
        serde_json::Value::Object(map) => {
            for (path_key, ref_key) in [
                ("posterPath", "poster"),
                ("backdropPath", "backdrop"),
                ("profilePath", "profile"),
            ] {
                if map.get(path_key).is_some_and(|v| !v.is_null()) {
                    let r = &map[ref_key];
                    assert!(
                        r["src"]
                            .as_str()
                            .is_some_and(|s| s.starts_with("http://requestimg.localhost/")),
                        "{at}.{ref_key} missing"
                    );
                }
            }
            for (k, v) in map {
                assert_images(v, &format!("{at}.{k}"));
            }
        }
        serde_json::Value::Array(items) => {
            for (i, v) in items.iter().enumerate() {
                assert_images(v, &format!("{at}[{i}]"));
            }
        }
        _ => {}
    }
}

fn check_decorated<T: serde::de::DeserializeOwned + Serialize + Decorate>(name: &str, text: &str) {
    let mut value: T = serde_json::from_str(text).unwrap();
    value.decorate();
    let json = serde_json::to_value(&value).unwrap();
    assert_images(&json, name);
    assert!(json.to_string().contains("requestimg.localhost"), "{name}");
}

#[test]
fn every_image_path_has_an_image_ref() {
    check_decorated::<DiscoverResponse>("discover", fixture!("discover"));
    check_decorated::<PagedMedia>("paged-media", fixture!("paged-media"));
    check_decorated::<MediaSearchResults>("search", fixture!("search"));
    check_decorated::<MediaDetails>("media-movie", fixture!("media-movie"));
    check_decorated::<MediaDetails>("media-tv", fixture!("media-tv"));
    check_decorated::<RequestView>("request-view", fixture!("request-view"));
    check_decorated::<RequestPage>("request-page", fixture!("request-page"));
    check_decorated::<RequestDetail>("request-detail", fixture!("request-detail"));
}

#[test]
fn builds_list_queries() {
    assert_eq!(
        requests_query(&[RequestStatus::Approved, RequestStatus::Sent], 2),
        [
            ("mine", "true".to_owned()),
            ("page", "2".to_owned()),
            ("status", "approved,sent".to_owned())
        ]
    );
    assert_eq!(
        requests_query(&[], 0),
        [("mine", "true".to_owned()), ("page", "1".to_owned())]
    );
    let filters: BrowseFilters = serde_json::from_str(
        r#"{"page":3,"genres":[16,35],"yearFrom":1990,"rating":7,"sort":"release"}"#,
    )
    .unwrap();
    assert_eq!(
        filters.query(),
        [
            ("page", "3".to_owned()),
            ("genres", "16,35".to_owned()),
            ("yearFrom", "1990".to_owned()),
            ("rating", "7".to_owned()),
            ("sort", "release".to_owned())
        ]
    );
}
