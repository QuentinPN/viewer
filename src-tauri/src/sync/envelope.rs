//! Linked playback envelope (ADR 0124): one JSON object of at most 1024 bytes,
//! relayed by Emby `SendString`. Decoding checks every bound of the ADR; a
//! rejected message is dropped without answer, only its reason is logged.

use serde_json::{Map, Value, json};

pub const MAX_BYTES: usize = 1024;
pub const MAX_POSITION_MS: u32 = 86_400_000;
const MAX_SEQ: u64 = (1 << 31) - 1;

/// Why a message was rejected; the codes are those of `docs/sync/vectors.json`.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Reject {
    Json,
    Size,
    Version,
    Field,
    Kind,
}

impl Reject {
    pub fn code(self) -> &'static str {
        match self {
            Self::Json => "json",
            Self::Size => "size",
            Self::Version => "version",
            Self::Field => "field",
            Self::Kind => "kind",
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Cmd {
    Pause,
    Play,
    Seek { position_ms: u32 },
    Next,
    Previous,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ByeReason {
    Stopped,
    Left,
    Idle,
    Busy,
    Timeout,
}

impl ByeReason {
    const ALL: [Self; 5] = [
        Self::Stopped,
        Self::Left,
        Self::Idle,
        Self::Busy,
        Self::Timeout,
    ];

    pub fn as_str(self) -> &'static str {
        match self {
            Self::Stopped => "stopped",
            Self::Left => "left",
            Self::Idle => "idle",
            Self::Busy => "busy",
            Self::Timeout => "timeout",
        }
    }
}

/// What `load` opens on the TV: no URL, the TV negotiates its own stream.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Load {
    pub item_id: String,
    pub media_source_id: Option<String>,
    pub position_ms: u32,
    pub audio_stream_index: Option<i32>,
    pub subtitle_stream_index: Option<i32>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Body {
    Join,
    Load(Load),
    Ready {
        item_id: String,
        position_ms: u32,
    },
    State {
        item_id: String,
        position_ms: u32,
        paused: bool,
        buffering: bool,
    },
    Cmd(Cmd),
    Buffering {
        on: bool,
    },
    Ping {
        t: u64,
        drift_ms: Option<i64>,
    },
    Pong {
        t: u64,
    },
    Bye {
        reason: ByeReason,
    },
}

impl Body {
    pub fn kind(&self) -> &'static str {
        match self {
            Self::Join => "join",
            Self::Load(_) => "load",
            Self::Ready { .. } => "ready",
            Self::State { .. } => "state",
            Self::Cmd(_) => "cmd",
            Self::Buffering { .. } => "buffering",
            Self::Ping { .. } => "ping",
            Self::Pong { .. } => "pong",
            Self::Bye { .. } => "bye",
        }
    }

    /// The fields of the kind, as they go on the wire.
    pub fn fields(&self) -> Map<String, Value> {
        let mut m = Map::new();
        let mut put = |k: &str, v: Value| {
            m.insert(k.to_owned(), v);
        };
        match self {
            Self::Join => {}
            Self::Load(l) => {
                put("itemId", json!(l.item_id));
                if let Some(s) = &l.media_source_id {
                    put("mediaSourceId", json!(s));
                }
                put("positionMs", json!(l.position_ms));
                if let Some(a) = l.audio_stream_index {
                    put("audioStreamIndex", json!(a));
                }
                if let Some(s) = l.subtitle_stream_index {
                    put("subtitleStreamIndex", json!(s));
                }
            }
            Self::Ready {
                item_id,
                position_ms,
            } => {
                put("itemId", json!(item_id));
                put("positionMs", json!(position_ms));
            }
            Self::State {
                item_id,
                position_ms,
                paused,
                buffering,
            } => {
                put("itemId", json!(item_id));
                put("positionMs", json!(position_ms));
                put("paused", json!(paused));
                put("buffering", json!(buffering));
            }
            Self::Cmd(c) => {
                let action = match c {
                    Cmd::Pause => "pause",
                    Cmd::Play => "play",
                    Cmd::Seek { position_ms } => {
                        put("positionMs", json!(position_ms));
                        "seek"
                    }
                    Cmd::Next => "next",
                    Cmd::Previous => "previous",
                };
                put("action", json!(action));
            }
            Self::Buffering { on } => put("on", json!(on)),
            Self::Ping { t, drift_ms } => {
                put("t", json!(t));
                if let Some(d) = drift_ms {
                    put("driftMs", json!(d));
                }
            }
            Self::Pong { t } => put("t", json!(t)),
            Self::Bye { reason } => put("reason", json!(reason.as_str())),
        }
        m
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Envelope {
    pub from: String,
    pub link: String,
    pub seq: u32,
    pub body: Body,
}

impl Envelope {
    pub fn encode(&self) -> String {
        let mut m = Map::new();
        m.insert("tv".into(), json!("sync"));
        m.insert("v".into(), json!(1));
        m.insert("from".into(), json!(self.from));
        m.insert("link".into(), json!(self.link));
        m.insert("seq".into(), json!(self.seq));
        m.insert("kind".into(), json!(self.body.kind()));
        m.extend(self.body.fields());
        Value::Object(m).to_string()
    }

    pub fn decode(raw: &str) -> Result<Self, Reject> {
        if raw.len() > MAX_BYTES {
            return Err(Reject::Size);
        }
        let value: Value = serde_json::from_str(raw).map_err(|_| Reject::Json)?;
        let m = value.as_object().ok_or(Reject::Json)?;
        if m.get("tv").and_then(Value::as_str) != Some("sync")
            || m.get("v").and_then(Value::as_u64) != Some(1)
        {
            return Err(Reject::Version);
        }
        let from = id(m, "from", 1)?.ok_or(Reject::Field)?;
        let link = id(m, "link", 8)?.ok_or(Reject::Field)?;
        let seq = m
            .get("seq")
            .and_then(Value::as_u64)
            .filter(|s| *s <= MAX_SEQ)
            .ok_or(Reject::Field)? as u32;
        let kind = m.get("kind").and_then(Value::as_str).ok_or(Reject::Field)?;
        let body = body(m, kind)?;
        Ok(Self {
            from,
            link,
            seq,
            body,
        })
    }
}

/// `[A-Za-z0-9-]` of `min` to 64 characters: session, link, item and source ids.
pub fn is_id(s: &str, min: usize) -> bool {
    (min..=64).contains(&s.len()) && s.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'-')
}

/// `Ok(None)` when absent, `Err` when present but out of bounds.
fn id(m: &Map<String, Value>, key: &str, min: usize) -> Result<Option<String>, Reject> {
    match m.get(key) {
        None => Ok(None),
        Some(v) => v
            .as_str()
            .filter(|s| is_id(s, min))
            .map(|s| Some(s.to_owned()))
            .ok_or(Reject::Field),
    }
}

fn opt_int(m: &Map<String, Value>, key: &str, lo: i64, hi: i64) -> Result<Option<i64>, Reject> {
    match m.get(key) {
        None => Ok(None),
        Some(v) => v
            .as_i64()
            .filter(|n| (lo..=hi).contains(n))
            .map(Some)
            .ok_or(Reject::Field),
    }
}

fn int(m: &Map<String, Value>, key: &str, lo: i64, hi: i64) -> Result<i64, Reject> {
    opt_int(m, key, lo, hi)?.ok_or(Reject::Field)
}

fn flag(m: &Map<String, Value>, key: &str) -> Result<bool, Reject> {
    m.get(key).and_then(Value::as_bool).ok_or(Reject::Field)
}

fn body(m: &Map<String, Value>, kind: &str) -> Result<Body, Reject> {
    let item = || id(m, "itemId", 1)?.ok_or(Reject::Field);
    let position = || int(m, "positionMs", 0, MAX_POSITION_MS.into()).map(|p| p as u32);
    let track = |k| opt_int(m, k, -1, 999).map(|t| t.map(|t| t as i32));
    Ok(match kind {
        "join" => Body::Join,
        "load" => Body::Load(Load {
            item_id: item()?,
            media_source_id: id(m, "mediaSourceId", 1)?,
            position_ms: position()?,
            audio_stream_index: track("audioStreamIndex")?,
            subtitle_stream_index: track("subtitleStreamIndex")?,
        }),
        "ready" => Body::Ready {
            item_id: item()?,
            position_ms: position()?,
        },
        "state" => Body::State {
            item_id: item()?,
            position_ms: position()?,
            paused: flag(m, "paused")?,
            buffering: flag(m, "buffering")?,
        },
        "cmd" => Body::Cmd(match m.get("action").and_then(Value::as_str) {
            Some("pause") => Cmd::Pause,
            Some("play") => Cmd::Play,
            Some("seek") => Cmd::Seek {
                position_ms: position()?,
            },
            Some("next") => Cmd::Next,
            Some("previous") => Cmd::Previous,
            _ => return Err(Reject::Field),
        }),
        "buffering" => Body::Buffering { on: flag(m, "on")? },
        "ping" => Body::Ping {
            t: m.get("t").and_then(Value::as_u64).ok_or(Reject::Field)?,
            drift_ms: opt_int(m, "driftMs", i64::MIN, i64::MAX)?,
        },
        "pong" => Body::Pong {
            t: m.get("t").and_then(Value::as_u64).ok_or(Reject::Field)?,
        },
        "bye" => {
            let reason = m.get("reason").and_then(Value::as_str);
            Body::Bye {
                reason: ByeReason::ALL
                    .into_iter()
                    .find(|r| Some(r.as_str()) == reason)
                    .ok_or(Reject::Field)?,
            }
        }
        _ => return Err(Reject::Kind),
    })
}

/// Link as the filter sees it: the peer, the link id and the last `seq` read.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct LinkView<'a> {
    pub peer: &'a str,
    pub link: &'a str,
    pub last_seq: Option<u32>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Accept {
    Yes,
    No,
    /// A `join` or `load` of another session while linked: answer `bye busy`.
    Busy,
}

/// Whether a valid message is read (ADR 0124 « Hors sujet »).
pub fn accept(link: Option<&LinkView>, from: &str, msg_link: &str, seq: u32, kind: &str) -> Accept {
    let opens = matches!(kind, "join" | "load");
    match link {
        None if opens => Accept::Yes,
        None => Accept::No,
        Some(l) if from == l.peer => {
            if msg_link == l.link && l.last_seq.is_none_or(|last| seq > last) {
                Accept::Yes
            } else {
                Accept::No
            }
        }
        Some(_) if opens => Accept::Busy,
        Some(_) => Accept::No,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const VECTORS: &str = include_str!("../../../docs/sync/vectors.json");

    fn vectors() -> Value {
        serde_json::from_str(VECTORS).unwrap()
    }

    #[test]
    fn max_bytes_matches_the_vectors() {
        assert_eq!(vectors()["maxBytes"].as_u64(), Some(MAX_BYTES as u64));
    }

    #[test]
    fn valid_vectors_decode_and_round_trip() {
        let v = vectors();
        let cases = v["valid"].as_array().unwrap();
        assert!(!cases.is_empty());
        for case in cases {
            let name = case["name"].as_str().unwrap();
            let env = Envelope::decode(case["raw"].as_str().unwrap())
                .unwrap_or_else(|r| panic!("{name}: rejected ({})", r.code()));
            assert_eq!(env.body.kind(), case["kind"], "{name}");
            assert_eq!(env.from, case["from"].as_str().unwrap(), "{name}");
            assert_eq!(env.link, case["link"].as_str().unwrap(), "{name}");
            assert_eq!(u64::from(env.seq), case["seq"].as_u64().unwrap(), "{name}");
            assert_eq!(Value::Object(env.body.fields()), case["fields"], "{name}");
            assert_eq!(Envelope::decode(&env.encode()), Ok(env), "{name}");
        }
    }

    #[test]
    fn invalid_vectors_are_rejected_for_their_reason() {
        let v = vectors();
        let cases = v["invalid"].as_array().unwrap();
        assert!(!cases.is_empty());
        for case in cases {
            let name = case["name"].as_str().unwrap();
            let got = Envelope::decode(case["raw"].as_str().unwrap()).map(|e| e.body.kind());
            assert_eq!(
                got.map_err(Reject::code),
                Err(case["reason"].as_str().unwrap()),
                "{name}"
            );
        }
    }

    #[test]
    fn accept_vectors() {
        let v = vectors();
        let cases = v["accept"].as_array().unwrap();
        assert!(!cases.is_empty());
        for case in cases {
            let name = case["name"].as_str().unwrap();
            let s = &case["state"];
            let view = (!s.is_null()).then(|| LinkView {
                peer: s["peer"].as_str().unwrap(),
                link: s["link"].as_str().unwrap(),
                last_seq: s["lastSeq"].as_u64().map(|n| n as u32),
            });
            let m = &case["msg"];
            let got = accept(
                view.as_ref(),
                m["from"].as_str().unwrap(),
                m["link"].as_str().unwrap(),
                m["seq"].as_u64().unwrap() as u32,
                m["kind"].as_str().unwrap(),
            );
            let want = match &case["accept"] {
                Value::Bool(true) => Accept::Yes,
                Value::Bool(false) => Accept::No,
                Value::String(s) if s == "busy" => Accept::Busy,
                other => panic!("{name}: unexpected {other}"),
            };
            assert_eq!(got, want, "{name}");
        }
    }

    #[test]
    fn encoded_envelopes_fit_the_limit() {
        let env = Envelope {
            from: "s".repeat(64),
            link: "l".repeat(64),
            seq: MAX_SEQ as u32,
            body: Body::Load(Load {
                item_id: "i".repeat(64),
                media_source_id: Some("m".repeat(64)),
                position_ms: MAX_POSITION_MS,
                audio_stream_index: Some(999),
                subtitle_stream_index: Some(-1),
            }),
        };
        assert!(env.encode().len() <= MAX_BYTES);
    }
}
