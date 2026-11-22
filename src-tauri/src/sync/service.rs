//! IO glue of the leader: one task owns the state machine, reads the
//! player, carries out its effects. Envelopes go out in order through a second
//! task, so a slow server never delays a `pong`. Only kind, seq and reject
//! reasons are logged, never an envelope.

use std::time::{Duration, Instant};

use tauri::{Emitter, Manager};
use tokio::sync::{mpsc, oneshot};

use super::envelope::{Body, Envelope, MAX_POSITION_MS};
use super::leader::{Effect, Input, Leader, Peer, PlayerAction, Snapshot, Status};
use super::{SyncDevice, tv_devices};
use crate::AppState;
use crate::emby::EmbyError;
use crate::emby::session::EmbyService;
use crate::player::{PlayerCommand, PlayerEngine, PlayerEvent};

/// `Status` of the link, after each change.
pub const STATUS_EVENT: &str = "sync://status";
/// A `Notice` the UI shows once.
pub const NOTICE_EVENT: &str = "sync://notice";
/// A `UiAction` the player view carries out (next, previous, stop).
pub const ACTION_EVENT: &str = "sync://action";

/// Timers of the state machine are checked this often.
const TICK: Duration = Duration::from_millis(250);
const MAX_SEQ: u32 = (1 << 31) - 1;

type Ack = Box<dyn FnOnce() + Send>;

enum Msg {
    Player(PlayerEvent),
    Playing(Current),
    Incoming(String),
    /// A `join` whose sender was looked up in the session list, off the task.
    Join(Envelope, Option<String>),
    OwnSession(Option<String>),
    Invite(Peer, oneshot::Sender<Result<(), &'static str>>),
    Unlink,
    /// `bye stopped` if linked, acknowledged once sent.
    Stop(Ack),
    Status(oneshot::Sender<Status>),
}

enum Out {
    Send { to: String, text: String },
    Flush(Ack),
}

/// The playback `emby_play` started.
struct Current {
    item_id: String,
    media_source_id: String,
    /// Direct play: mpv's FFmpeg indexes are Emby's stream indexes.
    direct: bool,
}

/// Handle to the linked playback task. Cheap to clone; sending never blocks.
#[derive(Clone)]
pub struct SyncHandle {
    tx: mpsc::UnboundedSender<Msg>,
}

impl SyncHandle {
    pub fn spawn(app: tauri::AppHandle) -> Self {
        let (tx, rx) = mpsc::unbounded_channel();
        let (out, out_rx) = mpsc::unbounded_channel();
        tauri::async_runtime::spawn(send_loop(app.clone(), out_rx));
        tauri::async_runtime::spawn(run(
            Glue {
                app,
                leader: Leader::default(),
                start: Instant::now(),
                seq: 0,
                own: None,
                current: None,
                loaded: false,
                paused: false,
                buffering: false,
                out,
                me: tx.downgrade(),
            },
            rx,
        ));
        Self { tx }
    }

    pub fn player_event(&self, event: &PlayerEvent) {
        if !matches!(
            event,
            PlayerEvent::TimePos { .. } | PlayerEvent::Duration { .. } | PlayerEvent::Tracks { .. }
        ) {
            let _ = self.tx.send(Msg::Player(event.clone()));
        }
    }

    /// Called by `emby_play` before the file is loaded.
    pub fn playing(&self, item_id: &str, media_source_id: &str, direct: bool) {
        let _ = self.tx.send(Msg::Playing(Current {
            item_id: item_id.to_owned(),
            media_source_id: media_source_id.to_owned(),
            direct,
        }));
    }

    /// The `String` of a `SendString` the socket received.
    pub fn incoming(&self, raw: String) {
        let _ = self.tx.send(Msg::Incoming(raw));
    }

    pub fn own_session(&self, id: Option<String>) {
        let _ = self.tx.send(Msg::OwnSession(id));
    }

    pub async fn invite(&self, peer: Peer) -> Result<(), EmbyError> {
        let (tx, rx) = oneshot::channel();
        let _ = self.tx.send(Msg::Invite(peer, tx));
        match rx.await {
            Ok(Ok(())) => Ok(()),
            Ok(Err(why)) => Err(EmbyError::Sync(why.into())),
            Err(_) => Err(EmbyError::Sync("service arrêté".into())),
        }
    }

    pub fn unlink(&self) {
        let _ = self.tx.send(Msg::Unlink);
    }

    pub async fn status(&self) -> Status {
        let (tx, rx) = oneshot::channel();
        let _ = self.tx.send(Msg::Status(tx));
        rx.await.unwrap_or(Status::Idle)
    }

