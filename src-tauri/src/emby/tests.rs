//! Contract tests against a local fake Emby server. Response shapes mirror what
//! Emby Server 4.10.1.0 returned during the Gate 2 probe (ADR 0004).

use std::io::{BufRead, BufReader, Read, Write};
use std::net::TcpListener;
use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, Ordering};
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
                // A body starting with `JPEG:` is served as an image.
                let (content_type, body) = match body.strip_prefix("JPEG:") {
                    Some(b) => ("image/jpeg", b.to_owned()),
                    None => ("application/json", body),
                };
                let reply = format!(
                    "HTTP/1.1 {status} X\r\nContent-Type: {content_type}\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{body}",
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

/// Library views as returned by Emby 4.10.1.0 (numeric ids), plus types this
/// client does not browse yet.
fn views() -> String {
    r#"{"Items":[
        {"Name":"Anime","Id":"10","Type":"CollectionFolder","CollectionType":"tvshows","ImageTags":{"Primary":"56f6b8b2_6384"}},
        {"Name":"Films","Id":"5","Type":"CollectionFolder","CollectionType":"movies","ImageTags":{}},
        {"Name":"Musique","Id":"7","Type":"CollectionFolder","CollectionType":"music","ImageTags":{}},
        {"Name":"TV en direct","Id":"9","Type":"UserView","CollectionType":"livetv"}
    ],"TotalRecordCount":4}"#
        .into()
}

fn movies_page() -> String {
    r#"{"Items":[
        {"Name":"2 Coeurs","Id":"101","Type":"Movie","ProductionYear":2020,"RunTimeTicks":61033890000,
         "ImageTags":{"Primary":"7b213eb5_6379"},"PrimaryImageAspectRatio":0.6666666666666666,
         "UserData":{"PlaybackPositionTicks":0,"PlayCount":1,"IsFavorite":false,"Played":true}},
        {"Name":"Sans affiche","Id":"102","Type":"Movie","ImageTags":{}},
        {"Name":"Id douteux","Id":"../x","Type":"Movie"}
    ],"TotalRecordCount":707}"#
        .into()
}

/// `emby_like` plus library browsing. `revoked` makes every token call answer 401.
fn browse_like(revoked: Arc<AtomicBool>) -> Route {
    let base = emby_like(204);
    Box::new(move |r| {
        let token_ok = !revoked.load(Ordering::SeqCst)
            && r.auth
                .as_deref()
                .is_some_and(|a| a.contains(&format!("Token=\"{TOKEN}\"")));
        let (path, query) = r.path.split_once('?').unwrap_or((&r.path, ""));
        let items = format!("/emby/Users/{USER_ID}/Items");
        let views_path = format!("/emby/Users/{USER_ID}/Views");
        let sections = format!("/emby/Users/{USER_ID}/HomeSections");
        let section_items = format!("/emby/Users/{USER_ID}/Sections/resume/Items");
        match path {
            p if (p == views_path
                || p == sections
                || p == section_items
                || p.starts_with(&items)
                || p.starts_with("/emby/Items/")
                || p.starts_with("/emby/Shows/")
                || p.starts_with("/emby/Sessions/"))
                && !token_ok =>
            {
                (401, String::new())
            }
            p if p == views_path => (200, views()),
            p if p == items && query.contains("ParentId=5") => (200, movies_page()),
            p if p == items => (200, r#"{"Items":[],"TotalRecordCount":0}"#.into()),
            "/emby/Items/101/Images/Primary" => (200, "JPEG:fake-jpeg-bytes".into()),
            "/emby/Items/102/Images/Primary" => (200, "<html>pas une image</html>".into()),
            p if p == format!("{items}/Resume") => (200, resume_items()),
            p if p == format!("{items}/Latest") && query.contains("ParentId=5") => {
                (200, latest_movies())
            }
            "/emby/Shows/NextUp" if query.contains(&format!("UserId={USER_ID}")) => {
                (200, next_up())
            }
            p if p == sections => (200, home_sections()),
            p if p == section_items => (200, resume_section()),
            p if p == format!("{items}/30207") => (200, movie_detail()),
            p if p == format!("{items}/80473") => (200, series_detail()),
            p if p == format!("{items}/80475") => (200, episode_detail()),
            p if p.starts_with(&format!("{items}/")) => (404, NOT_FOUND_TEXT.into()),
            "/emby/Shows/80473/Seasons" => (200, seasons()),
            "/emby/Shows/80473/Episodes" if query.contains("SeasonId=80474") => {
                (200, episodes_page())
            }
            // Emby trusts SeasonId: a season of another series returns its episodes.
            "/emby/Shows/80473/Episodes" if query.contains("SeasonId=67639") => {
                (200, foreign_episodes())
            }
            p if p.starts_with("/emby/Shows/") => (404, NOT_FOUND_TEXT.into()),
            "/emby/Items/30207/PlaybackInfo" if r.method == "POST" => (200, playback_direct()),
            "/emby/Items/40000/PlaybackInfo" if r.method == "POST" => (200, playback_transcode()),
            p if p.ends_with("/PlaybackInfo") => (404, NOT_FOUND_TEXT.into()),
            "/emby/Sessions/Playing"
            | "/emby/Sessions/Playing/Progress"
            | "/emby/Sessions/Playing/Stopped"
                if r.method == "POST" =>
            {
                (204, String::new())
            }
            _ => base(r),
        }
    })
}

/// PlaybackInfo answer of Emby 4.10.1.0 for a film it lets the client play directly
/// (trimmed): the stream URL carries the token in `api_key`.
fn playback_direct() -> String {
    format!(
        r#"{{"MediaSources":[{{"Chapters":[],"Protocol":"File","Id":"mediasource_30207",
        "Path":"/share/Films/2 Coeurs (2020)/2 Coeurs.mkv","Type":"Default","Container":"mkv",
        "Size":3760231036,"Name":"2 Coeurs","IsRemote":false,"HasMixedProtocols":false,
        "RunTimeTicks":60000000000,"SupportsTranscoding":true,"SupportsDirectStream":true,
        "SupportsDirectPlay":true,"IsInfiniteStream":false,"RequiresOpening":false,
        "RequiresClosing":false,"RequiresLooping":false,"SupportsProbing":false,
        "MediaStreams":[],"Formats":[],"Bitrate":5000000,"RequiredHttpHeaders":{{}},
        "DirectStreamUrl":"/videos/30207/original.mkv?DeviceId=dev&MediaSourceId=mediasource_30207&PlaySessionId=ps1&api_key={TOKEN}",
        "AddApiKeyToDirectStreamUrl":false,"ReadAtNativeFramerate":false,
        "DefaultAudioStreamIndex":1,"DefaultSubtitleStreamIndex":3,"ItemId":"30207",
        "MimeType":"video/x-matroska"}}],"PlaySessionId":"ps1"}}"#
    )
}

/// Same shape when the profile does not cover the file: HLS transcode and reasons.
fn playback_transcode() -> String {
    format!(
        r#"{{"MediaSources":[{{"Protocol":"File","Id":"mediasource_40000","Container":"avi",
        "SupportsTranscoding":true,"SupportsDirectStream":false,"SupportsDirectPlay":false,
        "RequiredHttpHeaders":{{}},
        "TranscodingUrl":"/videos/40000/master.m3u8?DeviceId=dev&MediaSourceId=mediasource_40000&PlaySessionId=ps2&api_key={TOKEN}&VideoCodec=h264&TranscodeReasons=ContainerNotSupported",
        "TranscodingSubProtocol":"hls","TranscodingContainer":"ts","DefaultSubtitleStreamIndex":2,
        "MediaStreams":[{{"Type":"Video","Index":0}},{{"Type":"Subtitle","Index":2,"Codec":"subrip",
        "Language":"fre","DeliveryMethod":"External",
        "DeliveryUrl":"/Videos/40000/mediasource_40000/Subtitles/2/0/Stream.subrip?api_key={TOKEN}"}}]}}],
        "PlaySessionId":"ps2"}}"#
    )
}

/// 404 body of Emby 4.10.1.0 for an unknown item: French text, not JSON.
const NOT_FOUND_TEXT: &str =
    "L'objet demandé ne peut pas être trouvé. Il a peut-être été récemment supprimé du serveur.";

