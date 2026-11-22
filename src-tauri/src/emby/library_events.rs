//! Session socket: while signed in, one WebSocket to the Emby
//! server. It declares the session's capabilities on each connect, carries the
//! linked playback's `SendString` messages and, while the home is shown,
//! `LibraryChanged`, so a title added on the server reaches « Ajoutés
//! récemment » at once. The UI only receives an event without payload; the
//! socket URL carries the token and is never logged.

use std::sync::atomic::{AtomicBool, Ordering};
use std::time::Duration;

use futures_util::{SinkExt, StreamExt};
use serde::Deserialize;
use tauri::{Emitter, Manager};
use tokio::time::{Instant, sleep, sleep_until};
use tokio_tungstenite::tungstenite::Message;

use super::client::EmbyClient;
use super::session::EmbyService;
use crate::sync::{SyncHandle, own_session_id};

/// Emitted (no payload) once a burst of library changes has settled.
pub const LIBRARY_CHANGED_EVENT: &str = "emby://library-changed";

/// A scan sends changes in bursts, posters often in a later `ItemsUpdated`:
/// the home is read again once nothing came for this long.
pub const SETTLE: Duration = Duration::from_secs(2);
/// Sent so idle sockets are not closed by the server or a proxy.
pub const KEEP_ALIVE: Duration = Duration::from_secs(30);
/// How often the socket checks the session it was opened for is still the one.
const SESSION_CHECK: Duration = Duration::from_secs(1);

/// Delay before reconnect attempt `attempt` (0-based): 2 s, 5 s, 15 s, then 30 s.
pub fn backoff(attempt: u32) -> Duration {
    Duration::from_secs(match attempt {
        0 => 2,
        1 => 5,
        2 => 15,
        _ => 30,
    })
}

#[derive(Deserialize)]
#[serde(rename_all = "PascalCase")]
struct SocketMessage {
    message_type: String,
    #[serde(default)]
    data: Option<serde_json::Value>,
}

#[derive(Deserialize, Default)]
#[serde(rename_all = "PascalCase", default)]
struct LibraryChange {
    items_added: Vec<serde_json::Value>,
    items_updated: Vec<serde_json::Value>,
    items_removed: Vec<serde_json::Value>,
}

/// True for a `LibraryChanged` frame that adds, updates or removes items.
/// Anything else (user data, sessions, malformed text) is ignored.
pub fn is_library_change(text: &str) -> bool {
    let Ok(msg) = serde_json::from_str::<SocketMessage>(text) else {
        return false;
    };
    if msg.message_type != "LibraryChanged" {
        return false;
    }
    let change = msg
        .data
        .and_then(|d| serde_json::from_value::<LibraryChange>(d).ok())
        .unwrap_or_default();
    !(change.items_added.is_empty()
        && change.items_updated.is_empty()
        && change.items_removed.is_empty())
}

/// `String` of a `SendString` general command, the relay of linked playback.
pub fn send_string_of(text: &str) -> Option<String> {
    let msg = serde_json::from_str::<SocketMessage>(text).ok()?;
    if msg.message_type != "GeneralCommand" {
        return None;
    }
    let data = msg.data?;
    if data.get("Name")?.as_str()? != "SendString" {
        return None;
    }
    Some(data.get("Arguments")?.get("String")?.as_str()?.to_owned())
}

/// Whether the home is shown: `LibraryChanged` only matters then.
#[derive(Default)]
pub struct LibraryEvents {
    home_shown: AtomicBool,
}

impl LibraryEvents {
    pub fn start(&self) {
        self.home_shown.store(true, Ordering::Relaxed);
    }

    pub fn stop(&self) {
        self.home_shown.store(false, Ordering::Relaxed);
    }
}

/// Runs for the app's lifetime: one socket per signed-in session.
pub fn spawn(app: tauri::AppHandle) {
    tauri::async_runtime::spawn(supervise(app));
}

/// Socket URL of the open session, `None` when signed out.
fn session_url(app: &tauri::AppHandle) -> Option<(EmbyClient, String)> {
    let client = app.state::<EmbyService>().client().ok()?;
    let url = client.library_socket_url().ok()?;
    Some((client, url))
}

