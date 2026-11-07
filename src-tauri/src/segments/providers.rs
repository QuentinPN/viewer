//! IntroDB and TheIntroDB read endpoints (ADR 0030), both anonymous. Contracts
//! checked against their docs and real answers: `docs/evidence/intros-generiques-2026-11-07.md`.

use serde::Deserialize;

use super::{Found, Segment, SegmentKind, SegmentQuery, SegmentSource, Transient};

/// Larger answers are refused: a real one is under 1 KiB.
const MAX_BODY_BYTES: usize = 64 * 1024;

#[derive(Debug, Clone, Copy, PartialEq)]
enum Api {
    IntroDb,
    TheIntroDb,
}

/// One external source and where it answers.
#[derive(Debug, Clone)]
pub struct SegmentProvider {
    api: Api,
    base: String,
}

impl SegmentProvider {
    pub fn introdb() -> Self {
        Self::introdb_at("https://api.introdb.app")
    }

    pub fn theintrodb() -> Self {
        Self::theintrodb_at("https://api.theintrodb.org/v4")
    }

    pub fn introdb_at(base: &str) -> Self {
        Self {
            api: Api::IntroDb,
            base: base.trim_end_matches('/').to_owned(),
        }
    }

    pub fn theintrodb_at(base: &str) -> Self {
        Self {
            api: Api::TheIntroDb,
            base: base.trim_end_matches('/').to_owned(),
        }
    }

    /// The request URL, or `None` when the item lacks an id this source takes.
    /// Only one id is sent.
    pub fn url(&self, query: &SegmentQuery) -> Option<String> {
        let mut params: Vec<(&str, String)> = Vec::new();
        let path = match self.api {
            // IntroDB takes the IMDb id only.
            Api::IntroDb => {
                params.push(("imdb_id", query.ids.imdb.clone()?));
                if query.episode.is_none() {
                    params.push(("is_movie", "true".into()));
                }
                "/segments"
            }
            // TheIntroDB resolves TVDB first, then TMDB, then IMDb.
            Api::TheIntroDb => {
                let ids = &query.ids;
                let id = ids
                    .tvdb
                    .map(|v| ("tvdb_id", v.to_string()))
                    .or(ids.tmdb.map(|v| ("tmdb_id", v.to_string())))
                    .or(ids.imdb.clone().map(|v| ("imdb_id", v)))?;
                params.push(id);
                if let Some(ms) = query.duration_ms {
                    params.push(("duration_ms", ms.to_string()));
                }
                "/media"
            }
        };
        if let Some((season, episode)) = query.episode {
            params.push(("season", season.to_string()));
            params.push(("episode", episode.to_string()));
        }
        url::Url::parse_with_params(&format!("{}{path}", self.base), &params)
            .ok()
            .map(String::from)
    }

    pub async fn fetch(
        &self,
        http: &reqwest::Client,
        query: &SegmentQuery,
    ) -> Result<Found, Transient> {
        let Some(url) = self.url(query) else {
            return Ok(Found::default());
        };
        let mut res = http
            .get(url)
            .header("Accept", "application/json")
            .send()
            .await
            .map_err(|_| Transient)?;
        let status = res.status().as_u16();
        match status {
            200 => {}
            // A missing item, or a query it refuses: asking again will not help.
            429 => return Err(Transient),
            400..=499 => return Ok(Found::default()),
            _ => return Err(Transient),
        }
        let mut body = Vec::new();
        while let Some(chunk) = res.chunk().await.map_err(|_| Transient)? {
            if body.len() + chunk.len() > MAX_BODY_BYTES {
                return Err(Transient);
            }
            body.extend_from_slice(&chunk);
        }
        match self.api {
            Api::IntroDb => parse_introdb(&body),
            Api::TheIntroDb => parse_theintrodb(&body),
        }
        .ok_or(Transient)
    }
}

#[derive(Deserialize)]
struct IntroDbAnswer {
    #[serde(default)]
    intro: Option<IntroDbSegment>,
    /// End credits.
    #[serde(default)]
    outro: Option<IntroDbSegment>,
    #[serde(default)]
    no_intro: bool,
    #[serde(default)]
    no_outro: bool,
}

#[derive(Deserialize)]
struct IntroDbSegment {
    start_sec: f64,
    end_sec: f64,
}

/// `GET /segments`: `null` is "not timed yet", unless `no_*` confirms there is none.
pub fn parse_introdb(body: &[u8]) -> Option<Found> {
    let answer: IntroDbAnswer = serde_json::from_slice(body).ok()?;
    let segment = |kind, s: Option<IntroDbSegment>| {
        s.and_then(|s| Segment::new(kind, s.start_sec, Some(s.end_sec), SegmentSource::IntroDb))
    };
    let segments = segment(SegmentKind::Intro, answer.intro)
        .into_iter()
        .chain(segment(SegmentKind::Credits, answer.outro))
        .collect();
    let absent = [
        (answer.no_intro, SegmentKind::Intro),
        (answer.no_outro, SegmentKind::Credits),
    ]
    .into_iter()
    .filter_map(|(none, kind)| none.then_some(kind))
    .collect();
    Some(Found { segments, absent })
}

#[derive(Deserialize)]
struct TheIntroDbAnswer {
    #[serde(default)]
    intro: Vec<TheIntroDbRange>,
    #[serde(default)]
    credits: Vec<TheIntroDbRange>,
}

#[derive(Deserialize)]
struct TheIntroDbRange {
    #[serde(default)]
    start_ms: Option<i64>,
    #[serde(default)]
    end_ms: Option<i64>,
}

/// `GET /v4/media`: a null start is the beginning of the file, a null end its end;
/// an intro with no end, or a 0 end ("no segment"), is dropped.
pub fn parse_theintrodb(body: &[u8]) -> Option<Found> {
    let answer: TheIntroDbAnswer = serde_json::from_slice(body).ok()?;
    let seconds = |ms: i64| ms as f64 / 1000.0;
    let ranges = |kind, list: Vec<TheIntroDbRange>| {
        list.into_iter()
            .filter(|r| r.end_ms != Some(0))
            .filter_map(move |r| {
                Segment::new(
                    kind,
                    seconds(r.start_ms.unwrap_or(0)),
                    r.end_ms.map(seconds),
                    SegmentSource::TheIntroDb,
                )
            })
    };
    let segments = ranges(SegmentKind::Intro, answer.intro)
        .chain(ranges(SegmentKind::Credits, answer.credits))
        .collect();
    Some(Found {
        segments,
        absent: Vec::new(),
    })
}