/// Trimmed `/Users/{Id}/Items/30207` answer: real field names and value shapes,
/// including the NAS path, which must never reach the UI.
fn movie_detail() -> String {
    r#"{"Name":"2 Coeurs","OriginalTitle":"2 Hearts","Id":"30207","Type":"Movie",
        "Path":"/share/Films/2 Coeurs (2020)/2 Coeurs.mkv","FileName":"2 Coeurs.mkv",
        "PremiereDate":"2020-10-15T22:00:00.0000000Z","CriticRating":17,"OfficialRating":"PG-13",
        "Overview":"Lorsque la maladie frappe deux personnes opposées…",
        "Taglines":["Découvrez le mystère qui les relie tous."],
        "Genres":["Romance","Drame"],"CommunityRating":6.234,"RunTimeTicks":61033890000,"ProductionYear":2020,
        "People":[{"Name":"Jacob Elordi","Id":"30209","Role":"Chris","Type":"Actor","PrimaryImageTag":"4b8a"},
                  {"Name":"Lance Hool","Id":"30300","Type":"Director"},
                  {"Name":"Scénariste","Id":"30301","Type":"Writer"}],
        "Studios":[{"Name":"Silver Lion Films","Id":30218}],
        "UserData":{"PlaybackPositionTicks":18310167000,"PlayCount":0,"IsFavorite":false,"Played":false},
        "ImageTags":{"Primary":"7b213eb5_6379","Logo":"028272e6_6379"},
        "BackdropImageTags":["2edd4b0e_6379"],
        "MediaSources":[{"Protocol":"File","Id":"mediasource_30207",
          "Path":"/share/Films/2 Coeurs (2020)/2 Coeurs.mkv",
          "Container":"mkv","Size":3760231036,"Name":"2 Hearts (2020)","Bitrate":4928712,"SupportsDirectPlay":true,
          "MediaStreams":[
            {"Codec":"hevc","Type":"Video","Height":1038,"Width":1920,"BitDepth":10,"VideoRange":"HDR 10",
             "ExtendedVideoSubTypeDescription":"HDR 10","Title":"release.name.x265","IsDefault":true},
            {"Codec":"ac3","Language":"fre","Type":"Audio","Channels":6,"IsDefault":true,"DisplayTitle":"French AC3 5.1"},
            {"Codec":"eac3","Language":"eng","Type":"Audio","Channels":6},
            {"Codec":"subrip","Language":"fre","Type":"Subtitle","IsDefault":true,"IsForced":true,"Title":"Forcés"},
            {"Codec":"PGSSUB","Language":"eng","Type":"Subtitle","IsExternal":false},
            {"Codec":"mjpeg","Type":"EmbeddedImage"}]}]}"#
        .into()
}

fn series_detail() -> String {
    r#"{"Name":"2.5 Dimensional Seduction","OriginalTitle":"2.5次元の誘惑","Id":"80473","Type":"Series",
        "Path":"/share/Anime/2.5 Dimensional Seduction","Status":"Ended","EndDate":"2024-12-13T00:00:00.0000000Z",
        "ProductionYear":2024,"RunTimeTicks":14400000000,"Overview":"Masamune Okumura…","Taglines":[],
        "Genres":["Animation"],"CommunityRating":6.9,"OfficialRating":"FR-16",
        "UserData":{"UnplayedItemCount":24,"PlaybackPositionTicks":0,"PlayCount":0,"Played":false},
        "ImageTags":{"Primary":"4b62464b_6385"},"BackdropImageTags":[]}"#
        .into()
}

fn episode_detail() -> String {
    r#"{"Name":"La Nouvelle Recrue","Id":"80475","Type":"Episode","SeriesId":"80473"}"#.into()
}

fn seasons() -> String {
    r#"{"Items":[
        {"Name":"Épisodes spéciaux","Id":"80480","IndexNumber":0,"Type":"Season","SeriesId":"80473",
         "UserData":{"UnplayedItemCount":2,"Played":false}},
        {"Name":"Saison 1","Id":"80474","IndexNumber":1,"Type":"Season","SeriesId":"80473",
         "UserData":{"UnplayedItemCount":0,"Played":true},"ImageTags":{"Primary":"1cc83503_6385"}},
        {"Name":"Saison d'un autre","Id":"67639","IndexNumber":1,"Type":"Season","SeriesId":"67638"}
    ],"TotalRecordCount":3}"#
        .into()
}

fn episodes_page() -> String {
    r#"{"Items":[
        {"Name":"La Nouvelle Recrue","Id":"80475","IndexNumber":1,"ParentIndexNumber":1,"Type":"Episode",
         "SeriesId":"80473","SeasonId":"80474","RunTimeTicks":14400640000,"Overview":"Seul membre du club…",
         "PremiereDate":"2024-07-04T22:00:00.0000000Z","ImageTags":{"Primary":"9e617fa1_6385"},
         "UserData":{"PlaybackPositionTicks":7200320000,"Played":false}},
        {"Name":"Double","Id":"80476","IndexNumber":2,"IndexNumberEnd":3,"ParentIndexNumber":1,"Type":"Episode",
         "SeriesId":"80473","SeasonId":"80474","ImageTags":{},"UserData":{"Played":true}},
        {"Name":"Date to Date","Id":"80490","IndexNumber":1,"ParentIndexNumber":0,"Type":"Episode",
         "SeriesId":"80473","SeasonId":"80480","SeasonName":"Épisodes spéciaux"}
    ],"TotalRecordCount":24}"#
        .into()
}

fn foreign_episodes() -> String {
    r#"{"Items":[{"Name":"Autre","Id":"67640","Type":"Episode","SeriesId":"67638","SeasonId":"67639"}],
        "TotalRecordCount":13}"#
        .into()
}

/// `/Users/{Id}/Items/Resume` (trimmed, field shapes of Emby 4.10.1.0): a film
/// with a thumb, an episode with its still, one with only the series thumb, and
/// items the home rows do not play.
fn resume_items() -> String {
    r#"{"Items":[
        {"Name":"2 Coeurs","Id":"30207","Type":"Movie","RunTimeTicks":61033890000,
         "ImageTags":{"Primary":"7b213eb5_6379","Thumb":"bbcac8ec_6379"},
         "BackdropImageTags":["2edd4b0e_6379"],
         "UserData":{"PlaybackPositionTicks":18310000000,"PlayedPercentage":30.0,"Played":false}},
        {"Name":" Le Professeur ","Id":"70227","Type":"Episode","IndexNumber":2,"ParentIndexNumber":5,
         "SeriesName":"La casa de papel","SeriesId":"70185","RunTimeTicks":30000000000,
         "ImageTags":{"Primary":"b31c7276_6384"},"BackdropImageTags":[],
         "ParentThumbItemId":"70185","ParentThumbImageTag":"724ef532_6384",
         "ParentBackdropItemId":"70185","ParentBackdropImageTags":["6da1a59e_6384"],
         "UserData":{"PlaybackPositionTicks":6000000000,"Played":false}},
        {"Name":"Sans image","Id":"70228","Type":"Episode","IndexNumber":3,"ParentIndexNumber":5,
         "SeriesName":"La casa de papel","SeriesId":"70185","ImageTags":{},
         "ParentThumbItemId":"70185","ParentThumbImageTag":"724ef532_6384",
         "UserData":{"PlaybackPositionTicks":600000000,"Played":false}},
        {"Name":"Chanson","Id":"5000","Type":"Audio","UserData":{"PlaybackPositionTicks":10}},
        {"Name":"Id douteux","Id":"../x","Type":"Movie"}
    ],"TotalRecordCount":5}"#
        .into()
}

/// `/Shows/NextUp` (trimmed): an unstarted episode with only the series backdrop.
fn next_up() -> String {
    r#"{"Items":[
        {"Name":"Épisode 4","Id":"70229","Type":"Episode","IndexNumber":4,"ParentIndexNumber":5,
         "SeriesName":"La casa de papel","SeriesId":"70185","RunTimeTicks":30000000000,
         "ImageTags":{},"ParentBackdropItemId":"70185","ParentBackdropImageTags":["6da1a59e_6384"],
         "UserData":{"PlaybackPositionTicks":0,"Played":false}}
    ],"TotalRecordCount":1}"#
        .into()
}

/// `/Users/{Id}/HomeSections` (trimmed): a bare array; "resume" includes Next Up.
fn home_sections() -> String {
    r#"[
        {"Name":"Mes Médias","Id":"smalllibrarytiles","SectionType":"userviews","IncludeNextUpInResume":true},
        {"Name":"Reprendre la lecture","Id":"resume","SectionType":"resume","IncludeNextUpInResume":true},
        {"Name":"Films : Derniers ajouts","Id":"latestmedia_5","SectionType":"latestmedia","ParentId":"5"}
    ]"#
    .into()
}

/// `/Users/{Id}/Sections/resume/Items` (trimmed): what Emby 4.10.1.0 lists when
/// `/Shows/NextUp` is empty — films in progress and next episodes together.
fn resume_section() -> String {
    r#"{"Items":[
        {"Name":"Runner","Id":"103617","Type":"Movie","RunTimeTicks":58680640000,"ImageTags":{},
         "UserData":{"PlaybackPositionTicks":6000000000,"Played":false}},
        {"Name":"01h00 - 02h00","Id":"44443","Type":"Episode","IndexNumber":2,"ParentIndexNumber":1,
         "SeriesName":"24 Heures chrono","SeriesId":"44440","RunTimeTicks":24705280000,
         "ImageTags":{"Primary":"df10f940_6381"},"ParentThumbItemId":"44440","ParentThumbImageTag":"858f5468_6381",
         "UserData":{"PlaybackPositionTicks":0,"PlayCount":0,"Played":false}},
        {"Name":"Épisode 4","Id":"70229","Type":"Episode","IndexNumber":4,"ParentIndexNumber":5,
         "SeriesName":"La casa de papel","SeriesId":"70185","ImageTags":{},
         "UserData":{"PlaybackPositionTicks":0,"Played":false}}
    ],"TotalRecordCount":3}"#
        .into()
}

