//! Golden JSON dump for the Go port (ADR 0128). Test-only, and silent unless
//! `VIEWER_GOLDEN_OUT` names a directory: then `dump_golden` writes there the exact
//! JSON the Svelte frontend receives (command results, command errors, events), built
//! from the fixtures of `emby/tests.rs` (fake local Emby server) and from literals
//! copied from the unit tests of the modules, never from a real server.
//!
//! Typed values are serialized with `serde_json::to_string_pretty` on the typed value
//! itself, as Tauri's IPC does: keys come in struct field order. Values built with
//! `json!` (tables, projections of non-IPC types) have alphabetical keys.
//!
//! Normalizations, so the output is the same on every run and holds no secret:
//! the fake server's `http://127.0.0.1:<port>` becomes `http://emby.test`, and the
//! fixture token becomes `TOKEN_PLACEHOLDER`.

use std::path::{Path, PathBuf};
use std::sync::atomic::Ordering;

use serde::Serialize;
use serde_json::{Value, json};

use crate::emby::EmbyError;
use crate::emby::chapters;
use crate::emby::images::{self, ImageRequest, ImageType};
use crate::emby::models::{
    BaseItemDto, ChapterDto, ItemsOptions, MediaStreamDto, PersonDto, PlayMethod,
    PlaybackInfoResponse, PlaybackStart, PlaybackStopped, ResumePoint, SavedServer, SessionStatus,
    WatchFilter,
};
use crate::emby::playback::{self, MAX_STREAMING_BITRATE, PlaybackPlan};
use crate::emby::quality::{BITRATE_CHOICES, QualityCaps};
use crate::emby::server_url::ServerUrl;
use crate::emby::tests::{
    FakeEmby, PASSWORD, SERVER_ID, TOKEN, emby_like, fixture, run, signed_in_browser,
};
use crate::emby::titles::{self, TitlesSource};
use crate::emby::tracks::{self, SubtitleMode, TrackPreferences};
use crate::player::{
    EndReason, ExternalSubtitle, PlaybackRequest, PlayerDiagnostics, PlayerError, PlayerEvent,
    SubtitleStyle, Track, TrackKind,
};
use crate::sync::SyncDevice;
use crate::sync::leader::{Notice, Status, UiAction};
use crate::updater::UpdateInfo;

const OUT_ENV: &str = "VIEWER_GOLDEN_OUT";
const TOKEN_PLACEHOLDER: &str = "TOKEN_PLACEHOLDER";
const FAKE_HOST: &str = "http://emby.test";
/// Server of the pure cases (no network involved).
const PURE_SERVER: &str = "https://emby.test";

/// What the fake server of `emby/tests.rs` answers, for the `source` of an entry.
const BROWSE: &str = "emby/tests.rs: browse_like() fake server";

struct Entry {
    file: String,
    area: String,
    case: String,
    channel: String,
    rust_type: String,
    source: String,
    note: Option<String>,
}

struct Golden {
    dir: PathBuf,
    entries: Vec<Entry>,
    replacements: Vec<(String, String)>,
}

impl Golden {
    fn new(dir: PathBuf) -> Self {
        Self {
            dir,
            entries: Vec::new(),
            replacements: vec![(TOKEN.to_owned(), TOKEN_PLACEHOLDER.to_owned())],
        }
    }

    /// A fake server's address, rewritten in every file from now on.
    fn fake_server(&mut self, url: &str) {
        self.replacements
            .push((url.to_owned(), FAKE_HOST.to_owned()));
    }

    /// Writes `<area>.<case>.json` and records it in the index.
    fn put<T: Serialize + ?Sized>(
        &mut self,
        area: &str,
        case: &str,
        channel: &str,
        rust_type: &str,
        source: &str,
        value: &T,
    ) {
        let file = format!("{area}.{case}.json");
        assert!(
            !self.entries.iter().any(|e| e.file == file),
            "golden file written twice: {file}"
        );
        let mut text = serde_json::to_string_pretty(value).unwrap();
        for (from, to) in &self.replacements {
            text = text.replace(from, to);
        }
        for leak in ["SECRET", TOKEN] {
            assert!(!text.contains(leak), "{file} holds {leak}:\n{text}");
        }
        text.push('\n');
        std::fs::write(self.dir.join(&file), text).unwrap();
        self.entries.push(Entry {
            file,
            area: area.to_owned(),
            case: case.to_owned(),
            channel: channel.to_owned(),
            rust_type: rust_type.to_owned(),
            source: source.to_owned(),
            note: None,
        });
    }

    /// Result of the Tauri command `command` (a variant is appended to the case).
    fn command<T: Serialize + ?Sized>(
        &mut self,
        area: &str,
        command: &str,
        variant: Option<&str>,
        rust_type: &str,
        source: &str,
        value: &T,
    ) {
        let case = match variant {
            Some(v) => format!("{command}.{v}"),
            None => command.to_owned(),
        };
        self.put(
            area,
            &case,
            &format!("command {command}"),
            rust_type,
            source,
            value,
        );
    }

    /// Adds a remark to the last entry.
    fn note(&mut self, text: &str) {
        self.entries.last_mut().unwrap().note = Some(text.to_owned());
    }

    fn write_index(&mut self) {
        self.entries.sort_by(|a, b| a.file.cmp(&b.file));
        let files: Vec<Value> = self
            .entries
            .iter()
            .map(|e| {
                let mut v = json!({
                    "file": e.file,
                    "area": e.area,
                    "case": e.case,
                    "channel": e.channel,
                    "rustType": e.rust_type,
                    "source": e.source,
                });
                if let Some(note) = &e.note {
                    v["note"] = json!(note);
                }
                v
            })
            .collect();
        let index = json!({
            "about": "Golden JSON of the Rust native side (ADR 0128): what the Svelte frontend receives, as serde produces it. Generated by `VIEWER_GOLDEN_OUT=<dir> cargo test --manifest-path src-tauri/Cargo.toml golden -- --nocapture` (src-tauri/src/golden.rs). Do not edit by hand.",
            "keyOrder": "Typed values keep the struct field order of the IPC. Tables and projections built with json! (marked in `note`) have alphabetical keys: compare them as JSON, not as text.",
            "normalizations": [
                format!("the fake server http://127.0.0.1:<port> is written {FAKE_HOST}"),
                format!("the fixture access token is written {TOKEN_PLACEHOLDER}"),
                "pure cases use https://emby.test; image URLs use the fixed local origin http://emby-img.localhost",
            ],
            "files": files,
        });
        let mut text = serde_json::to_string_pretty(&index).unwrap();
        text.push('\n');
        std::fs::write(self.dir.join("index.json"), text).unwrap();
    }
}

/// Removes the files a previous run listed in its `index.json`, nothing else.
fn remove_previous(dir: &Path) {
    let Ok(text) = std::fs::read_to_string(dir.join("index.json")) else {
        return;
    };
    let Ok(index) = serde_json::from_str::<Value>(&text) else {
        return;
    };
    for file in index["files"].as_array().into_iter().flatten() {
        if let Some(name) = file["file"].as_str()
            && name.ends_with(".json")
            && !name.contains(['/', '\\'])
        {
            let _ = std::fs::remove_file(dir.join(name));
        }
    }
}

#[test]
fn dump_golden() {
    let Some(out) = std::env::var_os(OUT_ENV) else {
        return;
    };
    let dir = PathBuf::from(out);
    std::fs::create_dir_all(&dir).unwrap();
    remove_previous(&dir);
    let mut g = Golden::new(dir);
    session_cases(&mut g);
    browse_cases(&mut g);
    playback_cases(&mut g);
    error_cases(&mut g);
    server_url_cases(&mut g);
    image_cases(&mut g);
    titles_cases(&mut g);
    quality_cases(&mut g);
    chapter_cases(&mut g);
    track_cases(&mut g);
    pure_playback_cases(&mut g);
    event_cases(&mut g);
    player_cases(&mut g);
    g.write_index();
    println!("golden: {} files", g.entries.len() + 1);
}

fn err_json(e: &EmbyError) -> Value {
    serde_json::to_value(e).unwrap()
}

// ---------------------------------------------------------------- sign-in

