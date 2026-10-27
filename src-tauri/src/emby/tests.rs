//! Contract tests against a local fake Emby server. Response shapes mirror what
//! Emby Server 4.10.1.0 returned during the Gate 2 probe (ADR 0004).

use std::io::{BufRead, BufReader, Read, Write};
use std::net::TcpListener;
use std::path::PathBuf;
use std::sync::{Arc, Mutex};

use super::EmbyError;
use super::client::AccessToken;
use super::credentials::{CredentialStore, MemoryCredentialStore, account};
use super::session::{EmbyService, SessionFile};

const SERVER_ID: &str = "d969e3d9465b49ec8f73a5f1eab2e86f";
const USER_ID: &str = "4a1f0e2b9c8d4e7fa6b5c4d3e2f1a0b9";
const TOKEN: &str = "0123456789abcdef0123456789abcdef";
const PASSWORD: &str = "s3cret-pw";
const REAL_DEVICE_ID: &str = "viewer-real-server-test";

#[derive(Debug, Clone)]
struct Recorded {
    method: String,
    path: String,
    auth: Option<String>,
    body: String,
}

type Route = Box<dyn Fn(&Recorded) -> (u16, String) + Send + Sync>;

/// One-request-per-connection HTTP server on 127.0.0.1 recording every request.
struct FakeEmby {
    url: String,
    requests: Arc<Mutex<Vec<Recorded>>>,
}

impl FakeEmby {
    fn start(route: Route) -> Self {
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let url = format!("http://{}", listener.local_addr().unwrap());
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
                let (mut auth, mut len) = (None, 0usize);
                loop {
                    let mut h = String::new();
                    reader.read_line(&mut h).unwrap();
                    let h = h.trim_end();
                    if h.is_empty() {
                        break;
                    }
                    let (name, value) = h.split_once(':').unwrap();
                    match name.to_ascii_lowercase().as_str() {
                        "x-emby-authorization" => auth = Some(value.trim().to_owned()),
                        "content-length" => len = value.trim().parse().unwrap(),
                        _ => {}
                    }
                }
                let mut body = vec![0; len];
                reader.read_exact(&mut body).unwrap();
                let req = Recorded {
                    method,
                    path,
                    auth,
                    body: String::from_utf8_lossy(&body).into_owned(),
                };
                let (status, body) = route(&req);
                log.lock().unwrap().push(req);
                let reply = format!(
                    "HTTP/1.1 {status} X\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{body}",
                    body.len()
                );
                let _ = stream.write_all(reply.as_bytes());
            }
        });
        Self { url, requests }
    }

    fn requests(&self) -> Vec<Recorded> {
        self.requests.lock().unwrap().clone()
    }
}

fn public_info() -> String {
    format!(
        r#"{{"LocalAddresses":[],"RemoteAddresses":[],"ServerName":"ServeurTest","Version":"4.10.1.0","Id":"{SERVER_ID}"}}"#
    )
}

fn auth_result() -> String {
    format!(
        r#"{{"User":{{"Name":"testeur","Id":"{USER_ID}","ServerId":"{SERVER_ID}","Policy":{{"IsAdministrator":false,"IsDisabled":false}}}},"SessionInfo":{{"Id":"s1","DeviceId":"x"}},"AccessToken":"{TOKEN}","ServerId":"{SERVER_ID}"}}"#
    )
}