/// `/Users/{Id}/Items/Latest?ParentId=5`: a bare array, newest first.
fn latest_movies() -> String {
    r#"[
        {"Name":"Runner","Id":"103617","Type":"Movie","ProductionYear":2026,"RunTimeTicks":58680640000,
         "ImageTags":{"Primary":"aa11bb22_6390"},"PrimaryImageAspectRatio":0.6666666666666666,
         "UserData":{"PlaybackPositionTicks":0,"Played":false}},
        {"Name":"Coffret","Id":"103618","Type":"BoxSet","ImageTags":{}}
    ]"#
    .into()
}

fn signed_in_browser() -> (FakeEmby, Fixture, Arc<AtomicBool>) {
    let revoked = Arc::new(AtomicBool::new(false));
    let server = FakeEmby::start(browse_like(Arc::clone(&revoked)));
    let fx = fixture();
    run(fx.service.sign_in(&server.url, "testeur", PASSWORD)).unwrap();
    (server, fx, revoked)
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

#[test]
fn libraries_keep_films_and_series_with_local_image_urls() {
    let (server, fx, _) = signed_in_browser();
    let libs = run(fx.service.libraries()).unwrap();

    let names: Vec<_> = libs.iter().map(|l| l.name.as_str()).collect();
    assert_eq!(names, ["Anime", "Films"]);
    let json = serde_json::to_value(&libs).unwrap();
    assert_eq!(json[0]["kind"], "tvShows");
    assert_eq!(json[1]["kind"], "movies");
    let image = libs[0].image.as_ref().unwrap();
    assert!(
        image
            .src
            .starts_with("http://emby-img.localhost/10/Primary?tag=56f6b8b2_6384&maxWidth="),
        "{}",
        image.src
    );
    assert!(libs[1].image.is_none(), "no tag, no image");

    let req = server.requests().into_iter().last().unwrap();
    assert!(req.auth.unwrap().contains(&format!("Token=\"{TOKEN}\"")));
    // Nothing the UI receives points at the server or carries the token.
    let text = json.to_string();
    assert!(
        !text.contains(&server.url) && !text.contains(TOKEN),
        "{text}"
    );
}

#[test]
fn items_page_queries_one_sorted_page_and_maps_cards() {
    let (server, fx, _) = signed_in_browser();
    let page = run(fx.service.items("5", 50, 50)).unwrap();

    let req = server.requests().into_iter().last().unwrap();
    let (path, query) = req.path.split_once('?').unwrap();
    assert_eq!(path, format!("/emby/Users/{USER_ID}/Items"));
    for expected in [
        "ParentId=5",
        "Recursive=true",
        "IncludeItemTypes=Movie",
        "SortBy=SortName",
        "SortOrder=Ascending",
        "StartIndex=50",
        "Limit=50",
        "EnableUserData=true",
    ] {
        assert!(
            query.split('&').any(|p| p == expected),
            "{expected} in {query}"
        );
    }
    assert!(!req.path.contains(TOKEN));

    assert_eq!((page.start_index, page.total), (50, 707));
    assert_eq!(page.next_index, 53, "counts the dropped item too");
    assert_eq!(page.items.len(), 2, "the item with an unsafe id is dropped");
    let movie = &page.items[0];
    assert_eq!(movie.name, "2 Coeurs");
    assert_eq!(movie.year, Some(2020));
    assert_eq!(movie.runtime_minutes, Some(101));
    assert!(movie.played);
    assert!(movie.poster.as_ref().unwrap().srcset.contains(" 480w"));
    assert!(page.items[1].poster.is_none());
}

#[test]
fn items_refuse_unknown_library_and_clamp_page_size() {
    let (server, fx, _) = signed_in_browser();
    // Emby would answer an unknown ParentId with the whole server: never send one.
    let err = run(fx.service.items("999", 0, 50)).unwrap_err();
    assert!(matches!(err, EmbyError::NotFound), "{err:?}");
    assert!(server.requests().iter().all(|r| !r.path.contains("/Items")));

    run(fx.service.items("10", 0, 100_000)).unwrap();
    let req = server.requests().into_iter().last().unwrap();
    assert!(req.path.contains("Limit=200"), "{}", req.path);
    assert!(req.path.contains("IncludeItemTypes=Series"), "{}", req.path);
}

#[test]
fn token_revoked_while_browsing_ends_the_session() {
    let (_server, fx, revoked) = signed_in_browser();
    revoked.store(true, Ordering::SeqCst);

    let err = run(fx.service.libraries()).unwrap_err();
    assert!(matches!(err, EmbyError::SessionExpired), "{err:?}");
    assert!(stored_token(&fx).is_none());
    assert!(fx.service.current_user().is_none());
    let fx = reopen(fx.store, fx.path);
    assert!(run(fx.service.restore()).unwrap().is_none());
}

#[test]
fn artwork_is_fetched_with_auth_and_checked() {
    use super::images::ImageRequest;
    let (server, fx, _) = signed_in_browser();
    let req = ImageRequest::parse("/101/Primary", Some("tag=7b213eb5_6379&maxWidth=360")).unwrap();
    let (content_type, bytes) = run(fx.service.image(&req)).unwrap();
    assert_eq!(content_type, "image/jpeg");
    assert_eq!(bytes, b"fake-jpeg-bytes");
    let sent = server.requests().into_iter().last().unwrap();
    assert!(
        sent.path.starts_with("/emby/Items/101/Images/Primary?"),
        "{}",
        sent.path
    );
    assert!(sent.path.contains("maxWidth=360") && sent.path.contains("tag=7b213eb5_6379"));
    assert!(sent.auth.unwrap().contains(&format!("Token=\"{TOKEN}\"")));

    let not_image = ImageRequest::parse("/102/Primary", Some("tag=x&maxWidth=360")).unwrap();
    let err = run(fx.service.image(&not_image)).unwrap_err();
    assert!(matches!(err, EmbyError::InvalidResponse(_)), "{err:?}");

    run(fx.service.sign_out()).unwrap();
    let err = run(fx.service.image(&req)).unwrap_err();
    assert!(matches!(err, EmbyError::NotSignedIn), "{err:?}");
}

#[test]
fn movie_detail_maps_metadata_and_tech_info_without_the_nas_path() {
    let (server, fx, _) = signed_in_browser();
    let d = run(fx.service.item_detail("30207")).unwrap();

    let req = server.requests().into_iter().last().unwrap();
    assert_eq!(req.path, format!("/emby/Users/{USER_ID}/Items/30207"));
    let json = serde_json::to_string(&d).unwrap();
    assert!(!json.contains("/share"), "NAS path leaked: {json}");
    assert!(!json.contains(&server.url), "server address leaked: {json}");

    assert_eq!(d.kind, super::models::DetailKind::Movie);
    assert_eq!(d.original_title.as_deref(), Some("2 Hearts"));
    assert_eq!(
        d.tagline.as_deref(),
        Some("Découvrez le mystère qui les relie tous.")
    );
    assert_eq!(d.community_rating, Some(6.2));
    assert_eq!(d.critic_rating, Some(17));
    assert_eq!(d.runtime_minutes, Some(101));
    assert_eq!(d.directors, ["Lance Hool"]);
    assert_eq!(d.cast.len(), 1);
    assert_eq!(d.cast[0].role.as_deref(), Some("Chris"));
    assert_eq!(d.studios, ["Silver Lion Films"]);
    let resume = d.resume.unwrap();
    assert_eq!(resume.position_seconds, 1831);
    assert!((resume.fraction.unwrap() - 0.3).abs() < 0.001);
    assert!(
        d.backdrop
            .unwrap()
            .src
            .contains("/30207/Backdrop?tag=2edd4b0e_6379")
    );
    assert!(d.logo.unwrap().src.contains("/30207/Logo?"));
    assert_eq!(
        (d.end_year, d.continuing, d.unplayed_count),
        (None, None, None)
    );

    let media = &d.media[0];
    assert_eq!(media.container.as_deref(), Some("mkv"));
    let video = media.video.as_ref().unwrap();
    assert_eq!((video.height, video.bit_depth), (Some(1038), Some(10)));
    assert_eq!(video.range.as_deref(), Some("HDR 10"));
    assert_eq!(media.audio.len(), 2);
    assert_eq!(media.audio[0].language.as_deref(), Some("fre"));
    assert_eq!(media.audio[0].channels, Some(6));
    assert_eq!(
        media.subtitles.len(),
        2,
        "embedded images are not subtitles"
    );
    assert!(media.subtitles[0].is_forced);
}

#[test]
fn series_detail_seasons_and_episodes() {
    let (server, fx, _) = signed_in_browser();
    let d = run(fx.service.item_detail("80473")).unwrap();
    assert_eq!(d.kind, super::models::DetailKind::Series);
    assert_eq!(
        (d.year, d.end_year, d.continuing),
        (Some(2024), Some(2024), Some(false))
    );
    assert_eq!(d.unplayed_count, Some(24));
    assert_eq!(d.runtime_minutes, None, "a series has no single runtime");
    assert!(d.media.is_empty() && d.backdrop.is_none() && d.tagline.is_none());

    let seasons = run(fx.service.seasons("80473")).unwrap();
    let names: Vec<_> = seasons.iter().map(|s| (s.index, s.name.as_str())).collect();
    assert_eq!(
        names,
        [(Some(0), "Épisodes spéciaux"), (Some(1), "Saison 1")],
        "a season of another series is dropped"
    );
    assert!(seasons[1].played);
    let req = server.requests().into_iter().last().unwrap();
    assert!(
        req.path.starts_with("/emby/Shows/80473/Seasons?"),
        "{}",
        req.path
    );
    assert!(req.path.contains(&format!("UserId={USER_ID}")));

    let page = run(fx.service.episodes("80473", "80474", 0, 500)).unwrap();
    let req = server.requests().into_iter().last().unwrap();
    let (path, query) = req.path.split_once('?').unwrap();
    assert_eq!(path, "/emby/Shows/80473/Episodes");
    for expected in [
        "SeasonId=80474",
        "StartIndex=0",
        "Limit=200",
        "Fields=Overview",
    ] {
        assert!(
            query.split('&').any(|p| p == expected),
            "{expected} in {query}"
        );
    }
    assert!(!req.path.contains(TOKEN));
    assert_eq!((page.total, page.next_index), (24, 3));
    let first = &page.items[0];
    assert_eq!((first.season_index, first.index), (Some(1), Some(1)));
    assert_eq!(first.runtime_minutes, Some(24));
    assert_eq!(first.resume.as_ref().unwrap().position_seconds, 720);
    assert!(first.thumb.as_ref().unwrap().srcset.contains(" 640w"));
    let double = &page.items[1];
    assert_eq!((double.index, double.index_end), (Some(2), Some(3)));
    assert!(double.played && double.resume.is_none() && double.thumb.is_none());
    assert_eq!(
        page.items[2].season_index,
        Some(0),
        "a special aired during the season is kept"
    );
}

#[test]
fn detail_refuses_foreign_seasons_unknown_ids_and_other_types() {
    let (server, fx, revoked) = signed_in_browser();
    let err = run(fx.service.episodes("80473", "67639", 0, 50)).unwrap_err();
    assert!(matches!(err, EmbyError::NotFound), "{err:?}");

    let err = run(fx.service.item_detail("999")).unwrap_err();
    assert!(matches!(err, EmbyError::NotFound), "{err:?}");
    let err = run(fx.service.seasons("999")).unwrap_err();
    assert!(matches!(err, EmbyError::NotFound), "{err:?}");
    let err = run(fx.service.item_detail("80475")).unwrap_err();
    assert!(matches!(err, EmbyError::InvalidResponse(_)), "{err:?}");

    let before = server.requests().len();
    for bad in ["../Users", "1?x=2", ""] {
        assert!(run(fx.service.item_detail(bad)).is_err());
        assert!(run(fx.service.episodes("80473", bad, 0, 10)).is_err());
    }
    assert_eq!(
        server.requests().len(),
        before,
        "unsafe ids never reach the server"
    );

    revoked.store(true, Ordering::SeqCst);
    let err = run(fx.service.seasons("80473")).unwrap_err();
    assert!(matches!(err, EmbyError::SessionExpired), "{err:?}");
    assert!(stored_token(&fx).is_none());
}

#[test]
fn playback_negotiates_direct_play_with_the_token_in_a_header_only() {
    use super::models::PlayMethod;
    let (server, fx, _) = signed_in_browser();

    let (plan, headers) = run(fx.service.prepare_playback("30207", false)).unwrap();
    assert_eq!(plan.method, PlayMethod::DirectPlay);
    assert_eq!(
        plan.url,
        format!(
            "{}/emby/videos/30207/original.mkv?DeviceId=dev&MediaSourceId=mediasource_30207&PlaySessionId=ps1",
            server.url
        )
    );
    assert!(!plan.url.contains(TOKEN));
    assert_eq!(headers, vec![("X-Emby-Token".to_owned(), TOKEN.to_owned())]);
    assert_eq!(plan.media_source_id, "mediasource_30207");
    assert_eq!(plan.play_session_id.as_deref(), Some("ps1"));

    let req = server.requests().pop().unwrap();
    assert_eq!(req.method, "POST");
    assert_eq!(
        req.path,
        format!("/emby/Items/30207/PlaybackInfo?UserId={USER_ID}")
    );
    assert!(req.auth.unwrap().contains(&format!("Token=\"{TOKEN}\"")));
    let body: serde_json::Value = serde_json::from_str(&req.body).unwrap();
    assert_eq!(body["UserId"], USER_ID);
    assert_eq!(body["IsPlayback"], true);
    assert_eq!(body["AutoOpenLiveStream"], false);
    assert!(body["MaxStreamingBitrate"].as_u64().unwrap() > 0);
    assert_eq!(
        body["DeviceProfile"]["DirectPlayProfiles"][0]["Type"],
        "Video"
    );
    assert!(!req.body.contains(TOKEN));
}

#[test]
fn playback_transcodes_through_hls_under_the_bitrate_limit() {
    use super::models::PlayMethod;
    let server = FakeEmby::start(browse_like(Arc::new(AtomicBool::new(false))));
    let fx = fixture();
    let service = fx.service.with_max_streaming_bitrate(3_000_000);
    run(service.sign_in(&server.url, "testeur", PASSWORD)).unwrap();

    let (plan, headers) = run(service.prepare_playback("40000", false)).unwrap();
    assert_eq!(plan.method, PlayMethod::Transcode);
    assert_eq!(
        plan.url,
        format!(
            "{}/emby/videos/40000/master.m3u8?DeviceId=dev&MediaSourceId=mediasource_40000&PlaySessionId=ps2&VideoCodec=h264&TranscodeReasons=ContainerNotSupported",
            server.url
        )
    );
    assert_eq!(plan.container.as_deref(), Some("ts"));
    assert_eq!(
        plan.transcode_reasons.as_deref(),
        Some("conteneur non pris en charge")
    );
    assert_eq!(plan.subtitles.len(), 1);
    assert_eq!(
        plan.subtitles[0].source,
        format!(
            "{}/emby/Videos/40000/mediasource_40000/Subtitles/2/0/Stream.subrip",
            server.url
        )
    );
    assert!(plan.subtitles[0].selected);
    assert!(!format!("{plan:?}").contains(TOKEN));
    assert_eq!(headers, vec![("X-Emby-Token".to_owned(), TOKEN.to_owned())]);

    let body: serde_json::Value =
        serde_json::from_str(&server.requests().pop().unwrap().body).unwrap();
    assert_eq!(body["MaxStreamingBitrate"], 3_000_000);
    assert_eq!(body["DeviceProfile"]["MaxStreamingBitrate"], 3_000_000);
}

#[test]
fn playback_reports_unknown_items_and_bad_ids() {
    let (server, fx, _) = signed_in_browser();
    let err = run(fx.service.prepare_playback("99999", false)).unwrap_err();
    assert!(matches!(err, EmbyError::NotFound), "{err:?}");

    let before = server.requests().len();
    for id in ["../Users", "1?x=2", ""] {
        assert!(run(fx.service.prepare_playback(id, false)).is_err());
    }
    assert_eq!(server.requests().len(), before);
}

#[test]
fn playback_with_a_revoked_token_ends_the_session() {
    let (_server, fx, revoked) = signed_in_browser();
    revoked.store(true, Ordering::SeqCst);
    let err = run(fx.service.prepare_playback("30207", false)).unwrap_err();
    assert!(matches!(err, EmbyError::SessionExpired), "{err:?}");
    assert!(fx.service.current_user().is_none());
}

#[test]
fn resume_starts_at_the_server_position_within_the_runtime() {
    let (server, fx, _) = signed_in_browser();
    let (plan, _) = run(fx.service.prepare_playback("30207", true)).unwrap();
    // Film detail: PlaybackPositionTicks 18310167000, source runtime 60000000000.
    assert_eq!(plan.start_ticks, 18_310_167_000);
    assert_eq!(plan.run_time_ticks, Some(60_000_000_000));
    let paths: Vec<String> = server.requests().into_iter().map(|r| r.path).collect();
    assert!(paths.contains(&format!("/emby/Users/{USER_ID}/Items/30207")));

    let before = server.requests().len();
    let (plan, _) = run(fx.service.prepare_playback("30207", false)).unwrap();
    assert_eq!(plan.start_ticks, 0);
    assert_eq!(
        server.requests().len(),
        before + 1,
        "no item read without resume"
    );
}

#[test]
fn playback_reports_post_typed_bodies_with_auth_in_the_header() {
    use super::models::PlaybackReportBody;
    use super::reporting::{Report, ReportKind};
    let (server, fx, revoked) = signed_in_browser();
    let report = |kind, event_name, failed| Report {
        kind,
        body: PlaybackReportBody {
            item_id: "30207".into(),
            media_source_id: "mediasource_30207".into(),
            play_session_id: Some("ps1".into()),
            position_ticks: 18_310_167_000,
            run_time_ticks: Some(60_000_000_000),
            is_paused: (kind != ReportKind::Stopped).then_some(false),
            can_seek: (kind != ReportKind::Stopped).then_some(true),
            play_method: (kind != ReportKind::Stopped).then_some("DirectPlay"),
            event_name,
            failed,
        },
    };
    for r in [
        report(ReportKind::Start, None, None),
        report(ReportKind::Progress, Some("TimeUpdate"), None),
        report(ReportKind::Stopped, None, Some(false)),
    ] {
        run(fx.service.report_playback(&r)).unwrap();
    }
    let sent: Vec<Recorded> = server
        .requests()
        .into_iter()
        .filter(|r| r.path.starts_with("/emby/Sessions/"))
        .collect();
    let paths: Vec<&str> = sent.iter().map(|r| r.path.as_str()).collect();
    assert_eq!(
        paths,
        [
            "/emby/Sessions/Playing",
            "/emby/Sessions/Playing/Progress",
            "/emby/Sessions/Playing/Stopped"
        ]
    );
    for r in &sent {
        assert_eq!(r.method, "POST");
        assert!(
            r.auth
                .as_deref()
                .unwrap()
                .contains(&format!("Token=\"{TOKEN}\""))
        );
        assert!(!r.path.contains(TOKEN) && !r.body.contains(TOKEN));
    }
    let progress: serde_json::Value = serde_json::from_str(&sent[1].body).unwrap();
    assert_eq!(progress["ItemId"], "30207");
    assert_eq!(progress["PlaySessionId"], "ps1");
    assert_eq!(progress["PositionTicks"], 18_310_167_000_i64);
    assert_eq!(progress["EventName"], "TimeUpdate");
    assert_eq!(progress["PlayMethod"], "DirectPlay");
    let stopped: serde_json::Value = serde_json::from_str(&sent[2].body).unwrap();
    assert_eq!(stopped["Failed"], false);
    assert!(stopped.get("IsPaused").is_none());

    // A bad id never reaches the server; a revoked token ends the session.
    let mut bad = report(ReportKind::Progress, None, None);
    bad.body.item_id = "../Users".into();
    assert!(run(fx.service.report_playback(&bad)).is_err());
    revoked.store(true, Ordering::SeqCst);
    let err = run(fx
        .service
        .report_playback(&report(ReportKind::Stopped, None, None)))
    .unwrap_err();
    assert!(matches!(err, EmbyError::SessionExpired), "{err:?}");
    assert!(fx.service.current_user().is_none());
}

#[test]
fn continue_watching_maps_films_and_episodes_with_wide_artwork() {
    let (server, fx, revoked) = signed_in_browser();
    let rows = run(fx.service.continue_watching()).unwrap();

    let req = server.requests().into_iter().last().unwrap();
    let (path, query) = req.path.split_once('?').unwrap();
    assert_eq!(path, format!("/emby/Users/{USER_ID}/Items/Resume"));
    for expected in [
        "Limit=16",
        "MediaTypes=Video",
        "IncludeItemTypes=Movie%2CEpisode",
        "EnableUserData=true",
        "EnableImageTypes=Primary%2CBackdrop%2CThumb",
    ] {
        assert!(
            query.split('&').any(|p| p == expected),
            "{expected} in {query}"
        );
    }

    let ids: Vec<_> = rows.iter().map(|r| r.id.as_str()).collect();
    assert_eq!(
        ids,
        ["30207", "70227", "70228"],
        "audio and unsafe id dropped"
    );
    let film = serde_json::to_value(&rows[0]).unwrap();
    assert_eq!(film["kind"], "movie");
    assert!(film["seriesName"].is_null());
    assert_eq!(film["runtimeMinutes"], 101);
    assert_eq!(film["resume"]["positionSeconds"], 1831);
    assert!(
        film["image"]["src"]
            .as_str()
            .unwrap()
            .starts_with("http://emby-img.localhost/30207/Thumb?tag=bbcac8ec_6379"),
        "{film}"
    );
    let episode = &rows[1];
    assert_eq!(episode.name, "Le Professeur");
    assert_eq!(episode.series_name.as_deref(), Some("La casa de papel"));
    assert_eq!((episode.season_index, episode.index), (Some(5), Some(2)));
    assert_eq!(episode.resume.as_ref().unwrap().position_seconds, 600);
    assert!(
        episode
            .image
            .as_ref()
            .unwrap()
            .src
            .contains("/70227/Primary?"),
        "own still first"
    );
    assert!(
        rows[2]
            .image
            .as_ref()
            .unwrap()
            .src
            .contains("/70185/Thumb?tag=724ef532_6384"),
        "series thumb when the episode has no still"
    );

    let text = serde_json::to_string(&rows).unwrap();
    assert!(!text.contains(&server.url) && !text.contains(TOKEN));
    revoked.store(true, Ordering::SeqCst);
    assert!(matches!(
        run(fx.service.continue_watching()).unwrap_err(),
        EmbyError::SessionExpired
    ));
}

#[test]
fn next_up_lists_episodes_with_the_series_backdrop_as_fallback() {
    let (server, fx, revoked) = signed_in_browser();
    let rows = run(fx.service.next_up()).unwrap();
    let paths: Vec<_> = server.requests().into_iter().map(|r| r.path).collect();
    assert!(paths.iter().any(|p| p.starts_with("/emby/Shows/NextUp?")));
    assert!(paths.iter().all(|p| !p.contains(TOKEN)));
    let section = paths
        .iter()
        .find(|p| p.starts_with(&format!("/emby/Users/{USER_ID}/Sections/resume/Items?")))
        .expect("the resume home section is read");
    assert!(section.contains("Limit=16"), "{section}");
    // `/Shows/NextUp` first, then what only the resume section lists: the film in
    // progress and the duplicate are left out.
    let ids: Vec<_> = rows.iter().map(|r| r.id.as_str()).collect();
    assert_eq!(ids, ["70229", "44443"]);
    assert_eq!(rows[1].series_name.as_deref(), Some("24 Heures chrono"));
    assert!(
        rows[1]
            .image
            .as_ref()
            .unwrap()
            .src
            .contains("/44443/Primary?")
    );
    revoked.store(true, Ordering::SeqCst);
    assert!(matches!(
        run(fx.service.next_up()).unwrap_err(),
        EmbyError::SessionExpired
    ));
}

#[test]
fn next_up_without_home_sections_keeps_shows_next_up() {
    // A server without home sections answers 404: the row still comes from
    // `/Shows/NextUp`.
    let server = FakeEmby::start(Box::new({
        let inner = browse_like(Arc::new(AtomicBool::new(false)));
        move |r| {
            if r.path.contains("/HomeSections") {
                (404, NOT_FOUND_TEXT.into())
            } else {
                inner(r)
            }
        }
    }));
    let fx = fixture();
    run(fx.service.sign_in(&server.url, "testeur", PASSWORD)).unwrap();
    let rows = run(fx.service.next_up()).unwrap();
    assert_eq!(rows.len(), 1);
    assert_eq!(rows[0].id, "70229");
    assert!(
        server
            .requests()
            .iter()
            .all(|r| !r.path.contains("/Sections/")),
        "no section read without the list"
    );
}

#[test]
fn next_up_episode_from_the_shows_endpoint() {
    let (_server, fx, _) = signed_in_browser();
    let rows: Vec<_> = run(fx.service.next_up())
        .unwrap()
        .into_iter()
        .filter(|r| r.id == "70229")
        .collect();
    assert_eq!(rows.len(), 1);
    assert!(rows[0].resume.is_none());
    assert_eq!(rows[0].runtime_minutes, Some(50));
    assert!(
        rows[0]
            .image
            .as_ref()
            .unwrap()
            .src
            .contains("/70185/Backdrop?tag=6da1a59e_6384"),
        "{:?}",
        rows[0].image
    );
}

#[test]
fn latest_keeps_the_library_kind_and_refuses_unknown_libraries() {
    let (server, fx, _) = signed_in_browser();
    let items = run(fx.service.latest("5")).unwrap();
    let req = server.requests().into_iter().last().unwrap();
    let (path, query) = req.path.split_once('?').unwrap();
    assert_eq!(path, format!("/emby/Users/{USER_ID}/Items/Latest"));
    assert!(query.split('&').any(|p| p == "ParentId=5"), "{query}");
    assert!(query.split('&').any(|p| p == "Limit=16"), "{query}");
    let names: Vec<_> = items.iter().map(|i| i.name.as_str()).collect();
    assert_eq!(names, ["Runner"], "the box set is not a film");
    assert_eq!(items[0].year, Some(2026));

    // Emby answers 500 to an unknown ParentId: such ids never reach it.
    let before = server.requests().len();
    assert!(matches!(
        run(fx.service.latest("999")).unwrap_err(),
        EmbyError::NotFound
    ));
    assert!(
        server.requests()[before..]
            .iter()
            .all(|r| !r.path.contains("/Items/Latest")),
        "only the library list is asked again"
    );
}

#[test]
fn state_after_playback_has_watched_status_and_resume_point() {
    let (_server, fx, _) = signed_in_browser();
    let film = run(fx.service.playback_state("30207")).unwrap();
    assert_eq!(film.item_id, "30207");
    assert!(!film.played);
    let resume = film.resume.unwrap();
    assert_eq!(resume.position_seconds, 1831);
    let json = serde_json::to_value(run(fx.service.playback_state("80475")).unwrap()).unwrap();
    assert_eq!(json["itemId"], "80475");
    assert!(json.get("played").is_some() && json.get("resume").is_some());
    assert!(matches!(
        run(fx.service.playback_state("99999")).unwrap_err(),
        EmbyError::NotFound
    ));
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

/// Real server browsing, on demand only (same variables as above): libraries,
/// first and last page of each, one poster, then sign-out.
#[test]
#[ignore = "needs a real Emby server and credentials in environment variables"]
fn real_server_libraries_and_pages() {
    use super::images::ImageRequest;
    let var = |k: &str| std::env::var(k).unwrap_or_else(|_| panic!("{k} manquant"));
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
    run(fx.service.sign_in(
        &var("VIEWER_EMBY_URL"),
        &var("VIEWER_EMBY_USER"),
        &var("VIEWER_EMBY_PASSWORD"),
    ))
    .unwrap();

    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let libs = run(fx.service.libraries()).unwrap();
        assert!(!libs.is_empty());
        for lib in &libs {
            let started = std::time::Instant::now();
            let first = run(fx.service.items(&lib.id, 0, 60)).unwrap();
            let elapsed = started.elapsed();
            let last_start = first.total.saturating_sub(1) / 60 * 60;
            let last = run(fx.service.items(&lib.id, last_start, 60)).unwrap();
            println!(
                "{:?} {}: total={} first={} ({} ms) last page start={} n={} posters={}/{}",
                lib.kind,
                lib.name,
                first.total,
                first.items.len(),
                elapsed.as_millis(),
                last_start,
                last.items.len(),
                first.items.iter().filter(|i| i.poster.is_some()).count(),
                first.items.len()
            );
            assert_eq!(last.items.len() as u32, first.total - last_start);
        }
        let poster = libs
            .iter()
            .find_map(|l| {
                run(fx.service.items(&l.id, 0, 10))
                    .unwrap()
                    .items
                    .into_iter()
                    .find_map(|i| i.poster)
            })
            .expect("at least one poster");
        let url = poster
            .src
            .strip_prefix("http://emby-img.localhost")
            .unwrap();
        let (path, query) = url.split_once('?').unwrap();
        let req = ImageRequest::parse(path, Some(query)).unwrap();
        let (content_type, bytes) = run(fx.service.image(&req)).unwrap();
        println!("poster: {content_type}, {} bytes", bytes.len());
        assert!(content_type.starts_with("image/"));
    }));
    run(fx.service.sign_out()).unwrap();
    if let Err(e) = result {
        std::panic::resume_unwind(e);
    }
}