fn session_cases(g: &mut Golden) {
    let src = "emby/tests.rs: emby_like() fake server (public_info, auth_result, user)";
    let server = FakeEmby::start(emby_like(204));
    g.fake_server(&server.url);
    let fx = fixture();
    let s = &fx.service;

    let probed = run(s.probe(&server.url)).unwrap();
    g.command("models", "emby_probe", None, "ServerSummary", src, &probed);

    // The same call as the Tauri command, trimmed name as the UI sends it.
    let user = run(s.sign_in(&server.url, "testeur", PASSWORD)).unwrap();
    g.command("models", "emby_sign_in", None, "SignedInUser", src, &user);

    let info = run(s.server_info()).unwrap();
    g.command(
        "models",
        "emby_server_info",
        None,
        "ServerSummary",
        src,
        &info,
    );

    let restored = run(s.restore()).unwrap();
    g.command(
        "models",
        "emby_restore",
        Some("authenticated"),
        "SessionStatus",
        src,
        &restored,
    );

    g.command(
        "models",
        "emby_last_server",
        None,
        "Option<String>",
        src,
        &s.last_server_url(),
    );

    s.change_server().unwrap();
    g.command(
        "models",
        "emby_shelf",
        None,
        "Vec<ShelfServer>",
        src,
        &s.shelf(),
    );

    let empty = fixture();
    let signed_out = run(empty.service.restore()).unwrap();
    g.command(
        "models",
        "emby_restore",
        Some("signed_out"),
        "SessionStatus",
        "emby/tests.rs: fixture() with no saved session",
        &signed_out,
    );

    // Statuses that need an offline or a rejecting server; built by hand with the
    // same types and fixed texts (the real `Unreachable` text comes from the OS).
    let saved = SavedServer {
        url: FAKE_HOST.into(),
        id: SERVER_ID.into(),
        name: "ServeurTest".into(),
        version: "4.10.1.0".into(),
        encrypted: false,
        user_id: "4a1f0e2b9c8d4e7fa6b5c4d3e2f1a0b9".into(),
        user_name: "testeur".into(),
    };
    g.command(
        "models",
        "emby_restore",
        Some("authentication_required"),
        "SessionStatus",
        "hand-built: SessionStatus::AuthenticationRequired with EmbyError::SessionExpired",
        &SessionStatus::AuthenticationRequired {
            server: saved.clone(),
            error: EmbyError::SessionExpired,
        },
    );
    g.command(
        "models",
        "emby_restore",
        Some("server_unreachable"),
        "SessionStatus",
        "hand-built: SessionStatus::ServerUnreachable with EmbyError::Timeout",
        &SessionStatus::ServerUnreachable {
            server: saved,
            error: EmbyError::Timeout,
        },
    );
}

// ----------------------------------------------------------------- browse

fn browse_cases(g: &mut Golden) {
    let (server, fx, revoked) = signed_in_browser();
    g.fake_server(&server.url);
    let s = &fx.service;

    g.command(
        "models",
        "emby_libraries",
        None,
        "Vec<Library>",
        &format!("{BROWSE}; views()"),
        &run(s.libraries()).unwrap(),
    );
    g.command(
        "models",
        "emby_items",
        Some("movies_page"),
        "ItemPage",
        &format!("{BROWSE}; movies_page(), items(\"5\", 50, 50, default)"),
        &run(s.items("5", 50, 50, &ItemsOptions::default())).unwrap(),
    );
    g.command(
        "models",
        "emby_sort_names",
        None,
        "Vec<SortNameEntry>",
        &format!("{BROWSE}; sort_names_answer()"),
        &run(s.sort_names("5", &ItemsOptions::default())).unwrap(),
    );
    let drama = ItemsOptions {
        watch: WatchFilter::Played,
        favorites: true,
        genre: Some("Drame".into()),
        ..Default::default()
    };
    g.command(
        "models",
        "emby_random_item",
        Some("found"),
        "Option<MediaItem>",
        &format!("{BROWSE}; random_answer() with the genre Drame"),
        &run(s.random_item("5", &drama)).unwrap(),
    );
    g.command(
        "models",
        "emby_random_item",
        Some("none"),
        "Option<MediaItem>",
        &format!("{BROWSE}; random_answer() without a match"),
        &run(s.random_item("5", &ItemsOptions::default())).unwrap(),
    );
    g.command(
        "models",
        "emby_production_years",
        None,
        "Vec<i32>",
        &format!("{BROWSE}; years_answer()"),
        &run(s.production_years("5")).unwrap(),
    );
    g.command(
        "models",
        "emby_genres",
        None,
        "Vec<String>",
        &format!("{BROWSE}; genres()"),
        &run(s.genres("5")).unwrap(),
    );
    g.command(
        "models",
        "emby_continue_watching",
        None,
        "Vec<WatchItem>",
        &format!("{BROWSE}; resume_items()"),
        &run(s.continue_watching()).unwrap(),
    );
    g.command(
        "models",
        "emby_next_up",
        None,
        "Vec<WatchItem>",
        &format!("{BROWSE}; next_up()"),
        &run(s.next_up()).unwrap(),
    );
    g.command(
        "models",
        "emby_hero",
        None,
        "Vec<HeroItem>",
        &format!("{BROWSE}; resume_items() then hero_latest()"),
        &run(s.hero()).unwrap(),
    );
    g.command(
        "models",
        "emby_favorites",
        None,
        "Vec<MediaItem>",
        &format!("{BROWSE}; favorites()"),
        &run(s.favorites()).unwrap(),
    );
    g.command(
        "models",
        "emby_latest",
        None,
        "Vec<MediaItem>",
        &format!("{BROWSE}; latest_movies()"),
        &run(s.latest("5")).unwrap(),
    );
    g.command(
        "models",
        "emby_search",
        None,
        "SearchResults",
        &format!("{BROWSE}; search_answer(), query \"  Heures\\u{{7}}  chrono\\n\""),
        &run(s.search("  Heures\u{7}  chrono\n")).unwrap(),
    );

    for (variant, on) in [("set", true), ("cleared", false)] {
        g.command(
            "models",
            "emby_set_favorite",
            Some(variant),
            "bool",
            &format!("{BROWSE}; FavoriteItems/30207"),
            &run(s.set_favorite("30207", on)).unwrap(),
        );
    }
    for (variant, on) in [("played", true), ("unplayed", false)] {
        g.command(
            "models",
            "emby_set_played",
            Some(variant),
            "PlaybackStopped",
            &format!("{BROWSE}; PlayedItems/30207"),
            &run(s.set_played("30207", on)).unwrap(),
        );
    }
    g.command(
        "models",
        "emby_hide_from_resume",
        None,
        "PlaybackStopped",
        &format!("{BROWSE}; HideFromResume on 30207"),
        &run(s.hide_from_resume("30207")).unwrap(),
    );
    g.command(
        "models",
        "emby_series_next_episode",
        Some("some"),
        "Option<WatchItem>",
        &format!("{BROWSE}; first_unplayed() of 80473"),
        &run(s.series_next_episode("80473")).unwrap(),
    );
    g.command(
        "models",
        "emby_series_next_episode",
        Some("none"),
        "Option<WatchItem>",
        &format!("{BROWSE}; no unwatched episode for 80500"),
        &run(s.series_next_episode("80500")).unwrap(),
    );

    g.command(
        "models",
        "emby_playlists",
        None,
        "Vec<Playlist>",
        &format!("{BROWSE}; playlists()"),
        &run(s.playlists()).unwrap(),
    );
    g.command(
        "models",
        "emby_home_playlists",
        None,
        "Vec<PlaylistCard>",
        &format!("{BROWSE}; home_playlists()"),
        &run(s.home_playlists()).unwrap(),
    );
    g.command(
        "models",
        "emby_playlist_items",
        None,
        "Vec<WatchItem>",
        &format!("{BROWSE}; playlist_items()"),
        &run(s.playlist_items("60001")).unwrap(),
    );
    g.command(
        "models",
        "emby_add_to_playlist",
        None,
        "u32",
        &format!("{BROWSE}; Playlists/60001/Items POST"),
        &run(s.add_to_playlist("60001", "30207")).unwrap(),
    );
    g.command(
        "models",
        "emby_create_playlist",
        None,
        "PlaylistCreated",
        &format!("{BROWSE}; Playlists POST, name \"  Soirée ciné \""),
        &run(s.create_playlist("  Soirée ciné ", "30207")).unwrap(),
    );

    for (variant, id, fixture) in [
        ("movie", "30207", "movie_detail()"),
        ("series", "80473", "series_detail()"),
        (
            "episode",
            "80475",
            "episode_detail() with adjacent_episodes()",
        ),
        ("bare_episode", "80476", "bare_episode_detail()"),
        ("two_versions", "30500", "two_versions_detail()"),
    ] {
        g.command(
            "models",
            "emby_item_detail",
            Some(variant),
            "ItemDetail",
            &format!("{BROWSE}; {fixture}"),
            &run(s.item_detail(id)).unwrap(),
        );
    }
    g.command(
        "models",
        "emby_seasons",
        None,
        "Vec<Season>",
        &format!("{BROWSE}; seasons()"),
        &run(s.seasons("80473")).unwrap(),
    );
    g.command(
        "models",
        "emby_episodes",
        None,
        "EpisodePage",
        &format!("{BROWSE}; episodes_page(), episodes(\"80473\", \"80474\", 0, 500)"),
        &run(s.episodes("80473", "80474", 0, 500)).unwrap(),
    );
    for (variant, id) in [("with_resume", "30207"), ("episode", "80475")] {
        g.command(
            "models",
            "emby_playback_state",
            Some(variant),
            "PlaybackStopped",
            &format!("{BROWSE}; item {id}"),
            &run(s.playback_state(id)).unwrap(),
        );
    }

    // Chapters, local trailer, titles of a person, a collection, similar titles.
    g.command(
        "chapters",
        "emby_chapters",
        Some("film"),
        "Vec<Chapter>",
        &format!("{BROWSE}; movie_detail() chapters"),
        &run(s.chapters("30207")).unwrap(),
    );
    g.command(
        "chapters",
        "emby_chapters",
        Some("none"),
        "Vec<Chapter>",
        &format!("{BROWSE}; series_detail() has no chapters"),
        &run(s.chapters("80473")).unwrap(),
    );
    g.command(
        "titles",
        "emby_local_trailer",
        None,
        "Option<String>",
        &format!("{BROWSE}; LocalTrailers of 30207"),
        &run(s.local_trailer("30207")).unwrap(),
    );
    for (variant, source) in [
        ("person", TitlesSource::Person { id: "30209".into() }),
        (
            "collection",
            TitlesSource::Collection {
                id: "120034".into(),
            },
        ),
        ("similar", TitlesSource::Similar { id: "30207".into() }),
    ] {
        g.command(
            "titles",
            "emby_titles",
            Some(variant),
            "Vec<MediaItem>",
            &format!("{BROWSE}; person_titles()"),
            &run(s.titles(&source)).unwrap(),
        );
    }

    // Quality setting of the session (a local address here).
    g.command(
        "quality",
        "emby_playback_quality",
        Some("default"),
        "PlaybackQuality",
        &format!("{BROWSE}; no cap saved"),
        &s.playback_quality().unwrap(),
    );
    let caps = QualityCaps {
        local: Some(40_000_000),
        remote: Some(4_000_000),
    };
    g.command(
        "quality",
        "emby_set_playback_quality",
        None,
        "PlaybackQuality",
        &format!("{BROWSE}; caps local 40 Mbit/s, remote 4 Mbit/s"),
        &s.set_playback_quality(caps).unwrap(),
    );

    // Errors of the same session, last: the token is then revoked.
    let service_errors = [
        (
            "not_found",
            "item_detail(\"999\"): 404 text of Emby",
            run(s.item_detail("999")).unwrap_err(),
        ),
        (
            "unknown_library",
            "items(\"999\"): refused before any request",
            run(s.items("999", 0, 50, &ItemsOptions::default())).unwrap_err(),
        ),
        (
            "invalid_id",
            "series_next_episode(\"../80473\"): refused before any request",
            run(s.series_next_episode("../80473")).unwrap_err(),
        ),
        (
            "invalid_playlist_name",
            "create_playlist(\"   \", \"30207\")",
            run(s.create_playlist("   ", "30207")).unwrap_err(),
        ),
        (
            "quality_not_offered",
            "set_playback_quality with a cap off the list",
            s.set_playback_quality(QualityCaps {
                local: Some(1),
                remote: None,
            })
            .unwrap_err(),
        ),
    ];
    for (case, source, error) in &service_errors {
        g.put(
            "errors",
            &format!("service.{case}"),
            "command error (EmbyError)",
            "EmbyError",
            &format!("{BROWSE}; {source}"),
            error,
        );
    }
    revoked.store(true, Ordering::SeqCst);
    g.put(
        "errors",
        "service.session_expired",
        "command error (EmbyError)",
        "EmbyError",
        &format!("{BROWSE}; token revoked then libraries()"),
        &run(s.libraries()).unwrap_err(),
    );
}

