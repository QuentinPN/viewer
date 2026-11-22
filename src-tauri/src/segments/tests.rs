use std::collections::HashMap;
use std::io::{BufRead, BufReader, Write};
use std::net::TcpListener;
use std::sync::{Arc, Mutex};

use super::providers::{parse_introdb, parse_theintrodb};
use super::*;

// Real answers of 2026-11-07.
const INTRODB_TV: &str = r#"{"imdb_id":"tt0903747","media_type":"tv","is_movie":false,"season":1,"episode":1,
  "intro":null,"recap":null,
  "outro":{"start_sec":3431,"end_sec":3500,"start_ms":3431000,"end_ms":3500000,"confidence":1,"submission_count":1,"updated_at":"2026-04-14T12:21:01.935Z"},
  "post_credits":null,"no_intro":true,"no_recap":true,"no_outro":false}"#;
const INTRODB_EMPTY: &str = r#"{"imdb_id":"tt9999998","media_type":"movie","is_movie":true,"season":0,"episode":0,
  "intro":null,"recap":null,"outro":null,"post_credits":null,"no_intro":false,"no_recap":false,"no_outro":false}"#;
// Shape of IntroDB's documentation example (https://introdb.app).
const INTRODB_INTRO: &str = r#"{"imdb_id":"tt0903747","media_type":"tv","is_movie":false,"season":1,"episode":1,
  "intro":{"start_sec":2.5,"end_sec":58,"start_ms":2500,"end_ms":58000,"confidence":0.92,"submission_count":14,"updated_at":"2026-09-12T21:03:17.409Z"},
  "recap":null,"outro":null,"post_credits":null,"no_intro":false,"no_recap":true,"no_outro":false}"#;
const THEINTRODB_TV: &str = r#"{"tvdb_id":71663,"type":"tv","season":1,"episode":1,
  "intro":[{"start_ms":null,"end_ms":24724}],
  "credits":[{"start_ms":1332112,"end_ms":1441100}]}"#;
// TheIntroDB's specification example for a film (theintrodb.org/openapi.yaml).
const THEINTRODB_MOVIE: &str = r#"{"tvdb_id":169,"type":"movie",
  "intro":[{"start_ms":null,"end_ms":23000}],
  "recap":[{"start_ms":25000,"end_ms":134000}],
  "credits":[{"start_ms":5801777,"end_ms":6371111},{"start_ms":6408000,"end_ms":null}],
  "preview":[{"start_ms":1680000,"end_ms":1740000}]}"#;
const THEINTRODB_404: &str = r#"{"error":"no submissions for this media","code":"no_submissions"}"#;

fn seg(kind: SegmentKind, start: f64, end: Option<f64>, source: SegmentSource) -> Segment {
    Segment::new(kind, start, end, source).unwrap()
}

fn ids(pairs: &[(&str, &str)]) -> HashMap<String, String> {
    pairs
        .iter()
        .map(|(k, v)| (k.to_string(), v.to_string()))
        .collect()
}

fn episode_query() -> SegmentQuery {
    SegmentQuery {
        ids: MediaIds::from_provider_ids(&ids(&[
            ("Tvdb", "71663"),
            ("IMDB", "tt0903747"),
            ("Tmdb", "2406"),
        ])),
        episode: Some((1, 1)),
        duration_ms: Some(1_441_100),
    }
}

#[test]
fn introdb_answers() {
    let found = parse_introdb(INTRODB_TV.as_bytes()).unwrap();
    assert_eq!(
        found.segments,
        [seg(
            SegmentKind::Credits,
            3431.0,
            Some(3500.0),
            SegmentSource::IntroDb
        )]
    );
    assert_eq!(found.absent, [SegmentKind::Intro]);
    let found = parse_introdb(INTRODB_INTRO.as_bytes()).unwrap();
    assert_eq!(
        found.segments,
        [seg(
            SegmentKind::Intro,
            2.5,
            Some(58.0),
            SegmentSource::IntroDb
        )]
    );
    assert!(found.absent.is_empty());
    assert_eq!(
        parse_introdb(INTRODB_EMPTY.as_bytes()).unwrap(),
        Found::default()
    );
    assert!(parse_introdb(b"<html>").is_none());
}