/// Real server details, on demand only (same variables as above): detail pages of
/// the first `VIEWER_DETAIL_SAMPLE` (default 40) items of each library, then seasons
/// and the first page of episodes of every sampled series, and one backdrop.
#[test]
#[ignore = "needs a real Emby server and credentials in environment variables"]
fn real_server_details_seasons_episodes() {
    use super::images::ImageRequest;
    use super::models::DetailKind;
    let var = |k: &str| std::env::var(k).unwrap_or_else(|_| panic!("{k} manquant"));
    let sample: u32 = std::env::var("VIEWER_DETAIL_SAMPLE")
        .ok()
        .and_then(|v| v.parse().ok())
        .unwrap_or(40);
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
    run(fx.service.sign_in(
        &var("VIEWER_EMBY_URL"),
        &var("VIEWER_EMBY_USER"),
        &var("VIEWER_EMBY_PASSWORD"),
    ))
    .unwrap();

    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let mut backdrop = None;
        let (mut details, mut seasons_seen, mut episodes_seen) = (0, 0, 0);
        let mut slowest = std::time::Duration::ZERO;
        for lib in run(fx.service.libraries()).unwrap() {
            let page = run(fx.service.items(&lib.id, 0, sample)).unwrap();
            for item in page.items {
                let started = std::time::Instant::now();
                let d = run(fx.service.item_detail(&item.id))
                    .unwrap_or_else(|e| panic!("detail {} ({}): {e:?}", item.id, item.name));
                slowest = slowest.max(started.elapsed());
                details += 1;
                let json = serde_json::to_string(&d).unwrap();
                assert!(!json.contains("\"Path\"") && !json.contains("/share/"));
                if backdrop.is_none() {
                    backdrop = d.backdrop.clone();
                }
                match d.kind {
                    DetailKind::Movie => assert!(!d.media.is_empty(), "{}: no media", d.name),
                    DetailKind::Series => {
                        let seasons = run(fx.service.seasons(&d.id)).unwrap();
                        seasons_seen += seasons.len();
                        for s in &seasons {
                            let eps = run(fx.service.episodes(&d.id, &s.id, 0, 100))
                                .unwrap_or_else(|e| panic!("episodes {}/{}: {e:?}", d.id, s.id));
                            assert!(eps.items.len() as u32 <= eps.total.max(1));
                            episodes_seen += eps.items.len();
                        }
                    }
                }
            }
        }
        println!(
            "details={details} seasons={seasons_seen} episodes={episodes_seen} slowest detail={} ms",
            slowest.as_millis()
        );
        let backdrop = backdrop.expect("at least one backdrop");
        let url = backdrop
            .src
            .strip_prefix("http://emby-img.localhost")
            .unwrap();
        let (path, query) = url.split_once('?').unwrap();
        let req = ImageRequest::parse(path, Some(query)).unwrap();
        let (content_type, bytes) = run(fx.service.image(&req)).unwrap();
        println!("backdrop: {content_type}, {} bytes", bytes.len());
        assert!(content_type.starts_with("image/"));
    }));
    run(fx.service.sign_out()).unwrap();
    if let Err(e) = result {
        std::panic::resume_unwind(e);
    }
}

