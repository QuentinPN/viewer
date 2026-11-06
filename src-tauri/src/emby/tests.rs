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
use super::models::{SessionStatus, SignedInUser};
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
                // Status 0: the connection is dropped without an answer.
                if status == 0 {
                    continue;
                }
                // A body starting with `JPEG:` is served as an image; `JFIF:` adds the
                // JPEG signature the artwork cache checks (ADR 0019).
                let (content_type, body) = if let Some(b) = body.strip_prefix("JFIF:") {
                    (
                        "image/jpeg",
                        [&[0xFF, 0xD8, 0xFF][..], b.as_bytes()].concat(),
                    )
                } else if let Some(b) = body.strip_prefix("JPEG:") {
                    ("image/jpeg", b.as_bytes().to_vec())
                } else {
                    ("application/json", body.into_bytes())
                };
                let head = format!(
                    "HTTP/1.1 {status} X\r\nContent-Type: {content_type}\r\nContent-Length: {}\r\nConnection: close\r\n\r\n",
                    body.len()
                );
                let _ = stream.write_all(&[head.as_bytes(), &body].concat());
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
        let favorite_items = format!("/emby/Users/{USER_ID}/FavoriteItems/");
        match path {
            p if (p == views_path
                || p == sections
                || p == section_items
                || p.starts_with(&items)
                || p.starts_with("/emby/Items/")
                || p.starts_with("/emby/Shows/")
                || p.starts_with("/emby/Sessions/")
                || p == "/emby/Genres"
                || p.starts_with(&favorite_items))
                && !token_ok =>
            {
                (401, String::new())
            }
            p if p == views_path => (200, views()),
            "/emby/Genres" if query.contains("ParentId=5") => (200, genres()),
            p if p == items && query.contains("SearchTerm=") => (200, search_answer(query)),
            p if p == items && query.contains("ParentId=5") => (200, movies_page()),
            p if p == items && query.contains("Filters=IsFavorite") => (200, favorites()),
            p if p == items && query.contains("ImageTypes=Backdrop") => (200, hero_latest()),
            p if p == format!("{favorite_items}30207") && r.method == "POST" => (
                200,
                r#"{"IsFavorite":true,"Played":false,"PlaybackPositionTicks":0}"#.into(),
            ),
            p if p == format!("{favorite_items}30207") && r.method == "DELETE" => (
                200,
                r#"{"IsFavorite":false,"Played":false,"PlaybackPositionTicks":0}"#.into(),
            ),
            p if p.starts_with(&favorite_items) => (404, NOT_FOUND_TEXT.into()),
            p if p == items => (200, r#"{"Items":[],"TotalRecordCount":0}"#.into()),
            "/emby/Items/101/Images/Primary" => (200, "JPEG:fake-jpeg-bytes".into()),
            "/emby/Items/102/Images/Primary" => (200, "<html>pas une image</html>".into()),
            "/emby/Items/103/Images/Primary" => (200, "JFIF:cached-artwork".into()),
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
            p if p == format!("{items}/80476") => (200, bare_episode_detail()),
            p if p == format!("{items}/80474") => (200, season_detail()),
            p if p.starts_with(&format!("{items}/")) => (404, NOT_FOUND_TEXT.into()),
            "/emby/Shows/80473/Seasons" => (200, seasons()),
            "/emby/Shows/80473/Episodes" if query.contains("IsPlayed=false") => {
                (200, first_unplayed())
            }
            "/emby/Shows/80500/Episodes" if query.contains("IsPlayed=false") => {
                (200, r#"{"Items":[],"TotalRecordCount":0}"#.into())
            }
            "/emby/Shows/80473/Episodes" if query.contains("SeasonId=80474") => {
                (200, episodes_page())
            }
            // Emby trusts SeasonId: a season of another series returns its episodes.
            "/emby/Shows/80473/Episodes" if query.contains("SeasonId=67639") => {
                (200, foreign_episodes())
            }
            "/emby/Shows/80473/Episodes" if query.contains("AdjacentTo=80475") => {
                (200, adjacent_episodes())
            }
            "/emby/Shows/80473/Episodes" if query.contains("AdjacentTo=") => {
                (200, r#"{"Items":[],"TotalRecordCount":0}"#.into())
            }
            p if p.starts_with("/emby/Shows/") => (404, NOT_FOUND_TEXT.into()),
            // 30207 honours a refusal of direct play (ADR 0023); 80475 ignores it.
            "/emby/Items/30207/PlaybackInfo"
                if r.method == "POST" && r.body.contains("\"EnableDirectPlay\":false") =>
            {
                (200, playback_transcode())
            }
            "/emby/Items/30207/PlaybackInfo" | "/emby/Items/80475/PlaybackInfo"
                if r.method == "POST" =>
            {
                (200, playback_direct())
            }
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
        "UserData":{"PlaybackPositionTicks":18310167000,"PlayCount":0,"IsFavorite":true,"Played":false},
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

/// Trimmed `/Users/{Id}/Items/{Id}` answer of an episode (Emby 4.10.1.0 field set):
/// series artwork comes as `Parent…` fields.
fn episode_detail() -> String {
    r#"{"Name":" La Nouvelle Recrue ","Id":"80475","Type":"Episode","SeriesId":"80473",
        "SeriesName":"2.5 Dimensional Seduction","SeasonId":"80474","SeasonName":"Saison 1",
        "IndexNumber":1,"ParentIndexNumber":1,"Path":"/share/Anime/2.5/S01E01.mkv",
        "PremiereDate":"2024-07-04T22:00:00.0000000Z","RunTimeTicks":14400640000,
        "Overview":"Seul membre du club…","CommunityRating":7.4,"Studios":[{"Name":"J.C.Staff","Id":1}],
        "People":[{"Name":"Kyo Kim","Id":"1","Type":"Director"},
                  {"Name":"Mariko Kunisawa","Id":"2","Type":"Writer"},
                  {"Name":"Habitué","Id":"3","Type":"Actor","Role":"Okumura"},
                  {"Name":"Invitée","Id":"4","Type":"GuestStar","Role":"Lilysa"}],
        "UserData":{"PlaybackPositionTicks":7200000000,"Played":false,"IsFavorite":false},
        "ImageTags":{"Primary":"9e617fa1_6385"},"BackdropImageTags":[],
        "ParentBackdropItemId":"80473","ParentBackdropImageTags":["b4ckd70p_6385"],
        "ParentLogoItemId":"80473","ParentLogoImageTag":"l0g0_6385","SeriesPrimaryImageTag":"4b62464b_6385",
        "MediaSources":[{"Protocol":"File","Id":"ms80475","Path":"/share/Anime/2.5/S01E01.mkv",
          "Container":"mkv","Size":400000000,"Bitrate":2200000,
          "MediaStreams":[{"Codec":"h264","Type":"Video","Height":1080,"Width":1920},
                          {"Codec":"aac","Language":"jpn","Type":"Audio","Channels":2,"IsDefault":true},
                          {"Codec":"ass","Language":"fre","Type":"Subtitle","IsDefault":true}]}]}"#
        .into()
}

/// An episode without its own image, missing from the server's `AdjacentTo` answer.
fn bare_episode_detail() -> String {
    r#"{"Name":"Double","Id":"80476","Type":"Episode","SeriesId":"80473","SeasonId":"80474",
        "IndexNumber":2,"IndexNumberEnd":3,"ParentIndexNumber":1,
        "ParentBackdropItemId":"80473","ParentBackdropImageTags":["b4ckd70p_6385"]}"#
        .into()
}

fn season_detail() -> String {
    r#"{"Name":"Saison 1","Id":"80474","Type":"Season","SeriesId":"80473"}"#.into()
}

/// `AdjacentTo=80475`: Emby returns a few episodes before it, then the rest of the
/// series across seasons; an item of another series is ignored.
fn adjacent_episodes() -> String {
    r#"{"Items":[
        {"Name":"Spécial","Id":"80470","Type":"Episode","SeriesId":"80473","ParentIndexNumber":0,"IndexNumber":1},
        {"Name":"Intrus","Id":"67640","Type":"Episode","SeriesId":"67638","ParentIndexNumber":1,"IndexNumber":9},
        {"Name":"La Nouvelle Recrue","Id":"80475","Type":"Episode","SeriesId":"80473","ParentIndexNumber":1,"IndexNumber":1},
        {"Name":" Double ","Id":"80476","Type":"Episode","SeriesId":"80473","ParentIndexNumber":1,"IndexNumber":2,"IndexNumberEnd":3},
        {"Name":"Saison 2","Id":"80490","Type":"Episode","SeriesId":"80473","ParentIndexNumber":2,"IndexNumber":1}
    ],"TotalRecordCount":5}"#
        .into()
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
         "ParentLogoItemId":"70185","ParentLogoImageTag":"9c1f0e2a_6384",
         "SeriesPrimaryImageTag":"51d2b7e0_6384","Overview":" Le casse continue. ",
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

/// `/Users/{Id}/Items?ImageTypes=Backdrop&IsPlayed=false` for the home hero
/// (trimmed): titles already resumed, one without backdrop tag, a series fully
/// watched (no episode left), and more than the hero shows.
fn hero_latest() -> String {
    r#"{"Items":[
        {"Name":"La casa de papel","Id":"70185","Type":"Series","BackdropImageTags":["6da1a59e_6384"]},
        {"Name":"2 Coeurs","Id":"30207","Type":"Movie","BackdropImageTags":["2edd4b0e_6379"]},
        {"Name":"Dark","Id":"80473","Type":"Series","ProductionYear":2017,"Overview":"Winden.",
         "ImageTags":{"Primary":"aa11_1","Logo":"bb22_1"},"BackdropImageTags":["cc33_1"],
         "UserData":{"UnplayedItemCount":18,"Played":false}},
        {"Name":"Sans fond","Id":"30300","Type":"Movie","BackdropImageTags":[]},
        {"Name":"Tout vu","Id":"80500","Type":"Series","BackdropImageTags":["dd44_1"]},
        {"Name":"Nouveau film","Id":"30301","Type":"Movie","RunTimeTicks":60000000000,
         "BackdropImageTags":["ee55_1"],"UserData":{"PlaybackPositionTicks":0,"Played":false}},
        {"Name":"En trop","Id":"30302","Type":"Movie","BackdropImageTags":["ff66_1"]}
    ],"TotalRecordCount":7}"#
        .into()
}

