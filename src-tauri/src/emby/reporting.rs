//! Playback reporting (ADR 0008): player events become Emby start, progress and stop
//! reports, sent in order by one background task. The UI never sends reports.

use std::time::{Duration, Instant};

use tauri::{Emitter, Manager};

use super::EmbyError;
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

#[derive(Debug, Clone, PartialEq)]
pub struct Report {
    pub kind: ReportKind,
    pub body: PlaybackReportBody,
}

/// Pure state machine from player events to reports; time is passed in.
#[derive(Debug, Default)]
pub struct Tracker {
    phase: Phase,
}

#[derive(Debug, Default)]
enum Phase {
    #[default]
    Idle,
    /// Announced, file not loaded yet: nothing is reported if loading fails.
    Pending(ActivePlayback),
    Playing(Playing),
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
                play_method: live.then_some(p.method.as_emby()),
                event_name: event,
                failed: None,
            },
        }
    }
}

impl Tracker {
    /// Announces a new playback; one still playing is reported stopped first.
    pub fn begin(&mut self, playback: ActivePlayback, now: Instant) -> Vec<Report> {
        let out = self.stop(false, now);
        self.phase = Phase::Pending(playback);
        out
    }

    pub fn on_event(&mut self, event: &PlayerEvent, now: Instant) -> Vec<Report> {
        if matches!(self.phase, Phase::Playing(_)) {
            match event {
                PlayerEvent::EndFile { reason, .. } => {
                    return self.stop(*reason == EndReason::Error, now);
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
            Phase::Playing(mut p) => {
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

enum Message {
    Begin(ActivePlayback),
    Player(PlayerEvent),
    Flush(std::sync::mpsc::Sender<()>),
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
}

async fn run(app: tauri::AppHandle, mut rx: tokio::sync::mpsc::UnboundedReceiver<Message>) {
    let mut tracker = Tracker::default();
    loop {
        // Wake up every second at most, so periodic progress is sent while paused.
        let message = match tokio::time::timeout(Duration::from_secs(1), rx.recv()).await {
            Ok(Some(m)) => Some(m),
            Ok(None) => break,
            Err(_) => None,
        };
        let now = Instant::now();
        let mut reports = match message {
            Some(Message::Begin(p)) => tracker.begin(p, now),
            Some(Message::Player(e)) => tracker.on_event(&e, now),
            // Earlier messages were fully handled: their reports are sent.
            Some(Message::Flush(ack)) => {
                let _ = ack.send(());
                Vec::new()
            }
            None => Vec::new(),
        };
        reports.extend(tracker.tick(now));
        for report in reports {
            deliver(&app, &report).await;
        }
    }
}

async fn deliver(app: &tauri::AppHandle, report: &Report) {
    let emby = app.state::<EmbyService>();
    let mut result = emby.report_playback(report).await;
    if report.kind == ReportKind::Stopped
        && matches!(
            result,
            Err(EmbyError::Unreachable(_) | EmbyError::Timeout | EmbyError::Server(_))
        )
    {
        tokio::time::sleep(STOP_RETRY_DELAY).await;
        result = emby.report_playback(report).await;
    }
    if let Err(e) = &result {
        log::warn!("playback report {:?} not sent: {}", report.kind, e.kind());
    }
    if report.kind == ReportKind::Stopped {
        match emby.playback_state(&report.body.item_id).await {
            Ok(state) => {
                let _ = app.emit(PLAYBACK_STOPPED_EVENT, &state);
            }
            Err(e) => log::warn!("state after playback not read: {}", e.kind()),
        }
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
}