// --------------------------------------------------------------- playback

/// The `PlaybackStart` of `emby_play` (commands.rs), from the plan the service
/// prepared: the same field expressions, hand-mirrored (the command needs AppState).
fn playback_start(item_id: &str, resume: bool, plan: &PlaybackPlan) -> PlaybackStart {
    let limit = plan.bitrate_limit;
    PlaybackStart {
        media_source_id: plan.media_source_id.clone(),
        item_id: item_id.to_owned(),
        resume,
        method: plan.method,
        container: plan.container.clone(),
        transcode_reasons: plan.transcode_reasons.clone(),
        title: plan.title.clone(),
        bitrate_cap: limit.and_then(|l| l.cap),
        local_address: limit.is_none_or(|l| l.local_address),
    }
}

/// The request `emby_play` hands to the engine, from the plan and its headers.
fn engine_request(plan: &PlaybackPlan, headers: &[(String, String)]) -> PlaybackRequest {
    PlaybackRequest {
        source: plan.url.clone(),
        http_headers: headers.to_vec(),
        start_seconds: (plan.start_ticks > 0).then(|| plan.start_ticks as f64 / 10_000_000.0),
        subtitles: plan.subtitles.clone(),
        tracks: plan.tracks,
        subtitle_style: SubtitleStyle::default(),
    }
}

/// Projection of a plan, which is not an IPC type: what the player and the reports
/// read from it (the token is not in the URL, it travels in `headers`).
fn plan_json(plan: &PlaybackPlan, headers: &[(String, String)]) -> Value {
    json!({
        "method": plan.method,
        "url": plan.url,
        "headers": headers,
        "mediaSourceId": plan.media_source_id,
        "playSessionId": plan.play_session_id,
        "container": plan.container,
        "runTimeTicks": plan.run_time_ticks,
        "startTicks": plan.start_ticks,
        "transcodeReasons": plan.transcode_reasons,
        "subtitles": plan.subtitles,
        "title": plan.title,
        "bitrateLimit": plan.bitrate_limit.map(|l| json!({
            "bitrate": l.bitrate,
            "cap": l.cap,
            "localAddress": l.local_address,
        })),
        "defaultAudioStreamIndex": plan.default_audio_stream_index,
        "defaultSubtitleStreamIndex": plan.default_subtitle_stream_index,
        "tracks": plan.tracks,
        "streamCount": plan.streams.len(),
    })
}

/// (case, item, resume, version, force transcode, preferences, fixture)
type PlayCase<'a> = (
    &'a str,
    &'a str,
    bool,
    Option<&'a str>,
    bool,
    &'a TrackPreferences,
    &'a str,
);

fn playback_cases(g: &mut Golden) {
    let (server, fx, _) = signed_in_browser();
    g.fake_server(&server.url);
    let s = &fx.service;
    let info_body = |server: &FakeEmby| -> Value {
        let req = server
            .requests()
            .into_iter()
            .rfind(|r| r.path.contains("/PlaybackInfo"))
            .unwrap();
        serde_json::from_str(&req.body).unwrap()
    };
    let english = TrackPreferences {
        audio_language: Some("eng".into()),
        subtitle_language: Some("eng".into()),
        subtitle_mode: SubtitleMode::Always,
    };
    let none = TrackPreferences::default();
    let off = TrackPreferences {
        subtitle_mode: SubtitleMode::Off,
        ..TrackPreferences::default()
    };

    let cases: [PlayCase; 8] = [
        (
            "direct_play",
            "30207",
            false,
            None,
            false,
            &none,
            "playback_direct(), movie_detail()",
        ),
        (
            "direct_play_resume",
            "30207",
            true,
            None,
            false,
            &none,
            "playback_direct(), movie_detail() resume point",
        ),
        (
            "direct_play_subtitles_off",
            "30207",
            false,
            None,
            false,
            &off,
            "playback_direct(), subtitles off",
        ),
        (
            "episode_direct_play",
            "80475",
            false,
            None,
            false,
            &none,
            "playback_direct(), episode_detail() title",
        ),
        (
            "fallback_transcode",
            "30207",
            false,
            None,
            true,
            &none,
            "playback_transcode() after a refused direct play",
        ),
        (
            "transcode_english",
            "40001",
            false,
            None,
            false,
            &english,
            "playback_transcode_audio(2), no item (no title)",
        ),
        (
            "two_versions_second",
            "30500",
            false,
            Some("30501"),
            false,
            &none,
            "playback_two_versions(), two_versions_detail()",
        ),
        (
            "transcode_no_title",
            "40000",
            false,
            None,
            false,
            &none,
            "playback_transcode(), no item (no title)",
        ),
    ];
    for (case, item, resume, version, force, prefs, fixture) in cases {
        let (plan, headers) = run(s.prepare_source(item, version, resume, force, prefs)).unwrap();
        let src = format!("{BROWSE}; {fixture}");
        g.command(
            "playback",
            "emby_play",
            Some(case),
            "PlaybackStart",
            &src,
            &playback_start(item, resume, &plan),
        );
        g.note("hand mirror of the value emby_play returns (commands.rs); the command itself needs AppState");
        g.put(
            "playback",
            &format!("engine_request.{case}"),
            "Rust to player engine (not IPC)",
            "PlaybackRequest",
            &src,
            &engine_request(&plan, &headers),
        );
        g.note("what emby_play hands to the engine; headers hold the token placeholder");
        g.put(
            "playback",
            &format!("plan.{case}"),
            "internal (not IPC)",
            "PlaybackPlan (json! projection)",
            &src,
            &plan_json(&plan, &headers),
        );
        g.note("projection of PlaybackPlan built with json!, alphabetical keys");
        if matches!(case, "direct_play" | "transcode_english") {
            g.put(
                "playback",
                &format!("playbackinfo_request.{case}"),
                "Rust to Emby (not IPC)",
                "PlaybackInfoRequest (body of POST /Items/{id}/PlaybackInfo)",
                &src,
                &info_body(&server),
            );
            g.note("request body read back from the fake server, alphabetical keys");
        }
    }

    // Plans refused by the server's answer.
    let refused = [
        (
            "unknown_item",
            "prepare_playback(\"99999\"): PlaybackInfo 404",
            run(s.prepare_playback("99999", false)).unwrap_err(),
        ),
        (
            "fallback_still_offers_the_file",
            "prepare_transcode(\"80475\"): the server ignores the refusal",
            run(s.prepare_transcode("80475", false)).unwrap_err(),
        ),
        (
            "resume_without_item",
            "prepare_playback(\"40000\", true): the item is required for a resume",
            run(s.prepare_playback("40000", true)).unwrap_err(),
        ),
    ];
    for (case, source, error) in &refused {
        g.put(
            "errors",
            &format!("playback.{case}"),
            "command error (EmbyError)",
            "EmbyError",
            &format!("{BROWSE}; {source}"),
            error,
        );
    }
}

// ----------------------------------------------------------------- errors