/// `/Shows/80473/Episodes?IsPlayed=false&Limit=1` (trimmed), started a little.
fn first_unplayed() -> String {
    r#"{"Items":[
        {"Name":"Secrets","Id":"80475","Type":"Episode","IndexNumber":1,"ParentIndexNumber":1,
         "SeriesId":"80473","RunTimeTicks":30000000000,
         "UserData":{"PlaybackPositionTicks":3000000000,"Played":false}}
    ],"TotalRecordCount":18}"#
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

/// `/Users/{Id}/Items?SearchTerm=`: 25 films (one more than shown) with a series
/// mixed in, episodes (one found by its series name only, one without an id),
/// or one series with `TotalRecordCount: 0` as Emby 4.10.1.0 answers.
fn search_answer(query: &str) -> String {
    if query.contains("IncludeItemTypes=Episode") {
        return r#"{"Items":[
            {"Name":"Les heures chrono","Id":"44501","Type":"Episode","SeriesName":"24 Heures chrono",
             "SeriesId":"44440","ParentIndexNumber":1,"IndexNumber":12,"RunTimeTicks":25800000000,
             "ImageTags":{"Primary":"ep_12"},"UserData":{"Played":false,"PlaybackPositionTicks":0}},
            {"Name":"Jour 2 : 8h00 - 9h00","Id":"44502","Type":"Episode","SeriesName":"24 Heures chrono",
             "ParentIndexNumber":2,"IndexNumber":1},
            {"Name":"Heures sombres","Id":"","Type":"Episode"},
            {"Name":"Intrus","Id":"8998","Type":"Movie"}
        ],"TotalRecordCount":0}"#
            .into();
    }
    if query.contains("IncludeItemTypes=Movie") {
        let mut items: Vec<String> = (0..25)
            .map(|i| {
                format!(
                    r#"{{"Name":"Film {i}","Id":"{}","Type":"Movie","ProductionYear":2001,
                    "ImageTags":{{"Primary":"aa_{i}"}},"UserData":{{"Played":false}}}}"#,
                    9000 + i
                )
            })
            .collect();
        items.insert(3, r#"{"Name":"Intrus","Id":"8999","Type":"Series"}"#.into());
        format!(r#"{{"Items":[{}],"TotalRecordCount":0}}"#, items.join(","))
    } else {
        r#"{"Items":[
            {"Name":"24 Heures chrono","Id":"44440","Type":"Series","ProductionYear":2001,
             "ImageTags":{"Primary":"0e0af3ab_6381"},"UserData":{"UnplayedItemCount":204,"Played":false}}
        ],"TotalRecordCount":0}"#
            .into()
    }
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

/// `/Users/{Id}/Items?Filters=IsFavorite`: a film, a series, a collection (not
/// shown) and an unusable id.
fn favorites() -> String {
    r#"{"Items":[
        {"Name":"2 Coeurs","Id":"30207","Type":"Movie","ProductionYear":2020,
         "ImageTags":{"Primary":"7b213eb5_6379"},"UserData":{"IsFavorite":true,"Played":false}},
        {"Name":"Arcane","Id":"80473","Type":"Series","ProductionYear":2021,
         "ImageTags":{},"UserData":{"IsFavorite":true,"UnplayedItemCount":18}},
        {"Name":"Collection","Id":"555","Type":"BoxSet","UserData":{"IsFavorite":true}},
        {"Name":"Id douteux","Id":"../x","Type":"Movie"}
    ],"TotalRecordCount":4}"#
        .into()
}

/// `/Genres?ParentId=5`: names as the server stores them, one unusable.
fn genres() -> String {
    r#"{"Items":[
        {"Name":"Action","Id":"901","Type":"Genre"},
        {"Name":"Science-Fiction","Id":"902","Type":"Genre"},
        {"Name":"Drame|Romance","Id":"903","Type":"Genre"},
        {"Name":"Science-Fiction","Id":"904","Type":"Genre"}
    ],"TotalRecordCount":4}"#
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

fn authenticated(status: SessionStatus) -> SignedInUser {
    match status {
        SessionStatus::Authenticated { user } => user,
        other => panic!("expected authenticated, got {other:?}"),
    }
}

/// `(error kind)` of an `AuthenticationRequired` status.
fn reauth_kind(status: SessionStatus) -> &'static str {
    match status {
        SessionStatus::AuthenticationRequired { server, error } => {
            assert_eq!(server.user_name, "testeur");
            error.kind()
        }
        other => panic!("expected authenticationRequired, got {other:?}"),
    }
}

fn unreachable_kind(status: SessionStatus) -> &'static str {
    match status {
        SessionStatus::ServerUnreachable { server, error } => {
            assert_eq!(server.id, SERVER_ID);
            error.kind()
        }
        other => panic!("expected serverUnreachable, got {other:?}"),
    }
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
    let user = authenticated(run(fx.service.restore()).unwrap());
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
    let status = run(fx.service.restore()).unwrap();
    assert_eq!(reauth_kind(status), "sessionExpired");
    assert!(stored_token(&fx).is_none());
    assert!(fx.service.current_user().is_none());
    assert_eq!(
        fx.service.last_server_url().as_deref(),
        Some(server.url.as_str())
    );
    // After a restart the reauthentication screen is still prefilled.
    let fx = reopen(fx.store, fx.path);
    assert_eq!(
        reauth_kind(run(fx.service.restore()).unwrap()),
        "sessionExpired"
    );
    // Signing in again clears the hint.
    run(fx.service.sign_in(&server.url, "testeur", PASSWORD)).unwrap();
    let file: SessionFile =
        serde_json::from_str(&std::fs::read_to_string(&fx.path).unwrap()).unwrap();
    assert!(file.reauth.is_none());
}

#[test]
fn restore_with_nothing_saved_is_signed_out() {
    let fx = fixture();
    assert!(matches!(
        run(fx.service.restore()).unwrap(),
        SessionStatus::SignedOut
    ));
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
        assert!(matches!(
            run(fx.service.restore()).unwrap(),
            SessionStatus::SignedOut
        ));
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
        image.src.starts_with(&format!(
            "http://emby-img.localhost/{SERVER_ID}/10/Primary?tag=56f6b8b2_6384&maxWidth="
        )),
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
    let page = run(fx.service.items("5", 50, 50, &Default::default())).unwrap();

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
    let err = run(fx.service.items("999", 0, 50, &Default::default())).unwrap_err();
    assert!(matches!(err, EmbyError::NotFound), "{err:?}");
    assert!(server.requests().iter().all(|r| !r.path.contains("/Items")));

    run(fx.service.items("10", 0, 100_000, &Default::default())).unwrap();
    let req = server.requests().into_iter().last().unwrap();
    assert!(req.path.contains("Limit=200"), "{}", req.path);
    assert!(req.path.contains("IncludeItemTypes=Series"), "{}", req.path);
}

#[test]
fn items_send_the_chosen_sort_and_filters() {
    use super::models::{ItemsOptions, SortKey, WatchFilter};
    let (server, fx, _) = signed_in_browser();
    let query_of = |options: &ItemsOptions| {
        run(fx.service.items("5", 0, 60, options)).unwrap();
        let req = server.requests().into_iter().last().unwrap();
        let query = req.path.split_once('?').unwrap().1.to_owned();
        query.split('&').map(str::to_owned).collect::<Vec<_>>()
    };

    let plain = query_of(&ItemsOptions::default());
    assert!(
        plain
            .iter()
            .all(|p| !p.starts_with("Filters=") && !p.starts_with("Genres="))
    );

    let q = query_of(&ItemsOptions {
        sort: SortKey::DateAdded,
        descending: true,
        watch: WatchFilter::Unplayed,
        favorites: true,
        genre: Some("Science-Fiction & Fantastique".into()),
    });
    for expected in [
        "SortBy=DateCreated%2CSortName",
        "SortOrder=Descending",
        "Filters=IsUnplayed%2CIsFavorite",
        "Genres=Science-Fiction+%26+Fantastique",
        "ParentId=5",
        "StartIndex=0",
    ] {
        assert!(q.iter().any(|p| p == expected), "{expected} in {q:?}");
    }

    for (sort, sort_by) in [
        (SortKey::Name, "SortBy=SortName"),
        (SortKey::Year, "SortBy=ProductionYear%2CSortName"),
        (SortKey::Rating, "SortBy=CommunityRating%2CSortName"),
    ] {
        let q = query_of(&ItemsOptions {
            sort,
            ..Default::default()
        });
        assert!(q.iter().any(|p| p == sort_by), "{sort_by} in {q:?}");
    }
    for (watch, filters) in [
        (WatchFilter::Played, "Filters=IsPlayed"),
        (WatchFilter::InProgress, "Filters=IsResumable"),
    ] {
        let q = query_of(&ItemsOptions {
            watch,
            ..Default::default()
        });
        assert!(q.iter().any(|p| p == filters), "{filters} in {q:?}");
    }
}

#[test]
fn items_refuse_a_genre_the_server_cannot_filter_on() {
    use super::models::ItemsOptions;
    let (server, fx, _) = signed_in_browser();
    let before = server.requests().len();
    let long = "x".repeat(101);
    for genre in ["", "  ", "Drame|Romance", "a\u{7}b", long.as_str()] {
        let options = ItemsOptions {
            genre: Some(genre.to_owned()),
            ..Default::default()
        };
        let err = run(fx.service.items("5", 0, 60, &options)).unwrap_err();
        assert!(
            matches!(err, EmbyError::InvalidResponse(_)),
            "{genre:?}: {err:?}"
        );
    }
    assert_eq!(server.requests().len(), before, "nothing sent");
}