async fn supervise(app: tauri::AppHandle) {
    let mut attempt = 0;
    let mut last: Option<String> = None;
    loop {
        let Some((client, url)) = session_url(&app) else {
            last = None;
            sleep(SESSION_CHECK).await;
            continue;
        };
        if last.as_deref() != Some(url.as_str()) {
            attempt = 0;
            app.state::<SyncHandle>().own_session(None);
            last = Some(url.clone());
        }
        match tokio_tungstenite::connect_async(url.as_str()).await {
            Ok((socket, _)) => {
                log::info!("session socket: connected");
                attempt = 0;
                tauri::async_runtime::spawn(on_connect(app.clone(), client));
                listen(&app, socket, &url).await;
                log::info!("session socket: disconnected");
            }
            // The error text never holds the URL; its kind is enough to diagnose.
            Err(e) => log::warn!("session socket: connection failed ({})", error_kind(&e)),
        }
        // A new session reconnects at once; the same one backs off.
        if session_url(&app).is_some_and(|(_, now)| now == url) {
            sleep(backoff(attempt)).await;
            attempt = attempt.saturating_add(1);
        }
    }
}

/// Makes the session reachable by `SendString`, then learns its id.
async fn on_connect(app: tauri::AppHandle, client: EmbyClient) {
    if let Err(e) = client.post_capabilities().await {
        log::warn!("session socket: capabilities refused ({})", e.kind());
        return;
    }
    let Some(user) = app.state::<EmbyService>().current_user() else {
        return;
    };
    match client.sessions(&user.user_id).await {
        Ok(list) => {
            let own = own_session_id(&list, client.device_id());
            if own.is_none() {
                log::warn!("session socket: own session not listed");
            }
            app.state::<SyncHandle>().own_session(own);
        }
        Err(e) => log::warn!("session socket: sessions unavailable ({})", e.kind()),
    }
}

fn error_kind(e: &tokio_tungstenite::tungstenite::Error) -> &'static str {
    use tokio_tungstenite::tungstenite::Error;
    match e {
        Error::Io(_) => "réseau",
        Error::Tls(_) => "TLS",
        Error::Http(r) if r.status().as_u16() == 401 => "jeton refusé",
        Error::Http(_) => "réponse HTTP",
        Error::Url(_) => "adresse",
        _ => "protocole",
    }
}

