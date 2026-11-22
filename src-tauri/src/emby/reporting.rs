//! Playback reporting: player events become Emby start, progress and stop
//! reports, sent in order by one background task. The UI never sends reports.

use std::time::{Duration, Instant};

use tauri::{Emitter, Manager};

use super::EmbyError;
use super::client::EmbyClient;
use super::models::{PlayMethod, PlaybackReportBody};
use super::playback::PlaybackPlan;
use super::session::EmbyService;
use crate::player::{EndReason, PlayerEvent};

/// Progress cadence, also while paused so the server keeps the session alive.
pub const PROGRESS_INTERVAL: Duration = Duration::from_secs(10);
/// UI event carrying the item's server state once a playback stopped.
pub const PLAYBACK_STOPPED_EVENT: &str = "emby://playback-stopped";
const TICKS_PER_SECOND: f64 = 10_000_000.0;
const STOP_RETRY_DELAY: Duration = Duration::from_secs(1);
/// A lost stop report: waits between tries, doubled up to the cap.
const STOP_RESEND_FIRST: Duration = Duration::from_secs(5);
const STOP_RESEND_CAP: Duration = Duration::from_secs(15);
/// An older report could overwrite a playback made elsewhere in the meantime.
const STOP_RESEND_GIVE_UP: Duration = Duration::from_secs(3600);
/// How long `emby_play` waits for the resend before asking the server's position.
const STOP_RESEND_WAIT: Duration = Duration::from_secs(5);

/// A playback announced by `emby_play`, reported once the file is loaded.
#[derive(Debug, Clone, PartialEq)]
pub struct ActivePlayback {
    pub item_id: String,
    pub media_source_id: String,
    pub play_session_id: Option<String>,
    pub method: PlayMethod,
    pub run_time_ticks: Option<i64>,
    pub start_ticks: i64,
}