fn user() -> String {
    format!(r#"{{"Name":"testeur","Id":"{USER_ID}","Policy":{{"IsAdministrator":false}}}}"#)
}

/// Fake server behaving like Emby 4.10: correct password, token checks, logout.
fn emby_like(logout_status: u16) -> Route {
    Box::new(move |r| {
        let has_token = r
            .auth
            .as_deref()
            .is_some_and(|a| a.contains(&format!("Token=\"{TOKEN}\"")));
        match (r.method.as_str(), r.path.as_str()) {
            ("GET", "/emby/System/Info/Public") => (200, public_info()),
            ("POST", "/emby/Users/AuthenticateByName") => {
                if r.body.contains(&format!("\"Pw\":\"{PASSWORD}\"")) {
                    (200, auth_result())
                } else {
                    (
                        401,
                        "\"Nom d'utilisateur ou mot de passe incorrect.\"".into(),
                    )
                }
            }
            ("GET", p) if p == format!("/emby/Users/{USER_ID}") => {
                if has_token {
                    (200, user())
                } else {
                    (401, String::new())
                }
            }
            ("POST", "/emby/Sessions/Logout") => (logout_status, String::new()),
            _ => (404, String::new()),
        }
    })
}

struct Fixture {
    service: EmbyService,
    store: Arc<MemoryCredentialStore>,
    path: PathBuf,
}

struct SharedStore(Arc<MemoryCredentialStore>);

impl CredentialStore for SharedStore {
    fn save(&self, a: &str, t: &AccessToken) -> Result<(), EmbyError> {
        self.0.save(a, t)
    }
    fn load(&self, a: &str) -> Result<Option<AccessToken>, EmbyError> {
        self.0.load(a)
    }
    fn delete(&self, a: &str) -> Result<(), EmbyError> {
        self.0.delete(a)
    }
}

fn fixture() -> Fixture {
    let dir = std::env::temp_dir().join(format!("viewer-emby-test-{}", uuid::Uuid::new_v4()));
    reopen(
        Arc::new(MemoryCredentialStore::default()),
        dir.join("session.json"),
    )
}

/// Same store and session file, new service: simulates an app restart.
fn reopen(store: Arc<MemoryCredentialStore>, path: PathBuf) -> Fixture {
    let service = EmbyService::new(
        Box::new(SharedStore(Arc::clone(&store))),
        path.clone(),
        "PC \"test\", bureau".into(),
        "0.1.0".into(),
    );
    Fixture {
        service,
        store,
        path,
    }
}

fn run<T>(f: impl std::future::Future<Output = T>) -> T {
    tauri::async_runtime::block_on(f)
}

fn stored_token(fx: &Fixture) -> Option<AccessToken> {
    fx.store.load(&account(SERVER_ID, USER_ID)).unwrap()
}

#[test]
fn sign_in_stores_token_securely_and_sends_auth_in_header() {
    let server = FakeEmby::start(emby_like(204));
    let fx = fixture();
    let user = run(fx.service.sign_in(&server.url, " testeur ", PASSWORD)).unwrap();

    assert_eq!(user.user_name, "testeur");
    assert!(!user.is_administrator);
    assert_eq!(user.server.name, "ServeurTest");
    assert!(!user.server.encrypted);
    assert_eq!(stored_token(&fx).unwrap().expose(), TOKEN);
    assert_eq!(fx.service.current_user(), Some(user));

    let reqs = server.requests();
    let auth = &reqs[1];
    assert_eq!(auth.path, "/emby/Users/AuthenticateByName");
    assert!(
        auth.body.contains("\"Username\":\"testeur\""),
        "{}",
        auth.body
    );
    let header = auth.auth.as_deref().unwrap();
    assert!(header.starts_with("Emby Client=\"Viewer\""), "{header}");
    assert!(header.contains("Device=\"PC test bureau\""), "{header}");
    assert!(!header.contains("Token="), "{header}");
    // Neither the token nor the password ever appears in a URL.
    assert!(
        reqs.iter()
            .all(|r| !r.path.contains(TOKEN) && !r.path.contains(PASSWORD))
    );

    // session.json has the metadata needed to restore, and no secret.
    let file = std::fs::read_to_string(&fx.path).unwrap();
    assert!(!file.contains(TOKEN) && !file.contains(PASSWORD), "{file}");
    let parsed: SessionFile = serde_json::from_str(&file).unwrap();
    assert_eq!(parsed.session.unwrap().user_id, USER_ID);
    assert!(header.contains(&format!("DeviceId=\"{}\"", parsed.device_id)));
}

#[test]
fn wrong_password_is_reported_and_nothing_is_stored() {
    let server = FakeEmby::start(emby_like(204));
    let fx = fixture();
    let err = run(fx.service.sign_in(&server.url, "testeur", "nope")).unwrap_err();
    assert!(matches!(err, EmbyError::InvalidCredentials), "{err:?}");
    assert_eq!(err.kind(), "invalidCredentials");
    assert!(stored_token(&fx).is_none());
    assert!(fx.service.current_user().is_none());
    assert!(!fx.path.exists());
}

#[test]
fn restore_after_restart_reuses_token_and_keeps_device_id() {
    let server = FakeEmby::start(emby_like(204));
    let fx = fixture();
    run(fx.service.sign_in(&server.url, "testeur", PASSWORD)).unwrap();
    let device_before =
        serde_json::from_str::<SessionFile>(&std::fs::read_to_string(&fx.path).unwrap())
            .unwrap()
            .device_id;

    let fx = reopen(fx.store, fx.path);
    let user = run(fx.service.restore()).unwrap().unwrap();
    assert_eq!(user.user_id, USER_ID);
    let check = server.requests().into_iter().last().unwrap();
    let header = check.auth.unwrap();
    assert!(header.contains(&format!("Token=\"{TOKEN}\"")));
    assert!(header.contains(&format!("DeviceId=\"{device_before}\"")));
}

#[test]
fn restore_with_revoked_token_forgets_it() {
    let server = FakeEmby::start(emby_like(204));
    let fx = fixture();
    run(fx.service.sign_in(&server.url, "testeur", PASSWORD)).unwrap();
    // Token revoked on the server side (e.g. device removed in the dashboard).
    fx.store
        .save(
            &account(SERVER_ID, USER_ID),
            &AccessToken::new("revoked".into()),
        )
        .unwrap();

    let fx = reopen(fx.store, fx.path);
    let err = run(fx.service.restore()).unwrap_err();
    assert!(matches!(err, EmbyError::SessionExpired), "{err:?}");
    assert!(stored_token(&fx).is_none());
    assert_eq!(
        fx.service.last_server_url().as_deref(),
        Some(server.url.as_str())
    );
    assert!(run(fx.service.restore()).unwrap().is_none());
}

#[test]
fn restore_with_nothing_saved_is_signed_out() {
    let fx = fixture();
    assert!(run(fx.service.restore()).unwrap().is_none());
}

#[test]
fn sign_out_revokes_on_server_and_forgets_locally_even_if_logout_fails() {
    for logout_status in [204, 500] {
        let server = FakeEmby::start(emby_like(logout_status));
        let fx = fixture();
        run(fx.service.sign_in(&server.url, "testeur", PASSWORD)).unwrap();
        run(fx.service.sign_out()).unwrap();

        let logout = server.requests().into_iter().last().unwrap();
        assert_eq!(logout.path, "/emby/Sessions/Logout");
        assert!(logout.auth.unwrap().contains(&format!("Token=\"{TOKEN}\"")));
        assert!(stored_token(&fx).is_none(), "status {logout_status}");
        assert!(fx.service.current_user().is_none());
        assert!(matches!(fx.service.client(), Err(EmbyError::NotSignedIn)));
        let fx = reopen(fx.store, fx.path);
        assert!(run(fx.service.restore()).unwrap().is_none());
    }
}

#[test]
fn malformed_or_foreign_server_responses_are_rejected() {
    let server = FakeEmby::start(Box::new(|_| (200, "<html>NAS</html>".into())));
    let fx = fixture();
    let err = run(fx.service.probe(&server.url)).unwrap_err();
    assert!(matches!(err, EmbyError::InvalidResponse(_)), "{err:?}");

    // AuthenticateByName answered for a different server id than the probe.
    let server = FakeEmby::start(Box::new(|r| match r.path.as_str() {
        "/emby/System/Info/Public" => (200, public_info()),
        _ => (200, auth_result().replace(SERVER_ID, "another")),
    }));
    let err = run(fx.service.sign_in(&server.url, "testeur", PASSWORD)).unwrap_err();
    assert!(matches!(err, EmbyError::InvalidResponse(_)), "{err:?}");
    assert!(stored_token(&fx).is_none());

    let server = FakeEmby::start(Box::new(|_| (503, String::new())));
    let err = run(fx.service.probe(&server.url)).unwrap_err();
    assert!(matches!(err, EmbyError::Server(503)), "{err:?}");
}

#[test]
fn unreachable_server_is_reported() {
    // Bind then drop to get a local port with nothing listening.
    let port = TcpListener::bind("127.0.0.1:0")
        .unwrap()
        .local_addr()
        .unwrap()
        .port();
    let fx = fixture();
    let err = run(fx.service.probe(&format!("http://127.0.0.1:{port}"))).unwrap_err();
    assert!(matches!(err, EmbyError::Unreachable(_)), "{err:?}");
}

#[test]
fn errors_serialize_with_kind_and_french_message() {
    let json = serde_json::to_string(&EmbyError::InsecureRemoteUrl).unwrap();
    assert!(json.contains("\"kind\":\"insecureRemoteUrl\""), "{json}");
    assert!(json.contains("https://"), "{json}");
}

/// Real server check, run on demand only:
/// `VIEWER_EMBY_URL=… VIEWER_EMBY_USER=… VIEWER_EMBY_PASSWORD=… cargo test -- --ignored real_server`
/// Signs in, restores from the credential store, then signs out (revokes the token).
#[test]
#[ignore = "needs a real Emby server and credentials in environment variables"]
fn real_server_sign_in_restore_sign_out() {
    let var = |k: &str| std::env::var(k).unwrap_or_else(|_| panic!("{k} manquant"));
    let (url, name, pw) = (
        var("VIEWER_EMBY_URL"),
        var("VIEWER_EMBY_USER"),
        var("VIEWER_EMBY_PASSWORD"),
    );
    // Fixed device id: repeated runs reuse one device entry on the server.
    let path = std::env::temp_dir()
        .join(format!("viewer-emby-real-{}", uuid::Uuid::new_v4()))
        .join("session.json");
    SessionFile {
        device_id: REAL_DEVICE_ID.into(),
        ..SessionFile::default()
    }
    .save(&path)
    .unwrap();
    let fx = reopen(Arc::new(MemoryCredentialStore::default()), path);

    let err = run(fx.service.sign_in(&url, &name, "definitely-wrong")).unwrap_err();
    assert!(matches!(err, EmbyError::InvalidCredentials), "{err:?}");

    let user = run(fx.service.sign_in(&url, &name, &pw)).unwrap();
    println!(
        "signed in: server={} {} user={} admin={}",
        user.server.name, user.server.version, user.user_name, user.is_administrator
    );
    let file = std::fs::read_to_string(&fx.path).unwrap();
    // The password may equal the user name (stored on purpose), so check the
    // fields rather than the raw text.
    let parsed: SessionFile = serde_json::from_str(&file).unwrap();
    assert!(!file.to_ascii_lowercase().contains("\"pw\""));
    if pw != name {
        assert!(!file.contains(&pw));
    }
    assert_eq!(parsed.device_id, REAL_DEVICE_ID);
    let token = stored_token_for(&fx, &user.server.id, &user.user_id).unwrap();
    assert!(!file.contains(token.expose()));

    let fx = reopen(fx.store, fx.path);
    let restored = run(fx.service.restore()).unwrap().unwrap();
    assert_eq!(restored.user_id, user.user_id);

    run(fx.service.sign_out()).unwrap();
    assert!(stored_token_for(&fx, &user.server.id, &user.user_id).is_none());
    // The revoked token must now be refused by the server.
    fx.store
        .save(&account(&user.server.id, &user.user_id), &token)
        .unwrap();
    let fx = reopen(fx.store, fx.path);
    // sign_out cleared the session record, so restore has nothing to try.
    assert!(run(fx.service.restore()).unwrap().is_none());
    let client = super::client::EmbyClient::new(
        super::server_url::ServerUrl::parse(&url).unwrap(),
        super::client::DeviceIdentity {
            name: "test".into(),
            id: REAL_DEVICE_ID.into(),
            version: "0.1.0".into(),
        },
    )
    .unwrap()
    .with_token(token);
    let err = run(client.user(&user.user_id)).unwrap_err();
    assert!(matches!(err, EmbyError::SessionExpired), "{err:?}");
    println!("revoked token refused: OK");
}

fn stored_token_for(fx: &Fixture, server_id: &str, user_id: &str) -> Option<AccessToken> {
    fx.store.load(&account(server_id, user_id)).unwrap()
}