/// Real server, on demand: PlaybackInfo for sampled films and first episodes, the
/// method chosen for each, then one stream read with the token in a header (Range,
/// 1 KiB), refused without it. `VIEWER_PLAYBACK_SAMPLE` items per library (20).
#[test]
#[ignore = "needs a real Emby server and credentials in environment variables"]
fn real_server_playback_negotiation() {
    use std::collections::BTreeMap;
    let var = |k: &str| std::env::var(k).unwrap_or_else(|_| panic!("{k} manquant"));
    let sample: u32 = std::env::var("VIEWER_PLAYBACK_SAMPLE")
        .ok()
        .and_then(|v| v.parse().ok())
        .unwrap_or(20);
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
    run(fx.service.sign_in(
        &var("VIEWER_EMBY_URL"),
        &var("VIEWER_EMBY_USER"),
        &var("VIEWER_EMBY_PASSWORD"),
    ))
    .unwrap();

    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let mut methods: BTreeMap<String, u32> = BTreeMap::new();
        let mut first = None;
        let mut slowest = std::time::Duration::ZERO;
        for lib in run(fx.service.libraries()).unwrap() {
            for item in run(fx.service.items(&lib.id, 0, sample)).unwrap().items {
                // A series plays through the first episode of its first season.
                let id = if item.item_type == "Series" {
                    let seasons = run(fx.service.seasons(&item.id)).unwrap();
                    let Some(season) = seasons.iter().find(|s| s.index != Some(0)) else {
                        continue;
                    };
                    let eps = run(fx.service.episodes(&item.id, &season.id, 0, 1)).unwrap();
                    let Some(ep) = eps.items.first() else {
                        continue;
                    };
                    ep.id.clone()
                } else {
                    item.id.clone()
                };
                let started = std::time::Instant::now();
                let outcome = run(fx.service.prepare_playback(&id, false));
                slowest = slowest.max(started.elapsed());
                let key = match &outcome {
                    Ok((plan, headers)) => {
                        let token = &headers.last().unwrap().1;
                        assert!(!plan.url.contains(token.as_str()), "token in URL");
                        assert!(!plan.url.to_ascii_lowercase().contains("api_key"));
                        if first.is_none() {
                            first = Some((plan.clone(), headers.clone()));
                        }
                        format!(
                            "{:?} {}",
                            plan.method,
                            plan.container.as_deref().unwrap_or("?")
                        )
                    }
                    Err(e) => format!("{} ({e})", e.kind()),
                };
                *methods.entry(key).or_default() += 1;
            }
        }
        println!(
            "methods: {methods:?}; slowest PlaybackInfo {} ms",
            slowest.as_millis()
        );

        let (plan, headers) = first.expect("at least one playable item");
        let http = reqwest::Client::new();
        let get = |with_auth: bool| {
            let mut req = http.get(&plan.url).header("Range", "bytes=0-1023");
            if with_auth {
                for (k, v) in &headers {
                    req = req.header(k, v);
                }
            }
            run(async { req.send().await.unwrap() })
        };
        assert_eq!(get(false).status().as_u16(), 401);
        let res = get(true);
        assert_eq!(res.status().as_u16(), 206);
        let content_type = res
            .headers()
            .get("content-type")
            .and_then(|v| v.to_str().ok())
            .unwrap_or_default()
            .to_owned();
        let bytes = run(res.bytes()).unwrap();
        println!(
            "stream: {:?}, {content_type}, {} bytes with the header, 401 without",
            plan.method,
            bytes.len()
        );
        assert_eq!(bytes.len(), 1024);
    }));
    run(fx.service.sign_out()).unwrap();
    if let Err(e) = result {
        std::panic::resume_unwind(e);
    }
}