#[test]
fn theintrodb_answers_with_open_ends() {
    let found = parse_theintrodb(THEINTRODB_TV.as_bytes()).unwrap();
    assert_eq!(
        found.segments,
        [
            seg(
                SegmentKind::Intro,
                0.0,
                Some(24.724),
                SegmentSource::TheIntroDb
            ),
            seg(
                SegmentKind::Credits,
                1332.112,
                Some(1441.1),
                SegmentSource::TheIntroDb
            ),
        ]
    );
    // Recap and preview are not offered; a null credits end runs to the end.
    let found = parse_theintrodb(THEINTRODB_MOVIE.as_bytes()).unwrap();
    let kinds: Vec<_> = found
        .segments
        .iter()
        .map(|s| (s.kind, s.start, s.end))
        .collect();
    assert_eq!(
        kinds,
        [
            (SegmentKind::Intro, 0.0, Some(23.0)),
            (SegmentKind::Credits, 5801.777, Some(6371.111)),
            (SegmentKind::Credits, 6408.0, None),
        ]
    );
    // "0 may be returned for no segment"; an intro without end is meaningless.
    let none = r#"{"intro":[{"start_ms":0,"end_ms":0},{"start_ms":5000,"end_ms":null}],"credits":[{"start_ms":0,"end_ms":0}]}"#;
    assert!(
        parse_theintrodb(none.as_bytes())
            .unwrap()
            .segments
            .is_empty()
    );
}

#[test]
fn invalid_ranges_are_dropped() {
    use SegmentKind::*;
    let s = SegmentSource::Emby;
    assert!(Segment::new(Intro, -1.0, Some(10.0), s).is_none());
    assert!(Segment::new(Intro, 10.0, Some(10.5), s).is_none());
    assert!(Segment::new(Intro, 20.0, Some(10.0), s).is_none());
    assert!(Segment::new(Intro, f64::NAN, Some(10.0), s).is_none());
    assert!(Segment::new(Credits, 10.0, Some(f64::INFINITY), s).is_none());
    assert!(Segment::new(Credits, 30_000.0, None, s).is_none());
    assert!(Segment::new(Intro, 0.0, None, s).is_none());
    assert!(Segment::new(Credits, 100.0, None, s).is_some());
}

#[test]
fn provider_ids_are_validated_whatever_the_case() {
    let parsed = MediaIds::from_provider_ids(&ids(&[
        ("IMDB", " tt0284718 "),
        ("tmdb", "2406"),
        ("Tvdb", "74922"),
        ("Zap2It", "EP00446167"),
    ]));
    assert_eq!(
        parsed,
        MediaIds {
            imdb: Some("tt0284718".into()),
            tmdb: Some(2406),
            tvdb: Some(74922),
        }
    );
    for bad in [
        ("Imdb", "tt12"),
        ("Imdb", "tt0284718&x=1"),
        ("Imdb", "nm0000001"),
        ("Tmdb", "-3"),
        ("Tmdb", "0"),
        ("Tvdb", "12a"),
        ("Tvdb", "999999999999"),
    ] {
        assert!(
            MediaIds::from_provider_ids(&ids(&[bad])).is_empty(),
            "{bad:?}"
        );
    }
}

#[test]
fn urls_carry_one_id_and_the_episode() {
    let q = episode_query();
    assert_eq!(
        SegmentProvider::introdb().url(&q).unwrap(),
        "https://api.introdb.app/segments?imdb_id=tt0903747&season=1&episode=1"
    );
    assert_eq!(
        SegmentProvider::theintrodb().url(&q).unwrap(),
        "https://api.theintrodb.org/v4/media?tvdb_id=71663&duration_ms=1441100&season=1&episode=1"
    );
    let movie = SegmentQuery {
        ids: MediaIds {
            imdb: Some("tt23173980".into()),
            tmdb: Some(1049574),
            tvdb: None,
        },
        episode: None,
        duration_ms: None,
    };
    assert_eq!(
        SegmentProvider::introdb().url(&movie).unwrap(),
        "https://api.introdb.app/segments?imdb_id=tt23173980&is_movie=true"
    );
    assert_eq!(
        SegmentProvider::theintrodb().url(&movie).unwrap(),
        "https://api.theintrodb.org/v4/media?tmdb_id=1049574"
    );
    let no_imdb = SegmentQuery {
        ids: MediaIds {
            imdb: None,
            ..movie.ids
        },
        ..movie
    };
    assert!(SegmentProvider::introdb().url(&no_imdb).is_none());
}