fn error_cases(g: &mut Golden) {
    let all = [
        EmbyError::InvalidUrl("adresse vide".into()),
        EmbyError::InsecureRemoteUrl,
        EmbyError::Unreachable("connexion refusée".into()),
        EmbyError::Timeout,
        EmbyError::InvalidCredentials,
        EmbyError::Forbidden,
        EmbyError::Server(503),
        EmbyError::InvalidResponse("JSON illisible".into()),
        EmbyError::NotFound,
        EmbyError::NotSignedIn,
        EmbyError::SessionExpired,
        EmbyError::AccountUnavailable,
        EmbyError::ServerMismatch,
        EmbyError::Cancelled,
        EmbyError::CredentialStore("accès refusé".into()),
        EmbyError::PlaybackRefused("lecture non autorisée pour ce compte".into()),
        EmbyError::NoCompatibleStream,
        EmbyError::Player("source invalide".into()),
        EmbyError::Settings("débit de lecture non proposé".into()),
        EmbyError::Sync("TV introuvable".into()),
    ];
    g.put(
        "errors",
        "emby_error.all_kinds",
        "command error (EmbyError, the Err of almost every emby_* and sync_* command)",
        "Vec<EmbyError>",
        "hand-built: every variant of EmbyError with a fixed text",
        &all,
    );
    g.note("EmbyError serializes as {kind, message}; the messages are French and the argument texts here are fixed examples");

    let player = [
        PlayerError::LibraryUnavailable("libmpv-2.dll".into()),
        PlayerError::UnsupportedApi("1.0".into()),
        PlayerError::InvalidSource("schéma non pris en charge".into()),
        PlayerError::CertificateStore("accès refusé".into()),
        PlayerError::Mpv {
            context: "loadfile".into(),
            message: "unsupported format".into(),
        },
        PlayerError::NotOpen,
    ];
    g.put(
        "errors",
        "player_error.all_kinds",
        "command error (PlayerError, the Err of the player_* commands)",
        "Vec<PlayerError>",
        "hand-built: every variant of PlayerError",
        &player,
    );
    g.note("PlayerError serializes as a bare string, not as {kind, message}");

    g.put(
        "errors",
        "other_shapes",
        "command error (other Err types)",
        "() and String",
        "commands.rs: `Result<_, ()>` (app_info, emby_last_server, sync_status) and `Result<bool, String>` (window_set_fullscreen)",
        &json!({
            "unit": serde_json::to_value(()).unwrap(),
            "string": serde_json::to_value("message d'erreur").unwrap(),
        }),
    );
    g.note("an Err(()) reaches the frontend as null, an Err(String) as a bare string; built with json!");

    // Real errors of the sign-in and of the address check.
    let server = FakeEmby::start(emby_like(204));
    g.fake_server(&server.url);
    let fx = fixture();
    g.put(
        "errors",
        "service.invalid_credentials",
        "command error (EmbyError)",
        "EmbyError",
        "emby/tests.rs: emby_like() fake server, sign_in with a wrong password",
        &run(fx.service.sign_in(&server.url, "testeur", "nope")).unwrap_err(),
    );
    g.put(
        "errors",
        "service.not_signed_in",
        "command error (EmbyError)",
        "EmbyError",
        "emby/tests.rs: fixture() with no session, libraries()",
        &run(fx.service.libraries()).unwrap_err(),
    );
    let down = FakeEmby::start(Box::new(|_| (503, String::new())));
    g.put(
        "errors",
        "service.server_error",
        "command error (EmbyError)",
        "EmbyError",
        "emby/tests.rs: FakeEmby answering 503, probe()",
        &run(fx.service.probe(&down.url)).unwrap_err(),
    );
    let foreign = FakeEmby::start(Box::new(|_| (200, "<html>Emby</html>".into())));
    let invalid = run(fx.service.probe(&foreign.url)).unwrap_err();
    g.put(
        "errors",
        "service.invalid_response",
        "command error (EmbyError)",
        "EmbyError",
        "emby/tests.rs: FakeEmby answering HTML, probe()",
        &invalid,
    );
    g.note("the message holds a serde error text: compare `kind` only");
    for (case, input) in [
        ("invalid_url", ""),
        ("insecure_remote_url", "http://media.example.com"),
    ] {
        g.put(
            "errors",
            &format!("service.{case}"),
            "command error (EmbyError)",
            "EmbyError",
            &format!("ServerUrl::parse({input:?}), what emby_probe and emby_sign_in report"),
            &ServerUrl::parse(input).unwrap_err(),
        );
    }
}

// --------------------------------------------------------------- server_url

fn server_url_cases(g: &mut Golden) {
    let inputs = [
        "http://192.168.1.10:8096",
        "192.168.1.10:8096",
        " http://192.168.1.10:8096/ ",
        "http://emby.local:8096/emby",
        "http://192.168.1.10:8096/web/index.html",
        "http://192.168.1.10:8096/proxy/web",
        "https://media.example.com/proxy/",
        "https://media.example.com/emby/",
        "https://8.8.8.8",
        "http://127.0.0.1:8096",
        "http://10.1.2.3",
        "http://172.16.0.1",
        "http://169.254.1.1",
        "http://[::1]:8096",
        "http://[fd12::1]",
        "http://localhost:8096",
        "http://emby.home.arpa",
        "http://emby.lan",
        "http://8.8.8.8:8096",
        "http://media.example.com",
        "http://172.32.0.1",
        "",
        "   ",
        "ftp://192.168.1.10",
        "file:///C:/x",
        "http://user:pw@192.168.1.10",
        "http://192.168.1.10/?api_key=x",
        "http://192.168.1.10/#x",
        "http://",
    ];
    let rows: Vec<Value> = inputs
        .iter()
        .map(|input| match ServerUrl::parse(input) {
            Ok(url) => json!({
                "input": input,
                "url": url.as_str(),
                "https": url.is_https(),
                "local": url.is_local(),
                "api": url.api("/System/Info/Public"),
            }),
            Err(error) => json!({"input": input, "error": err_json(&error)}),
        })
        .collect();
    g.put(
        "server_url",
        "normalize",
        "function ServerUrl::parse (emby_probe, emby_sign_in)",
        "table of ServerUrl or EmbyError",
        "emby/server_url.rs tests: normalizes_common_inputs, plain_http_only_on_local_network, rejects_unsafe_or_malformed_urls, plus a few more",
        &rows,
    );
    g.note("table built with json!; InvalidUrl messages for a malformed address come from the url crate: compare `kind` only");
}

// ------------------------------------------------------------------ images

fn image_cases(g: &mut Golden) {
    let widths: [(&str, &[u32]); 6] = [
        ("poster", images::POSTER_WIDTHS),
        ("library", images::LIBRARY_WIDTHS),
        ("backdrop", images::BACKDROP_WIDTHS),
        ("logo", images::LOGO_WIDTHS),
        ("thumb", images::THUMB_WIDTHS),
        ("photo", images::PHOTO_WIDTHS),
    ];
    let types = [
        ImageType::Primary,
        ImageType::Backdrop,
        ImageType::Thumb,
        ImageType::Logo,
    ];
    let mut rows: Vec<Value> = Vec::new();
    for (name, w) in widths {
        for kind in types {
            rows.push(json!({
                "widths": name,
                "imageType": kind.as_str(),
                "serverId": "srv1",
                "itemId": "74668",
                "tag": "ab12_6384",
                "result": images::image_ref("srv1", "74668", kind, Some("ab12_6384"), w),
            }));
        }
    }
    for (case, server, item, tag, w) in [
        ("no_tag", "srv1", "1", None, images::POSTER_WIDTHS),
        (
            "tag_with_quote",
            "srv1",
            "1",
            Some("a\"b"),
            images::POSTER_WIDTHS,
        ),
        (
            "unsafe_item_id",
            "srv1",
            "../x",
            Some("t"),
            images::POSTER_WIDTHS,
        ),
        (
            "unsafe_server_id",
            "a/b",
            "1",
            Some("t"),
            images::POSTER_WIDTHS,
        ),
        ("no_widths", "srv1", "1", Some("t"), &[][..]),
    ] {
        rows.push(json!({
            "case": case,
            "imageType": "Primary",
            "serverId": server,
            "itemId": item,
            "tag": tag,
            "result": images::image_ref(server, item, ImageType::Primary, tag, w),
        }));
    }
    g.put(
        "images",
        "image_ref",
        "field of Library, MediaItem, ItemDetail, Episode... (ImageRef)",
        "table of Option<ImageRef>",
        "emby/images.rs: image_ref() with POSTER/LIBRARY/BACKDROP/LOGO/THUMB/PHOTO widths; the origin is the fixed http://emby-img.localhost, no server and no token",
        &rows,
    );
    g.note("table built with json!");

    let requests = [
        ("/srv1/74668/Primary", Some("tag=ab12_6384&maxWidth=360")),
        ("/srv1/74668/Backdrop", Some("maxWidth=1920&tag=t-1")),
        ("/srv1/74668/Thumb", Some("tag=x&maxWidth=32")),
        ("/srv1/74668/Logo", Some("tag=x&maxWidth=640")),
        ("/srv1/74668/Primary", Some("tag=x")),
        ("/srv1/74668/Primary", Some("maxWidth=360")),
        ("/srv1/74668/Primary", Some("tag=x&maxWidth=8000")),
        ("/srv1/74668/Primary", Some("tag=x&maxWidth=31")),
        ("/srv1/74668/Primary", Some("tag=x&maxWidth=360&api_key=y")),
        ("/srv1/74668/Chapter", Some("tag=x&maxWidth=360")),
        ("/srv1/../Users/Primary", Some("tag=x&maxWidth=360")),
        ("/srv1/74668/Primary/0", Some("tag=x&maxWidth=360")),
        ("/srv1/74668/Primary", Some("tag=a%2Fb&maxWidth=360")),
        ("/srv1/74668/Primary", None),
        ("/74668/Primary", Some("tag=x&maxWidth=360")),
    ];
    let rows: Vec<Value> = requests
        .iter()
        .map(|(path, query)| {
            let parsed = ImageRequest::parse(path, *query).map(|r| {
                json!({
                    "serverId": r.server_id,
                    "itemId": r.item_id,
                    "imageType": r.image_type.as_str(),
                    "tag": r.tag,
                    "maxWidth": r.max_width,
                    "apiPath": r.api_path(),
                })
            });
            json!({"path": path, "query": query, "parsed": parsed})
        })
        .collect();
    g.put(
        "images",
        "request_parse",
        "function ImageRequest::parse (emby-img protocol handler)",
        "table of Option<ImageRequest>",
        "emby/images.rs test parses_only_well_formed_requests, plus accepted and edge cases",
        &rows,
    );
    g.note("table built with json!; `parsed` null means the request is refused");
}