async fn listen<S>(app: &tauri::AppHandle, socket: S, url: &str)
where
    S: futures_util::Stream<Item = Result<Message, tokio_tungstenite::tungstenite::Error>>
        + futures_util::Sink<Message>
        + Unpin,
{
    let (mut tx, mut rx) = socket.split();
    let mut keep_alive = tokio::time::interval(KEEP_ALIVE);
    let mut session_check = tokio::time::interval(SESSION_CHECK);
    let events = app.state::<LibraryEvents>();
    // Set by a library change: the event goes out once nothing more came.
    let mut due: Option<Instant> = None;
    loop {
        tokio::select! {
            frame = rx.next() => match frame {
                Some(Ok(Message::Text(text))) => {
                    if let Some(sync) = send_string_of(text.as_str()) {
                        app.state::<SyncHandle>().incoming(sync);
                    } else if is_library_change(text.as_str())
                        && events.home_shown.load(Ordering::Relaxed)
                    {
                        due = Some(Instant::now() + SETTLE);
                    }
                }
                Some(Ok(Message::Close(_))) | Some(Err(_)) | None => break,
                Some(Ok(_)) => {}
            },
            _ = keep_alive.tick() => {
                let alive = Message::text(r#"{"MessageType":"KeepAlive"}"#);
                if tx.send(alive).await.is_err() {
                    break;
                }
            }
            _ = session_check.tick() => {
                // Signed out or another session: this socket is no longer ours.
                if session_url(app).is_none_or(|(_, now)| now != url) {
                    let _ = tx.send(Message::Close(None)).await;
                    break;
                }
            }
            _ = sleep_until(due.unwrap_or_else(Instant::now)), if due.is_some() => {
                due = None;
                let _ = app.emit(LIBRARY_CHANGED_EVENT, ());
            }
        }
    }
    // A change seen just before the socket dropped is still announced.
    if due.is_some() {
        let _ = app.emit(LIBRARY_CHANGED_EVENT, ());
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn added_items_are_a_change() {
        let text = r#"{"MessageType":"LibraryChanged","Data":{"ItemsAdded":["1","2"],"ItemsUpdated":[],"ItemsRemoved":[],"FoldersAddedTo":["9"]}}"#;
        assert!(is_library_change(text));
    }

    #[test]
    fn updated_or_removed_items_are_a_change() {
        assert!(is_library_change(
            r#"{"MessageType":"LibraryChanged","Data":{"ItemsUpdated":["1"]}}"#
        ));
        assert!(is_library_change(
            r#"{"MessageType":"LibraryChanged","Data":{"ItemsRemoved":["1"]}}"#
        ));
    }

    #[test]
    fn empty_change_is_ignored() {
        assert!(!is_library_change(
            r#"{"MessageType":"LibraryChanged","Data":{"FoldersAddedTo":["9"]}}"#
        ));
        assert!(!is_library_change(r#"{"MessageType":"LibraryChanged"}"#));
    }

    #[test]
    fn other_messages_are_ignored() {
        // Frame as sent by Emby 4.10.2 on a favourite change (2026-11-13).
        let user_data = r#"{"MessageType":"UserDataChanged","MessageId":"582e","Data":{"UserId":"5f82","UserDataList":[{"IsFavorite":true,"ItemId":"68397"}]}}"#;
        assert!(!is_library_change(user_data));
        assert!(!is_library_change("not json"));
        assert!(!is_library_change(
            r#"{"MessageType":"ForceKeepAlive","Data":60}"#
        ));
    }

    fn client(server: &str) -> EmbyClient {
        use super::super::client::{AccessToken, DeviceIdentity};
        use super::super::server_url::ServerUrl;
        let device = DeviceIdentity {
            name: "pc".into(),
            id: "dev-1".into(),
            version: "1.0.0".into(),
        };
        EmbyClient::new(ServerUrl::parse(server).unwrap(), device)
            .unwrap()
            .with_token(AccessToken::new("tok".into()))
    }

    #[test]
    fn socket_url_follows_the_server_scheme() {
        assert_eq!(
            client("http://192.168.1.14:8096")
                .library_socket_url()
                .unwrap(),
            "ws://192.168.1.14:8096/embywebsocket?api_key=tok&deviceId=dev-1"
        );
        assert_eq!(
            client("https://emby.example.com/")
                .library_socket_url()
                .unwrap(),
            "wss://emby.example.com/embywebsocket?api_key=tok&deviceId=dev-1"
        );
    }

    #[test]
    fn socket_url_needs_a_session() {
        use super::super::client::DeviceIdentity;
        use super::super::server_url::ServerUrl;
        let device = DeviceIdentity {
            name: "pc".into(),
            id: "dev-1".into(),
            version: "1.0.0".into(),
        };
        let anonymous = EmbyClient::new(
            ServerUrl::parse("http://192.168.1.14:8096").unwrap(),
            device,
        )
        .unwrap();
        assert!(anonymous.library_socket_url().is_err());
    }

    #[test]
    fn send_string_frames_carry_the_relayed_text() {
        let frame = r#"{"MessageType":"GeneralCommand","Data":{"Name":"SendString","Arguments":{"String":"{\"tv\":\"sync\"}"}}}"#;
        assert_eq!(send_string_of(frame).as_deref(), Some(r#"{"tv":"sync"}"#));
        let other = r#"{"MessageType":"GeneralCommand","Data":{"Name":"DisplayMessage","Arguments":{"String":"x"}}}"#;
        assert_eq!(send_string_of(other), None);
        let bare = r#"{"MessageType":"GeneralCommand","Data":{"Name":"SendString"}}"#;
        assert_eq!(send_string_of(bare), None);
        assert_eq!(
            send_string_of(r#"{"MessageType":"LibraryChanged","Data":{}}"#),
            None
        );
        assert_eq!(send_string_of("not json"), None);
    }

    #[test]
    fn backoff_grows_then_caps() {
        let secs: Vec<u64> = (0..6).map(|a| backoff(a).as_secs()).collect();
        assert_eq!(secs, vec![2, 5, 15, 30, 30, 30]);
    }
}