impl ActivePlayback {
    pub fn new(item_id: &str, plan: &PlaybackPlan) -> Self {
        Self {
            item_id: item_id.to_owned(),
            media_source_id: plan.media_source_id.clone(),
            play_session_id: plan.play_session_id.clone(),
            method: plan.method,
            run_time_ticks: plan.run_time_ticks,
            start_ticks: plan.start_ticks,
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ReportKind {
    Start,
    Progress,
    Stopped,
}

impl ReportKind {
    pub fn path(self) -> &'static str {
        match self {
            Self::Start => "/Sessions/Playing",
            Self::Progress => "/Sessions/Playing/Progress",
            Self::Stopped => "/Sessions/Playing/Stopped",
        }
    }
}

/// Margin before the end under which an EOF is a normal end (credits, rounding).
const EARLY_EOF_TICKS: i64 = 30 * TICKS_PER_SECOND as i64;

#[derive(Debug, Clone, PartialEq)]
pub struct Report {
    pub kind: ReportKind,
    pub body: PlaybackReportBody,
}

impl Report {
    /// The progress report sent ahead of a lost stop report.
    pub fn late_progress(&self) -> Self {
        Self {
            kind: ReportKind::Progress,
            body: PlaybackReportBody {
                is_paused: Some(false),
                can_seek: Some(true),
                event_name: Some("TimeUpdate"),
                failed: None,
                ..self.body.clone()
            },
        }
    }
}

/// Pure state machine from player events to reports; time is passed in.
#[derive(Debug, Default)]
pub struct Tracker {
    phase: Phase,
    /// Play session of a transcode replaced by a quality change, to stop on the
    /// server once the new stream is loaded.
    ended_encoding: Option<String>,
}

#[derive(Debug, Default)]
enum Phase {
    #[default]
    Idle,
    /// Announced, file not loaded yet: nothing is reported if loading fails.
    Pending(ActivePlayback),
    Playing(Playing),
    /// A quality change: the old stream plays on until the new one,
    /// same item, is loaded. Neither stop nor start is reported.
    Switching(Playing, ActivePlayback),
}

#[derive(Debug)]
struct Playing {
    playback: ActivePlayback,
    position_ticks: i64,
    paused: bool,
    last_sent: Instant,
    /// A seek finished: report the next position.
    seeked: bool,
}

impl Playing {
    /// An end of file well before the known duration: mpv reports a stream cut
    /// by the network as EOF once its cache drains. Never "watched".
    fn ended_early(&self) -> bool {
        self.playback
            .run_time_ticks
            .is_some_and(|run| run - self.position_ticks > EARLY_EOF_TICKS)
    }

    fn report(&mut self, kind: ReportKind, event: Option<&'static str>, now: Instant) -> Report {
        self.last_sent = now;
        let p = &self.playback;
        let live = kind != ReportKind::Stopped;
        Report {
            kind,
            body: PlaybackReportBody {
                item_id: p.item_id.clone(),
                media_source_id: p.media_source_id.clone(),
                play_session_id: p.play_session_id.clone(),
                position_ticks: self.position_ticks,
                run_time_ticks: p.run_time_ticks,
                is_paused: live.then_some(self.paused),
                can_seek: live.then_some(true),
                // Also on a stop: `late_progress` needs it.
                play_method: Some(p.method.as_emby()),
                event_name: event,
                failed: None,
            },
        }
    }
}

impl Tracker {
    fn is_idle(&self) -> bool {
        matches!(self.phase, Phase::Idle)
    }

    /// Announces a new playback; one still playing is reported stopped first.
    pub fn begin(&mut self, playback: ActivePlayback, now: Instant) -> Vec<Report> {
        let out = self.stop(false, now);
        self.phase = Phase::Pending(playback);
        out
    }

    /// A quality change of the item playing: no stop, no new start; a
    /// `QualityChange` progress with the new session once its file is loaded.
    /// Anything else playing (or nothing) is a new playback, as `begin`.
    pub fn switch(&mut self, playback: ActivePlayback, now: Instant) -> Vec<Report> {
        match std::mem::take(&mut self.phase) {
            Phase::Playing(old) | Phase::Switching(old, _)
                if old.playback.item_id == playback.item_id =>
            {
                self.phase = Phase::Switching(old, playback);
                Vec::new()
            }
            other => {
                self.phase = other;
                self.begin(playback, now)
            }
        }
    }

    /// The transcode a quality change replaced, once; see `ended_encoding`.
    pub fn take_ended_encoding(&mut self) -> Option<String> {
        self.ended_encoding.take()
    }

    /// Events while a quality change loads. `None`: the switch fell back to the
    /// old playback, to be handled as usual.
    fn on_switching(&mut self, event: &PlayerEvent, now: Instant) -> Option<Vec<Report>> {
        let Phase::Switching(old, _) = &mut self.phase else {
            return Some(Vec::new());
        };
        match event {
            // The old file ends as the new one replaces it.
            PlayerEvent::EndFile {
                reason: EndReason::Stopped,
                ..
            } => Some(Vec::new()),
            PlayerEvent::TimePos { seconds } => {
                if let Some(t) = ticks(*seconds) {
                    old.position_ticks = t;
                }
                Some(Vec::new())
            }
            PlayerEvent::FileLoaded => {
                let Phase::Switching(old, new) = std::mem::take(&mut self.phase) else {
                    return Some(Vec::new());
                };
                let replaced = &old.playback;
                if replaced.method == PlayMethod::Transcode
                    && replaced.play_session_id != new.play_session_id
                {
                    self.ended_encoding = replaced.play_session_id.clone();
                }
                let mut playing = Playing {
                    position_ticks: new.start_ticks,
                    playback: new,
                    paused: false,
                    last_sent: now,
                    seeked: false,
                };
                let report = playing.report(ReportKind::Progress, Some("QualityChange"), now);
                self.phase = Phase::Playing(playing);
                Some(vec![report])
            }
            // The new stream failed or the player closed: the old playback ends.
            PlayerEvent::EndFile { .. } | PlayerEvent::Shutdown => {
                if let Phase::Switching(old, _) = std::mem::take(&mut self.phase) {
                    self.phase = Phase::Playing(old);
                }
                None
            }
            _ => Some(Vec::new()),
        }
    }

    pub fn on_event(&mut self, event: &PlayerEvent, now: Instant) -> Vec<Report> {
        if matches!(self.phase, Phase::Switching(..))
            && let Some(reports) = self.on_switching(event, now)
        {
            return reports;
        }
        if let Phase::Playing(p) = &self.phase {
            match event {
                PlayerEvent::EndFile { reason, .. } => {
                    let failed = match reason {
                        EndReason::Error => true,
                        EndReason::Eof => p.ended_early(),
                        _ => false,
                    };
                    return self.stop(failed, now);
                }
                PlayerEvent::Shutdown => return self.stop(false, now),
                _ => {}
            }
        }
        match (&mut self.phase, event) {
            (Phase::Pending(_), PlayerEvent::FileLoaded) => {
                let Phase::Pending(playback) = std::mem::take(&mut self.phase) else {
                    return Vec::new();
                };
                let mut playing = Playing {
                    position_ticks: playback.start_ticks,
                    playback,
                    paused: false,
                    last_sent: now,
                    seeked: false,
                };
                let report = playing.report(ReportKind::Start, None, now);
                self.phase = Phase::Playing(playing);
                vec![report]
            }
            // The file this playback replaces ends before the new one loads.
            (
                Phase::Pending(_),
                PlayerEvent::EndFile {
                    reason: EndReason::Stopped,
                    ..
                },
            ) => Vec::new(),
            (Phase::Pending(_), PlayerEvent::EndFile { .. } | PlayerEvent::Shutdown) => {
                self.phase = Phase::Idle;
                Vec::new()
            }
            (Phase::Playing(p), PlayerEvent::TimePos { seconds }) => {
                if let Some(t) = ticks(*seconds) {
                    p.position_ticks = t;
                }
                if std::mem::take(&mut p.seeked) {
                    vec![p.report(ReportKind::Progress, Some("TimeUpdate"), now)]
                } else {
                    Vec::new()
                }
            }
            (Phase::Playing(p), PlayerEvent::Duration { seconds }) => {
                if p.playback.run_time_ticks.is_none() {
                    p.playback.run_time_ticks = ticks(*seconds).filter(|&t| t > 0);
                }
                Vec::new()
            }
            (Phase::Playing(p), PlayerEvent::Paused { paused }) if *paused != p.paused => {
                p.paused = *paused;
                let event = if *paused { "Pause" } else { "Unpause" };
                vec![p.report(ReportKind::Progress, Some(event), now)]
            }
            (Phase::Playing(p), PlayerEvent::Seeked) => {
                p.seeked = true;
                Vec::new()
            }
            _ => Vec::new(),
        }
    }

    /// Periodic progress, due every [`PROGRESS_INTERVAL`] since the last report.
    pub fn tick(&mut self, now: Instant) -> Vec<Report> {
        match &mut self.phase {
            Phase::Playing(p) if now.duration_since(p.last_sent) >= PROGRESS_INTERVAL => {
                vec![p.report(ReportKind::Progress, Some("TimeUpdate"), now)]
            }
            _ => Vec::new(),
        }
    }

    fn stop(&mut self, failed: bool, now: Instant) -> Vec<Report> {
        match std::mem::take(&mut self.phase) {
            Phase::Playing(mut p) | Phase::Switching(mut p, _) => {
                let mut report = p.report(ReportKind::Stopped, None, now);
                report.body.failed = Some(failed);
                vec![report]
            }
            _ => Vec::new(),
        }
    }
}

fn ticks(seconds: f64) -> Option<i64> {
    (seconds.is_finite() && seconds >= 0.0).then(|| (seconds * TICKS_PER_SECOND).round() as i64)
}

/// When a lost stop report is sent again, and when it is given up.
#[derive(Debug, Clone, Copy, PartialEq)]
struct ResendSchedule {
    give_up_at: Instant,
    next_at: Instant,
    delay: Duration,
}

impl ResendSchedule {
    fn new(now: Instant) -> Self {
        Self {
            give_up_at: now + STOP_RESEND_GIVE_UP,
            next_at: now + STOP_RESEND_FIRST,
            delay: STOP_RESEND_FIRST,
        }
    }

    fn due(&self, now: Instant) -> bool {
        now >= self.next_at
    }

    fn expired(&self, now: Instant) -> bool {
        now >= self.give_up_at
    }

    /// The try at `now` failed.
    fn postpone(&mut self, now: Instant) {
        self.delay = (self.delay * 2).min(STOP_RESEND_CAP);
        self.next_at = now + self.delay;
    }
}

/// A stop report the server did not get, and the session it belongs to.
struct LostStop {
    report: Report,
    owner: EmbyClient,
    schedule: ResendSchedule,
}

/// A failure that may pass, unlike a refused token or a bad request.
fn is_transient(error: &EmbyError) -> bool {
    matches!(
        error,
        EmbyError::Unreachable(_) | EmbyError::Timeout | EmbyError::Server(_)
    )
}

enum Message {
    Begin(ActivePlayback),
    Switch(ActivePlayback),
    Player(PlayerEvent),
    Flush(std::sync::mpsc::Sender<()>),
    ResendStop(tokio::sync::oneshot::Sender<()>),
}

/// Handle to the reporting task. Cheap to clone; sending never blocks.
#[derive(Clone)]
pub struct Reporter {
    tx: tokio::sync::mpsc::UnboundedSender<Message>,
}

impl Reporter {
    pub fn spawn(app: tauri::AppHandle) -> Self {
        let (tx, rx) = tokio::sync::mpsc::unbounded_channel();
        tauri::async_runtime::spawn(run(app, rx));
        Self { tx }
    }

    pub fn begin(&self, playback: ActivePlayback) {
        let _ = self.tx.send(Message::Begin(playback));
    }

    /// A quality change of the playback in progress.
    pub fn switch(&self, playback: ActivePlayback) {
        let _ = self.tx.send(Message::Switch(playback));
    }

    /// Forwards the events the tracker uses (track lists are left out).
    pub fn player_event(&self, event: &PlayerEvent) {
        if !matches!(
            event,
            PlayerEvent::Tracks { .. } | PlayerEvent::Buffering { .. }
        ) {
            let _ = self.tx.send(Message::Player(event.clone()));
        }
    }

    /// Blocks until every report queued so far was sent, at most `timeout`. Not for
    /// the UI thread or an async task. Returns false on timeout.
    pub fn flush(&self, timeout: Duration) -> bool {
        let (ack, done) = std::sync::mpsc::channel();
        self.tx.send(Message::Flush(ack)).is_ok() && done.recv_timeout(timeout).is_ok()
    }

    /// Sends a lost stop report now, so that the server's position is the right one
    /// before a new playback asks for it. Waits a bounded time.
    pub async fn resend_stop(&self) {
        let (ack, done) = tokio::sync::oneshot::channel();
        if self.tx.send(Message::ResendStop(ack)).is_ok() {
            let _ = tokio::time::timeout(STOP_RESEND_WAIT, done).await;
        }
    }
}

async fn run(app: tauri::AppHandle, mut rx: tokio::sync::mpsc::UnboundedReceiver<Message>) {
    let mut tracker = Tracker::default();
    let mut lost: Option<LostStop> = None;
    loop {
        // Wake up every second at most, so periodic progress is sent while paused.
        let message = match tokio::time::timeout(Duration::from_secs(1), rx.recv()).await {
            Ok(Some(m)) => Some(m),
            Ok(None) => break,
            Err(_) => None,
        };
        let now = Instant::now();
        let mut reports = match message {
            // A late stop must never overwrite the position of a newer playback.
            Some(Message::Begin(p)) => {
                lost = None;
                tracker.begin(p, now)
            }
            Some(Message::Switch(p)) => tracker.switch(p, now),
            Some(Message::Player(e)) => tracker.on_event(&e, now),
            // Earlier messages were fully handled: their reports are sent.
            Some(Message::Flush(ack)) => {
                resend(&app, &mut lost).await;
                let _ = ack.send(());
                Vec::new()
            }
            Some(Message::ResendStop(ack)) => {
                resend(&app, &mut lost).await;
                let _ = ack.send(());
                Vec::new()
            }
            None => Vec::new(),
        };
        reports.extend(tracker.tick(now));
        if let Some(session) = tracker.take_ended_encoding() {
            // Only once mpv reads the new stream; a failure is only logged.
            if let Err(e) = app.state::<EmbyService>().stop_encoding(&session).await {
                log::warn!("replaced transcode not stopped: {}", e.kind());
            }
        }
        for report in reports {
            // The stop of a playback replaced by a new one stays lost, same rule as Begin.
            if let Some(stop) = deliver(&app, report).await
                && tracker.is_idle()
            {
                lost = Some(stop);
            }
        }
        if lost
            .as_ref()
            .is_some_and(|l| l.schedule.due(Instant::now()))
        {
            resend(&app, &mut lost).await;
        }
    }
}

/// Sends a report; returns a stop report the server did not get.
async fn deliver(app: &tauri::AppHandle, report: Report) -> Option<LostStop> {
    let emby = app.state::<EmbyService>();
    let owner = emby.client().ok();
    let mut result = emby.report_playback(&report).await;
    if report.kind == ReportKind::Stopped && result.as_ref().is_err_and(is_transient) {
        tokio::time::sleep(STOP_RETRY_DELAY).await;
        result = emby.report_playback(&report).await;
    }
    if let Err(e) = &result {
        log::warn!("playback report {:?} not sent: {}", report.kind, e.kind());
    }
    if report.kind != ReportKind::Stopped {
        return None;
    }
    announce_state(app, &report.body.item_id).await;
    match (result, owner) {
        (Err(e), Some(owner)) if is_transient(&e) => Some(LostStop {
            report,
            owner,
            schedule: ResendSchedule::new(Instant::now()),
        }),
        _ => None,
    }
}

/// Tries a lost stop report again; it stays lost only if the failure may pass.
async fn resend(app: &tauri::AppHandle, lost: &mut Option<LostStop>) {
    let Some(mut stop) = lost.take() else {
        return;
    };
    if stop.schedule.expired(Instant::now()) {
        log::warn!("stop report given up");
        return;
    }
    let emby = app.state::<EmbyService>();
    match emby.resend_stop(&stop.owner, &stop.report).await {
        Ok(()) => {
            log::info!("stop report sent after the cut");
            announce_state(app, &stop.report.body.item_id).await;
        }
        Err(e) if is_transient(&e) => {
            stop.schedule.postpone(Instant::now());
            *lost = Some(stop);
        }
        Err(e) => log::warn!("stop report given up: {}", e.kind()),
    }
}

/// Tells the UI what the server now holds for the item.
async fn announce_state(app: &tauri::AppHandle, item_id: &str) {
    match app.state::<EmbyService>().playback_state(item_id).await {
        Ok(state) => {
            let _ = app.emit(PLAYBACK_STOPPED_EVENT, &state);
        }
        Err(e) => log::warn!("state after playback not read: {}", e.kind()),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn playback() -> ActivePlayback {
        ActivePlayback {
            item_id: "30207".into(),
            media_source_id: "mediasource_30207".into(),
            play_session_id: Some("ps1".into()),
            method: PlayMethod::DirectPlay,
            run_time_ticks: None,
            start_ticks: 600_000_000,
        }
    }

    fn kinds(reports: &[Report]) -> Vec<(ReportKind, Option<&'static str>)> {
        reports
            .iter()
            .map(|r| (r.kind, r.body.event_name))
            .collect()
    }

    fn loaded(t0: Instant) -> Tracker {
        let mut tracker = Tracker::default();
        assert!(tracker.begin(playback(), t0).is_empty());
        tracker
            .on_event(&PlayerEvent::FileLoaded, t0)
            .pop()
            .unwrap();
        tracker
    }

    #[test]
    fn start_is_reported_on_load_at_the_resume_position() {
        let t0 = Instant::now();
        let mut tracker = Tracker::default();
        tracker.begin(playback(), t0);
        assert!(
            tracker.tick(t0 + PROGRESS_INTERVAL).is_empty(),
            "not loaded"
        );
        let start = tracker.on_event(&PlayerEvent::FileLoaded, t0);
        assert_eq!(kinds(&start), vec![(ReportKind::Start, None)]);
        let body = serde_json::to_value(&start[0].body).unwrap();
        assert_eq!(
            body,
            serde_json::json!({
                "ItemId": "30207", "MediaSourceId": "mediasource_30207",
                "PlaySessionId": "ps1", "PositionTicks": 600_000_000,
                "IsPaused": false, "CanSeek": true, "PlayMethod": "DirectPlay"
            })
        );
    }

    #[test]
    fn progress_follows_pause_seek_and_the_interval() {
        let t0 = Instant::now();
        let mut t = loaded(t0);
        let pos = |s| PlayerEvent::TimePos { seconds: s };
        assert!(t.on_event(&pos(61.0), t0).is_empty());
        assert!(
            t.on_event(&PlayerEvent::Duration { seconds: 6000.0 }, t0)
                .is_empty()
        );
        // mpv reports the initial unpaused state: no change, nothing sent.
        assert!(
            t.on_event(&PlayerEvent::Paused { paused: false }, t0)
                .is_empty()
        );
        assert!(t.tick(t0 + Duration::from_secs(9)).is_empty());

        let progress = t.tick(t0 + PROGRESS_INTERVAL);
        assert_eq!(
            kinds(&progress),
            vec![(ReportKind::Progress, Some("TimeUpdate"))]
        );
        assert_eq!(progress[0].body.position_ticks, 610_000_000);
        assert_eq!(progress[0].body.run_time_ticks, Some(60_000_000_000));

        let t1 = t0 + Duration::from_secs(12);
        let pause = t.on_event(&PlayerEvent::Paused { paused: true }, t1);
        assert_eq!(kinds(&pause), vec![(ReportKind::Progress, Some("Pause"))]);
        assert_eq!(pause[0].body.is_paused, Some(true));
        // Still reported while paused, from the last report.
        assert!(t.tick(t1 + Duration::from_secs(9)).is_empty());
        assert_eq!(t.tick(t1 + PROGRESS_INTERVAL).len(), 1);

        let t2 = t1 + Duration::from_secs(11);
        assert_eq!(
            kinds(&t.on_event(&PlayerEvent::Paused { paused: false }, t2)),
            vec![(ReportKind::Progress, Some("Unpause"))]
        );
        assert!(t.on_event(&PlayerEvent::Seeked, t2).is_empty());
        let after_seek = t.on_event(&pos(1200.5), t2);
        assert_eq!(
            kinds(&after_seek),
            vec![(ReportKind::Progress, Some("TimeUpdate"))]
        );
        assert_eq!(after_seek[0].body.position_ticks, 12_005_000_000);
        assert!(t.on_event(&pos(1201.0), t2).is_empty());
        assert!(t.on_event(&pos(f64::NAN), t2).is_empty());
    }

    #[test]
    fn stop_carries_the_last_position_and_failure() {
        let t0 = Instant::now();
        for (event, failed) in [
            (
                PlayerEvent::EndFile {
                    reason: EndReason::Eof,
                    error: None,
                },
                false,
            ),
            (
                PlayerEvent::EndFile {
                    reason: EndReason::Error,
                    error: Some("x".into()),
                },
                true,
            ),
            (PlayerEvent::Shutdown, false),
        ] {
            let mut t = loaded(t0);
            // 10 s before the end of the 6000 s file: a normal end.
            t.on_event(&PlayerEvent::Duration { seconds: 6000.0 }, t0);
            t.on_event(&PlayerEvent::TimePos { seconds: 5990.0 }, t0);
            let stop = t.on_event(&event, t0);
            assert_eq!(kinds(&stop), vec![(ReportKind::Stopped, None)], "{event:?}");
            let body = serde_json::to_value(&stop[0].body).unwrap();
            assert_eq!(body["PositionTicks"], 59_900_000_000_i64);
            assert_eq!(body["Failed"], failed);
            assert!(body.get("IsPaused").is_none());
            // Nothing more once stopped.
            assert!(t.on_event(&PlayerEvent::Shutdown, t0).is_empty());
            assert!(t.tick(t0 + PROGRESS_INTERVAL * 3).is_empty());
        }
    }

    #[test]
    fn an_early_end_of_file_is_reported_as_failed_at_the_last_position() {
        let t0 = Instant::now();
        let mut t = loaded(t0);
        t.on_event(&PlayerEvent::Duration { seconds: 6000.0 }, t0);
        t.on_event(&PlayerEvent::TimePos { seconds: 1000.0 }, t0);
        let stop = t.on_event(
            &PlayerEvent::EndFile {
                reason: EndReason::Eof,
                error: None,
            },
            t0,
        );
        let body = serde_json::to_value(&stop[0].body).unwrap();
        assert_eq!(body["Failed"], true);
        assert_eq!(body["PositionTicks"], 10_000_000_000_i64);
    }

    #[test]
    fn a_file_that_never_loads_is_never_reported() {
        let t0 = Instant::now();
        let mut t = Tracker::default();
        t.begin(playback(), t0);
        let replaced = PlayerEvent::EndFile {
            reason: EndReason::Stopped,
            error: None,
        };
        assert!(t.on_event(&replaced, t0).is_empty());
        let failed = PlayerEvent::EndFile {
            reason: EndReason::Error,
            error: None,
        };
        assert!(t.on_event(&failed, t0).is_empty());
        assert!(t.on_event(&PlayerEvent::FileLoaded, t0).is_empty());

        // Events without any announced playback (local files of the prototype).
        let mut idle = Tracker::default();
        assert!(idle.on_event(&PlayerEvent::FileLoaded, t0).is_empty());
        assert!(idle.on_event(&PlayerEvent::Shutdown, t0).is_empty());
    }

    #[test]
    fn a_lost_stop_report_is_resent_with_a_growing_delay_then_given_up() {
        let t0 = Instant::now();
        let mut s = ResendSchedule::new(t0);
        assert!(!s.due(t0 + Duration::from_secs(4)));
        assert!(s.due(t0 + STOP_RESEND_FIRST));

        let t1 = t0 + STOP_RESEND_FIRST;
        s.postpone(t1);
        assert!(!s.due(t1 + Duration::from_secs(9)));
        assert!(s.due(t1 + Duration::from_secs(10)));

        let t2 = t1 + Duration::from_secs(10);
        s.postpone(t2);
        assert!(s.due(t2 + STOP_RESEND_CAP));
        s.postpone(t2);
        assert!(s.due(t2 + STOP_RESEND_CAP), "capped");

        assert!(!s.expired(t0 + STOP_RESEND_GIVE_UP - Duration::from_secs(1)));
        assert!(s.expired(t0 + STOP_RESEND_GIVE_UP));
    }

    #[test]
    fn a_late_progress_carries_the_position_of_the_lost_stop() {
        let stop = Report {
            kind: ReportKind::Stopped,
            body: PlaybackReportBody {
                item_id: "30207".into(),
                media_source_id: "ms".into(),
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
        let late = stop.late_progress();
        assert_eq!(late.kind, ReportKind::Progress);
        assert_eq!(late.body.position_ticks, 7_583_000_000);
        assert_eq!(late.body.play_session_id.as_deref(), Some("ps1"));
        assert_eq!(late.body.play_method, Some("Transcode"));
        assert_eq!(late.body.is_paused, Some(false));
        assert_eq!(late.body.can_seek, Some(true));
        assert_eq!(late.body.event_name, Some("TimeUpdate"));
        assert_eq!(late.body.failed, None);
    }

    #[test]
    fn only_a_passing_failure_keeps_the_stop_report() {
        assert!(is_transient(&EmbyError::Timeout));
        assert!(is_transient(&EmbyError::Unreachable("x".into())));
        assert!(is_transient(&EmbyError::Server(503)));
        assert!(!is_transient(&EmbyError::SessionExpired));
        assert!(!is_transient(&EmbyError::NotSignedIn));
        assert!(!is_transient(&EmbyError::Cancelled));
    }

    #[test]
    fn a_new_playback_stops_the_current_one() {
        let t0 = Instant::now();
        let mut t = loaded(t0);
        let mut next = playback();
        next.item_id = "80475".into();
        let stop = t.begin(next, t0);
        assert_eq!(kinds(&stop), vec![(ReportKind::Stopped, None)]);
        assert_eq!(stop[0].body.item_id, "30207");
        let start = t.on_event(&PlayerEvent::FileLoaded, t0);
        assert_eq!(start[0].body.item_id, "80475");
    }

    fn switched(method: PlayMethod, session: &str) -> ActivePlayback {
        ActivePlayback {
            play_session_id: Some(session.into()),
            method,
            start_ticks: 900_000_000,
            ..playback()
        }
    }

    #[test]
    fn quality_switch_goes_on_with_a_quality_change_progress() {
        let t0 = Instant::now();
        let mut t = loaded(t0);
        assert!(
            t.switch(switched(PlayMethod::Transcode, "ps2"), t0)
                .is_empty()
        );
        let old_end = PlayerEvent::EndFile {
            reason: EndReason::Stopped,
            error: None,
        };
        assert!(t.on_event(&old_end, t0).is_empty(), "no stop");
        let reports = t.on_event(&PlayerEvent::FileLoaded, t0);
        assert_eq!(
            kinds(&reports),
            vec![(ReportKind::Progress, Some("QualityChange"))]
        );
        assert_eq!(reports[0].body.play_session_id.as_deref(), Some("ps2"));
        assert_eq!(reports[0].body.position_ticks, 900_000_000);
        // The old stream was direct play: nothing to stop on the server.
        assert_eq!(t.take_ended_encoding(), None);
    }

    #[test]
    fn a_replaced_transcode_is_stopped_once() {
        let t0 = Instant::now();
        let mut t = loaded(t0);
        t.switch(switched(PlayMethod::Transcode, "ps2"), t0);
        t.on_event(&PlayerEvent::FileLoaded, t0);
        t.switch(switched(PlayMethod::DirectPlay, "ps3"), t0);
        t.on_event(&PlayerEvent::FileLoaded, t0);
        assert_eq!(t.take_ended_encoding().as_deref(), Some("ps2"));
        assert_eq!(t.take_ended_encoding(), None);
    }

    #[test]
    fn a_failed_switch_stops_the_old_playback() {
        let t0 = Instant::now();
        let mut t = loaded(t0);
        t.switch(switched(PlayMethod::Transcode, "ps2"), t0);
        let failed = PlayerEvent::EndFile {
            reason: EndReason::Error,
            error: Some("x".into()),
        };
        let stop = t.on_event(&failed, t0);
        assert_eq!(kinds(&stop), vec![(ReportKind::Stopped, None)]);
        assert_eq!(stop[0].body.play_session_id.as_deref(), Some("ps1"));
    }

    #[test]
    fn a_switch_to_another_item_is_a_new_playback() {
        let t0 = Instant::now();
        let mut t = loaded(t0);
        let other = ActivePlayback {
            item_id: "80475".into(),
            ..playback()
        };
        let stop = t.switch(other, t0);
        assert_eq!(kinds(&stop), vec![(ReportKind::Stopped, None)]);
    }
}