/// Real server, on demand, **writes to the test account** (authorized 2026-10-28):
/// a film never started is reported started, paused, sought to 20 % and stopped
/// through the tracker; the server must then hold that resume point and hand it to
/// `prepare_playback(…, true)`. Cleanup: a playback stopped at 0 clears it again.
#[test]
#[ignore = "needs a real Emby server and credentials in environment variables"]
fn real_server_playback_reporting_and_resume() {
    use super::models::LibraryKind;
    use super::reporting::{ActivePlayback, Tracker};
    use crate::player::PlayerEvent;
    use std::time::{Duration, Instant};

    let var = |k: &str| std::env::var(k).unwrap_or_else(|_| panic!("{k} manquant"));
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
    run(fx.service.sign_in(
        &var("VIEWER_EMBY_URL"),
        &var("VIEWER_EMBY_USER"),
        &var("VIEWER_EMBY_PASSWORD"),
    ))
    .unwrap();

    // Sends what the tracker produces for `events`, in order, like the reporter task.
    let play = |id: &str, resume: bool, events: &[PlayerEvent]| {
        let (plan, _) = run(fx.service.prepare_playback(id, resume)).unwrap();
        let mut tracker = Tracker::default();
        let mut now = Instant::now();
        let mut reports = tracker.begin(ActivePlayback::new(id, &plan), now);
        for e in events {
            now += Duration::from_secs(1);
            reports.extend(tracker.on_event(e, now));
        }
        for r in &reports {
            run(fx.service.report_playback(r)).unwrap_or_else(|e| panic!("{:?}: {e}", r.kind));
        }
        let sent: Vec<String> = reports
            .iter()
            .map(|r| format!("{:?}{}", r.kind, r.body.event_name.unwrap_or("")))
            .collect();
        (plan, sent)
    };

    let film = run(fx.service.libraries())
        .unwrap()
        .into_iter()
        .filter(|l| l.kind == LibraryKind::Movies)
        .flat_map(|l| run(fx.service.items(&l.id, 0, 60)).unwrap().items)
        .filter(|i| !i.played)
        .find(|i| {
            run(fx.service.item_detail(&i.id))
                .map(|d| d.resume.is_none())
                .unwrap_or(false)
        })
        .expect("a film never started on the test account");
    println!("film: {} ({})", film.name, film.id);

    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let (plan, _) = run(fx.service.prepare_playback(&film.id, false)).unwrap();
        let runtime = plan.run_time_ticks.expect("runtime") as f64 / 1e7;
        let target = (runtime * 0.2).round();
        let (_, sent) = play(
            &film.id,
            false,
            &[
                PlayerEvent::FileLoaded,
                PlayerEvent::TimePos { seconds: 10.0 },
                PlayerEvent::Paused { paused: true },
                PlayerEvent::Paused { paused: false },
                PlayerEvent::Seeked,
                PlayerEvent::TimePos { seconds: target },
                PlayerEvent::Shutdown,
            ],
        );
        println!("sent: {sent:?}");
        assert_eq!(
            sent,
            [
                "Start",
                "ProgressPause",
                "ProgressUnpause",
                "ProgressTimeUpdate",
                "Stopped"
            ]
        );

        let state = run(fx.service.playback_state(&film.id)).unwrap();
        println!("after stop at {target} s of {runtime:.0} s: {state:?}");
        assert!(!state.played);
        let resume = state.resume.expect("resume point kept by the server");
        assert!((f64::from(resume.position_seconds) - target).abs() <= 1.0);

        let (resumed, _) = run(fx.service.prepare_playback(&film.id, true)).unwrap();
        println!("resume start: {} s", resumed.start_ticks as f64 / 1e7);
        assert!((resumed.start_ticks as f64 / 1e7 - target).abs() <= 1.0);
    }));

    // Cleanup, also after a failed check: a playback stopped at 0 clears the point.
    let (_, sent) = play(
        &film.id,
        false,
        &[
            PlayerEvent::FileLoaded,
            PlayerEvent::TimePos { seconds: 0.0 },
            PlayerEvent::Shutdown,
        ],
    );
    let state = run(fx.service.playback_state(&film.id)).unwrap();
    println!("cleanup {sent:?}: {state:?}");
    run(fx.service.sign_out()).unwrap();
    if let Err(e) = result {
        std::panic::resume_unwind(e);
    }
    assert!(!state.played && state.resume.is_none(), "cleanup failed");
}