    /// Ends a link with `bye stopped` and waits, at most `timeout`, until it was
    /// sent (sign-out, server change).
    pub async fn stop(&self, timeout: Duration) {
        let (tx, rx) = oneshot::channel();
        let ack: Ack = Box::new(move || {
            let _ = tx.send(());
        });
        if self.tx.send(Msg::Stop(ack)).is_ok() {
            let _ = tokio::time::timeout(timeout, rx).await;
        }
    }

    /// Same as `stop`, blocking: for app close, off the UI thread and async tasks.
    pub fn stop_blocking(&self, timeout: Duration) -> bool {
        let (tx, rx) = std::sync::mpsc::channel();
        let ack: Ack = Box::new(move || {
            let _ = tx.send(());
        });
        self.tx.send(Msg::Stop(ack)).is_ok() && rx.recv_timeout(timeout).is_ok()
    }
}

/// TVs of the open session, read from the server.
pub async fn devices(emby: &EmbyService) -> Result<Vec<SyncDevice>, EmbyError> {
    let client = emby.client()?;
    let user = emby.current_user().ok_or(EmbyError::NotSignedIn)?;
    let sessions = client.sessions(&user.user_id).await?;
    Ok(tv_devices(&sessions, client.device_id()))
}

struct Glue {
    app: tauri::AppHandle,
    leader: Leader,
    start: Instant,
    seq: u32,
    own: Option<String>,
    current: Option<Current>,
    loaded: bool,
    paused: bool,
    buffering: bool,
    out: mpsc::UnboundedSender<Out>,
    /// Feeds lookups back into this task; weak, so it never keeps it alive.
    me: mpsc::WeakUnboundedSender<Msg>,
}

async fn run(mut glue: Glue, mut rx: mpsc::UnboundedReceiver<Msg>) {
    let mut tick = tokio::time::interval(TICK);
    tick.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Skip);
    loop {
        tokio::select! {
            msg = rx.recv() => match msg {
                Some(m) => glue.on(m).await,
                None => break,
            },
            _ = tick.tick() => {
                if glue.leader.is_active() {
                    glue.handle(Input::Tick);
                }
            }
        }
    }
}

impl Glue {
    fn now(&self) -> u64 {
        self.start.elapsed().as_millis() as u64
    }

    /// Never waits on the lock (held while an engine is created): this runs on
    /// the async runtime, and a busy player just skips one read.
    fn engine(&self) -> Option<std::sync::Arc<crate::player::mpv::MpvEngine>> {
        use std::sync::TryLockError;
        match self.app.state::<AppState>().player.try_lock() {
            Ok(guard) => guard.clone(),
            Err(TryLockError::Poisoned(p)) => p.into_inner().clone(),
            Err(TryLockError::WouldBlock) => None,
        }
    }

    /// The local playback with mpv's position now, `None` when nothing plays.
    fn snapshot(&self) -> Option<Snapshot> {
        let current = self.current.as_ref().filter(|_| self.loaded)?;
        let engine = self.engine()?;
        let seconds = engine.position_seconds()?;
        let (audio, subtitle) = if current.direct {
            engine.selected_streams()
        } else {
            // A remux or transcode numbers its streams otherwise: the TV keeps its own.
            (None, None)
        };
        Some(Snapshot {
            item_id: current.item_id.clone(),
            media_source_id: Some(current.media_source_id.clone()),
            position_ms: (seconds * 1000.0)
                .round()
                .clamp(0.0, MAX_POSITION_MS.into()) as u32,
            paused: self.paused,
            buffering: self.buffering,
            audio_stream_index: audio,
            subtitle_stream_index: subtitle,
        })
    }

    /// Runs one input; the player is only read when a link may need it.
    fn handle(&mut self, input: Input) {
        let needs = self.leader.is_active()
            || matches!(input, Input::Invite { .. } | Input::Received { .. });
        let snap = if needs { self.snapshot() } else { None };
        let now = self.now();
        let fx = self.leader.handle(now, snap.as_ref(), input);
        self.apply(fx);
    }