// ------------------------------------------------------------------ titles

fn titles_cases(g: &mut Golden) {
    let sources = [
        r#"{"kind":"person","id":"30209"}"#,
        r#"{"kind":"person","id":"../Users"}"#,
        r#"{"kind":"person","id":"12 34"}"#,
        r#"{"kind":"genre","name":" Science-Fiction ","itemType":"Movie"}"#,
        r#"{"kind":"genre","name":"Drame|Comédie","itemType":"Movie"}"#,
        r#"{"kind":"genre","name":"Drame","itemType":"Episode"}"#,
        r#"{"kind":"studio","name":"HBO","itemType":"Series"}"#,
        r#"{"kind":"collection","id":"120034"}"#,
        r#"{"kind":"similar","id":"30207"}"#,
    ];
    let rows: Vec<Value> = sources
        .iter()
        .map(|input| {
            let source: TitlesSource = serde_json::from_str(input).unwrap();
            match titles::titles_params(&source) {
                Ok(params) => json!({"source": serde_json::from_str::<Value>(input).unwrap(), "params": params}),
                Err(error) => json!({"source": serde_json::from_str::<Value>(input).unwrap(), "error": err_json(&error)}),
            }
        })
        .collect();
    g.put(
        "titles",
        "params",
        "function titles_params (emby_titles)",
        "table of query parameters or EmbyError",
        "emby/titles.rs tests: person_query_matches_the_tv, genre_and_studio_queries_filter_by_name_and_type, unusable_names_and_types_are_refused, collection_titles_in_release_order",
        &rows,
    );
    g.note("table built with json!; `source` is the JSON the UI sends, `params` the ordered (name, value) pairs");

    let similar: Vec<Value> = [("a", "u1"), ("30207", "4a1f"), ("../x", "u1"), ("", "u1")]
        .iter()
        .map(|(id, user)| json!({"id": id, "userId": user, "params": titles::similar_params(id, user)}))
        .collect();
    g.put(
        "titles",
        "similar_params",
        "function similar_params (emby_titles, kind similar)",
        "table of Option<query parameters>",
        "emby/titles.rs test similar_leaves_the_title_out",
        &similar,
    );
    g.note("table built with json!; null params means the id is refused");

    let raw = json!([
        {"Name": "Dune - Saga", "Id": 120034},
        {"Name": "Dune - Saga", "Id": "120034"},
        {"Name": "  ", "Id": "5"},
        {"Name": "Sans id"},
        {"Name": "Mauvais", "Id": "../x"},
        {"Name": "B", "Id": "2"},
        {"Name": "C", "Id": "3"},
        {"Name": "D", "Id": "4"}
    ]);
    let refs = [
        ("list", titles::collection_refs(Some(&raw))),
        ("none", titles::collection_refs(None)),
        (
            "not_a_list",
            titles::collection_refs(Some(&json!({"Id": 1}))),
        ),
    ];
    let rows: Vec<Value> = refs
        .iter()
        .map(|(case, found)| json!({"case": case, "collections": found}))
        .collect();
    g.put(
        "titles",
        "collection_refs",
        "field `collections` of ItemDetail",
        "table of Vec<CollectionRef>",
        "emby/titles.rs test collections_parse_like_the_tv",
        &json!({"input": raw, "rows": rows}),
    );
    g.note("table built with json!");

    // Order of a list of titles, by source.
    let item = |id: &str,
                kind: &str,
                name: &str,
                year: Option<i32>,
                premiere: Option<&str>|
     -> BaseItemDto {
        serde_json::from_value(json!({
            "Id": id, "Type": kind, "Name": name, "ProductionYear": year, "PremiereDate": premiere,
        }))
        .unwrap()
    };
    let person = vec![
        item("1", "Movie", "Zorro", Some(2020), None),
        item("2", "Movie", "alpha", Some(2020), None),
        item("3", "Series", "Sans année", None, None),
        item("4", "Episode", "Pilote", Some(2024), None),
        item("5", "Movie", "Récent", Some(2023), None),
        item("1", "Movie", "Zorro", Some(2020), None),
    ];
    let collection = vec![
        item("d", "Movie", "Inédit", None, None),
        item(
            "a",
            "Movie",
            "Dune 2",
            Some(2024),
            Some("2024-02-27T23:00:00.0000000Z"),
        ),
        item(
            "b",
            "Movie",
            "Dune",
            Some(2021),
            Some("2021-09-14T22:00:00.0000000Z"),
        ),
        item("c", "Movie", "Dune 1984", Some(1984), None),
    ];
    let mut similar_list = vec![
        item("a", "Movie", "Soi", None, None),
        item("b", "Series", "B", None, None),
    ];
    similar_list.extend((0..20).map(|n| item(&format!("x{n}"), "Movie", "X", None, None)));
    let ids = |source: &TitlesSource, list: &[BaseItemDto]| -> Vec<String> {
        titles::order_titles(source, list.to_vec())
            .into_iter()
            .map(|i| i.id)
            .collect()
    };
    g.put(
        "titles",
        "order",
        "function order_titles (emby_titles)",
        "table of ordered ids",
        "emby/titles.rs tests: person_titles_newest_first_then_a_to_z, collection_titles_in_release_order, similar_leaves_the_title_out",
        &json!([
            {"source": "person", "inputIds": person.iter().map(|i| i.id.clone()).collect::<Vec<_>>(),
             "orderedIds": ids(&TitlesSource::Person { id: "1".into() }, &person)},
            {"source": "collection", "inputIds": collection.iter().map(|i| i.id.clone()).collect::<Vec<_>>(),
             "orderedIds": ids(&TitlesSource::Collection { id: "9".into() }, &collection)},
            {"source": "similar (title a left out, 12 kept)", "inputIds": similar_list.iter().map(|i| i.id.clone()).collect::<Vec<_>>(),
             "orderedIds": ids(&TitlesSource::Similar { id: "a".into() }, &similar_list)},
        ]),
    );
    g.note("table built with json!; inputs are described by the unit-test fixtures (id, Type, Name, ProductionYear, PremiereDate)");

    let people: Vec<Value> = [
        json!({"Id": " 30209 "}),
        json!({"Id": 30209}),
        json!({"Id": null}),
        json!({"Id": "a/b"}),
        json!({"Id": true}),
        json!({}),
    ]
    .into_iter()
    .map(|input| {
        let person: PersonDto = serde_json::from_value(input.clone()).unwrap();
        json!({"person": input, "id": titles::person_id(&person)})
    })
    .collect();
    g.put(
        "titles",
        "person_id",
        "function person_id (cast of ItemDetail)",
        "table of Option<String>",
        "emby/titles.rs test person_id_reads_a_string_or_a_number",
        &people,
    );
    g.note("table built with json!");
}

// ----------------------------------------------------------------- quality

fn quality_cases(g: &mut Golden) {
    g.put(
        "quality",
        "bitrate_choices",
        "field `choices` of PlaybackQuality",
        "[u64; 7]",
        "emby/quality.rs: BITRATE_CHOICES",
        &BITRATE_CHOICES,
    );
    let caps: Vec<QualityCaps> = [
        r#"{}"#,
        r#"{"local":null,"remote":4000000}"#,
        r#"{"local":40000000,"remote":1500000}"#,
        r#"{"local":0}"#,
        r#"{"local":3500000}"#,
        r#"{"remote":400000000}"#,
    ]
    .iter()
    .map(|j| serde_json::from_str(j).unwrap())
    .collect();
    let rows: Vec<Value> = caps
        .iter()
        .map(|c| match c.validated() {
            Ok(ok) => json!({
                "caps": c,
                "valid": ok,
                "forLocalAddress": ok.for_address(true),
                "forRemoteAddress": ok.for_address(false),
            }),
            Err(error) => json!({"caps": c, "error": err_json(&error)}),
        })
        .collect();
    g.put(
        "quality",
        "caps_validation",
        "function QualityCaps::validated and for_address (emby_set_playback_quality)",
        "table of QualityCaps or EmbyError",
        "emby/quality.rs tests: quality_caps_accept_only_the_offered_choices, quality_caps_pick_the_cap_of_the_address",
        &rows,
    );
    g.note("table built with json!");
}

// ---------------------------------------------------------------- chapters