#[test]
fn emby_markers_from_chapters() {
    let chapter = |ticks: i64, marker: &str| ChapterDto {
        start_position_ticks: ticks,
        name: None,
        marker_type: Some(marker.into()),
    };
    let chapters = [
        chapter(0, "Chapter"),
        chapter(300_000_000, "IntroStart"),
        chapter(900_000_000, "IntroEnd"),
        chapter(24_000_000_000, "CreditsStart"),
    ];
    assert_eq!(
        emby_markers(&chapters),
        [
            seg(SegmentKind::Intro, 30.0, Some(90.0), SegmentSource::Emby),
            seg(SegmentKind::Credits, 2400.0, None, SegmentSource::Emby),
        ]
    );
    // What the test server has: regular chapters only.
    let plain: Vec<_> = (0..9)
        .map(|i| chapter(i * 3_000_000_000, "Chapter"))
        .collect();
    assert!(emby_markers(&plain).is_empty());
    assert!(emby_markers(&[chapter(300_000_000, "IntroStart")]).is_empty());
}

/// A request path and its lowercased headers.
type Request = (String, Vec<String>);

/// Serves `(status, body)` by path prefix and records request lines and headers.
struct FakeService {
    url: String,
    requests: Arc<Mutex<Vec<Request>>>,
}

impl FakeService {
    fn start(routes: Vec<(&'static str, u16, &'static str)>) -> Self {
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
                let path = line
                    .split_whitespace()
                    .nth(1)
                    .unwrap_or_default()
                    .to_owned();
                let mut headers = Vec::new();
                loop {
                    let mut h = String::new();
                    reader.read_line(&mut h).unwrap();
                    if h.trim_end().is_empty() {
                        break;
                    }
                    headers.push(h.trim_end().to_ascii_lowercase());
                }
                let (status, body) = routes
                    .iter()
                    .find(|(prefix, _, _)| path.starts_with(prefix))
                    .map(|&(_, s, b)| (s, b))
                    .unwrap_or((404, "{}"));
                log.lock().unwrap().push((path, headers));
                let head = format!(
                    "HTTP/1.1 {status} X\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n",
                    body.len()
                );
                let _ = stream.write_all(format!("{head}{body}").as_bytes());
            }
        });
        Self { url, requests }
    }

    fn paths(&self) -> Vec<String> {
        self.requests
            .lock()
            .unwrap()
            .iter()
            .map(|(p, _)| p.clone())
            .collect()
    }
}

fn service(introdb: &FakeService, theintrodb: &FakeService) -> SegmentService {
    SegmentService::with_providers(
        "0.1.0",
        vec![
            SegmentProvider::introdb_at(&introdb.url),
            SegmentProvider::theintrodb_at(&format!("{}/v4", theintrodb.url)),
        ],
    )
}

fn block_on<F: std::future::Future>(f: F) -> F::Output {
    tauri::async_runtime::block_on(f)
}

#[test]
fn each_kind_comes_from_the_first_source_that_has_it() {
    // IntroDB: credits, and confirmed no intro: TheIntroDB is not asked.
    let a = FakeService::start(vec![("/segments", 200, INTRODB_TV)]);
    let b = FakeService::start(vec![("/v4/media", 200, THEINTRODB_TV)]);
    let svc = service(&a, &b);
    let q = episode_query();
    let found = block_on(svc.lookup("k1".into(), Vec::new(), Some(&q), true));
    assert_eq!(
        found,
        [seg(
            SegmentKind::Credits,
            3431.0,
            Some(3500.0),
            SegmentSource::IntroDb
        )]
    );
    assert_eq!(
        a.paths(),
        ["/segments?imdb_id=tt0903747&season=1&episode=1"]
    );
    assert!(b.paths().is_empty());
    // No Emby header and the app's own user agent.
    let headers = a.requests.lock().unwrap()[0].1.clone();
    assert!(headers.iter().all(|h| !h.starts_with("x-emby")));
    assert!(headers.contains(&"user-agent: viewer/0.1.0".to_owned()));

    // IntroDB knows nothing: TheIntroDB gives both.
    let a = FakeService::start(vec![("/segments", 200, INTRODB_EMPTY)]);
    let b = FakeService::start(vec![("/v4/media", 200, THEINTRODB_TV)]);
    let found = block_on(service(&a, &b).lookup("k2".into(), Vec::new(), Some(&q), true));
    assert_eq!(found.len(), 2);
    assert!(found.iter().all(|s| s.source == SegmentSource::TheIntroDb));
    assert_eq!(
        b.paths(),
        ["/v4/media?tvdb_id=71663&duration_ms=1441100&season=1&episode=1"]
    );

    // Emby's intro wins; only the credits come from outside.
    let a = FakeService::start(vec![("/segments", 200, INTRODB_INTRO)]);
    let b = FakeService::start(vec![("/v4/media", 200, THEINTRODB_TV)]);
    let emby = vec![seg(
        SegmentKind::Intro,
        30.0,
        Some(90.0),
        SegmentSource::Emby,
    )];
    let found = block_on(service(&a, &b).lookup("k3".into(), emby, Some(&q), true));
    let sources: Vec<_> = found.iter().map(|s| (s.kind, s.source)).collect();
    assert_eq!(
        sources,
        [
            (SegmentKind::Intro, SegmentSource::Emby),
            (SegmentKind::Credits, SegmentSource::TheIntroDb),
        ]
    );
}

