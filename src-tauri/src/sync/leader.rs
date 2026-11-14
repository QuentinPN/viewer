//! Leader side of a linked playback (ADR 0124): the computer's state machine.
//! Pure: time is a monotonic millisecond count given by the caller, and every
//! outcome is an [`Effect`] the IO glue carries out.

use serde::Serialize;

use super::envelope::{Accept, Body, ByeReason, Cmd, Envelope, LinkView, Load, accept};

pub type Ms = u64;

/// Silence of the peer after which a request is given up.
pub const REPLY_TIMEOUT: Ms = 10_000;
/// A `load` the TV answered (it pings) leaves it this long to be `ready`.
pub const READY_TIMEOUT: Ms = 30_000;
/// No `ping` for this long: the link is lost.
pub const SILENCE: Ms = 5_000;
/// The TV buffering for this long: the computer plays on alone.
pub const BUFFERING_LIMIT: Ms = 15_000;
/// `state` goes out at least this often.
pub const STATE_EVERY: Ms = 1_000;

/// The local playback, read from the player when an input is handled.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Snapshot {
    pub item_id: String,
    pub media_source_id: Option<String>,
    pub position_ms: u32,
    pub paused: bool,
    pub buffering: bool,
    pub audio_stream_index: Option<i32>,
    pub subtitle_stream_index: Option<i32>,
}

/// A TV of the server's session list; the name never comes from a message.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Peer {
    pub session_id: String,
    pub name: String,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Input {
    /// « Envelope aussi sur la TV » with a new random link id.
    Invite {
        peer: Peer,
        link: String,
    },
    /// A valid envelope; `peer_name` is set when `from` is a listed TV.
    Received {
        env: Envelope,
        peer_name: Option<String>,
    },
    Tick,
    /// Pause, resume, seek or buffering changed locally. `resumed`: playback went
    /// from paused to playing.
    LocalChanged {
        resumed: bool,
    },
    /// A file finished loading (new episode, quality change…).
    FileLoaded,
    /// The local playback stopped (Retour, close, sign-out).
    LocalStopped,
    /// « Délier » or « Continuer sans la TV ».
    Unlink,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum PlayerAction {
    Pause,
    Play,
    Seek { position_ms: u32 },
}

/// Done by the UI with its own logic, as if the user asked.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub enum UiAction {
    Next,
    Previous,
    Stop,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(tag = "kind", rename_all = "camelCase")]