fn chapter_cases(g: &mut Golden) {
    let long = format!(
        r#"[{{"StartPositionTicks":0,"Name":"{}"}},{{"StartPositionTicks":600000000}}]"#,
        "x".repeat(500)
    );
    let cases: [(&str, &str, Option<i64>, &str); 6] = [
        (
            "real_film_answer",
            r#"[{"StartPositionTicks":0,"Name":"Opening Credits","MarkerType":"Chapter"},
                {"StartPositionTicks":2421170000,"Name":"Horn Island 1977","MarkerType":"Chapter"},
                {"StartPositionTicks":5489650000,"Name":"Someone to Take the Fall","MarkerType":"Chapter"},
                {"StartPositionTicks":7002000000,"Name":"Secret of Fly Fishing","MarkerType":"Chapter"}]"#,
            Some(61_154_010_000),
            "chapters_maps_the_real_answer_of_a_film",
        ),
        (
            "regular_only_sorted_named",
            r#"[{"StartPositionTicks":6000000000},
                {"StartPositionTicks":0,"Name":"  Début\n du film ","MarkerType":"Chapter"},
                {"StartPositionTicks":300000000,"MarkerType":"IntroStart"},
                {"StartPositionTicks":900000000,"MarkerType":"IntroEnd"},
                {"StartPositionTicks":3000000000,"Name":"","MarkerType":"Chapter"},
                {"StartPositionTicks":24000000000,"MarkerType":"CreditsStart"}]"#,
            None,
            "keeps_regular_chapters_only_sorted_and_named",
        ),
        (
            "drops_negative_duplicate_past_end",
            r#"[{"StartPositionTicks":-10,"Name":"Avant"},
                {"StartPositionTicks":0,"Name":"A"},
                {"StartPositionTicks":5000000,"Name":"Doublon"},
                {"StartPositionTicks":1200000000,"Name":"B"},
                {"StartPositionTicks":2000000000,"Name":"Après la fin"}]"#,
            Some(1_800_000_000),
            "drops_what_cannot_be_navigated",
        ),
        (
            "single_chapter",
            r#"[{"StartPositionTicks":0,"Name":"Seul"}]"#,
            None,
            "drops_what_cannot_be_navigated",
        ),
        (
            "markers_only",
            r#"[{"StartPositionTicks":0,"MarkerType":"IntroStart"},{"StartPositionTicks":1,"MarkerType":"IntroEnd"}]"#,
            None,
            "drops_what_cannot_be_navigated",
        ),
        (
            "name_of_500_chars_is_cut",
            &long,
            None,
            "long_names_are_cut",
        ),
    ];
    let rows: Vec<Value> = cases
        .iter()
        .map(|(case, input, runtime, test)| {
            let dtos: Vec<ChapterDto> = serde_json::from_str(input).unwrap();
            let name_len_check = chapters::chapters(&dtos, *runtime);
            json!({
                "case": case,
                "unitTest": test,
                "chapterDtos": if input.len() > 2000 { Value::Null } else { serde_json::from_str::<Value>(input).unwrap() },
                "runTimeTicks": runtime,
                "chapters": name_len_check,
            })
        })
        .collect();
    g.put(
        "chapters",
        "map",
        "function chapters::chapters (emby_chapters)",
        "table of Vec<Chapter>",
        "emby/chapters.rs unit tests (the `unitTest` of each row); inputs are the Emby `Chapters` JSON",
        &rows,
    );
    g.note("table built with json!; chapterDtos is null for the 500-character name (see the unit test)");
}

// ------------------------------------------------------------------ tracks

