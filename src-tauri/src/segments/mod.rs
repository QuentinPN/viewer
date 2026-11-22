//! Intro and credits segments for the player: Emby's chapter markers
//! first, then IntroDB, then TheIntroDB. Each kind comes from the first source that
//! gives it. Only the film's or series' provider ids leave the app, never a token,
//! a server address or a title.

mod providers;
#[cfg(test)]
mod tests;

use std::collections::{HashMap, HashSet};
use std::sync::Mutex;
use std::time::Duration;

use serde::Serialize;

use crate::emby::models::ChapterDto;
pub use providers::SegmentProvider;

const TICKS_PER_SECOND: f64 = 10_000_000.0;
/// Shorter than this is not worth a button.
const MIN_SEGMENT_SECONDS: f64 = 1.0;
/// TheIntroDB refuses longer submissions; anything beyond is malformed.
const MAX_POSITION_SECONDS: f64 = 6.0 * 3600.0;
/// Items kept in memory; the cache is emptied when full.
const CACHE_ENTRIES: usize = 256;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize)]
#[serde(rename_all = "camelCase")]
pub enum SegmentKind {
    Intro,
    Credits,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub enum SegmentSource {
    Emby,
    IntroDb,
    TheIntroDb,
}

/// A range to skip, in seconds of the file. `end: None` runs to the end of the file
/// (credits only).
#[derive(Debug, Clone, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Segment {
    pub kind: SegmentKind,
    pub start: f64,
    pub end: Option<f64>,
    pub source: SegmentSource,
}

impl Segment {
    /// `None` for a range that cannot be skipped: negative, reversed, too short,
    /// beyond six hours, or an intro with no end.
    pub fn new(
        kind: SegmentKind,
        start: f64,
        end: Option<f64>,
        source: SegmentSource,
    ) -> Option<Self> {
        let valid = |t: f64| t.is_finite() && (0.0..=MAX_POSITION_SECONDS).contains(&t);
        if !valid(start) {
            return None;
        }
        match end {
            Some(e) if !valid(e) || e - start < MIN_SEGMENT_SECONDS => return None,
            None if kind == SegmentKind::Intro => return None,
            _ => {}
        }
        Some(Self {
            kind,
            start,
            end,
            source,
        })
    }
}

/// External ids of a film or series, validated before they reach a URL.
#[derive(Debug, Clone, Default, PartialEq)]
pub struct MediaIds {
    /// `tt` and 7 or 8 digits.
    pub imdb: Option<String>,
    pub tmdb: Option<u64>,
    pub tvdb: Option<u64>,
}

impl MediaIds {
    /// From Emby's `ProviderIds`, whatever the key case (`Imdb`, `IMDB`).
    pub fn from_provider_ids(ids: &HashMap<String, String>) -> Self {
        let get = |name: &str| {
            ids.iter()
                .find(|(k, _)| k.eq_ignore_ascii_case(name))
                .map(|(_, v)| v.trim())
        };
        let number = |name| {
            get(name)
                .filter(|v| !v.is_empty() && v.bytes().all(|b| b.is_ascii_digit()))
                .and_then(|v| v.parse::<u64>().ok())
                .filter(|&n| (1..=100_000_000).contains(&n))
        };
        let imdb = get("Imdb")
            .filter(|v| {
                v.strip_prefix("tt").is_some_and(|d| {
                    (7..=8).contains(&d.len()) && d.bytes().all(|b| b.is_ascii_digit())
                })
            })
            .map(str::to_owned);
        Self {
            imdb,
            tmdb: number("Tmdb"),
            tvdb: number("Tvdb"),
        }
    }

    pub fn is_empty(&self) -> bool {
        self.imdb.is_none() && self.tmdb.is_none() && self.tvdb.is_none()
    }
}

/// What the external services are asked: a film, or an episode of a series by
/// the series' ids.
#[derive(Debug, Clone, PartialEq)]
pub struct SegmentQuery {
    pub ids: MediaIds,
    /// Season and episode numbers, for an episode.
    pub episode: Option<(u32, u32)>,
    /// Length of the file, which picks the release version at TheIntroDB.
    pub duration_ms: Option<u64>,
}