pub enum Notice {
    /// « <appareil> suit ta lecture. »
    Following { device: String },
    /// « Lien perdu avec <appareil>. La lecture continue ici. »
    Lost { device: String },
    /// « <appareil> ne répond pas. »
    NoAnswer { device: String },
    /// « <appareil> suit déjà une autre lecture. »
    Busy { device: String },
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(tag = "phase", rename_all = "camelCase")]
pub enum Status {
    Idle,
    /// « En attente de la TV… »: loading or buffering on the TV.
    #[serde(rename_all = "camelCase")]
    Waiting {
        device: String,
    },
    #[serde(rename_all = "camelCase")]
    Linked {
        device: String,
        drift_ms: Option<i64>,
    },
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Effect {
    /// The glue adds `from` and `seq`.
    Send {
        to: String,
        link: String,
        body: Body,
    },
    Player(PlayerAction),
    Ui(UiAction),
    Notice(Notice),
    /// `Leader::status` changed.
    Status,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Phase {
    /// `load` sent, waiting for `ready`. `heard`: the TV answered meanwhile.
    Loading {
        since: Ms,
        heard: bool,
        first: bool,
    },
    Linked,
}

#[derive(Debug, Clone)]
struct Link {
    peer: Peer,
    id: String,
    last_seq: Option<u32>,
    item_id: String,
    phase: Phase,
    last_heard: Ms,
    last_state: Ms,
    drift_ms: Option<i64>,
    /// The playback was paused for the link and resumes when it is done waiting.
    hold: bool,
    tv_buffering_since: Option<Ms>,
    /// The « suit ta lecture » notice was already shown (TV-started links).
    announced: bool,
}

#[derive(Debug, Default)]
pub struct Leader {
    link: Option<Link>,
}

impl Leader {
    pub fn status(&self) -> Status {
        match &self.link {
            None => Status::Idle,
            Some(l)
                if matches!(l.phase, Phase::Loading { .. }) || l.tv_buffering_since.is_some() =>
            {
                Status::Waiting {
                    device: l.peer.name.clone(),
                }
            }
            Some(l) => Status::Linked {
                device: l.peer.name.clone(),
                drift_ms: l.drift_ms,
            },
        }
    }

    pub fn is_active(&self) -> bool {
        self.link.is_some()
    }

    /// Handles one input at `now`; `snap` is the local playback, `None` when
    /// nothing plays.
    pub fn handle(&mut self, now: Ms, snap: Option<&Snapshot>, input: Input) -> Vec<Effect> {
        match input {
            Input::Invite { peer, link } => match snap {
                Some(s) if self.link.is_none() => self.start(now, s, peer, link, false),
                _ => Vec::new(),
            },
            Input::Received { env, peer_name } => self.received(now, snap, env, peer_name),
            Input::Tick => self.tick(now, snap),
            Input::LocalChanged { resumed } => {
                let Some(l) = &mut self.link else {
                    return Vec::new();
                };
                let mut fx = Vec::new();
                if resumed && l.hold {
                    // The user played on: nothing resumes later, nothing waits.
                    l.hold = false;
                    if l.tv_buffering_since.take().is_some() {
                        fx.push(Effect::Status);
                    }
                }
                fx.extend(self.state_now(now, snap));
                fx
            }
            Input::FileLoaded => {
                let (Some(l), Some(s)) = (&mut self.link, snap) else {
                    return Vec::new();
                };
                if l.item_id == s.item_id {
                    return self.state_now(now, snap);
                }
                // Next episode: a new `load` on the same link; the TV already accepted it.
                l.item_id = s.item_id.clone();
                l.phase = Phase::Loading {
                    since: now,
                    heard: true,
                    first: false,
                };
                l.tv_buffering_since = None;
                let mut fx = Vec::new();
                if !s.paused {
                    l.hold = true;
                    fx.push(Effect::Player(PlayerAction::Pause));
                }
                fx.push(send(l, Body::Load(load_of(s))));
                fx.push(Effect::Status);
                fx
            }
            Input::LocalStopped => self.end(ByeReason::Stopped, None, true),
            Input::Unlink => self.end(ByeReason::Left, None, true),
        }
    }

    fn start(
        &mut self,
        now: Ms,
        s: &Snapshot,
        peer: Peer,
        id: String,
        joined: bool,
    ) -> Vec<Effect> {
        let mut fx = Vec::new();
        if joined {
            fx.push(Effect::Notice(Notice::Following {
                device: peer.name.clone(),
            }));
        }
        let link = Link {
            peer,
            id,
            last_seq: None,
            item_id: s.item_id.clone(),
            phase: Phase::Loading {
                since: now,
                heard: joined,
                first: true,
            },
            last_heard: now,
            last_state: now,
            drift_ms: None,
            hold: !s.paused,
            tv_buffering_since: None,
            announced: joined,
        };
        if link.hold {
            fx.push(Effect::Player(PlayerAction::Pause));
        }
        fx.push(send(&link, Body::Load(load_of(s))));
        fx.push(Effect::Status);
        self.link = Some(link);
        fx
    }

    fn received(
        &mut self,
        now: Ms,
        snap: Option<&Snapshot>,
        env: Envelope,
        peer_name: Option<String>,
    ) -> Vec<Effect> {
        let view = self.link.as_ref().map(|l| LinkView {
            peer: &l.peer.session_id,
            link: &l.id,
            last_seq: l.last_seq,
        });
        match accept(
            view.as_ref(),
            &env.from,
            &env.link,
            env.seq,
            env.body.kind(),
        ) {
            Accept::No => return Vec::new(),
            Accept::Busy => {
                return vec![Effect::Send {
                    to: env.from,
                    link: env.link,
                    body: Body::Bye {
                        reason: ByeReason::Busy,
                    },
                }];
            }
            Accept::Yes => {}
        }
        let Some(l) = &mut self.link else {
            // Outside a link only a listed TV's `join` is read.
            let (Body::Join, Some(name)) = (&env.body, peer_name) else {
                return Vec::new();
            };
            let peer = Peer {
                session_id: env.from.clone(),
                name,
            };
            return match snap {
                Some(s) => {
                    let fx = self.start(now, s, peer, env.link, true);
                    if let Some(l) = &mut self.link {
                        l.last_seq = Some(env.seq);
                    }
                    fx
                }
                None => vec![Effect::Send {
                    to: env.from,
                    link: env.link,
                    body: Body::Bye {
                        reason: ByeReason::Idle,
                    },
                }],
            };
        };
        l.last_seq = Some(env.seq);
        l.last_heard = now;
        if let Phase::Loading { heard, .. } = &mut l.phase {
            *heard = true;
        }
        let linked = l.phase == Phase::Linked;
        match env.body {
            Body::Ping { t, drift_ms } => {
                let mut fx = vec![send(l, Body::Pong { t })];
                if drift_ms.is_some() && drift_ms != l.drift_ms {
                    l.drift_ms = drift_ms;
                    fx.push(Effect::Status);
                }
                fx
            }
            Body::Ready { item_id, .. } => {
                let Phase::Loading { first, .. } = l.phase else {
                    return Vec::new();
                };
                if item_id != l.item_id {
                    return Vec::new();
                }
                l.phase = Phase::Linked;
                let mut fx = Vec::new();
                let resume = std::mem::take(&mut l.hold);
                if resume {
                    fx.push(Effect::Player(PlayerAction::Play));
                }
                if let Some(s) = snap {
                    fx.push(send(l, state_of(s, if resume { false } else { s.paused })));
                    l.last_state = now;
                }
                if first && !l.announced {
                    l.announced = true;
                    fx.push(Effect::Notice(Notice::Following {
                        device: l.peer.name.clone(),
                    }));
                }
                fx.push(Effect::Status);
                fx
            }
            // Commands during a load are ignored; the next `state` says what holds.
            Body::Cmd(c) if linked => match c {
                Cmd::Pause => {
                    l.hold = false;
                    vec![Effect::Player(PlayerAction::Pause)]
                }
                Cmd::Play => {
                    l.hold = false;
                    vec![Effect::Player(PlayerAction::Play)]
                }
                Cmd::Seek { position_ms } => {
                    vec![Effect::Player(PlayerAction::Seek { position_ms })]
                }
                Cmd::Next => vec![Effect::Ui(UiAction::Next)],
                Cmd::Previous => vec![Effect::Ui(UiAction::Previous)],
            },
            Body::Buffering { on: true } if linked => {
                if l.tv_buffering_since.is_some() {
                    return Vec::new();
                }
                l.tv_buffering_since = Some(now);
                let mut fx = Vec::new();
                if snap.is_some_and(|s| !s.paused) {
                    l.hold = true;
                    fx.push(Effect::Player(PlayerAction::Pause));
                }
                fx.push(Effect::Status);
                fx
            }
            Body::Buffering { on: false } if linked => {
                if l.tv_buffering_since.take().is_none() {
                    return Vec::new();
                }
                let mut fx = Vec::new();
                if std::mem::take(&mut l.hold) {
                    fx.push(Effect::Player(PlayerAction::Play));
                }
                fx.push(Effect::Status);
                fx
            }
            Body::Bye { reason } => {
                let notice = match reason {
                    ByeReason::Busy => Some(Notice::Busy {
                        device: l.peer.name.clone(),
                    }),
                    ByeReason::Timeout => Some(Notice::NoAnswer {
                        device: l.peer.name.clone(),
                    }),
                    _ => None,
                };
                let mut fx = self.end(reason, notice, false);
                if reason == ByeReason::Stopped {
                    fx.push(Effect::Ui(UiAction::Stop));
                }
                fx
            }
            _ => Vec::new(),
        }
    }

    fn tick(&mut self, now: Ms, snap: Option<&Snapshot>) -> Vec<Effect> {
        let Some(l) = &self.link else {
            return Vec::new();
        };
        let no_answer = || {
            Some(Notice::NoAnswer {
                device: l.peer.name.clone(),
            })
        };
        match l.phase {
            Phase::Loading { since, heard, .. } => {
                let limit = if heard { READY_TIMEOUT } else { REPLY_TIMEOUT };
                if now.saturating_sub(since) >= limit {
                    let notice = no_answer();
                    return self.end(ByeReason::Timeout, notice, true);
                }
                Vec::new()
            }
            Phase::Linked => {
                if now.saturating_sub(l.last_heard) >= SILENCE {
                    let notice = Some(Notice::Lost {
                        device: l.peer.name.clone(),
                    });
                    // Nothing is sent: the peer is gone or unreachable.
                    return self.end(ByeReason::Timeout, notice, false);
                }
                if l.tv_buffering_since
                    .is_some_and(|since| now.saturating_sub(since) >= BUFFERING_LIMIT)
                {
                    let notice = no_answer();
                    return self.end(ByeReason::Timeout, notice, true);
                }
                if now.saturating_sub(l.last_state) >= STATE_EVERY {
                    return self.state_now(now, snap);
                }
                Vec::new()
            }
        }
    }

    /// A `state` now, when linked and something plays.
    fn state_now(&mut self, now: Ms, snap: Option<&Snapshot>) -> Vec<Effect> {
        match (&mut self.link, snap) {
            (Some(l), Some(s)) if l.phase == Phase::Linked => {
                l.last_state = now;
                vec![send(l, state_of(s, s.paused))]
            }
            _ => Vec::new(),
        }
    }

    /// Ends the link; `tell` sends `bye reason` to the peer. A playback paused for
    /// the link resumes, unless it stops.
    fn end(&mut self, reason: ByeReason, notice: Option<Notice>, tell: bool) -> Vec<Effect> {
        let Some(l) = self.link.take() else {
            return Vec::new();
        };
        let mut fx = Vec::new();
        if tell {
            fx.push(send(&l, Body::Bye { reason }));
        }
        if l.hold && reason != ByeReason::Stopped {
            fx.push(Effect::Player(PlayerAction::Play));
        }
        fx.extend(notice.map(Effect::Notice));
        fx.push(Effect::Status);
        fx
    }
}

fn send(l: &Link, body: Body) -> Effect {
    Effect::Send {
        to: l.peer.session_id.clone(),
        link: l.id.clone(),
        body,
    }
}

fn load_of(s: &Snapshot) -> Load {
    Load {
        item_id: s.item_id.clone(),
        media_source_id: s.media_source_id.clone(),
        position_ms: s.position_ms,
        audio_stream_index: s.audio_stream_index,
        subtitle_stream_index: s.subtitle_stream_index,
    }
}

fn state_of(s: &Snapshot, paused: bool) -> Body {
    Body::State {
        item_id: s.item_id.clone(),
        position_ms: s.position_ms,
        paused,
        buffering: s.buffering,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const TV: &str = "tv-1";
    const LINK: &str = "link-0001";

    fn snap(item: &str, paused: bool) -> Snapshot {
        Snapshot {
            item_id: item.into(),
            media_source_id: Some(format!("ms-{item}")),
            position_ms: 754_000,
            paused,
            buffering: false,
            audio_stream_index: Some(1),
            subtitle_stream_index: Some(-1),
        }
    }

    fn peer() -> Peer {
        Peer {
            session_id: TV.into(),
            name: "Shield".into(),
        }
    }

    fn env(from: &str, link: &str, seq: u32, body: Body) -> Input {
        Input::Received {
            env: Envelope {
                from: from.into(),
                link: link.into(),
                seq,
                body,
            },
            peer_name: (from == TV || from == "tv-2").then(|| "Shield".into()),
        }
    }

    fn tv(seq: u32, body: Body) -> Input {
        env(TV, LINK, seq, body)
    }

    fn to_tv(body: Body) -> Effect {
        Effect::Send {
            to: TV.into(),
            link: LINK.into(),
            body,
        }
    }

    fn bye(reason: ByeReason) -> Body {
        Body::Bye { reason }
    }

    fn ready(item: &str) -> Body {
        Body::Ready {
            item_id: item.into(),
            position_ms: 754_000,
        }
    }

    fn state(item: &str, paused: bool) -> Body {
        Body::State {
            item_id: item.into(),
            position_ms: 754_000,
            paused,
            buffering: false,
        }
    }

    fn invite(leader: &mut Leader, now: Ms, s: &Snapshot) -> Vec<Effect> {
        leader.handle(
            now,
            Some(s),
            Input::Invite {
                peer: peer(),
                link: LINK.into(),
            },
        )
    }

    /// Invited at 0, ready at 1000: linked and playing.
    fn linked() -> Leader {
        let mut l = Leader::default();
        let s = snap("68397", false);
        invite(&mut l, 0, &s);
        l.handle(1000, Some(&snap("68397", true)), tv(0, ready("68397")));
        l
    }

    fn has_send(fx: &[Effect], kind: &str) -> bool {
        fx.iter()
            .any(|e| matches!(e, Effect::Send { body, .. } if body.kind() == kind))
    }

    #[test]
    fn invite_pauses_and_loads_then_ready_links_and_resumes() {
        let mut l = Leader::default();
        let s = snap("68397", false);
        let fx = invite(&mut l, 0, &s);
        assert_eq!(
            fx,
            vec![
                Effect::Player(PlayerAction::Pause),
                to_tv(Body::Load(Load {
                    item_id: "68397".into(),
                    media_source_id: Some("ms-68397".into()),
                    position_ms: 754_000,
                    audio_stream_index: Some(1),
                    subtitle_stream_index: Some(-1),
                })),
                Effect::Status,
            ]
        );
        assert_eq!(
            l.status(),
            Status::Waiting {
                device: "Shield".into()
            }
        );
        let paused = snap("68397", true);
        let fx = l.handle(1000, Some(&paused), tv(0, ready("68397")));
        assert_eq!(
            fx,
            vec![
                Effect::Player(PlayerAction::Play),
                to_tv(state("68397", false)),
                Effect::Notice(Notice::Following {
                    device: "Shield".into()
                }),
                Effect::Status,
            ]
        );
        assert_eq!(
            l.status(),
            Status::Linked {
                device: "Shield".into(),
                drift_ms: None
            }
        );
    }

    #[test]
    fn invite_while_paused_stays_paused_at_ready() {
        let mut l = Leader::default();
        let s = snap("1", true);
        let fx = invite(&mut l, 0, &s);
        assert!(!fx.contains(&Effect::Player(PlayerAction::Pause)));
        let fx = l.handle(500, Some(&s), tv(0, ready("1")));
        assert!(!fx.contains(&Effect::Player(PlayerAction::Play)));
        assert!(fx.contains(&to_tv(state("1", true))));
    }

    #[test]
    fn invite_needs_a_playback() {
        let mut l = Leader::default();
        let fx = l.handle(
            0,
            None,
            Input::Invite {
                peer: peer(),
                link: LINK.into(),
            },
        );
        assert!(fx.is_empty());
        assert!(!l.is_active());
    }

    #[test]
    fn a_silent_tv_is_given_up_after_10_s() {
        let mut l = Leader::default();
        let s = snap("1", false);
        invite(&mut l, 0, &s);
        assert!(l.handle(9_999, Some(&s), Input::Tick).is_empty());
        let fx = l.handle(10_000, Some(&s), Input::Tick);
        assert_eq!(
            fx,
            vec![
                to_tv(bye(ByeReason::Timeout)),
                Effect::Player(PlayerAction::Play),
                Effect::Notice(Notice::NoAnswer {
                    device: "Shield".into()
                }),
                Effect::Status,
            ]
        );
        assert_eq!(l.status(), Status::Idle);
    }

    #[test]
    fn an_answering_tv_has_30_s_to_be_ready() {
        let mut l = Leader::default();
        let s = snap("1", false);
        invite(&mut l, 0, &s);
        let fx = l.handle(
            2_000,
            Some(&s),
            tv(
                0,
                Body::Ping {
                    t: 5,
                    drift_ms: None,
                },
            ),
        );
        assert_eq!(fx, vec![to_tv(Body::Pong { t: 5 })]);
        assert!(l.handle(29_999, Some(&s), Input::Tick).is_empty());
        let fx = l.handle(30_000, Some(&s), Input::Tick);
        assert!(fx.contains(&to_tv(bye(ByeReason::Timeout))));
        assert!(!l.is_active());
    }

    #[test]
    fn join_while_idle_answers_bye_idle() {
        let mut l = Leader::default();
        let fx = l.handle(0, None, env("tv-2", "link-new1", 0, Body::Join));
        assert_eq!(
            fx,
            vec![Effect::Send {
                to: "tv-2".into(),
                link: "link-new1".into(),
                body: bye(ByeReason::Idle)
            }]
        );
        assert!(!l.is_active());
    }

    #[test]
    fn join_while_playing_starts_and_announces() {
        let mut l = Leader::default();
        let s = snap("1", false);
        let fx = l.handle(0, Some(&s), tv(3, Body::Join));
        assert_eq!(
            fx[0],
            Effect::Notice(Notice::Following {
                device: "Shield".into()
            })
        );
        assert!(fx.contains(&Effect::Player(PlayerAction::Pause)));
        assert!(has_send(&fx, "load"));
        // Its seq is remembered: an older message of the TV is dropped.
        assert!(l.handle(10, Some(&s), tv(3, ready("1"))).is_empty());
        let fx = l.handle(20, Some(&s), tv(4, ready("1")));
        assert!(fx.contains(&Effect::Player(PlayerAction::Play)));
        // Announced once only.
        assert!(!fx.iter().any(|e| matches!(e, Effect::Notice(_))));
    }

    #[test]
    fn join_from_an_unlisted_session_is_ignored() {
        let mut l = Leader::default();
        let s = snap("1", false);
        assert!(
            l.handle(0, Some(&s), env("pc-9", "link-new1", 0, Body::Join))
                .is_empty()
        );
    }

    #[test]
    fn join_while_linked_answers_busy_to_the_other_tv() {
        let mut l = linked();
        let fx = l.handle(1500, None, env("tv-2", "link-0009", 0, Body::Join));
        assert_eq!(
            fx,
            vec![Effect::Send {
                to: "tv-2".into(),
                link: "link-0009".into(),
                body: bye(ByeReason::Busy)
            }]
        );
        assert!(matches!(l.status(), Status::Linked { .. }));
    }

    #[test]
    fn commands_map_to_local_actions() {
        let mut l = linked();
        let cases = [
            (Cmd::Pause, Effect::Player(PlayerAction::Pause)),
            (Cmd::Play, Effect::Player(PlayerAction::Play)),
            (
                Cmd::Seek {
                    position_ms: 128_000,
                },
                Effect::Player(PlayerAction::Seek {
                    position_ms: 128_000,
                }),
            ),
            (Cmd::Next, Effect::Ui(UiAction::Next)),
            (Cmd::Previous, Effect::Ui(UiAction::Previous)),
        ];
        for (i, (cmd, want)) in cases.into_iter().enumerate() {
            let fx = l.handle(1100 + i as u64, None, tv(1 + i as u32, Body::Cmd(cmd)));
            assert_eq!(fx, vec![want]);
        }
    }

    #[test]
    fn commands_during_a_load_are_ignored() {
        let mut l = Leader::default();
        let s = snap("1", false);
        invite(&mut l, 0, &s);
        assert!(
            l.handle(10, Some(&s), tv(0, Body::Cmd(Cmd::Pause)))
                .is_empty()
        );
    }

    #[test]
    fn state_goes_every_second_and_after_a_local_change() {
        let mut l = linked();
        let s = snap("68397", false);
        assert!(l.handle(1999, Some(&s), Input::Tick).is_empty());
        let fx = l.handle(1000 + STATE_EVERY, Some(&s), Input::Tick);
        assert_eq!(fx, vec![to_tv(state("68397", false))]);
        let p = snap("68397", true);
        let fx = l.handle(2100, Some(&p), Input::LocalChanged { resumed: false });
        assert_eq!(fx, vec![to_tv(state("68397", true))]);
        // Nothing plays (end of file): nothing to tell.
        assert!(l.handle(3200, None, Input::Tick).is_empty());
    }

    #[test]
    fn ping_is_answered_and_its_drift_shown() {
        let mut l = linked();
        let fx = l.handle(
            1200,
            None,
            tv(
                1,
                Body::Ping {
                    t: 99,
                    drift_ms: Some(-42),
                },
            ),
        );
        assert_eq!(fx, vec![to_tv(Body::Pong { t: 99 }), Effect::Status]);
        assert_eq!(
            l.status(),
            Status::Linked {
                device: "Shield".into(),
                drift_ms: Some(-42)
            }
        );
        // Same drift: no new status.
        let fx = l.handle(
            1300,
            None,
            tv(
                2,
                Body::Ping {
                    t: 100,
                    drift_ms: Some(-42),
                },
            ),
        );
        assert_eq!(fx, vec![to_tv(Body::Pong { t: 100 })]);
    }

    #[test]
    fn five_seconds_of_silence_lose_the_link() {
        let mut l = linked();
        l.handle(
            2000,
            None,
            tv(
                1,
                Body::Ping {
                    t: 1,
                    drift_ms: None,
                },
            ),
        );
        assert!(l.handle(6999, None, Input::Tick).is_empty());
        let fx = l.handle(7000, None, Input::Tick);
        assert_eq!(
            fx,
            vec![
                Effect::Notice(Notice::Lost {
                    device: "Shield".into()
                }),
                Effect::Status
            ]
        );
        assert_eq!(l.status(), Status::Idle);
        // No automatic relink: a later ping of the old link is ignored.
        assert!(
            l.handle(
                7100,
                None,
                tv(
                    2,
                    Body::Ping {
                        t: 2,
                        drift_ms: None
                    }
                )
            )
            .is_empty()
        );
    }

    #[test]
    fn tv_buffering_pauses_then_resumes() {
        let mut l = linked();
        let s = snap("68397", false);
        let fx = l.handle(1100, Some(&s), tv(1, Body::Buffering { on: true }));
        assert_eq!(
            fx,
            vec![Effect::Player(PlayerAction::Pause), Effect::Status]
        );
        assert!(matches!(l.status(), Status::Waiting { .. }));
        let p = snap("68397", true);
        let fx = l.handle(1500, Some(&p), tv(2, Body::Buffering { on: false }));
        assert_eq!(fx, vec![Effect::Player(PlayerAction::Play), Effect::Status]);
        assert!(matches!(l.status(), Status::Linked { .. }));
    }

    #[test]
    fn tv_buffering_does_not_resume_a_user_pause() {
        let mut l = linked();
        let s = snap("68397", false);
        l.handle(1100, Some(&s), tv(1, Body::Buffering { on: true }));
        // The user resumed, then paused again, while the TV buffered.
        l.handle(1200, Some(&s), Input::LocalChanged { resumed: true });
        let p = snap("68397", true);
        l.handle(1300, Some(&p), Input::LocalChanged { resumed: false });
        let fx = l.handle(1500, Some(&p), tv(2, Body::Buffering { on: false }));
        assert!(!fx.contains(&Effect::Player(PlayerAction::Play)));
    }

    #[test]
    fn tv_buffering_15_s_ends_the_link() {
        let mut l = linked();
        let s = snap("68397", false);
        l.handle(1000, Some(&s), tv(1, Body::Buffering { on: true }));
        let p = snap("68397", true);
        // Pings keep coming; the buffering does not end.
        for (i, t) in (2000..16_000).step_by(1000).enumerate() {
            l.handle(
                t,
                Some(&p),
                tv(2 + i as u32, Body::Ping { t, drift_ms: None }),
            );
            assert!(
                !has_send(&l.handle(t, Some(&p), Input::Tick), "bye"),
                "at {t}"
            );
        }
        let fx = l.handle(16_000, Some(&p), Input::Tick);
        assert_eq!(
            fx,
            vec![
                to_tv(bye(ByeReason::Timeout)),
                Effect::Player(PlayerAction::Play),
                Effect::Notice(Notice::NoAnswer {
                    device: "Shield".into()
                }),
                Effect::Status,
            ]
        );
    }

    #[test]
    fn bye_stopped_stops_the_player() {
        let mut l = linked();
        let fx = l.handle(1100, None, tv(1, bye(ByeReason::Stopped)));
        assert_eq!(fx, vec![Effect::Status, Effect::Ui(UiAction::Stop)]);
        assert!(!l.is_active());
    }

    #[test]
    fn bye_busy_during_a_load_resumes_alone() {
        let mut l = Leader::default();
        let s = snap("1", false);
        invite(&mut l, 0, &s);
        let fx = l.handle(100, Some(&s), tv(0, bye(ByeReason::Busy)));
        assert_eq!(
            fx,
            vec![
                Effect::Player(PlayerAction::Play),
                Effect::Notice(Notice::Busy {
                    device: "Shield".into()
                }),
                Effect::Status,
            ]
        );
    }

    #[test]
    fn bye_left_lets_the_computer_play_on() {
        let mut l = linked();
        let fx = l.handle(1100, None, tv(1, bye(ByeReason::Left)));
        assert_eq!(fx, vec![Effect::Status]);
    }

    #[test]
    fn unlink_and_local_stop_tell_the_tv() {
        let mut l = linked();
        assert_eq!(
            l.handle(1100, None, Input::Unlink),
            vec![to_tv(bye(ByeReason::Left)), Effect::Status]
        );
        let mut l = linked();
        assert_eq!(
            l.handle(1100, None, Input::LocalStopped),
            vec![to_tv(bye(ByeReason::Stopped)), Effect::Status]
        );
        // Nothing linked: nothing sent.
        assert!(
            Leader::default()
                .handle(0, None, Input::LocalStopped)
                .is_empty()
        );
    }

    #[test]
    fn continue_without_the_tv_during_a_load_resumes() {
        let mut l = Leader::default();
        let s = snap("1", false);
        invite(&mut l, 0, &s);
        let fx = l.handle(100, Some(&s), Input::Unlink);
        assert_eq!(
            fx,
            vec![
                to_tv(bye(ByeReason::Left)),
                Effect::Player(PlayerAction::Play),
                Effect::Status
            ]
        );
    }

    #[test]
    fn next_episode_reloads_on_the_same_link() {
        let mut l = linked();
        let next = snap("68398", false);
        let fx = l.handle(5000, Some(&next), Input::FileLoaded);
        assert_eq!(fx[0], Effect::Player(PlayerAction::Pause));
        assert!(matches!(
            &fx[1],
            Effect::Send { link, body: Body::Load(load), .. }
                if link == LINK && load.item_id == "68398"
        ));
        assert!(matches!(l.status(), Status::Waiting { .. }));
        // A ready for the previous title is not this one.
        let p = snap("68398", true);
        assert!(l.handle(5100, Some(&p), tv(1, ready("68397"))).is_empty());
        let fx = l.handle(5200, Some(&p), tv(2, ready("68398")));
        assert!(fx.contains(&Effect::Player(PlayerAction::Play)));
        // Not announced again.
        assert!(!fx.iter().any(|e| matches!(e, Effect::Notice(_))));
        // The reloaded TV has 30 s, even before it pings.
        let mut l = linked();
        l.handle(5000, Some(&next), Input::FileLoaded);
        assert!(l.handle(14_999 + 1, Some(&next), Input::Tick).is_empty());
        assert!(has_send(&l.handle(35_000, Some(&next), Input::Tick), "bye"));
    }

    #[test]
    fn same_item_reloaded_only_sends_state() {
        let mut l = linked();
        let s = snap("68397", false);
        assert_eq!(
            l.handle(5000, Some(&s), Input::FileLoaded),
            vec![to_tv(state("68397", false))]
        );
    }

    #[test]
    fn messages_of_others_are_ignored_while_linked() {
        let mut l = linked();
        assert!(
            l.handle(1100, None, env("tv-2", LINK, 9, Body::Cmd(Cmd::Pause)))
                .is_empty()
        );
        assert!(
            l.handle(1100, None, env(TV, "link-0002", 9, Body::Cmd(Cmd::Pause)))
                .is_empty()
        );
    }
}