fn track_cases(g: &mut Golden) {
    // Streams of a real film (emby/tracks.rs film()) and of a transcode.
    let film: Vec<MediaStreamDto> = serde_json::from_str(
        r#"[{"Type":"Video","Index":0,"Codec":"hevc"},
        {"Type":"Audio","Index":1,"Codec":"ac3","Language":"fre","IsDefault":true},
        {"Type":"Audio","Index":2,"Codec":"ac3","Language":"eng"},
        {"Type":"Subtitle","Index":3,"Codec":"subrip","Language":"fre","IsForced":true},
        {"Type":"Subtitle","Index":4,"Codec":"subrip","Language":"fre","IsForced":true,"IsDefault":true},
        {"Type":"Subtitle","Index":5,"Codec":"subrip","Language":"fre"},
        {"Type":"Subtitle","Index":6,"Codec":"subrip","Language":"eng"},
        {"Type":"Subtitle","Index":7,"Codec":"PGSSUB","Language":"fra"},
        {"Type":"Subtitle","Index":8,"Codec":"PGSSUB","Language":"en"},
        {"Type":"Subtitle","Index":9,"Codec":"subrip","Language":"spa","IsExternal":true}]"#,
    )
    .unwrap();
    let transcode: Vec<MediaStreamDto> = serde_json::from_str(
        r#"[{"Type":"Audio","Index":1,"Language":"fre","IsDefault":true},
        {"Type":"Audio","Index":2,"Language":"eng"},
        {"Type":"Subtitle","Index":3,"Codec":"subrip","Language":"fre","DeliveryMethod":"External","DeliveryUrl":"/Videos/1/s/Subtitles/3/0/Stream.subrip"},
        {"Type":"Subtitle","Index":4,"Codec":"PGSSUB","Language":"eng","DeliveryMethod":"Encode"}]"#,
    )
    .unwrap();

    // (streams, method, server audio, server subtitle, preferences as the UI sends them)
    let film_cases: [(&str, Option<i32>, Option<i32>, &str); 12] = [
        ("DirectPlay", Some(1), Some(4), r#"{}"#),
        ("DirectPlay", Some(1), None, r#"{}"#),
        ("DirectPlay", Some(1), Some(4), r#"{"audioLanguage":"eng"}"#),
        ("DirectPlay", Some(1), Some(4), r#"{"audioLanguage":"jpn"}"#),
        ("DirectPlay", Some(1), Some(4), r#"{"subtitleMode":"off"}"#),
        (
            "DirectPlay",
            Some(1),
            Some(4),
            r#"{"subtitleMode":"forcedOnly"}"#,
        ),
        (
            "DirectPlay",
            Some(1),
            Some(4),
            r#"{"audioLanguage":"eng","subtitleMode":"forcedOnly"}"#,
        ),
        (
            "DirectPlay",
            Some(1),
            Some(4),
            r#"{"subtitleLanguage":"fre","subtitleMode":"always"}"#,
        ),
        (
            "DirectPlay",
            Some(1),
            Some(4),
            r#"{"audioLanguage":"eng","subtitleLanguage":"eng","subtitleMode":"always"}"#,
        ),
        (
            "DirectPlay",
            Some(1),
            Some(4),
            r#"{"subtitleLanguage":"spa","subtitleMode":"always"}"#,
        ),
        (
            "DirectPlay",
            Some(1),
            Some(4),
            r#"{"subtitleMode":"always"}"#,
        ),
        ("DirectStream", Some(1), Some(4), r#"{}"#),
    ];
    let mut rows: Vec<Value> = Vec::new();
    let mut run_case =
        |label: &str, streams: &[MediaStreamDto], method: &str, audio, subtitle, prefs: &str| {
            let method_enum = match method {
                "DirectPlay" => PlayMethod::DirectPlay,
                "DirectStream" => PlayMethod::DirectStream,
                _ => PlayMethod::Transcode,
            };
            let prefs_value: TrackPreferences = serde_json::from_str(prefs).unwrap();
            let choice = tracks::choose(streams, method_enum, audio, subtitle, &prefs_value);
            let subtitle_out = match choice.subtitle {
                tracks::SubtitleChoice::Player => json!("player"),
                tracks::SubtitleChoice::Off => json!("off"),
                tracks::SubtitleChoice::Stream(i) => json!({"stream": i}),
            };
            rows.push(json!({
                "streams": label,
                "method": method,
                "serverAudio": audio,
                "serverSubtitle": subtitle,
                "preferences": serde_json::from_str::<Value>(prefs).unwrap(),
                "audio": choice.audio,
                "subtitle": subtitle_out,
                "selection": choice.selection(method_enum),
            }));
        };
    for (method, audio, subtitle, prefs) in film_cases {
        run_case("film", &film, method, audio, subtitle, prefs);
    }
    run_case(
        "transcode",
        &transcode,
        "Transcode",
        Some(1),
        Some(3),
        r#"{"audioLanguage":"eng","subtitleLanguage":"eng","subtitleMode":"always"}"#,
    );
    run_case(
        "transcode",
        &transcode,
        "Transcode",
        Some(1),
        Some(4),
        r#"{}"#,
    );
    run_case(
        "transcode",
        &transcode,
        "Transcode",
        Some(1),
        Some(3),
        r#"{}"#,
    );
    g.put(
        "tracks",
        "choose",
        "function tracks::choose and Choice::selection (emby_play preferences)",
        "table of Choice and TrackSelection",
        "emby/tracks.rs unit tests (film() and the transcode streams of a_transcode_only_shows_subtitles_delivered_as_files)",
        &rows,
    );
    g.note("table built with json!; subtitle is \"player\", \"off\" or {\"stream\": index}; `streams` names the unit-test stream set (film = 10 streams with FR/EN audio and subtitles, transcode = 2 audio and 2 subtitles)");

    // Preferences as the UI sends them: accepted or refused.
    let prefs: Vec<Value> = [
        r#"{"audioLanguage":"fre","subtitleLanguage":"eng","subtitleMode":"always"}"#,
        r#"{"audioLanguage":"fra"}"#,
        r#"{"subtitleLanguage":"xx\n"}"#,
        r#"{"subtitleMode":"forcedOnly","audioLanguage":"eng"}"#,
        r#"{"subtitleMode":"smart"}"#,
    ]
    .iter()
    .map(|input| match serde_json::from_str::<TrackPreferences>(input) {
        Err(_) => json!({"input": serde_json::from_str::<Value>(input).unwrap(), "decode": "refused"}),
        Ok(p) => match p.validate() {
            Ok(()) => json!({"input": serde_json::from_str::<Value>(input).unwrap(), "decode": "ok", "validate": "ok"}),
            Err(e) => json!({"input": serde_json::from_str::<Value>(input).unwrap(), "decode": "ok", "validate": err_json(&e)}),
        },
    })
    .collect();
    g.put(
        "tracks",
        "preferences",
        "argument `preferences` of emby_play and emby_change_quality",
        "table of TrackPreferences decode and validate",
        "emby/tracks.rs test unknown_languages_are_refused",
        &prefs,
    );
    g.note("table built with json!; a value the decoder refuses is rejected by Tauri before the command runs");

    // Account preferences read from the user's Configuration.
    let object = json!({
        "AudioLanguagePreference": "fre, ENG,fre,not a code",
        "PlayDefaultAudioTrack": true,
        "SubtitleLanguagePreference": "fre",
        "SubtitleMode": "onlyforced",
        "ProfilePin": "1234"
    });
    let configs = [
        ("object", Some(object.clone())),
        ("array", Some(json!([object]))),
        ("missing", None),
        ("empty_array", Some(json!([]))),
        (
            "unknown_mode",
            Some(json!({"SubtitleMode": "Sometimes", "AudioLanguagePreference": ""})),
        ),
    ];
    let rows: Vec<Value> = configs
        .iter()
        .map(|(case, config)| {
            json!({
                "case": case,
                "configuration": config.as_ref().map(|c| {
                    // The pin of the unit test never reaches the golden files.
                    let mut c = c.clone();
                    if let Some(o) = c.as_object_mut() { o.remove("ProfilePin"); }
                    if let Some(o) = c.get_mut(0).and_then(|v| v.as_object_mut()) { o.remove("ProfilePin"); }
                    c
                }),
                "accountTracks": tracks::account_tracks(config.as_ref()),
            })
        })
        .collect();
    g.put(
        "tracks",
        "account_tracks",
        "command emby_account_tracks (AccountTracks)",
        "table of AccountTracks",
        "emby/tracks.rs tests: account_preferences_are_read_from_an_object_or_an_array, missing_or_unknown_account_preferences_ask_for_nothing",
        &rows,
    );
    g.note(
        "table built with json!; the call itself reads the user's Configuration from the server",
    );
}

// --------------------------------------------------------- pure playback

fn pure_playback_cases(g: &mut Golden) {
    g.put(
        "playback",
        "device_profile",
        "Rust to Emby (not IPC)",
        "DeviceProfile (sent in PlaybackInfo)",
        "emby/playback.rs: device_profile(MAX_STREAMING_BITRATE)",
        &playback::device_profile(MAX_STREAMING_BITRATE),
    );

    let server = ServerUrl::parse(PURE_SERVER).unwrap();
    let direct = r#"{"MediaSources":[{"Protocol":"File","Id":"mediasource_30207",
        "Type":"Default","Container":"mkv","SupportsTranscoding":true,
        "SupportsDirectStream":true,"SupportsDirectPlay":true,"RequiredHttpHeaders":{},
        "DirectStreamUrl":"/videos/30207/original.mkv?DeviceId=dev&MediaSourceId=mediasource_30207&PlaySessionId=abc&api_key=TOKEN_PLACEHOLDER",
        "AddApiKeyToDirectStreamUrl":false,"DefaultAudioStreamIndex":1}],"PlaySessionId":"abc"}"#;
    let direct_stream = direct.replace(
        r#""SupportsDirectPlay":true"#,
        r#""SupportsDirectPlay":false"#,
    );
    let transcode = r#"{"MediaSources":[{"Id":"mediasource_30207","Container":"mkv",
        "SupportsDirectPlay":false,"SupportsDirectStream":false,"SupportsTranscoding":true,
        "DirectStreamUrl":"/videos/30207/master.m3u8?PlaySessionId=p&api_key=TOKEN_PLACEHOLDER&TranscodeReasons=ContainerBitrateExceedsLimit",
        "TranscodingUrl":"/videos/30207/master.m3u8?PlaySessionId=p&api_key=TOKEN_PLACEHOLDER&TranscodeReasons=ContainerBitrateExceedsLimit",
        "TranscodingSubProtocol":"hls","TranscodingContainer":"ts","DefaultSubtitleStreamIndex":4,
        "MediaStreams":[
          {"Type":"Video","Index":0,"Codec":"h264"},
          {"Type":"Subtitle","Index":3,"Codec":"subrip","Language":"fre","Title":"Forcés",
           "DeliveryMethod":"External","DeliveryUrl":"/Videos/30207/mediasource_30207/Subtitles/3/0/Stream.subrip?api_key=TOKEN_PLACEHOLDER"},
          {"Type":"Subtitle","Index":4,"Codec":"subrip","Language":"eng",
           "DeliveryMethod":"External","DeliveryUrl":"/Videos/30207/mediasource_30207/Subtitles/4/0/Stream.subrip?api_key=TOKEN_PLACEHOLDER"},
          {"Type":"Subtitle","Index":5,"Codec":"PGSSUB","DeliveryMethod":"Encode"}]}],
        "PlaySessionId":"p"}"#;
    let reasons = transcode.replace(
        "ContainerBitrateExceedsLimit",
        "VideoCodecNotSupported,AudioCodecNotSupported",
    );
    let two_versions = r#"{"MediaSources":[
        {"Id":"mediasource_30500","Container":"mkv","SupportsDirectPlay":true,
         "DirectStreamUrl":"/videos/30500/original.mkv?MediaSourceId=mediasource_30500&api_key=TOKEN_PLACEHOLDER"},
        {"Id":"30501","Container":"mp4","SupportsDirectPlay":false,"SupportsTranscoding":true,
         "TranscodingUrl":"/videos/30500/master.m3u8?MediaSourceId=30501&api_key=TOKEN_PLACEHOLDER&TranscodeReasons=VideoBitrateNotSupported"}],
        "PlaySessionId":"p"}"#;
    let bad_subtitle = transcode.replace(
        "/Videos/30207/mediasource_30207/Subtitles/4/0/Stream.subrip",
        "http://evil.example/sub.srt",
    );
    let plan_of = |body: &str, version: Option<&str>| -> Value {
        let response: PlaybackInfoResponse = serde_json::from_str(body).unwrap();
        match playback::plan(&server, response, version) {
            Ok(plan) => json!({"plan": plan_json(&plan, &[])}),
            Err(e) => json!({"error": err_json(&e)}),
        }
    };
    let rows = [
        ("direct_play", direct.to_owned(), None, "DIRECT"),
        (
            "direct_stream",
            direct_stream,
            None,
            "direct_stream_when_only_that_is_allowed",
        ),
        ("transcode_hls", transcode.to_owned(), None, "TRANSCODE"),
        (
            "transcode_reasons",
            reasons,
            None,
            "transcode_reasons_in_french_and_no_subtitles_when_playing_the_file",
        ),
        (
            "version_first",
            two_versions.to_owned(),
            None,
            "TWO_VERSIONS",
        ),
        (
            "version_second",
            two_versions.to_owned(),
            Some("30501"),
            "TWO_VERSIONS",
        ),
        (
            "version_unknown",
            two_versions.to_owned(),
            Some("30502"),
            "TWO_VERSIONS",
        ),
        (
            "subtitle_off_the_server",
            bad_subtitle,
            None,
            "subtitle_urls_off_the_server_are_refused",
        ),
        (
            "error_not_allowed",
            r#"{"ErrorCode":"NotAllowed"}"#.to_owned(),
            None,
            "server_error_codes_and_empty_answers",
        ),
        (
            "error_rate_limit",
            r#"{"ErrorCode":"RateLimitExceeded"}"#.to_owned(),
            None,
            "server_error_codes_and_empty_answers",
        ),
        (
            "error_no_compatible_stream",
            r#"{"ErrorCode":"NoCompatibleStream"}"#.to_owned(),
            None,
            "server_error_codes_and_empty_answers",
        ),
        (
            "no_media_sources",
            r#"{"MediaSources":[]}"#.to_owned(),
            None,
            "server_error_codes_and_empty_answers",
        ),
        (
            "no_url_for_direct_play",
            r#"{"MediaSources":[{"Id":"m","SupportsDirectPlay":true}]}"#.to_owned(),
            None,
            "server_error_codes_and_empty_answers",
        ),
    ];
    for (case, body, version, test) in rows {
        let src = format!(
            "emby/playback.rs unit-test fixture `{test}`, plan() with the server {PURE_SERVER}"
        );
        g.put(
            "playback",
            &format!("plan_fn.{case}"),
            "function playback::plan (internal, not IPC)",
            "PlaybackPlan or EmbyError (json! projection)",
            &src,
            &plan_of(&body, version),
        );
        g.note("projection built with json!; the PlaybackInfo answer given to plan() is in the unit test (token replaced by TOKEN_PLACEHOLDER in its URLs)");
    }

    let urls = [
        "/videos/30207/original.mkv?DeviceId=dev&api_key=x",
        "/Videos/1/stream.mkv?Static=true&ApiKey=x&X-Emby-Token=y&AccessToken=z#f",
        "http://evil.example/videos/1/original.mkv",
        "//evil.example/videos/1/original.mkv",
        "/Users/1/Items",
        "/videos/../Users/1",
        "/videos/%2e%2e/Users/1",
        "videos/1/original.mkv",
        "/videos\\..\\Users",
    ];
    let rows: Vec<Value> = urls
        .iter()
        .map(|raw| match playback::stream_url(&server, raw) {
            Ok(url) => json!({"raw": raw, "url": url}),
            Err(e) => json!({"raw": raw, "error": err_json(&e)}),
        })
        .collect();
    g.put(
        "playback",
        "stream_url",
        "function playback::stream_url (internal, not IPC)",
        "table of String or EmbyError",
        "emby/playback.rs tests: stream_url_stays_under_videos_on_the_server, direct_play_without_token_in_url",
        &rows,
    );
    g.note("table built with json!");
}

