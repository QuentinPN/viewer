//! Gate 1 automated playback scenario (debug builds only).
//!
//! Enabled by `VIEWER_SPIKE_MEDIA=<path or http URL>` and `VIEWER_SPIKE_OUT=<dir>`.
//! Drives the real app (same `AppState` and engine as the IPC commands), writes the
//! current step to `<out>/checkpoint.txt` so `scripts/spike/run-gate1.ps1` can take
//! screenshots and send real keystrokes, and writes `<out>/report.json`.
//! Results are evidence for Gate 1; they are not a substitute for manual review.

use std::path::{Path, PathBuf};
use std::time::{Duration, Instant};

use serde::Serialize;
use tauri::{AppHandle, Manager, PhysicalSize};

use crate::AppState;
use crate::player::mpv::MpvEngine;
use crate::player::{
    MediaSource, PlaybackRequest, PlayerCommand, PlayerDiagnostics, PlayerEngine, TrackKind,
};

#[derive(Serialize)]
struct Check {
    name: String,
    passed: bool,
    detail: String,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct Report {
    surface_mode: crate::video_surface::SurfaceMode,
    media: String,
    checks: Vec<Check>,
    diagnostics_playing: Option<PlayerDiagnostics>,
    tracks: Vec<crate::player::Track>,
    teardown_ms: Vec<u128>,
}

pub fn maybe_start(app: AppHandle) {
    let (Some(media), Some(out)) = (
        std::env::var_os("VIEWER_SPIKE_MEDIA"),
        std::env::var_os("VIEWER_SPIKE_OUT"),
    ) else {
        return;
    };
    let out = PathBuf::from(out);
    let media = media.to_string_lossy().into_owned();
    std::thread::spawn(move || {
        let report = run(&app, &media, &out);
        let json = serde_json::to_string_pretty(&report).unwrap_or_default();
        let _ = std::fs::write(out.join("report.json"), json);
        checkpoint(&out, "done");
        std::thread::sleep(Duration::from_millis(500));
        // Same exit path as the user closing the window (see lib.rs).
        match app.get_webview_window("main") {
            Some(w) if w.destroy().is_ok() => {}
            _ => app.exit(0),
        }
    });
}

fn checkpoint(out: &Path, name: &str) {
    let _ = std::fs::write(out.join("checkpoint.txt"), name);
}

fn hold(out: &Path, name: &str, secs: u64) {
    checkpoint(out, name);
    std::thread::sleep(Duration::from_secs(secs));
}

fn wait_until(timeout: Duration, mut pred: impl FnMut() -> bool) -> bool {
    let deadline = Instant::now() + timeout;
    while Instant::now() < deadline {
        if pred() {
            return true;
        }
        std::thread::sleep(Duration::from_millis(100));
    }
    pred()
}

fn run(app: &AppHandle, media: &str, out: &Path) -> Report {
    let state = app.state::<AppState>();
    let window = app.get_webview_window("main").expect("main window");
    let mut checks = Vec::new();
    let mut push = |name: &str, passed: bool, detail: String| {
        log::info!("spike check {name}: {passed} ({detail})");
        checks.push(Check {
            name: name.into(),
            passed,
            detail,
        });
    };

    let mut teardown_ms = Vec::new();

    // Give the WebView time to load so overlay screenshots are meaningful.
    hold(out, "startup", 3);

    let source = match MediaSource::parse(media) {
        Ok(s) => s,
        Err(e) => {
            push("source", false, e.to_string());
            return finish(&state, media, checks, None, vec![], teardown_ms);
        }
    };
    let engine = match state.engine() {
        Ok(e) => e,
        Err(e) => {
            push("engine_create", false, e.to_string());
            return finish(&state, media, checks, None, vec![], teardown_ms);
        }
    };
    push("engine_create", true, state.library.display().to_string());

    // Optional test token, sent as a header to prove auth never needs the URL.
    let http_headers = std::env::var("VIEWER_SPIKE_HTTP_TOKEN")
        .map(|t| vec![("X-Emby-Token".to_owned(), t)])
        .unwrap_or_default();
    let request = PlaybackRequest {
        source: media.into(),
        http_headers,
        start_seconds: None,
        subtitles: Vec::new(),
        ..Default::default()
    };
    let started = Instant::now();
    let loaded = engine.load(&request, &source).is_ok()
        && wait_until(Duration::from_secs(15), || time_pos(&engine) > 1.0);
    push(
        "load_and_play",
        loaded,
        format!(
            "first second played after {} ms",
            started.elapsed().as_millis()
        ),
    );
    if !loaded {
        return finish(&state, media, checks, None, vec![], teardown_ms);
    }

    let diag = engine.diagnostics();
    push(
        "video_output",
        diag.video_output.is_some() && diag.osd_width.unwrap_or(0) > 0,
        format!(
            "vo={:?} gpu={:?} osd={:?}x{:?}",
            diag.video_output, diag.gpu_api, diag.osd_width, diag.osd_height
        ),
    );
    push(
        "hardware_decoding",
        diag.hwdec_current.as_deref().is_some_and(|h| h != "no"),
        format!("hwdec-current={:?}", diag.hwdec_current),
    );
    let diagnostics_playing = Some(diag);
    hold(out, "playing", 4);

    // Tracks: switch to every audio track, every subtitle track, then disable subtitles.
    let tracks = engine.tracks().unwrap_or_default();
    let audio: Vec<_> = tracks
        .iter()
        .filter(|t| t.kind == TrackKind::Audio)
        .map(|t| t.id)
        .collect();
    let subs: Vec<_> = tracks
        .iter()
        .filter(|t| t.kind == TrackKind::Sub)
        .map(|t| t.id)
        .collect();
    // `aid`/`sid` only reflect the request; `current-tracks/*` is the track mpv
    // actually decodes, and playback must keep advancing after the switch.
    for id in &audio {
        let requested = engine
            .command(&PlayerCommand::SetAudioTrack { id: Some(*id) })
            .is_ok();
        let active = requested
            && wait_until(Duration::from_secs(3), || {
                engine.property("current-tracks/audio/id") == Some(id.to_string())
            });
        let before = time_pos(&engine);
        let advancing =
            active && wait_until(Duration::from_secs(3), || time_pos(&engine) > before + 0.5);
        push(
            &format!("audio_track_{id}"),
            advancing,
            format!(
                "active={:?} lang={:?} codec={:?} ao={:?} advancing={advancing}",
                engine.property("current-tracks/audio/id"),
                engine.property("current-tracks/audio/lang"),
                engine.property("audio-codec-name"),
                engine.property("current-ao"),
            ),
        );
        hold(out, &format!("audio-{id}"), 2);
    }
    // For text subtitles, `sub-text` proves each track yields its own cues; only
    // a hash is recorded so reports never contain the subtitle content.
    let mut sub_hashes = Vec::new();
    // Every track starts from the same point so a sparse track cannot move the
    // playhead into a part of the file without dialogue.
    let anchor = time_pos(&engine).max(0.0);
    for id in &subs {
        let requested = engine
            .command(&PlayerCommand::SetSubtitleTrack { id: Some(*id) })
            .is_ok();
        let active = requested
            && wait_until(Duration::from_secs(3), || {
                engine.property("current-tracks/sub/id") == Some(id.to_string())
            });
        push(
            &format!("subtitle_track_{id}"),
            active,
            format!(
                "active={:?} lang={:?} codec={:?}",
                engine.property("current-tracks/sub/id"),
                engine.property("current-tracks/sub/lang"),
                engine.property("current-tracks/sub/codec"),
            ),
        );
        let _ = engine.command(&PlayerCommand::SeekAbsolute { seconds: anchor });
        let mut text = None;
        let found = active
            && wait_until(Duration::from_secs(20), || {
                text = engine.property("sub-text").filter(|t| !t.trim().is_empty());
                text.is_some()
            });
        let hash = text.as_deref().map(text_hash);
        sub_hashes.extend(hash);
        // Forced/signs tracks can legitimately have no cue in the window.
        push(
            &format!("subtitle_cue_{id}"),
            found,
            format!(
                "window={anchor:.0}-{:.0}s cue_chars={:?} cue_hash={hash:?}",
                anchor + 20.0,
                text.as_deref().map(|t| t.chars().count()),
            ),
        );
        hold(out, &format!("subtitle-{id}"), 3);
    }
    if sub_hashes.len() > 1 {
        let mut distinct = sub_hashes.clone();
        distinct.sort_unstable();
        distinct.dedup();
        push(
            "subtitle_cues_distinct",
            distinct.len() == sub_hashes.len(),
            format!(
                "{} distinct cues for {} tracks with a cue",
                distinct.len(),
                sub_hashes.len()
            ),
        );
    }
    if !subs.is_empty() {
        let off = engine
            .command(&PlayerCommand::SetSubtitleTrack { id: None })
            .is_ok()
            && wait_until(Duration::from_secs(3), || {
                engine.property("current-tracks/sub/id").is_none()
            });
        push(
            "subtitle_disable",
            off,
            format!("sid={:?}", engine.property("sid")),
        );
        hold(out, "subtitle-off", 3);
    }
    if let Some(first) = subs.first() {
        let _ = engine.command(&PlayerCommand::SetSubtitleTrack { id: Some(*first) });
    }
    if let Some(first) = audio.first() {
        let _ = engine.command(&PlayerCommand::SetAudioTrack { id: Some(*first) });
    }
    let report_tracks = tracks;

    // Seek to the middle of the file and check the reported position.
    let duration = engine.diagnostics().duration.unwrap_or(0.0);
    let target = (duration / 2.0).floor();
    let seek_ok = engine
        .command(&PlayerCommand::SeekAbsolute { seconds: target })
        .is_ok()
        && wait_until(Duration::from_secs(8), || {
            (time_pos(&engine) - target).abs() < 3.0
        });
    push(
        "seek_middle",
        seek_ok,
        format!(
            "target={target} pos={:.2} duration={duration:.1}",
            time_pos(&engine)
        ),
    );
    hold(out, "seeked-subtitles", 4);

    // Fullscreen: mpv's render size must follow the monitor size.
    let monitor = window.current_monitor().ok().flatten().map(|m| *m.size());
    let _ = window.set_fullscreen(true);
    let fs_ok = wait_until(Duration::from_secs(5), || {
        let d = engine.diagnostics();
        monitor.is_some_and(|m| {
            d.osd_width == Some(i64::from(m.width)) && d.osd_height == Some(i64::from(m.height))
        })
    });
    let d = engine.diagnostics();
    push(
        "fullscreen_render_size",
        fs_ok,
        format!(
            "monitor={monitor:?} osd={:?}x{:?}",
            d.osd_width, d.osd_height
        ),
    );
    hold(out, "fullscreen", 4);
    let _ = window.set_fullscreen(false);
    std::thread::sleep(Duration::from_secs(1));

    // Resize: render size must follow the window's client area.
    let _ = window.set_size(PhysicalSize::new(1000u32, 600u32));
    let resize_ok = wait_until(Duration::from_secs(5), || {
        let inner = window.inner_size().ok();
        let d = engine.diagnostics();
        inner.is_some_and(|s| {
            d.osd_width == Some(i64::from(s.width)) && d.osd_height == Some(i64::from(s.height))
        })
    });
    let d = engine.diagnostics();
    push(
        "resize_render_size",
        resize_ok,
        format!(
            "inner={:?} osd={:?}x{:?}",
            window.inner_size().ok(),
            d.osd_width,
            d.osd_height
        ),
    );
    hold(out, "resized", 3);

    // Keyboard through the WebView: the driver sends a real Space key press;
    // the Svelte layer maps it to TogglePause. Proves focus stays in the WebView.
    let _ = engine.command(&PlayerCommand::SetPaused { paused: false });
    let _ = window.set_focus();
    std::thread::sleep(Duration::from_millis(500));
    checkpoint(out, "keyboard-press-space");
    let key_ok = wait_until(Duration::from_secs(10), || {
        engine.property("pause").as_deref() == Some("yes")
    });
    push(
        "keyboard_space_pauses",
        key_ok,
        format!("pause={:?}", engine.property("pause")),
    );
    hold(out, "paused", 2);

    drop(engine);
    let t = Instant::now();
    state.close_player();
    teardown_ms.push(t.elapsed().as_millis());
    let closed = state.player.lock().map(|g| g.is_none()).unwrap_or(false);
    push("teardown", closed, format!("{} ms", teardown_ms[0]));
    hold(out, "closed", 2);

    // Repeated open/close cycles to catch hangs or crashes in teardown.
    let mut cycles_ok = true;
    for _ in 0..3 {
        let ok = state
            .engine()
            .and_then(|e| e.load(&request, &source))
            .is_ok();
        let played = ok
            && state
                .player
                .lock()
                .ok()
                .and_then(|g| g.clone())
                .is_some_and(|e| wait_until(Duration::from_secs(10), || time_pos(&e) > 0.3));
        let t = Instant::now();
        state.close_player();
        teardown_ms.push(t.elapsed().as_millis());
        cycles_ok &= played;
    }
    push(
        "open_close_cycles",
        cycles_ok,
        format!("teardown ms: {teardown_ms:?}"),
    );

    finish(
        &state,
        media,
        checks,
        diagnostics_playing,
        report_tracks,
        teardown_ms,
    )
}

fn time_pos(engine: &MpvEngine) -> f64 {
    engine
        .property("time-pos")
        .and_then(|v| v.parse().ok())
        .unwrap_or(-1.0)
}

fn finish(
    state: &AppState,
    media: &str,
    checks: Vec<Check>,
    diagnostics_playing: Option<PlayerDiagnostics>,
    tracks: Vec<crate::player::Track>,
    teardown_ms: Vec<u128>,
) -> Report {
    state.close_player();
    // Only the file name is recorded, so reports can be shared without local paths.
    let media = media.rsplit(['/', '\\']).next().unwrap_or(media).to_owned();
    Report {
        surface_mode: state.mode,
        media,
        checks,
        diagnostics_playing,
        tracks,
        teardown_ms,
    }
}

fn text_hash(text: &str) -> u64 {
    use std::hash::{DefaultHasher, Hash, Hasher};
    let mut h = DefaultHasher::new();
    text.hash(&mut h);
    h.finish()
}