    async fn on(&mut self, msg: Msg) {
        match msg {
            Msg::Player(ev) => match ev {
                PlayerEvent::FileLoaded => {
                    self.loaded = true;
                    self.paused = false;
                    self.buffering = false;
                    self.handle(Input::FileLoaded);
                }
                PlayerEvent::Paused { paused } => {
                    let resumed = self.paused && !paused;
                    self.paused = paused;
                    self.handle(Input::LocalChanged { resumed });
                }
                PlayerEvent::Buffering { active } => {
                    self.buffering = active;
                    self.handle(Input::LocalChanged { resumed: false });
                }
                PlayerEvent::Seeked => self.handle(Input::LocalChanged { resumed: false }),
                PlayerEvent::EndFile { .. } => self.loaded = false,
                PlayerEvent::Shutdown => {
                    self.loaded = false;
                    self.current = None;
                    self.handle(Input::LocalStopped);
                }
                _ => {}
            },
            Msg::Playing(current) => {
                self.current = Some(current);
                self.loaded = false;
            }
            Msg::Incoming(raw) => {
                let env = match Envelope::decode(&raw) {
                    Ok(env) => env,
                    Err(reason) => {
                        log::debug!("sync: message rejected ({})", reason.code());
                        return;
                    }
                };
                log::debug!("sync: received {} #{}", env.body.label(), env.seq);
                // A link opens on a listed TV only; its name comes from the server,
                // read off this task so timers and player events never wait on it.
                if !self.leader.is_active() && matches!(env.body, Body::Join) {
                    let (app, me) = (self.app.clone(), self.me.clone());
                    tauri::async_runtime::spawn(async move {
                        let name = device_name(&app, &env.from).await;
                        if let Some(me) = me.upgrade() {
                            let _ = me.send(Msg::Join(env, name));
                        }
                    });
                    return;
                }
                self.handle(Input::Received {
                    env,
                    peer_name: None,
                });
            }
            Msg::Join(env, peer_name) => self.handle(Input::Received { env, peer_name }),
            Msg::OwnSession(id) => {
                // Another account or server: a link of the old session ends here
                // (best-effort `bye stopped`, still sent as the old session).
                if id != self.own && self.leader.is_active() {
                    self.handle(Input::LocalStopped);
                }
                self.own = id;
            }
            Msg::Invite(peer, reply) => {
                let result = if self.own.is_none() {
                    Err("session Emby pas encore identifiée, réessaie dans un instant")
                } else if self.leader.is_active() {
                    Err("une TV suit déjà cette lecture")
                } else if self.snapshot().is_none() {
                    Err("aucune lecture en cours")
                } else {
                    let link = uuid::Uuid::new_v4().simple().to_string();
                    self.handle(Input::Invite { peer, link });
                    Ok(())
                };
                let _ = reply.send(result);
            }
            Msg::Unlink => self.handle(Input::Unlink),
            Msg::Stop(ack) => {
                self.handle(Input::LocalStopped);
                let _ = self.out.send(Out::Flush(ack));
            }
            Msg::Status(reply) => {
                let _ = reply.send(self.leader.status());
            }
        }
    }

    fn apply(&mut self, fx: Vec<Effect>) {
        for effect in fx {
            match effect {
                Effect::Send { to, link, body } => {
                    let Some(from) = self.own.clone() else {
                        log::warn!("sync: {} not sent, own session unknown", body.kind());
                        continue;
                    };
                    let env = Envelope {
                        from,
                        link,
                        seq: self.seq,
                        body,
                    };
                    log::debug!("sync: send {} #{}", env.body.label(), env.seq);
                    self.seq = (self.seq + 1).min(MAX_SEQ);
                    let _ = self.out.send(Out::Send {
                        to,
                        text: env.encode(),
                    });
                }
                Effect::Player(action) => {
                    let command = match action {
                        PlayerAction::Pause => PlayerCommand::SetPaused { paused: true },
                        PlayerAction::Play => PlayerCommand::SetPaused { paused: false },
                        PlayerAction::Seek { position_ms } => PlayerCommand::SeekAbsolute {
                            seconds: f64::from(position_ms) / 1000.0,
                        },
                    };
                    if let Some(engine) = self.engine()
                        && let Err(e) = engine.command(&command)
                    {
                        log::warn!("sync: player command failed: {e}");
                    }
                }
                Effect::Ui(action) => {
                    let _ = self.app.emit(ACTION_EVENT, action);
                }
                Effect::Notice(notice) => {
                    let _ = self.app.emit(NOTICE_EVENT, notice);
                }
                Effect::Status => {
                    let _ = self.app.emit(STATUS_EVENT, self.leader.status());
                }
            }
        }
    }
}

async fn send_loop(app: tauri::AppHandle, mut rx: mpsc::UnboundedReceiver<Out>) {
    while let Some(out) = rx.recv().await {
        match out {
            Out::Send { to, text } => {
                let client = app.state::<EmbyService>().client();
                let result = match client {
                    Ok(c) => c.send_string(&to, &text).await,
                    Err(e) => Err(e),
                };
                if let Err(e) = result {
                    log::warn!("sync: relay failed ({})", e.kind());
                }
            }
            Out::Flush(ack) => ack(),
        }
    }
}

async fn device_name(app: &tauri::AppHandle, session_id: &str) -> Option<String> {
    let emby = app.state::<EmbyService>();
    match devices(&emby).await {
        Ok(list) => list
            .into_iter()
            .find(|d| d.session_id == session_id)
            .map(|d| d.name),
        Err(e) => {
            log::warn!("sync: device list unavailable ({})", e.kind());
            None
        }
    }
}