// ------------------------------------------------------------------ events

fn event_cases(g: &mut Golden) {
    const PLAYER: &str = "player://event";
    let tracks = vec![
        Track {
            id: 1,
            kind: TrackKind::Video,
            title: None,
            lang: None,
            codec: Some("hevc".into()),
            selected: true,
            external: false,
            default: true,
        },
        Track {
            id: 1,
            kind: TrackKind::Audio,
            title: Some("Français 5.1".into()),
            lang: Some("fre".into()),
            codec: Some("ac3".into()),
            selected: true,
            external: false,
            default: true,
        },
        Track {
            id: 2,
            kind: TrackKind::Sub,
            title: Some("Forcés".into()),
            lang: Some("fre".into()),
            codec: Some("subrip".into()),
            selected: false,
            external: true,
            default: false,
        },
    ];
    let player_events: [(&str, PlayerEvent); 12] = [
        ("file_loaded", PlayerEvent::FileLoaded),
        ("time_pos", PlayerEvent::TimePos { seconds: 1831.25 }),
        ("duration", PlayerEvent::Duration { seconds: 6000.0 }),
        ("paused", PlayerEvent::Paused { paused: true }),
        ("buffering", PlayerEvent::Buffering { active: true }),
        (
            "tracks",
            PlayerEvent::Tracks {
                tracks: tracks.clone(),
            },
        ),
        ("video_reconfigured", PlayerEvent::VideoReconfigured),
        ("seeked", PlayerEvent::Seeked),
        (
            "end_file_eof",
            PlayerEvent::EndFile {
                reason: EndReason::Eof,
                error: None,
            },
        ),
        (
            "end_file_error",
            PlayerEvent::EndFile {
                reason: EndReason::Error,
                error: Some("loading failed".into()),
            },
        ),
        (
            "end_file_stopped",
            PlayerEvent::EndFile {
                reason: EndReason::Stopped,
                error: None,
            },
        ),
        ("shutdown", PlayerEvent::Shutdown),
    ];
    for (case, event) in &player_events {
        g.put(
            "events",
            &format!("player_event.{case}"),
            &format!("event {PLAYER}"),
            "PlayerEvent",
            "hand-built: representative value of the variant (player/mod.rs)",
            event,
        );
    }
    g.put(
        "events",
        "end_reason.all",
        &format!("event {PLAYER} (field `reason` of EndFile)"),
        "[EndReason; 5]",
        "hand-built: every EndReason",
        &[
            EndReason::Eof,
            EndReason::Stopped,
            EndReason::Quit,
            EndReason::Error,
            EndReason::Other,
        ],
    );

    g.put(
        "events",
        "library_changed",
        "event emby://library-changed",
        "() (no payload)",
        "emby/library_events.rs: app.emit(LIBRARY_CHANGED_EVENT, ())",
        &(),
    );
    g.note("the payload is null");
    let stopped = [
        (
            "with_resume",
            PlaybackStopped {
                item_id: "30207".into(),
                played: false,
                resume: Some(ResumePoint {
                    position_seconds: 1831,
                    fraction: Some(0.305),
                }),
            },
        ),
        (
            "watched",
            PlaybackStopped {
                item_id: "30207".into(),
                played: true,
                resume: None,
            },
        ),
    ];
    for (case, payload) in &stopped {
        g.put(
            "events",
            &format!("playback_stopped.{case}"),
            "event emby://playback-stopped",
            "PlaybackStopped",
            "hand-built: representative value (emby/reporting.rs emits the result of playback_state)",
            payload,
        );
    }

    let statuses = [
        ("idle", Status::Idle),
        (
            "waiting",
            Status::Waiting {
                device: "Shield".into(),
            },
        ),
        (
            "linked",
            Status::Linked {
                device: "Shield".into(),
                drift_ms: Some(-120),
            },
        ),
        (
            "linked_no_drift",
            Status::Linked {
                device: "Shield".into(),
                drift_ms: None,
            },
        ),
    ];
    for (case, payload) in &statuses {
        g.put(
            "events",
            &format!("sync_status.{case}"),
            "event sync://status (and the result of sync_status)",
            "Status",
            "hand-built: representative value (sync/leader.rs)",
            payload,
        );
    }
    let notices = [
        (
            "following",
            Notice::Following {
                device: "Shield".into(),
            },
        ),
        (
            "lost",
            Notice::Lost {
                device: "Shield".into(),
            },
        ),
        (
            "no_answer",
            Notice::NoAnswer {
                device: "Shield".into(),
            },
        ),
        (
            "busy",
            Notice::Busy {
                device: "Shield".into(),
            },
        ),
    ];
    for (case, payload) in &notices {
        g.put(
            "events",
            &format!("sync_notice.{case}"),
            "event sync://notice",
            "Notice",
            "hand-built: every Notice (sync/leader.rs)",
            payload,
        );
    }
    for (case, payload) in [
        ("next", UiAction::Next),
        ("previous", UiAction::Previous),
        ("stop", UiAction::Stop),
    ] {
        g.put(
            "events",
            &format!("sync_action.{case}"),
            "event sync://action",
            "UiAction",
            "hand-built: every UiAction (sync/leader.rs)",
            &payload,
        );
        g.note("the payload is a bare string");
    }
    g.put(
        "events",
        "sync_devices",
        "command sync_devices",
        "Vec<SyncDevice>",
        "hand-built: representative value (sync/mod.rs)",
        &[SyncDevice {
            session_id: "a1b2c3".into(),
            name: "Shield".into(),
        }],
    );

    g.put(
        "events",
        "update_ready",
        "event app://update-ready",
        "UpdateInfo",
        "hand-built: UpdateInfo::new (updater.rs)",
        &UpdateInfo::new(" 1.6.0 ", Some("  ## Nouveautés\n- Lecture liée  ")),
    );
    g.note("UpdateInfo has no rename_all: its fields are `version` and `notes`");
    g.put(
        "events",
        "update_ready.no_notes",
        "event app://update-ready",
        "UpdateInfo",
        "hand-built: UpdateInfo::new without notes (updater.rs)",
        &UpdateInfo::new("1.6.0", None),
    );
}

// ------------------------------------------------------------------ player

fn player_cases(g: &mut Golden) {
    g.put(
        "player",
        "player_tracks",
        "command player_tracks",
        "Vec<Track>",
        "hand-built: representative tracks (player/mod.rs)",
        &[
            Track {
                id: 1,
                kind: TrackKind::Audio,
                title: Some("Français 5.1".into()),
                lang: Some("fre".into()),
                codec: Some("ac3".into()),
                selected: true,
                external: false,
                default: true,
            },
            Track {
                id: 3,
                kind: TrackKind::Sub,
                title: None,
                lang: None,
                codec: None,
                selected: false,
                external: false,
                default: false,
            },
        ],
    );
    g.put(
        "player",
        "player_diagnostics",
        "command player_diagnostics",
        "PlayerDiagnostics",
        "hand-built: every field filled (player/mod.rs)",
        &PlayerDiagnostics {
            mpv_version: Some("mpv 0.40.0".into()),
            ffmpeg_version: Some("7.1".into()),
            video_output: Some("gpu-next".into()),
            gpu_api: Some("d3d11".into()),
            hwdec_current: Some("d3d11va".into()),
            video_codec: Some("hevc".into()),
            video_width: Some(3840),
            video_height: Some(2160),
            audio_codec: Some("ac3".into()),
            audio_output: Some("wasapi".into()),
            container: Some("matroska,webm".into()),
            osd_width: Some(1920),
            osd_height: Some(1080),
            time_pos: Some(12.5),
            duration: Some(6000.0),
            paused: Some(false),
            volume: Some(100.0),
            frame_drop_count: Some(0),
            decoder_frame_drop_count: Some(2),
            chapter_count: Some(3),
            subtitle_options: vec![("sub-scale".into(), "1.0".into())],
        },
    );
    g.put(
        "player",
        "player_diagnostics.empty",
        "command player_diagnostics",
        "PlayerDiagnostics",
        "PlayerDiagnostics::default(): nothing read yet",
        &PlayerDiagnostics::default(),
    );
    g.put(
        "player",
        "external_subtitle",
        "field `subtitles` of PlaybackRequest (not IPC)",
        "ExternalSubtitle",
        "hand-built (player/mod.rs)",
        &ExternalSubtitle {
            source: "https://emby.test/emby/Videos/1/s/Subtitles/3/0/Stream.subrip".into(),
            title: Some("Forcés".into()),
            lang: Some("fre".into()),
            selected: true,
        },
    );
}