/// Real server, on demand, **writes to the test account** (authorized 2026-10-28,
/// short sessions): with a 3 Mbit/s limit the server must transcode to HLS. The
/// playlist, first segment and external subtitles load with the header only, and
/// the stop report must end the encoding on the NAS. Cleanup: stopped at 0.
#[test]
#[ignore = "needs a real Emby server and credentials in environment variables"]
fn real_server_transcoding() {
    use super::models::{LibraryKind, PlayMethod};
    use super::reporting::{ActivePlayback, Tracker};
    use crate::player::PlayerEvent;
    use std::time::{Duration, Instant};

    let var = |k: &str| std::env::var(k).unwrap_or_else(|_| panic!("{k} manquant"));
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
    let service = fx.service.with_max_streaming_bitrate(3_000_000);
    let server_url = var("VIEWER_EMBY_URL");
    run(service.sign_in(
        &server_url,
        &var("VIEWER_EMBY_USER"),
        &var("VIEWER_EMBY_PASSWORD"),
    ))
    .unwrap();

    let film = run(service.libraries())
        .unwrap()
        .into_iter()
        .filter(|l| l.kind == LibraryKind::Movies)
        .flat_map(|l| run(service.items(&l.id, 0, 60)).unwrap().items)
        .filter(|i| !i.played)
        .find(|i| {
            run(service.item_detail(&i.id))
                .map(|d| d.resume.is_none())
                .unwrap_or(false)
        })
        .expect("a film never started on the test account");
    println!("film: {} ({})", film.name, film.id);

    let http = reqwest::Client::new();
    let (plan, headers) = run(service.prepare_playback(&film.id, false)).unwrap();
    let get = |url: &str| {
        let mut req = http.get(url);
        for (k, v) in &headers {
            req = req.header(k, v);
        }
        run(async { req.send().await.unwrap() })
    };
    // One tracker for the whole playback; sends what it produces, like the reporter.
    let tracker = std::cell::RefCell::new(Tracker::default());
    let begun = tracker
        .borrow_mut()
        .begin(ActivePlayback::new(&film.id, &plan), Instant::now());
    assert!(
        begun.is_empty(),
        "nothing is reported before the file loads"
    );
    let report = |events: &[PlayerEvent]| {
        let mut sent = Vec::new();
        for e in events {
            for r in tracker.borrow_mut().on_event(e, Instant::now()) {
                run(service.report_playback(&r)).unwrap_or_else(|e| panic!("{:?}: {e}", r.kind));
                sent.push(format!("{:?}", r.kind));
            }
        }
        sent
    };
    let transcoding_on_nas = || {
        let url = format!("{server_url}/emby/Sessions?DeviceId={REAL_DEVICE_ID}");
        let sessions: serde_json::Value = run(get(&url).json()).unwrap();
        sessions
            .as_array()
            .into_iter()
            .flatten()
            .any(|s| !s["TranscodingInfo"].is_null())
    };

    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        println!(
            "plan: {:?}, container {:?}, reasons {:?}, {} external subtitles",
            plan.method,
            plan.container,
            plan.transcode_reasons,
            plan.subtitles.len()
        );
        assert_eq!(plan.method, PlayMethod::Transcode);
        assert!(plan.url.contains("/master.m3u8?"));
        assert!(!plan.url.to_ascii_lowercase().contains("api_key"));
        assert!(plan.subtitles.iter().all(|s| !s.source.contains("api_key")));
        assert_eq!(plan.transcode_reasons.as_deref(), Some("débit trop élevé"));

        let started = Instant::now();
        assert_eq!(report(&[PlayerEvent::FileLoaded]), ["Start"]);
        let master = run(get(&plan.url).text()).unwrap();
        let base = url::Url::parse(&plan.url).unwrap();
        let variant = base
            .join(
                master
                    .lines()
                    .find(|l| !l.is_empty() && !l.starts_with('#'))
                    .unwrap(),
            )
            .unwrap();
        let playlist = run(get(variant.as_str()).text()).unwrap();
        let segment = variant
            .join(
                playlist
                    .lines()
                    .find(|l| !l.is_empty() && !l.starts_with('#'))
                    .unwrap(),
            )
            .unwrap();
        let res = get(segment.as_str());
        assert_eq!(res.status().as_u16(), 200);
        let bytes = run(res.bytes()).unwrap();
        println!(
            "segment 0: {} bytes after {:.1} s, transcoding on the NAS: {}",
            bytes.len(),
            started.elapsed().as_secs_f64(),
            transcoding_on_nas()
        );
        assert!(
            bytes.len() > 10_000 && bytes[0] == 0x47,
            "MPEG-TS sync byte"
        );

        for s in &plan.subtitles {
            let res = get(&s.source);
            println!(
                "subtitle {:?} {:?} selected={}: {}",
                s.title,
                s.lang,
                s.selected,
                res.status()
            );
            assert_eq!(res.status().as_u16(), 200);
        }
    }));

    // Stop report at 0, also after a failed check: ends the encoding, keeps no resume.
    let sent = report(&[PlayerEvent::TimePos { seconds: 0.0 }, PlayerEvent::Shutdown]);
    println!("stop: {sent:?}");
    let deadline = Instant::now() + Duration::from_secs(15);
    let mut still_encoding = transcoding_on_nas();
    while still_encoding && Instant::now() < deadline {
        std::thread::sleep(Duration::from_millis(500));
        still_encoding = transcoding_on_nas();
    }
    let state = run(service.playback_state(&film.id)).unwrap();
    println!("after stop: encoding {still_encoding}, {state:?}");
    run(service.sign_out()).unwrap();
    if let Err(e) = result {
        std::panic::resume_unwind(e);
    }
    assert!(
        !still_encoding,
        "the NAS still transcodes after the stop report"
    );
    assert!(!state.played && state.resume.is_none(), "cleanup failed");
}