/// Segments from Emby's chapter markers: `IntroStart`–`IntroEnd`, and
/// `CreditsStart` to the end of the file.
pub fn emby_markers(chapters: &[ChapterDto]) -> Vec<Segment> {
    let at = |marker: &str| {
        chapters
            .iter()
            .find(|c| c.marker_type.as_deref() == Some(marker))
            .map(|c| c.start_position_ticks as f64 / TICKS_PER_SECOND)
    };
    let intro = match (at("IntroStart"), at("IntroEnd")) {
        (Some(start), Some(end)) => {
            Segment::new(SegmentKind::Intro, start, Some(end), SegmentSource::Emby)
        }
        _ => None,
    };
    let credits = at("CreditsStart")
        .and_then(|start| Segment::new(SegmentKind::Credits, start, None, SegmentSource::Emby));
    intro.into_iter().chain(credits).collect()
}

/// One source's answer: its segments, and the kinds it confirmed the item has none
/// of (IntroDB's `no_intro`, `no_outro`).
#[derive(Debug, Default, PartialEq)]
pub struct Found {
    pub segments: Vec<Segment>,
    pub absent: Vec<SegmentKind>,
}

/// A failure worth retrying at the next playback: network, timeout, 429, 5xx or an
/// unreadable answer. Never shown to the user.
#[derive(Debug, PartialEq)]
pub struct Transient;

/// Asks the sources in order and keeps definitive answers in memory.
pub struct SegmentService {
    http: reqwest::Client,
    providers: Vec<SegmentProvider>,
    cache: Mutex<HashMap<String, Vec<Segment>>>,
}

impl SegmentService {
    pub fn new(version: &str) -> Self {
        Self::with_providers(
            version,
            vec![SegmentProvider::introdb(), SegmentProvider::theintrodb()],
        )
    }

    /// Separate from Emby's client: no Emby header, no redirect, TLS validated.
    pub fn with_providers(version: &str, providers: Vec<SegmentProvider>) -> Self {
        let http = reqwest::Client::builder()
            .user_agent(format!("Viewer/{version}"))
            .connect_timeout(Duration::from_secs(3))
            .timeout(Duration::from_secs(4))
            .redirect(reqwest::redirect::Policy::none())
            .build()
            .unwrap_or_default();
        Self {
            http,
            providers,
            cache: Mutex::new(HashMap::new()),
        }
    }

    pub fn cached(&self, key: &str) -> Option<Vec<Segment>> {
        self.cache
            .lock()
            .unwrap_or_else(|p| p.into_inner())
            .get(key)
            .cloned()
    }

    /// Emby's markers, completed by the external sources when `external` allows
    /// them and the item has ids. A source is skipped once every kind is settled.
    pub async fn lookup(
        &self,
        key: String,
        markers: Vec<Segment>,
        query: Option<&SegmentQuery>,
        external: bool,
    ) -> Vec<Segment> {
        let mut segments = markers;
        let mut settled: HashSet<SegmentKind> = segments.iter().map(|s| s.kind).collect();
        let mut complete = true;
        if let Some(query) = query.filter(|_| external) {
            for provider in &self.providers {
                if settled.len() == 2 {
                    break;
                }
                match provider.fetch(&self.http, query).await {
                    Ok(found) => {
                        let fresh: Vec<Segment> = found
                            .segments
                            .into_iter()
                            .filter(|s| !settled.contains(&s.kind))
                            .collect();
                        settled.extend(fresh.iter().map(|s| s.kind));
                        settled.extend(found.absent);
                        segments.extend(fresh);
                    }
                    Err(Transient) => complete = false,
                }
            }
        }
        segments.sort_by(|a, b| a.start.total_cmp(&b.start));
        if complete {
            let mut cache = self.cache.lock().unwrap_or_else(|p| p.into_inner());
            if cache.len() >= CACHE_ENTRIES {
                cache.clear();
            }
            cache.insert(key, segments.clone());
        }
        segments
    }
}