#[test]
fn options_from_the_ui_default_and_parse() {
    use super::models::{ItemsOptions, SortKey, WatchFilter};
    let parsed: ItemsOptions =
        serde_json::from_str(r#"{"sort":"rating","descending":true,"watch":"inProgress"}"#)
            .unwrap();
    assert_eq!(parsed.sort, SortKey::Rating);
    assert!(parsed.descending && !parsed.favorites && parsed.genre.is_none());
    assert_eq!(parsed.watch, WatchFilter::InProgress);
    assert_eq!(
        serde_json::from_str::<ItemsOptions>("{}").unwrap(),
        ItemsOptions::default()
    );
    assert!(serde_json::from_str::<ItemsOptions>(r#"{"sort":"random"}"#).is_err());
}

#[test]
fn genres_of_a_library_are_listed_without_unusable_names() {
    let (server, fx, _) = signed_in_browser();
    let genres = run(fx.service.genres("5")).unwrap();
    assert_eq!(genres, ["Action", "Science-Fiction"]);

    let req = server.requests().into_iter().last().unwrap();
    let (path, query) = req.path.split_once('?').unwrap();
    assert_eq!(path, "/emby/Genres");
    for expected in [
        format!("UserId={USER_ID}"),
        "ParentId=5".into(),
        "IncludeItemTypes=Movie".into(),
        "Recursive=true".into(),
    ] {
        assert!(
            query.split('&').any(|p| p == expected),
            "{expected} in {query}"
        );
    }
    assert!(!req.path.contains(TOKEN));

    // Unknown library: nothing asked about it.
    let err = run(fx.service.genres("999")).unwrap_err();
    assert!(matches!(err, EmbyError::NotFound), "{err:?}");
}

#[test]
fn favorites_row_lists_films_and_series_by_title() {
    let (server, fx, _) = signed_in_browser();
    let items = run(fx.service.favorites()).unwrap();
    let ids: Vec<_> = items.iter().map(|i| i.id.as_str()).collect();
    assert_eq!(ids, ["30207", "80473"]);
    assert_eq!(items[1].unplayed_count, Some(18));

    let req = server.requests().into_iter().last().unwrap();
    let (path, query) = req.path.split_once('?').unwrap();
    assert_eq!(path, format!("/emby/Users/{USER_ID}/Items"));
    for expected in [
        "Filters=IsFavorite",
        "Recursive=true",
        "IncludeItemTypes=Movie%2CSeries",
        "SortBy=SortName",
        "Limit=16",
    ] {
        assert!(
            query.split('&').any(|p| p == expected),
            "{expected} in {query}"
        );
    }
    assert!(!query.contains("ParentId"));
    assert!(!req.path.contains(TOKEN));
}

#[test]
fn hero_resumes_first_then_fills_with_latest() {
    use super::models::HeroReason;
    let (server, fx, _) = signed_in_browser();
    let hero = run(fx.service.hero()).unwrap();
    let ids: Vec<_> = hero.iter().map(|h| h.detail.id.as_str()).collect();
    // Resumed film and series, then latest; duplicates, no backdrop and the sixth dropped.
    assert_eq!(ids, ["30207", "70185", "80473", "80500", "30301"]);
    let reasons: Vec<_> = hero.iter().map(|h| h.reason).collect();
    assert_eq!(reasons[..2], [HeroReason::Resume; 2]);
    assert_eq!(reasons[2..], [HeroReason::Latest; 3]);

    let film = &hero[0];
    assert_eq!(film.play_id.as_deref(), Some("30207"));
    assert_eq!(film.resume.as_ref().unwrap().position_seconds, 1831);
    assert!(film.backdrop.src.contains("/30207/Backdrop?"));
    assert!(film.episode.is_none());

    // A resumed episode stands for its series, with the series' artwork.
    let series = &hero[1];
    assert_eq!(series.detail.item_type, "Series");
    assert_eq!(series.detail.name, "La casa de papel");
    assert!(
        series
            .detail
            .poster
            .as_ref()
            .unwrap()
            .src
            .contains("/70185/Primary?")
    );
    assert_eq!(series.play_id.as_deref(), Some("70227"));
    assert_eq!(series.resume.as_ref().unwrap().position_seconds, 600);
    let episode = series.episode.as_ref().unwrap();
    assert_eq!(
        (episode.name.as_str(), episode.season_index, episode.index),
        ("Le Professeur", Some(5), Some(2))
    );
    assert_eq!(series.overview.as_deref(), Some("Le casse continue."));
    assert!(series.backdrop.src.contains("/70185/Backdrop?"));
    assert!(series.logo.as_ref().unwrap().src.contains("/70185/Logo?"));

    // A latest series plays its first episode not watched, a fully watched one nothing.
    let dark = &hero[2];
    assert_eq!(dark.play_id.as_deref(), Some("80475"));
    assert_eq!(dark.episode.as_ref().unwrap().name, "Secrets");
    assert_eq!(dark.resume.as_ref().unwrap().position_seconds, 300);
    assert_eq!(dark.detail.unplayed_count, Some(18));
    assert_eq!(dark.overview.as_deref(), Some("Winden."));
    assert!(dark.logo.is_some());
    assert!(hero[3].play_id.is_none() && hero[3].episode.is_none());
    assert_eq!(hero[4].play_id.as_deref(), Some("30301"));
    assert!(hero[4].resume.is_none() && hero[4].logo.is_none());

    let requests = server.requests();
    let latest = requests
        .iter()
        .find(|r| r.path.contains("ImageTypes=Backdrop"))
        .unwrap();
    let (path, query) = latest.path.split_once('?').unwrap();
    assert_eq!(path, format!("/emby/Users/{USER_ID}/Items"));
    for expected in [
        "Recursive=true",
        "IncludeItemTypes=Movie%2CSeries",
        "IsPlayed=false",
        "SortBy=DateCreated",
        "SortOrder=Descending",
        "Limit=10",
    ] {
        assert!(
            query.split('&').any(|p| p == expected),
            "{expected} in {query}"
        );
    }
    let episodes = requests
        .iter()
        .find(|r| r.path.starts_with("/emby/Shows/80473/Episodes"))
        .unwrap();
    for expected in ["IsPlayed=false", "Limit=1", &format!("UserId={USER_ID}")] {
        assert!(
            episodes.path.split(['?', '&']).any(|p| p == expected),
            "{}",
            episodes.path
        );
    }
    // No lookup for films nor for the resumed series.
    assert!(
        !requests
            .iter()
            .any(|r| r.path.starts_with("/emby/Shows/70185"))
    );
    assert!(requests.iter().all(|r| !r.path.contains(TOKEN)));
}

#[test]
fn hero_with_a_revoked_token_ends_the_session() {
    let (_server, fx, revoked) = signed_in_browser();
    revoked.store(true, Ordering::SeqCst);
    let err = run(fx.service.hero()).unwrap_err();
    assert!(matches!(err, EmbyError::SessionExpired), "{err:?}");
}

#[test]
fn favorite_is_set_and_cleared_with_the_server_answer() {
    let (server, fx, _) = signed_in_browser();
    assert!(run(fx.service.set_favorite("30207", true)).unwrap());
    let req = server.requests().into_iter().last().unwrap();
    assert_eq!(req.method, "POST");
    assert_eq!(
        req.path,
        format!("/emby/Users/{USER_ID}/FavoriteItems/30207")
    );

    assert!(!run(fx.service.set_favorite("30207", false)).unwrap());
    let req = server.requests().into_iter().last().unwrap();
    assert_eq!(req.method, "DELETE");
    assert_eq!(
        req.path,
        format!("/emby/Users/{USER_ID}/FavoriteItems/30207")
    );

    let err = run(fx.service.set_favorite("99999", true)).unwrap_err();
    assert!(matches!(err, EmbyError::NotFound), "{err:?}");

    // An id that is not one never reaches the server.
    let before = server.requests().len();
    for id in ["", "../30207", "30207?x=1"] {
        let err = run(fx.service.set_favorite(id, true)).unwrap_err();
        assert!(
            matches!(err, EmbyError::InvalidResponse(_)),
            "{id}: {err:?}"
        );
    }
    assert_eq!(server.requests().len(), before);
}

#[test]
fn favorite_with_a_revoked_token_ends_the_session() {
    let (_server, fx, revoked) = signed_in_browser();
    revoked.store(true, Ordering::SeqCst);
    let err = run(fx.service.set_favorite("30207", true)).unwrap_err();
    assert!(matches!(err, EmbyError::SessionExpired), "{err:?}");
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
    assert_eq!(
        reauth_kind(run(fx.service.restore()).unwrap()),
        "sessionExpired"
    );
}

#[test]
fn artwork_is_fetched_with_auth_and_checked() {
    use super::images::ImageRequest;
    let (server, fx, _) = signed_in_browser();
    let req = ImageRequest::parse(
        &format!("/{SERVER_ID}/101/Primary"),
        Some("tag=7b213eb5_6379&maxWidth=360"),
    )
    .unwrap();
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

    let not_image = ImageRequest::parse(
        &format!("/{SERVER_ID}/102/Primary"),
        Some("tag=x&maxWidth=360"),
    )
    .unwrap();
    let err = run(fx.service.image(&not_image)).unwrap_err();
    assert!(matches!(err, EmbyError::InvalidResponse(_)), "{err:?}");

    // A URL built for another server is refused without any request (ADR 0012).
    let before = server.requests().len();
    let foreign = ImageRequest::parse(
        "/otherserver/101/Primary",
        Some("tag=7b213eb5_6379&maxWidth=360"),
    )
    .unwrap();
    let err = run(fx.service.image(&foreign)).unwrap_err();
    assert!(matches!(err, EmbyError::Forbidden), "{err:?}");
    assert_eq!(server.requests().len(), before);

    run(fx.service.sign_out()).unwrap();
    let err = run(fx.service.image(&req)).unwrap_err();
    assert!(matches!(err, EmbyError::NotSignedIn), "{err:?}");
}

#[test]
fn artwork_cache_spares_the_server_and_keeps_its_checks() {
    use super::artwork_cache::ArtworkCache;
    use super::images::ImageRequest;
    let server = FakeEmby::start(browse_like(Arc::new(AtomicBool::new(false))));
    let root = std::env::temp_dir().join(format!("viewer-artwork-svc-{}", uuid::Uuid::new_v4()));
    let fx = fixture();
    let fx = Fixture {
        service: fx
            .service
            .with_artwork_cache(ArtworkCache::new(root.clone(), 1 << 20)),
        ..fx
    };
    run(fx.service.sign_in(&server.url, "testeur", PASSWORD)).unwrap();
    let image = |item: &str| {
        ImageRequest::parse(
            &format!("/{SERVER_ID}/{item}/Primary"),
            Some("tag=c0ffee_1&maxWidth=360"),
        )
        .unwrap()
    };
    let fetched = || {
        server
            .requests()
            .iter()
            .filter(|r| r.path.contains("/Images/"))
            .count()
    };

    // Downloaded once, then read from the disk.
    let first = run(fx.service.image(&image("103"))).unwrap();
    assert_eq!(first.0, "image/jpeg");
    assert_eq!(&first.1[3..], b"cached-artwork");
    assert_eq!(fetched(), 1);
    assert_eq!(run(fx.service.image(&image("103"))).unwrap(), first);
    assert_eq!(fetched(), 1);
    let stats = run(fx.service.artwork_stats()).unwrap();
    assert_eq!((stats.entries, stats.hits, stats.misses), (1, 1, 1));

    // Bytes without an image signature are served as before but never kept.
    run(fx.service.image(&image("101"))).unwrap();
    run(fx.service.image(&image("101"))).unwrap();
    assert_eq!(fetched(), 3);

    // Only the server id and the image key on disk: no address, no token.
    let file = root
        .join(SERVER_ID)
        .join("103")
        .join("Primary-360-c0ffee_1.img");
    assert!(file.is_file());
    let names: Vec<_> = std::fs::read_dir(&root)
        .unwrap()
        .flatten()
        .map(|e| e.file_name())
        .collect();
    assert_eq!(names, [SERVER_ID]);
    let bytes = std::fs::read(&file).unwrap();
    assert!(!bytes.windows(TOKEN.len()).any(|w| w == TOKEN.as_bytes()));

    // The checks still come first: another server, or no session, gets nothing.
    let foreign = ImageRequest::parse(
        "/otherserver/103/Primary",
        Some("tag=c0ffee_1&maxWidth=360"),
    )
    .unwrap();
    let err = run(fx.service.image(&foreign)).unwrap_err();
    assert!(matches!(err, EmbyError::Forbidden), "{err:?}");
    run(fx.service.sign_out()).unwrap();
    let err = run(fx.service.image(&image("103"))).unwrap_err();
    assert!(matches!(err, EmbyError::NotSignedIn), "{err:?}");
    assert_eq!(fetched(), 3);

    // Emptied from the settings (ADR 0021): local only, no session needed.
    let cleared = run(fx.service.clear_artwork()).unwrap();
    assert_eq!((cleared.entries, cleared.bytes), (1, 3 + 14));
    assert!(!file.exists());
    assert_eq!(run(fx.service.artwork_stats()).unwrap().entries, 0);
    assert_eq!(fetched(), 3);
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
    assert!(d.favorite);
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
fn episode_detail_has_series_context_guests_and_neighbours_across_seasons() {
    use super::models::DetailKind;
    let (server, fx, _) = signed_in_browser();
    let d = run(fx.service.item_detail("80475")).unwrap();
    assert_eq!(d.kind, DetailKind::Episode);
    assert_eq!(d.name, "La Nouvelle Recrue");
    let json = serde_json::to_string(&d).unwrap();
    assert!(!json.contains("/share"), "NAS path leaked: {json}");

    let ep = d.episode.as_ref().unwrap();
    assert_eq!(ep.series_id, "80473");
    assert_eq!(ep.series_name, "2.5 Dimensional Seduction");
    assert_eq!(ep.season_id.as_deref(), Some("80474"));
    assert_eq!(
        (ep.season_index, ep.index, ep.index_end),
        (Some(1), Some(1), None)
    );
    let prev = ep.previous.as_ref().unwrap();
    assert_eq!(
        (prev.id.as_str(), prev.season_index, prev.index),
        ("80470", Some(0), Some(1)),
        "another series' episode in between is skipped"
    );
    let next = ep.next.as_ref().unwrap();
    assert_eq!(
        (next.id.as_str(), next.name.as_str(), next.index_end),
        ("80476", "Double", Some(3))
    );

    assert_eq!(d.directors, ["Kyo Kim"]);
    assert_eq!(d.writers, ["Mariko Kunisawa"]);
    let guests: Vec<_> = d.cast.iter().map(|c| c.name.as_str()).collect();
    assert_eq!(
        guests,
        ["Invitée"],
        "the regular cast belongs to the series"
    );
    assert!(d.studios.is_empty());
    assert_eq!(d.runtime_minutes, Some(24));
    assert_eq!(d.community_rating, Some(7.4));
    assert_eq!(d.resume.as_ref().unwrap().position_seconds, 720);
    assert_eq!(
        (d.end_year, d.continuing, d.unplayed_count),
        (None, None, None)
    );
    assert!(
        d.backdrop
            .as_ref()
            .unwrap()
            .src
            .contains("/80475/Primary?tag=9e617fa1_6385"),
        "the episode's own image first"
    );
    assert!(d.backdrop.as_ref().unwrap().srcset.contains(" 1920w"));
    assert!(
        d.poster
            .as_ref()
            .unwrap()
            .src
            .contains("/80473/Primary?tag=4b62464b_6385")
    );
    assert!(
        d.logo
            .as_ref()
            .unwrap()
            .src
            .contains("/80473/Logo?tag=l0g0_6385")
    );
    let media = &d.media[0];
    assert_eq!(media.audio[0].language.as_deref(), Some("jpn"));
    assert_eq!(media.subtitles.len(), 1);

    let reqs = server.requests();
    let adjacent = reqs.last().unwrap();
    let (path, query) = adjacent.path.split_once('?').unwrap();
    assert_eq!(path, "/emby/Shows/80473/Episodes");
    for expected in [
        "AdjacentTo=80475",
        &format!("UserId={USER_ID}"),
        "EnableImages=false",
        "EnableUserData=false",
    ] {
        assert!(
            query.split('&').any(|p| p == expected),
            "{expected} in {query}"
        );
    }
    assert!(!adjacent.path.contains(TOKEN));

    // No own image: the series backdrop; not in the server's answer: no neighbours.
    let bare = run(fx.service.item_detail("80476")).unwrap();
    assert!(
        bare.backdrop
            .unwrap()
            .src
            .contains("/80473/Backdrop?tag=b4ckd70p_6385")
    );
    let ep = bare.episode.unwrap();
    assert_eq!((ep.index, ep.index_end), (Some(2), Some(3)));
    assert!(ep.previous.is_none() && ep.next.is_none());
    assert!(bare.poster.is_none() && bare.logo.is_none());
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
    let err = run(fx.service.item_detail("80474")).unwrap_err();
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

    // The item is read at the same time (ADR 0017): find PlaybackInfo among both.
    let req = server
        .requests()
        .into_iter()
        .find(|r| r.path.contains("/PlaybackInfo"))
        .unwrap();
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

    let info = server
        .requests()
        .into_iter()
        .find(|r| r.path.contains("/PlaybackInfo"))
        .unwrap();
    let body: serde_json::Value = serde_json::from_str(&info.body).unwrap();
    assert_eq!(body["MaxStreamingBitrate"], 3_000_000);
    assert_eq!(body["DeviceProfile"]["MaxStreamingBitrate"], 3_000_000);
}

#[test]
fn playback_fallback_refuses_direct_play_and_names_the_reason() {
    use super::models::PlayMethod;
    let (server, fx, _) = signed_in_browser();

    // A normal negotiation keeps both direct methods allowed.
    run(fx.service.prepare_playback("30207", false)).unwrap();
    let first: serde_json::Value = serde_json::from_str(
        &server
            .requests()
            .into_iter()
            .find(|r| r.path.contains("/PlaybackInfo"))
            .unwrap()
            .body,
    )
    .unwrap();
    assert_eq!(first["EnableDirectPlay"], true);
    assert_eq!(first["EnableDirectStream"], true);

    let (plan, headers) = run(fx.service.prepare_transcode("30207", false)).unwrap();
    assert_eq!(plan.method, PlayMethod::Transcode);
    assert_eq!(
        plan.transcode_reasons.as_deref(),
        Some("échec de la lecture directe")
    );
    assert!(!format!("{plan:?}").contains(TOKEN));
    assert_eq!(headers, vec![("X-Emby-Token".to_owned(), TOKEN.to_owned())]);
    let forced: serde_json::Value = serde_json::from_str(
        &server
            .requests()
            .into_iter()
            .rfind(|r| r.path.contains("/PlaybackInfo"))
            .unwrap()
            .body,
    )
    .unwrap();
    assert_eq!(forced["EnableDirectPlay"], false);
    assert_eq!(forced["EnableDirectStream"], false);
}

#[test]
fn playback_fallback_does_not_loop_when_the_server_still_offers_the_file() {
    let (_server, fx, _) = signed_in_browser();
    let err = run(fx.service.prepare_transcode("80475", false)).unwrap_err();
    assert!(matches!(err, EmbyError::NoCompatibleStream), "{err:?}");
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

    let (plan, _) = run(fx.service.prepare_playback("30207", false)).unwrap();
    assert_eq!(plan.start_ticks, 0);
}

#[test]
fn playback_carries_the_title_of_a_film_or_an_episode() {
    use super::models::PlaybackTitle;
    let (server, fx, _) = signed_in_browser();

    let (plan, _) = run(fx.service.prepare_playback("30207", false)).unwrap();
    let film = plan.title.unwrap();
    assert_eq!(film.name, "2 Coeurs");
    assert_eq!(film.series_name, None);
    assert_eq!((film.season_index, film.index), (None, None));
    // The item is read alongside PlaybackInfo, even without resume.
    let paths: Vec<String> = server.requests().into_iter().map(|r| r.path).collect();
    assert!(paths.contains(&format!("/emby/Users/{USER_ID}/Items/30207")));

    let (plan, _) = run(fx.service.prepare_playback("80475", false)).unwrap();
    assert_eq!(
        plan.title,
        Some(PlaybackTitle {
            name: "La Nouvelle Recrue".into(),
            series_name: Some("2.5 Dimensional Seduction".into()),
            season_index: Some(1),
            index: Some(1),
            index_end: None,
        })
    );
}

#[test]
fn playback_starts_without_a_title_when_the_item_cannot_be_read() {
    let server = FakeEmby::start(browse_like(Arc::new(AtomicBool::new(false))));
    let fx = fixture();
    run(fx.service.sign_in(&server.url, "testeur", PASSWORD)).unwrap();
    // 40000 has PlaybackInfo but no item in the fake server.
    let (plan, _) = run(fx.service.prepare_playback("40000", false)).unwrap();
    assert_eq!(plan.title, None);
    // With resume the item is required: its error is the playback's.
    let err = run(fx.service.prepare_playback("40000", true)).unwrap_err();
    assert!(matches!(err, EmbyError::NotFound), "{err:?}");
}

#[test]
fn a_lost_stop_report_only_goes_to_the_session_it_belongs_to() {
    use super::models::PlaybackReportBody;
    use super::reporting::{Report, ReportKind};
    let (server, fx, _revoked) = signed_in_browser();
    let report = Report {
        kind: ReportKind::Stopped,
        body: PlaybackReportBody {
            item_id: "30207".into(),
            media_source_id: "mediasource_30207".into(),
            play_session_id: Some("ps1".into()),
            position_ticks: 7_583_000_000,
            run_time_ticks: Some(60_000_000_000),
            is_paused: None,
            can_seek: None,
            play_method: Some("Transcode"),
            event_name: None,
            failed: Some(true),
        },
    };
    let sent = || -> Vec<String> {
        server
            .requests()
            .into_iter()
            .filter(|r| r.path.starts_with("/emby/Sessions/Playing"))
            .map(|r| r.path)
            .collect()
    };
    let owner = fx.service.client().unwrap();
    run(fx.service.resend_stop(&owner, &report)).unwrap();
    let in_order = [
        "/emby/Sessions/Playing/Progress",
        "/emby/Sessions/Playing/Stopped",
    ];
    assert_eq!(sent(), in_order, "the progress carries the position");

    let other = super::client::EmbyClient::new(
        super::server_url::ServerUrl::parse(&server.url).unwrap(),
        super::client::DeviceIdentity {
            name: "test".into(),
            id: "other-device".into(),
            version: "0.1.0".into(),
        },
    )
    .unwrap()
    .with_token(AccessToken::new("another-token".into()));
    let err = run(fx.service.resend_stop(&other, &report)).unwrap_err();
    assert!(matches!(err, EmbyError::Cancelled), "{err:?}");
    assert_eq!(sent(), in_order, "no request for another session");

    run(fx.service.sign_out()).unwrap();
    let err = run(fx.service.resend_stop(&owner, &report)).unwrap_err();
    assert!(matches!(err, EmbyError::NotSignedIn), "{err:?}");
    assert_eq!(sent(), in_order, "no request once signed out");
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
        film["image"]["src"].as_str().unwrap().starts_with(&format!(
            "http://emby-img.localhost/{SERVER_ID}/30207/Thumb?tag=bbcac8ec_6379"
        )),
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
fn search_asks_films_series_and_episodes_apart_and_flags_more_results() {
    let (server, fx, revoked) = signed_in_browser();
    let results = run(fx.service.search("  Heures\u{7}  chrono\n")).unwrap();
    assert_eq!(results.query, "Heures chrono");
    let searches: Vec<_> = server
        .requests()
        .into_iter()
        .map(|r| r.path)
        .filter(|p| p.contains("SearchTerm="))
        .collect();
    assert_eq!(searches.len(), 3, "{searches:?}");
    for ty in ["Movie", "Series", "Episode"] {
        let path = searches
            .iter()
            .find(|p| p.contains(&format!("IncludeItemTypes={ty}&")))
            .unwrap_or_else(|| panic!("no {ty} search"));
        let (route, query) = path.split_once('?').unwrap();
        assert_eq!(route, format!("/emby/Users/{USER_ID}/Items"));
        let params: Vec<_> = query.split('&').collect();
        assert!(params.contains(&"SearchTerm=Heures+chrono"), "{query}");
        let limit = if ty == "Episode" {
            "Limit=100"
        } else {
            "Limit=25"
        };
        assert!(params.contains(&"Recursive=true") && params.contains(&limit));
        assert!(!query.contains(TOKEN) && !query.contains("ParentId"));
    }
    assert_eq!(results.movies.len(), 24);
    assert!(results.more_movies, "a 25th film means more");
    assert!(results.movies.iter().all(|m| m.item_type == "Movie"));
    assert_eq!(results.series.len(), 1);
    assert!(!results.more_series);
    assert_eq!(results.series[0].unplayed_count, Some(204));

    // Episodes: the 16:9 images of the home cards, series and numbers kept.
    let episode_search = searches
        .iter()
        .find(|p| p.contains("IncludeItemTypes=Episode&"))
        .unwrap();
    assert!(
        episode_search.contains("EnableImageTypes=Primary%2CBackdrop%2CThumb")
            && episode_search.contains("Limit=100"),
        "{episode_search}"
    );
    assert_eq!(
        results.episodes.len(),
        1,
        "found by series name only, no id or not an episode: dropped"
    );
    assert!(!results.more_episodes);
    let episode = &results.episodes[0];
    assert_eq!(episode.id, "44501");
    assert_eq!(episode.series_name.as_deref(), Some("24 Heures chrono"));
    assert_eq!((episode.season_index, episode.index), (Some(1), Some(12)));
    assert_eq!(episode.runtime_minutes, Some(43));
    assert!(
        episode
            .image
            .as_ref()
            .unwrap()
            .src
            .contains("/44501/Primary?")
    );

    revoked.store(true, Ordering::SeqCst);
    assert!(matches!(
        run(fx.service.search("chrono")).unwrap_err(),
        EmbyError::SessionExpired
    ));
}

#[test]
fn episode_titles_match_at_a_word_start_without_case_or_accents() {
    use super::session::title_matches;
    assert!(title_matches("Heureux Retours", "heureux"));
    assert!(title_matches("Moins d'une heure", "HÉURE"));
    assert!(title_matches("L'arnaque", "arnaq"));
    assert!(title_matches("Rex, chien flic", "chien flic"));
    assert!(title_matches("Él Niño", "el nino"));
    assert!(!title_matches("Shizu", "reincarne"), "series name only");
    assert!(
        !title_matches("Les heures", "eures"),
        "not a substring search"
    );
    assert!(
        !title_matches("Nos cœurs", "coeur"),
        "no ligature folding, as Emby"
    );
    assert!(!title_matches("Titre", ""));
}

#[test]
fn search_too_short_reaches_no_server() {
    let (server, fx, _) = signed_in_browser();
    let before = server.requests().len();
    for query in ["", "   ", "a", " é ", "\u{7}b\u{0}"] {
        assert_eq!(
            run(fx.service.search(query)).unwrap(),
            super::models::SearchResults::default(),
            "{query:?}"
        );
    }
    assert_eq!(server.requests().len(), before);
}

#[test]
fn search_query_is_cleaned_and_bounded() {
    use super::session::clean_search;
    assert_eq!(clean_search("\tLa  casa\r\nde papel "), "La casa de papel");
    assert_eq!(clean_search("a\u{0}b\u{1b}c"), "abc");
    let long = "é".repeat(150);
    assert_eq!(clean_search(&long).chars().count(), 100);
    assert_eq!(
        clean_search(&format!("{} x", "a".repeat(99))),
        "a".repeat(99)
    );
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
    let restored = authenticated(run(fx.service.restore()).unwrap());
    assert_eq!(restored.user_id, user.user_id);

    run(fx.service.sign_out()).unwrap();
    assert!(stored_token_for(&fx, &user.server.id, &user.user_id).is_none());
    // The revoked token must now be refused by the server.
    fx.store
        .save(&account(&user.server.id, &user.user_id), &token)
        .unwrap();
    let fx = reopen(fx.store, fx.path);
    // sign_out cleared the session record, so restore has nothing to try.
    assert!(matches!(
        run(fx.service.restore()).unwrap(),
        SessionStatus::SignedOut
    ));
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
            let first = run(fx.service.items(&lib.id, 0, 60, &Default::default())).unwrap();
            let elapsed = started.elapsed();
            let last_start = first.total.saturating_sub(1) / 60 * 60;
            let last = run(fx
                .service
                .items(&lib.id, last_start, 60, &Default::default()))
            .unwrap();
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
                run(fx.service.items(&l.id, 0, 10, &Default::default()))
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
            let page = run(fx.service.items(&lib.id, 0, sample, &Default::default())).unwrap();
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
                    DetailKind::Episode => panic!("{}: episode in a library grid", d.name),
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
            for item in run(fx.service.items(&lib.id, 0, sample, &Default::default()))
                .unwrap()
                .items
            {
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
        .flat_map(|l| {
            run(fx.service.items(&l.id, 0, 60, &Default::default()))
                .unwrap()
                .items
        })
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
        .flat_map(|l| {
            run(service.items(&l.id, 0, 60, &Default::default()))
                .unwrap()
                .items
        })
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
        .flat_map(|l| {
            run(fx.service.items(&l.id, 0, 60, &Default::default()))
                .unwrap()
                .items
        })
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
    let (series, first, second) = run(fx.service.items(&library.id, 0, 30, &Default::default()))
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

/// Cleanup of `scripts/e2e/gate3-upnext.mjs` (ADR 0018): playing an episode to its
/// end marks it played, so the scenario marks the items it listed in
/// `VIEWER_UNPLAY_IDS` (comma-separated) unplayed again on the test account.
#[test]
#[ignore = "needs the real Emby server, VIEWER_EMBY_* and VIEWER_UNPLAY_IDS"]
fn real_server_mark_unplayed() {
    let var = |k: &str| std::env::var(k).unwrap_or_else(|_| panic!("{k} manquant"));
    let ids = var("VIEWER_UNPLAY_IDS");
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
    for id in ids.split(',').filter(|id| !id.is_empty()) {
        run(client.set_played(&user.user_id, id, None)).unwrap();
        let detail = run(fx.service.item_detail(id)).unwrap();
        assert!(!detail.played, "{id} still played");
        println!("{id} unplayed");
    }
    run(fx.service.sign_out()).unwrap();
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

/// Search on the real server (ADR 0011), read-only: accents and case folded,
/// Sort and filters of library grids against the real server (ADR 0013), read
/// only: orders, watched split, favorites and genres as Emby 4.10 applies them.
#[test]
#[ignore = "needs the real Emby server and VIEWER_EMBY_* variables"]
fn real_server_library_filters() {
    use super::models::{ItemsOptions, MediaItem, SortKey, WatchFilter};
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

    let page = |id: &str, options: &ItemsOptions| {
        let t = std::time::Instant::now();
        let p = run(fx.service.items(id, 0, 60, options)).unwrap();
        (p, t.elapsed().as_millis())
    };
    let names = |items: &[MediaItem]| items.iter().map(|i| i.name.clone()).collect::<Vec<_>>();

    for lib in run(fx.service.libraries()).unwrap() {
        let (all, ms) = page(&lib.id, &ItemsOptions::default());
        println!(
            "{} ({:?}): {} au total, {ms} ms",
            lib.name, lib.kind, all.total
        );
        if all.total == 0 {
            continue;
        }

        let years = |descending: bool| {
            let (p, ms) = page(
                &lib.id,
                &ItemsOptions {
                    sort: SortKey::Year,
                    descending,
                    ..Default::default()
                },
            );
            println!(
                "  année {}: {:?} en {ms} ms",
                if descending { "↓" } else { "↑" },
                p.items.iter().take(5).map(|i| i.year).collect::<Vec<_>>()
            );
            p
        };
        let down = years(true);
        let ys: Vec<i32> = down.items.iter().filter_map(|i| i.year).collect();
        assert!(
            ys.windows(2).all(|w| w[0] >= w[1]),
            "années décroissantes : {ys:?}"
        );
        assert_eq!(down.total, all.total, "le tri ne change pas le total");
        let up = years(false);
        let ys: Vec<i32> = up.items.iter().filter_map(|i| i.year).collect();
        assert!(
            ys.windows(2).all(|w| w[0] <= w[1]),
            "années croissantes : {ys:?}"
        );

        let (added, ms) = page(
            &lib.id,
            &ItemsOptions {
                sort: SortKey::DateAdded,
                descending: true,
                ..Default::default()
            },
        );
        println!(
            "  ajout ↓: {:?} en {ms} ms",
            names(&added.items[..added.items.len().min(3)])
        );
        let (rated, ms) = page(
            &lib.id,
            &ItemsOptions {
                sort: SortKey::Rating,
                descending: true,
                ..Default::default()
            },
        );
        println!(
            "  note ↓: {:?} en {ms} ms",
            names(&rated.items[..rated.items.len().min(3)])
        );

        let count = |watch: WatchFilter| {
            let (p, ms) = page(
                &lib.id,
                &ItemsOptions {
                    watch,
                    ..Default::default()
                },
            );
            println!("  {watch:?}: {} en {ms} ms", p.total);
            p
        };
        let played = count(WatchFilter::Played);
        let unplayed = count(WatchFilter::Unplayed);
        let in_progress = count(WatchFilter::InProgress);
        assert!(played.items.iter().all(|i| i.played), "Vus : tous vus");
        assert!(
            unplayed.items.iter().all(|i| !i.played),
            "Non vus : aucun vu"
        );
        println!(
            "  vus + non vus = {} (total {}) ; en cours : {}",
            played.total + unplayed.total,
            all.total,
            in_progress.total
        );

        let (favorites, _) = page(
            &lib.id,
            &ItemsOptions {
                favorites: true,
                ..Default::default()
            },
        );
        println!("  favoris : {}", favorites.total);

        let t = std::time::Instant::now();
        let genres = run(fx.service.genres(&lib.id)).unwrap();
        println!(
            "  {} genres en {} ms : {:?}",
            genres.len(),
            t.elapsed().as_millis(),
            &genres[..genres.len().min(8)]
        );
        if let Some(genre) = genres.first() {
            let (g, ms) = page(
                &lib.id,
                &ItemsOptions {
                    genre: Some(genre.clone()),
                    ..Default::default()
                },
            );
            println!("  genre {genre:?} : {} en {ms} ms", g.total);
            assert!(g.total > 0 && g.total <= all.total, "{genre}: {}", g.total);
            let combined = page(
                &lib.id,
                &ItemsOptions {
                    genre: Some(genre.clone()),
                    watch: WatchFilter::Unplayed,
                    ..Default::default()
                },
            )
            .0;
            assert!(combined.total <= g.total, "les filtres se combinent");
        }
    }
    run(fx.service.sign_out()).unwrap();
}

/// word-prefix matching, films and series apart, too-short queries not sent.
#[test]
#[ignore = "needs the real Emby server and VIEWER_EMBY_* variables"]
fn real_server_search() {
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

    let search = |q: &str| {
        let t = std::time::Instant::now();
        let r = run(fx.service.search(q)).unwrap();
        println!(
            "{q:?} → {:?}: {} films{}, {} séries{}, {} épisodes{} in {} ms",
            r.query,
            r.movies.len(),
            if r.more_movies { "+" } else { "" },
            r.series.len(),
            if r.more_series { "+" } else { "" },
            r.episodes.len(),
            if r.more_episodes { "+" } else { "" },
            t.elapsed().as_millis()
        );
        for e in r.episodes.iter().take(5) {
            println!(
                "  épisode {:?} — {:?} S{:?}E{:?}, image {}",
                e.name,
                e.series_name,
                e.season_index,
                e.index,
                e.image.is_some()
            );
        }
        r
    };
    let names = |items: &[super::models::MediaItem]| {
        items.iter().map(|i| i.name.clone()).collect::<Vec<_>>()
    };

    let plain = search("reincarne");
    let accented = search("RÉINCARNE");
    assert!(!plain.movies.is_empty() && !plain.series.is_empty());
    assert_eq!(names(&plain.movies), names(&accented.movies));
    assert_eq!(names(&plain.series), names(&accented.series));
    assert!(plain.movies.iter().all(|m| m.item_type == "Movie"));
    assert!(plain.series.iter().all(|s| s.item_type == "Series"));
    assert!(plain.movies.iter().all(|m| m.poster.is_some()));

    // Episodes (ADR 0020): the server also matches the series name; only
    // episodes whose own title matches are kept.
    assert!(plain.episodes.iter().all(|e| {
        super::session::title_matches(&e.name, "reincarne")
            && e.series_name.is_some()
            && e.image.is_some()
    }));

    let prefix = search("  heure ");
    assert_eq!(prefix.query, "heure");
    assert!(names(&prefix.series).contains(&"24 Heures chrono".to_string()));
    assert!(!prefix.episodes.is_empty());
    assert!(search("eures").series.is_empty(), "not a substring search");

    let many = search("the");
    assert_eq!(many.movies.len(), super::session::SEARCH_SHOWN);
    assert!(many.more_movies);

    let none = search("zzzzqx");
    assert!(none.movies.is_empty() && none.series.is_empty());
    assert_eq!(search("a"), super::models::SearchResults::default());
    run(fx.service.sign_out()).unwrap();
}

// --- Gate 2.1: persistent session and reconnection (ADR 0012) ---

/// Points the saved session at another address and restarts the service, e.g. a
/// NAS that is off at startup.
fn retarget(fx: Fixture, url: &str) -> Fixture {
    let mut file: SessionFile =
        serde_json::from_str(&std::fs::read_to_string(&fx.path).unwrap()).unwrap();
    file.session.as_mut().unwrap().server_url = url.to_owned();
    file.save(&fx.path).unwrap();
    reopen(fx.store, fx.path)
}

fn short_timeouts(fx: Fixture) -> Fixture {
    Fixture {
        service: fx.service.with_timeouts(
            std::time::Duration::from_millis(500),
            std::time::Duration::from_millis(300),
        ),
        ..fx
    }
}

fn closed_port_url() -> String {
    let port = TcpListener::bind("127.0.0.1:0")
        .unwrap()
        .local_addr()
        .unwrap()
        .port();
    format!("http://127.0.0.1:{port}")
}

fn session_file(fx: &Fixture) -> SessionFile {
    serde_json::from_str(&std::fs::read_to_string(&fx.path).unwrap()).unwrap()
}

/// `emby_like` whose `/Users/{Id}` answer is chosen by the test.
fn user_answer(status: u16, body: String) -> Route {
    let base = emby_like(204);
    Box::new(move |r| {
        if r.path == format!("/emby/Users/{USER_ID}") {
            (status, body.clone())
        } else {
            base(r)
        }
    })
}

#[test]
fn restore_with_nas_offline_keeps_the_session_and_recovers_later() {
    let server = FakeEmby::start(emby_like(204));
    let fx = fixture();
    run(fx.service.sign_in(&server.url, "testeur", PASSWORD)).unwrap();

    let fx = retarget(fx, &closed_port_url());
    let status = run(fx.service.restore()).unwrap();
    assert_eq!(unreachable_kind(status), "unreachable");
    // Nothing is wiped by a connectivity failure, even after several attempts.
    let status = run(fx.service.restore()).unwrap();
    assert_eq!(unreachable_kind(status), "unreachable");
    assert_eq!(stored_token(&fx).unwrap().expose(), TOKEN);
    assert!(session_file(&fx).session.is_some());
    assert!(session_file(&fx).reauth.is_none());
    assert!(fx.service.current_user().is_none());

    // The NAS comes back: the same session opens without a password.
    let fx = retarget(fx, &server.url);
    let user = authenticated(run(fx.service.restore()).unwrap());
    assert_eq!(user.user_id, USER_ID);
}

#[test]
fn restore_on_timeout_or_server_errors_never_invalidates() {
    for (route, kind) in [
        (user_answer(500, String::new()), "server"),
        (user_answer(503, String::new()), "server"),
        (
            user_answer(200, "<html>proxy</html>".into()),
            "invalidResponse",
        ),
        (
            Box::new(|r: &Recorded| match r.path.as_str() {
                "/emby/System/Info/Public" => (502u16, String::new()),
                _ => (200, String::new()),
            }) as Route,
            "server",
        ),
    ] {
        let good = FakeEmby::start(emby_like(204));
        let fx = fixture();
        run(fx.service.sign_in(&good.url, "testeur", PASSWORD)).unwrap();
        let bad = FakeEmby::start(route);
        let fx = retarget(fx, &bad.url);
        let status = run(fx.service.restore()).unwrap();
        assert_eq!(unreachable_kind(status), kind);
        assert_eq!(stored_token(&fx).unwrap().expose(), TOKEN, "{kind}");
        assert!(session_file(&fx).session.is_some(), "{kind}");
    }

    // A server that accepts the connection but never answers in time.
    let good = FakeEmby::start(emby_like(204));
    let fx = fixture();
    run(fx.service.sign_in(&good.url, "testeur", PASSWORD)).unwrap();
    let base = emby_like(204);
    let slow = FakeEmby::start(Box::new(move |r| {
        if r.path == format!("/emby/Users/{USER_ID}") {
            std::thread::sleep(std::time::Duration::from_millis(800));
        }
        base(r)
    }));
    let fx = short_timeouts(retarget(fx, &slow.url));
    let status = run(fx.service.restore()).unwrap();
    assert_eq!(unreachable_kind(status), "timeout");
    assert_eq!(stored_token(&fx).unwrap().expose(), TOKEN);
    assert!(session_file(&fx).session.is_some());
}

#[test]
fn restore_probes_without_the_token_and_refuses_another_server() {
    let good = FakeEmby::start(emby_like(204));
    let fx = fixture();
    run(fx.service.sign_in(&good.url, "testeur", PASSWORD)).unwrap();
    // Another Emby server now answers at the saved address.
    let other = FakeEmby::start(Box::new(|r| match r.path.as_str() {
        "/emby/System/Info/Public" => (200, public_info().replace(SERVER_ID, "otherid")),
        _ => (200, user()),
    }));
    let fx = retarget(fx, &other.url);
    let status = run(fx.service.restore()).unwrap();
    assert_eq!(unreachable_kind(status), "serverMismatch");
    let reqs = other.requests();
    assert_eq!(reqs.len(), 1, "only the public probe: {reqs:?}");
    assert!(
        !reqs[0]
            .auth
            .as_deref()
            .unwrap_or_default()
            .contains("Token=")
    );
    assert_eq!(stored_token(&fx).unwrap().expose(), TOKEN);

    // On the right server, the probe still goes without the token.
    let fx = retarget(fx, &good.url);
    let before = good.requests().len();
    authenticated(run(fx.service.restore()).unwrap());
    let reqs = good.requests();
    let probe = &reqs[before];
    assert_eq!(probe.path, "/emby/System/Info/Public");
    assert!(!probe.auth.as_deref().unwrap_or_default().contains("Token="));
}

#[test]
fn disabled_or_deleted_account_requires_reauthentication() {
    let disabled = format!(
        r#"{{"Name":"testeur","Id":"{USER_ID}","Policy":{{"IsAdministrator":false,"IsDisabled":true}}}}"#
    );
    for route in [
        user_answer(200, disabled),
        user_answer(404, "\"User not found\"".into()),
        user_answer(403, String::new()),
    ] {
        let good = FakeEmby::start(emby_like(204));
        let fx = fixture();
        run(fx.service.sign_in(&good.url, "testeur", PASSWORD)).unwrap();
        let server = FakeEmby::start(route);
        let fx = retarget(fx, &server.url);
        let status = run(fx.service.restore()).unwrap();
        assert_eq!(reauth_kind(status), "accountUnavailable");
        assert!(stored_token(&fx).is_none());
        let file = session_file(&fx);
        assert!(file.session.is_none());
        assert_eq!(file.reauth.unwrap().user_name, "testeur");
    }
}

#[test]
fn a_token_removed_from_the_vault_asks_for_the_password() {
    let server = FakeEmby::start(emby_like(204));
    let fx = fixture();
    run(fx.service.sign_in(&server.url, "testeur", PASSWORD)).unwrap();
    fx.store.delete(&account(SERVER_ID, USER_ID)).unwrap();
    let fx = reopen(fx.store, fx.path);
    let before = server.requests().len();
    assert_eq!(
        reauth_kind(run(fx.service.restore()).unwrap()),
        "sessionExpired"
    );
    assert_eq!(
        server.requests().len(),
        before,
        "no request without a token"
    );
}

#[test]
fn server_errors_while_browsing_keep_the_session() {
    let mode = Arc::new(std::sync::atomic::AtomicU8::new(0));
    let flag = Arc::clone(&mode);
    let base = browse_like(Arc::new(AtomicBool::new(false)));
    let server = FakeEmby::start(Box::new(move |r| match flag.load(Ordering::SeqCst) {
        1 => (503, String::new()),
        2 => (0, String::new()), // connection dropped
        _ => base(r),
    }));
    let fx = fixture();
    run(fx.service.sign_in(&server.url, "testeur", PASSWORD)).unwrap();
    run(fx.service.libraries()).unwrap();

    mode.store(1, Ordering::SeqCst);
    let err = run(fx.service.libraries()).unwrap_err();
    assert!(matches!(err, EmbyError::Server(503)), "{err:?}");
    mode.store(2, Ordering::SeqCst);
    let err = run(fx.service.items("5", 0, 50, &Default::default())).unwrap_err();
    assert!(
        matches!(
            err,
            EmbyError::Unreachable(_) | EmbyError::InvalidResponse(_)
        ),
        "{err:?}"
    );
    assert_eq!(
        unreachable_kind(run(fx.service.restore()).unwrap()),
        "unreachable"
    );
    assert!(fx.service.current_user().is_some());
    assert_eq!(stored_token(&fx).unwrap().expose(), TOKEN);

    // Back online: the check succeeds and the known libraries are still accepted.
    mode.store(0, Ordering::SeqCst);
    authenticated(run(fx.service.restore()).unwrap());
    let before = server.requests().len();
    run(fx.service.items("5", 0, 50, &Default::default())).unwrap();
    assert_eq!(server.requests().len(), before + 1, "no /Views refetch");
}

/// Fake server issuing a new token at each sign-in (`tokA`, then `tokB`) and
/// accepting both, so a test can hold a client of a previous session.
fn rotating_tokens() -> Route {
    let issued = Arc::new(std::sync::atomic::AtomicUsize::new(0));
    Box::new(move |r| match (r.method.as_str(), r.path.as_str()) {
        ("GET", "/emby/System/Info/Public") => (200, public_info()),
        ("POST", "/emby/Users/AuthenticateByName") => {
            let n = issued.fetch_add(1, Ordering::SeqCst);
            let token = if n == 0 { "tokA" } else { "tokB" };
            (200, auth_result().replace(TOKEN, token))
        }
        ("POST", "/emby/Sessions/Logout") => (204, String::new()),
        _ => (200, user()),
    })
}

#[test]
fn a_late_401_from_a_previous_session_is_ignored() {
    let server = FakeEmby::start(rotating_tokens());
    let fx = fixture();
    run(fx.service.sign_in(&server.url, "testeur", PASSWORD)).unwrap();
    let old = fx.service.client().unwrap();
    run(fx.service.sign_in(&server.url, "testeur", PASSWORD)).unwrap();

    let late = fx
        .service
        .expire_on_401(&old, Err::<(), _>(EmbyError::SessionExpired));
    assert!(matches!(late, Err(EmbyError::Cancelled)), "{late:?}");
    assert!(fx.service.current_user().is_some());
    assert_eq!(stored_token(&fx).unwrap().expose(), "tokB");

    // After a sign-out, a late 401 of the closed session changes nothing either.
    let current = fx.service.client().unwrap();
    run(fx.service.sign_out()).unwrap();
    let late = fx
        .service
        .expire_on_401(&current, Err::<(), _>(EmbyError::SessionExpired));
    assert!(matches!(late, Err(EmbyError::Cancelled)), "{late:?}");
}

#[test]
fn concurrent_401s_end_the_session_once() {
    let server = FakeEmby::start(rotating_tokens());
    let fx = fixture();
    run(fx.service.sign_in(&server.url, "testeur", PASSWORD)).unwrap();
    let client = fx.service.client().unwrap();
    // Several home rows fail together with the same token.
    let results: Vec<_> = std::thread::scope(|sc| {
        let handles: Vec<_> = (0..4)
            .map(|_| {
                sc.spawn(|| {
                    fx.service
                        .expire_on_401(&client, Err::<(), _>(EmbyError::SessionExpired))
                        .unwrap_err()
                        .kind()
                })
            })
            .collect();
        handles.into_iter().map(|h| h.join().unwrap()).collect()
    });
    // Each failing request reports the expiry; the session ends once.
    assert!(
        results.iter().all(|k| *k == "sessionExpired"),
        "{results:?}"
    );
    assert!(fx.service.current_user().is_none());
    assert!(stored_token(&fx).is_none());
}

#[test]
fn change_server_during_a_check_cancels_it() {
    let good = FakeEmby::start(emby_like(204));
    let fx = fixture();
    run(fx.service.sign_in(&good.url, "testeur", PASSWORD)).unwrap();
    let base = emby_like(204);
    let slow = FakeEmby::start(Box::new(move |r| {
        std::thread::sleep(std::time::Duration::from_millis(300));
        base(r)
    }));
    let fx = retarget(fx, &slow.url);
    let result = std::thread::scope(|sc| {
        sc.spawn(|| {
            std::thread::sleep(std::time::Duration::from_millis(100));
            fx.service.change_server().unwrap();
        });
        run(fx.service.restore())
    });
    assert!(matches!(result, Err(EmbyError::Cancelled)), "{result:?}");
    assert!(fx.service.current_user().is_none());
    assert_eq!(fx.service.saved_sessions().len(), 1);
    assert_eq!(stored_token(&fx).unwrap().expose(), TOKEN);
}

#[test]
fn change_server_sets_the_session_aside_and_returns_to_it() {
    let server = FakeEmby::start(emby_like(204));
    let fx = fixture();
    run(fx.service.sign_in(&server.url, "testeur", PASSWORD)).unwrap();
    let before = server.requests().len();
    fx.service.change_server().unwrap();

    assert!(fx.service.current_user().is_none());
    assert_eq!(server.requests().len(), before, "no logout, nothing sent");
    assert_eq!(stored_token(&fx).unwrap().expose(), TOKEN);
    let fx = reopen(fx.store, fx.path);
    assert!(matches!(
        run(fx.service.restore()).unwrap(),
        SessionStatus::SignedOut
    ));
    let saved = fx.service.saved_sessions();
    assert_eq!(saved.len(), 1);
    assert_eq!(
        (saved[0].user_name.as_str(), saved[0].name.as_str()),
        ("testeur", "ServeurTest")
    );
    let json = serde_json::to_string(&saved).unwrap();
    assert!(!json.contains(TOKEN), "{json}");

    let err = run(fx.service.resume_saved("unknown", USER_ID)).unwrap_err();
    assert!(matches!(err, EmbyError::NotFound), "{err:?}");
    let user = authenticated(run(fx.service.resume_saved(SERVER_ID, USER_ID)).unwrap());
    assert_eq!(user.user_id, USER_ID);
    assert!(fx.service.saved_sessions().is_empty());
    assert!(session_file(&fx).session.is_some());
}

#[test]
fn set_aside_sessions_are_bounded_and_the_oldest_token_is_deleted() {
    let server = FakeEmby::start(emby_like(204));
    let fx = fixture();
    run(fx.service.sign_in(&server.url, "testeur", PASSWORD)).unwrap();
    let mut file = session_file(&fx);
    let template = file.session.clone().unwrap();
    for i in 0..super::session::MAX_SAVED {
        let mut r = template.clone();
        r.server_id = format!("srv{i}");
        fx.store
            .save(
                &account(&r.server_id, USER_ID),
                &AccessToken::new(format!("t{i}")),
            )
            .unwrap();
        file.saved.push(r);
    }
    file.save(&fx.path).unwrap();
    let fx = reopen(fx.store, fx.path);
    authenticated(run(fx.service.restore()).unwrap());
    fx.service.change_server().unwrap();

    let saved = fx.service.saved_sessions();
    assert_eq!(saved.len(), super::session::MAX_SAVED);
    assert_eq!(saved[0].id, SERVER_ID, "newest first");
    let oldest = account(&format!("srv{}", super::session::MAX_SAVED - 1), USER_ID);
    assert!(fx.store.load(&oldest).unwrap().is_none());
    assert!(fx.store.load(&account("srv0", USER_ID)).unwrap().is_some());
}

#[test]
fn sign_out_keeps_set_aside_sessions_and_clears_the_hint() {
    let server = FakeEmby::start(emby_like(204));
    let fx = fixture();
    run(fx.service.sign_in(&server.url, "testeur", PASSWORD)).unwrap();
    fx.service.change_server().unwrap();
    // A second server with its own id and user.
    let other_user = "0b1a2c3d4e5f60718293a4b5c6d7e8f9";
    let second = FakeEmby::start(Box::new(move |r| {
        let body = match r.path.as_str() {
            "/emby/System/Info/Public" => public_info().replace(SERVER_ID, "secondsrv"),
            "/emby/Users/AuthenticateByName" => auth_result()
                .replace(SERVER_ID, "secondsrv")
                .replace(USER_ID, other_user),
            _ => String::new(),
        };
        (if body.is_empty() { 204 } else { 200 }, body)
    }));
    run(fx.service.sign_in(&second.url, "testeur", PASSWORD)).unwrap();
    run(fx.service.sign_out()).unwrap();
    let saved = fx.service.saved_sessions();
    assert_eq!(saved.len(), 1);
    assert_eq!(saved[0].id, SERVER_ID);
    assert_eq!(stored_token(&fx).unwrap().expose(), TOKEN);
    assert!(session_file(&fx).reauth.is_none());
}

/// Home hero (ADR 0015) on the real server, read only: at most five titles, one per
/// film or series, each with a backdrop; a series plays an episode not watched.
#[test]
#[ignore = "needs the real Emby server and VIEWER_EMBY_* variables"]
fn real_server_hero() {
    use super::models::HeroReason;
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

    let t = std::time::Instant::now();
    let hero = run(fx.service.hero()).unwrap();
    println!("{} titre(s), {} ms", hero.len(), t.elapsed().as_millis());
    assert!(!hero.is_empty() && hero.len() <= 5);
    for (i, h) in hero.iter().enumerate() {
        println!(
            "{:?} {} « {} » logo={} résumé={} lecture={:?} épisode={:?} reprise={:?}",
            h.reason,
            h.detail.item_type,
            h.detail.name,
            h.logo.is_some(),
            h.overview.is_some(),
            h.play_id,
            h.episode,
            h.resume.as_ref().map(|r| r.position_seconds),
        );
        assert!(hero[..i].iter().all(|o| o.detail.id != h.detail.id));
        assert!(matches!(h.detail.item_type.as_str(), "Movie" | "Series"));
        assert!(!h.backdrop.src.is_empty());
        if h.reason == HeroReason::Latest && h.detail.item_type == "Series" {
            let e = h.episode.as_ref().expect("épisode à lire");
            assert!(e.season_index.is_some_and(|s| s > 0), "{e:?}");
        }
        if h.detail.item_type == "Movie" {
            assert_eq!(h.play_id.as_deref(), Some(h.detail.id.as_str()));
        }
    }
    let resumed = hero.iter().filter(|h| h.reason == HeroReason::Resume);
    assert!(
        hero.iter()
            .skip(resumed.count())
            .all(|h| h.reason == HeroReason::Latest)
    );
}

/// Read only: the last episode of a season of a series with several seasons has
/// its neighbours in the same season before and in the next season after (ADR 0016).
#[test]
#[ignore = "needs the real Emby server and VIEWER_EMBY_* variables"]
fn real_server_episode_detail() {
    use super::models::DetailKind;
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

    let mut checked = 0;
    'libraries: for lib in run(fx.service.libraries()).unwrap() {
        let page = run(fx.service.items(&lib.id, 0, 100, &Default::default())).unwrap();
        for series in page.items.iter().filter(|i| i.item_type == "Series") {
            let seasons: Vec<_> = run(fx.service.seasons(&series.id))
                .unwrap()
                .into_iter()
                .filter(|s| s.index.is_some_and(|i| i > 0))
                .collect();
            if seasons.len() < 2 {
                continue;
            }
            let eps = run(fx.service.episodes(&series.id, &seasons[0].id, 0, 200)).unwrap();
            let regular: Vec<_> = eps
                .items
                .iter()
                .filter(|e| e.season_index == seasons[0].index)
                .collect();
            let Some(last) = regular.last() else { continue };
            let t = std::time::Instant::now();
            let d = run(fx.service.item_detail(&last.id)).unwrap();
            let ms = t.elapsed().as_millis();
            let ep = d.episode.as_ref().expect("episode context");
            let show = |l: &Option<super::models::EpisodeLink>| {
                l.as_ref()
                    .map(|l| format!("S{:?}E{:?} {}", l.season_index, l.index, l.name))
            };
            println!(
                "{} S{:?}E{:?} « {} » en {ms} ms : précédent={:?} suivant={:?} réal={:?} scénario={:?} invités={} image={} logo={} médias={}",
                ep.series_name,
                ep.season_index,
                ep.index,
                d.name,
                show(&ep.previous),
                show(&ep.next),
                d.directors,
                d.writers,
                d.cast.len(),
                d.backdrop.is_some(),
                d.logo.is_some(),
                d.media.len(),
            );
            assert_eq!(d.kind, DetailKind::Episode);
            assert_eq!(ep.series_id, series.id);
            let next = ep
                .next
                .as_ref()
                .expect("an episode after the season's last");
            assert!(
                next.season_index > ep.season_index,
                "next crosses to a later season: {next:?}"
            );
            if regular.len() > 1 {
                let prev = ep.previous.as_ref().expect("previous in the season");
                assert_eq!(prev.id, regular[regular.len() - 2].id);
            }
            assert!(d.backdrop.is_some() && !d.media.is_empty());
            let json = serde_json::to_string(&d).unwrap();
            assert!(!json.contains("/share/"));
            checked += 1;
            if checked == 3 {
                break 'libraries;
            }
        }
    }
    assert!(checked > 0, "no series with two regular seasons");
}