#[test]
fn nothing_leaves_the_app_when_external_is_off_or_emby_has_both() {
    let a = FakeService::start(vec![("/segments", 200, INTRODB_TV)]);
    let b = FakeService::start(vec![("/v4/media", 200, THEINTRODB_TV)]);
    let svc = service(&a, &b);
    let q = episode_query();
    assert!(block_on(svc.lookup("off".into(), Vec::new(), Some(&q), false)).is_empty());
    let both = vec![
        seg(SegmentKind::Intro, 30.0, Some(90.0), SegmentSource::Emby),
        seg(SegmentKind::Credits, 2400.0, None, SegmentSource::Emby),
    ];
    assert_eq!(
        block_on(svc.lookup("both".into(), both.clone(), Some(&q), true)),
        both
    );
    assert!(block_on(svc.lookup("noids".into(), Vec::new(), None, true)).is_empty());
    assert!(a.paths().is_empty() && b.paths().is_empty());
}

#[test]
fn only_definitive_answers_are_cached() {
    let q = episode_query();
    // 404 at TheIntroDB is definitive: cached, nothing asked again.
    let a = FakeService::start(vec![("/segments", 200, INTRODB_EMPTY)]);
    let b = FakeService::start(vec![("/v4/media", 404, THEINTRODB_404)]);
    let svc = service(&a, &b);
    assert!(block_on(svc.lookup("item".into(), Vec::new(), Some(&q), true)).is_empty());
    assert_eq!(svc.cached("item"), Some(Vec::new()));

    // 429 and 5xx are retried at the next playback; the other source still counts.
    let a = FakeService::start(vec![("/segments", 429, r#"{"error":"rate"}"#)]);
    let b = FakeService::start(vec![("/v4/media", 200, THEINTRODB_TV)]);
    let svc = service(&a, &b);
    assert_eq!(
        block_on(svc.lookup("item".into(), Vec::new(), Some(&q), true)).len(),
        2
    );
    assert_eq!(svc.cached("item"), None);
    let a = FakeService::start(vec![("/segments", 503, "")]);
    let svc = service(&a, &b);
    block_on(svc.lookup("item".into(), Vec::new(), Some(&q), true));
    assert_eq!(svc.cached("item"), None);
}

#[test]
fn unreachable_or_hostile_services_give_nothing() {
    let q = episode_query();
    // Port closed.
    let closed = {
        let l = TcpListener::bind("127.0.0.1:0").unwrap();
        format!("http://{}", l.local_addr().unwrap())
    };
    let svc = SegmentService::with_providers(
        "0.1.0",
        vec![
            SegmentProvider::introdb_at(&closed),
            SegmentProvider::theintrodb_at(&closed),
        ],
    );
    assert!(block_on(svc.lookup("k".into(), Vec::new(), Some(&q), true)).is_empty());
    assert_eq!(svc.cached("k"), None);

    // A redirect is not followed, an oversized or malformed body is refused.
    let big: &'static str =
        Box::leak(format!("{{\"intro\":[{}]}}", "{}".repeat(40_000)).into_boxed_str());
    let a = FakeService::start(vec![("/segments", 302, "")]);
    let b = FakeService::start(vec![("/v4/media", 200, big)]);
    let svc = service(&a, &b);
    assert!(block_on(svc.lookup("k".into(), Vec::new(), Some(&q), true)).is_empty());
    let a = FakeService::start(vec![("/segments", 200, "not json")]);
    let svc = service(&a, &b);
    assert!(block_on(svc.lookup("k".into(), Vec::new(), Some(&q), true)).is_empty());
}