/// Real server, on demand, **writes to the test account** (authorized 2026-10-28):
/// the home rows load, every library has latest additions, and a film stopped
/// mid-way comes first in "Reprendre" at that position. Cleanup: stopped at 0.
#[test]
#[ignore = "needs a real Emby server and credentials in environment variables"]
fn real_server_home_rows() {
    use super::models::LibraryKind;
    use super::reporting::{ActivePlayback, Tracker};
    use crate::player::PlayerEvent;
    use std::time::{Duration, Instant};

    let var = |k: &str| std::env::var(k).unwrap_or_else(|_| panic!("{k} manquant"));
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
    run(fx.service.sign_in(
        &var("VIEWER_EMBY_URL"),
        &var("VIEWER_EMBY_USER"),
        &var("VIEWER_EMBY_PASSWORD"),
    ))
    .unwrap();

    let timed = |label: &str, started: Instant, n: usize| {
        println!("{label}: {n} cards in {} ms", started.elapsed().as_millis());
    };
    let t = Instant::now();
    let before = run(fx.service.continue_watching()).unwrap();
    timed("Reprendre", t, before.len());
    let t = Instant::now();
    let next = run(fx.service.next_up()).unwrap();
    timed("À suivre", t, next.len());
    let libraries = run(fx.service.libraries()).unwrap();
    for l in &libraries {
        let t = Instant::now();
        let latest = run(fx.service.latest(&l.id)).unwrap();
        timed(&format!("Ajoutés récemment · {}", l.name), t, latest.len());
        assert!(!latest.is_empty(), "{} has no latest additions", l.name);
        let expected = if l.kind == LibraryKind::Movies {
            "Movie"
        } else {
            "Series"
        };
        assert!(latest.iter().all(|i| i.item_type == expected));
        assert!(latest.iter().all(|i| i.poster.is_some()), "{}", l.name);
    }

    // Stops a playback of `id` at `seconds`, like the reporter task would.
    let stop_at = |id: &str, seconds: f64| {
        let (plan, _) = run(fx.service.prepare_playback(id, false)).unwrap();
        let mut tracker = Tracker::default();
        let mut now = Instant::now();
        let mut reports = tracker.begin(ActivePlayback::new(id, &plan), now);
        for e in [
            PlayerEvent::FileLoaded,
            PlayerEvent::TimePos { seconds },
            PlayerEvent::Shutdown,
        ] {
            now += Duration::from_secs(1);
            reports.extend(tracker.on_event(&e, now));
        }
        for r in &reports {
            run(fx.service.report_playback(r)).unwrap();
        }
        plan
    };

    let film = libraries
        .iter()
        .filter(|l| l.kind == LibraryKind::Movies)
        .flat_map(|l| run(fx.service.items(&l.id, 0, 60)).unwrap().items)
        .find(|i| !i.played && before.iter().all(|b| b.id != i.id))
        .expect("a film not in progress on the test account");
    println!("film: {} ({})", film.name, film.id);

    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let plan = stop_at(&film.id, 600.0);
        assert!(
            plan.run_time_ticks.unwrap_or(0) > 1_200 * 10_000_000,
            "film too short"
        );
        let rows = run(fx.service.continue_watching()).unwrap();
        let first = rows.first().expect("Reprendre is empty after the stop");
        println!("Reprendre after the stop: {first:?}");
        assert_eq!(first.id, film.id, "the film comes first");
        assert_eq!(first.resume.as_ref().unwrap().position_seconds, 600);
        assert!(first.image.is_some() || first.runtime_minutes.is_some());
    }));

    stop_at(&film.id, 0.0);
    let after = run(fx.service.continue_watching()).unwrap();
    println!("cleanup: Reprendre has {} cards", after.len());
    run(fx.service.sign_out()).unwrap();
    if let Err(e) = result {
        std::panic::resume_unwind(e);
    }
    assert!(after.iter().all(|r| r.id != film.id), "cleanup failed");
}

/// Next Up on the real server (ADR 0010): marks the first episode of a series
/// played, expects the second one in "À suivre" (Emby 4.10.1.0 lists it in its
/// "resume" home section, not in `/Shows/NextUp`), then marks it unplayed again.
/// Writes on the test account only, undone before signing out.
#[test]
#[ignore = "needs the real Emby server and VIEWER_EMBY_* variables"]
fn real_server_next_up() {
    use super::models::LibraryKind;

    let var = |k: &str| std::env::var(k).unwrap_or_else(|_| panic!("{k} manquant"));
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
    let user = run(fx.service.sign_in(
        &var("VIEWER_EMBY_URL"),
        &var("VIEWER_EMBY_USER"),
        &var("VIEWER_EMBY_PASSWORD"),
    ))
    .unwrap();
    let client = fx.service.client().unwrap();

    let before = run(fx.service.next_up()).unwrap();
    println!("À suivre before: {} cards", before.len());
    let in_progress = run(fx.service.continue_watching()).unwrap();

    // First series whose first two episodes of season 1 are both unwatched.
    let library = run(fx.service.libraries())
        .unwrap()
        .into_iter()
        .find(|l| l.kind == LibraryKind::TvShows && l.name == "Séries TV")
        .expect("Séries TV library");
    let (series, first, second) = run(fx.service.items(&library.id, 0, 30))
        .unwrap()
        .items
        .into_iter()
        .find_map(|s| {
            let season = run(fx.service.seasons(&s.id))
                .ok()?
                .into_iter()
                .find(|x| x.index == Some(1))?;
            let eps = run(fx.service.episodes(&s.id, &season.id, 0, 2))
                .ok()?
                .items;
            let [a, b] = <[_; 2]>::try_from(eps).ok()?;
            let fresh = |e: &super::models::Episode| !e.played && e.resume.is_none();
            (fresh(&a)
                && fresh(&b)
                && a.index == Some(1)
                && b.index == Some(2)
                && before
                    .iter()
                    .all(|n| n.series_name.as_deref() != Some(&s.name))
                && in_progress.iter().all(|r| r.id != a.id && r.id != b.id))
            .then_some((s, a, b))
        })
        .expect("a series with two unwatched episodes");
    println!(
        "series: {} — E1 {} — E2 {}",
        series.name, first.id, second.id
    );

    run(client.set_played(&user.user_id, &first.id, Some(&utc_stamp()))).unwrap();
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let t = std::time::Instant::now();
        let rows = run(fx.service.next_up()).unwrap();
        println!(
            "À suivre after marking E1: {} cards in {} ms, first {:?}",
            rows.len(),
            t.elapsed().as_millis(),
            rows.first()
        );
        let card = rows
            .iter()
            .find(|r| r.id == second.id)
            .expect("E2 is in À suivre");
        assert_eq!(card.series_name.as_deref(), Some(series.name.as_str()));
        assert_eq!((card.season_index, card.index), (Some(1), Some(2)));
        assert!(card.resume.is_none());
        assert!(card.image.is_some(), "no 16:9 image for the card");
        assert!(rows.iter().all(|r| r.id != first.id));
    }));

    run(client.set_played(&user.user_id, &first.id, None)).unwrap();
    let after = run(fx.service.next_up()).unwrap();
    let season = run(fx.service.seasons(&series.id))
        .unwrap()
        .into_iter()
        .find(|x| x.index == Some(1))
        .unwrap();
    let restored = run(fx.service.episodes(&series.id, &season.id, 0, 1))
        .unwrap()
        .items;
    println!(
        "cleanup: À suivre has {} cards, E1 played = {}",
        after.len(),
        restored[0].played
    );
    run(fx.service.sign_out()).unwrap();
    if let Err(e) = result {
        std::panic::resume_unwind(e);
    }
    assert!(!restored[0].played, "E1 is still played");
    assert_eq!(
        after.iter().map(|r| &r.id).collect::<Vec<_>>(),
        before.iter().map(|r| &r.id).collect::<Vec<_>>(),
        "À suivre is not back to its state before"
    );
}

/// Now as `yyyyMMddHHmmss` in UTC, the `DatePlayed` format.
fn utc_stamp() -> String {
    let secs = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap()
        .as_secs() as i64;
    let (days, rem) = (secs.div_euclid(86_400), secs.rem_euclid(86_400));
    // Civil date from days since 1970-01-01 (Howard Hinnant's algorithm).
    let z = days + 719_468;
    let era = z.div_euclid(146_097);
    let doe = z - era * 146_097;
    let yoe = (doe - doe / 1_460 + doe / 36_524 - doe / 146_096) / 365;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let day = doy - (153 * mp + 2) / 5 + 1;
    let month = if mp < 10 { mp + 3 } else { mp - 9 };
    let year = yoe + era * 400 + i64::from(month <= 2);
    format!(
        "{year:04}{month:02}{day:02}{:02}{:02}{:02}",
        rem / 3_600,
        rem % 3_600 / 60,
        rem % 60
    )
}
