# Viewer — Roadmap

Status vocabulary: `TODO`, `IN PROGRESS`, `BLOCKED`, `VERIFIED`. Never mark VERIFIED without recorded evidence.

## Gate 0 — Repository and toolchain [VERIFIED locally · CI not yet run]
Initialize Tauri 2 + Svelte 5 + TypeScript strict, Biome, svelte-check, Vitest, Rust fmt/clippy/test. Establish CI and build reproducibility.
- VERIFIED (2026-10-27): `npm run verify` green locally (Biome, svelte-check, Vitest 8/8, Vite build, cargo fmt, clippy `-D warnings`, cargo test 10/10 with real libmpv). Pinned libmpv fetch with SHA-256 check (`scripts/fetch-libmpv.ps1`). Decisions: ADR 0001, ADR 0002.
- TODO: first run of `.github/workflows/ci.yml` on GitHub (no remote configured yet). Release build of the full app with bundling stays disabled until Gate 5.

## Gate 1 — Native playback feasibility [IN PROGRESS · core path VERIFIED]
Prove local MKV, authorized HTTP stream, audio, subtitle switching, seek, fullscreen, overlay/focus, resize, hardware decode and teardown on Windows. Record environment, commands, evidence and limitations. **Blocks large UI work.**
- VERIFIED (2026-10-27, RTX 3080, Win10 22H2, 100 % scaling), evidence in `docs/evidence/gate1-2026-10-27.md`:
  - native video under a transparent WebView2 with HTML overlays (`parent` surface mode, ADR 0003);
  - local H.264 MKV and local HTTP stream with header token;
  - d3d11va hardware decoding;
  - ASS subtitles rendered;
  - switching between 3 audio and 3 ASS subtitle tracks, checked on the decoded track (`current-tracks/*`), distinct cues per track, subtitles disabled, UI selectors in sync; audio output `wasapi` opened;
  - seek, fullscreen and resize following;
  - real Space key → pause through the WebView;
  - teardown ≈100–145 ms, 3 open/close cycles, process exits cleanly;
  - manual, by the user: audible audio, mouse clicks on overlay controls, Escape leaves fullscreen and Space still works after it, Windows ✕ during playback closes at once with no leftover process;
  - window close during playback measured by `scripts/spike/close-probe.ps1`: 154–189 ms, exit code 0, no WebView2 error at exit.
- NOT VERIFIED:
  - rendering of a "Forced" subtitle track (no cue observed in the test window);
  - SRT embedded, PGS;
  - HEVC, AV1, 4K, HDR;
  - 125/150 % DPI, multi-monitor;
  - network loss, unsupported codec;
  - release build on a clean machine.
- Next: a HEVC sample and a sample with SRT/PGS subtitles, then rerun `scripts/spike/run-gate1.ps1`; manual 125/150 % DPI check.

## Gate 2 — Emby integration [IN PROGRESS · sign-in, libraries, detail pages, direct play and resume VERIFIED]
Connection/auth, typed API client, libraries, item details, PlaybackInfo, secure tokens, session reporting, fake-server contract tests.
- Access authorized by the user (2026-10-27): Emby 4.10.1.0 on the QNAP, LAN HTTP only, non-admin test account. Decision: ADR 0004 (`EmbyClient` in Rust, token in the Windows Credential Manager).
- VERIFIED (2026-10-27), evidence in `docs/evidence/gate2-2026-10-27.md`:
  - server address normalization, connection test, plain HTTP accepted only to a local address;
  - sign-in, token stored in the Windows Credential Manager only, auth in the `X-Emby-Authorization` header;
  - restore after restart, revoked token detected and forgotten, sign-out revoking the token on the server;
  - typed French errors with a hint (wrong password, unreachable, not Emby, expired session);
  - fake-server contract tests, `#[ignore]` real-server test, UI E2E on the real server.
- VERIFIED (2026-10-27): film and series libraries, poster grid paged by 60 and sorted by name, watched and unwatched-episode badges, artwork through the local `emby-img` protocol (ADR 0005), narrow layout; real server: 4 libraries, 15 to 707 items, every last page reached.
- VERIFIED (2026-10-28), evidence in `docs/evidence/gate2-2026-10-28.md`: film and series detail pages (backdrop, logo, metadata, people, technical info without the NAS path), seasons and paged episodes, back to the grid with scroll and focus kept; real server: 195 details, 267 seasons, 3 454 episodes deserialized.
- NOT VERIFIED: HTTPS with TLS validation against a real server (none available); retries, cancellation, offline state; 125/150 % DPI; resume and watched display against real data (test account has no history).
- Measured limitation: a cold poster costs ~170–230 ms on the NAS (server-side resize), so a fast scroll leaves posters blank for a few seconds; no virtualization yet (707 cards stay in the DOM).
- VERIFIED (2026-10-28), evidence in `docs/evidence/gate2-lecture-2026-10-28.md`, ADR 0007: PlaybackInfo with a libmpv device profile, Direct Play / Direct Stream / Transcode decision from the server, stream URL without its `api_key` and token in the `X-Emby-Token` header, play button on the film detail page and on each episode, back to the detail page with focus kept; real server: 90 sampled items all Direct Play, stream 206 with the header and 401 without; CDP scenario `gate2-play` 13/13.
- NOT VERIFIED (playback): picture, sound and subtitles seen or heard by a person through the Emby path; HEVC/AV1/HDR/Dolby Vision/DTS/TrueHD rendering; items with several sources.
- VERIFIED (2026-10-28), evidence in `docs/evidence/gate2-suivi-2026-10-28.md`, ADR 0008: playback reporting from Rust (`/Sessions/Playing`, progress every 10 s and on pause / unpause / seek, stopped with `Failed` on error), stop flushed when the window closes during playback, watched state and resume point read back after the stop, "Reprendre" / "Lire depuis le début" on films, started episodes resume; real server (test account, cleaned up): resume point recorded at the stop position and cleared by a stop near 0; CDP scenario `gate2-resume` 20/20.
- NOT VERIFIED (reporting): watched state set by the server at the end of a file; stop on a hard kill (none is sent by design); close with an unreachable server (3 s bound); audio/subtitle track choice not reported.
- TODO: transcoding (the stop report now exists to end the server's encoding). Episode detail, search, sorting and filters come with Gate 3.

## Gate 3 — Core experience [TODO]
Home, film/series browsing, details, search, accessible player UI, responsive layouts and artwork cache.
- Known input: the control bar hides subtitles at narrow widths (Gate 1 finding); views must provide an opaque background when no video plays (transparent window).

## Gate 4 — End-to-end quality [TODO]
Verify real server resume/watched state, unsupported format fallback, network interruptions, performance and security reviews.

## Gate 5 — Packaging [TODO · BLOCKED on licence decision]
Redistribution/license audit, release build, Windows installer, clean-machine smoke test, documentation. The current libmpv build is GPL (ADR 0002): choose a GPL-3.0 project licence or an in-house LGPL libmpv before any distribution.

## Work log
For each completed slice record date, changed behavior, tests executed, outcomes, blockers and next step. Do not fabricate progress.

### 2026-10-27 — Gate 0 and Gate 1 spike
- **Changed**: project scaffolded (Svelte 5 + Vite + Tauri 2); Rust `PlayerEngine` trait with a libmpv implementation (runtime-loaded, hardened options, header auth, log redaction); typed IPC commands; Gate 1 prototype overlay; automated Gate 1 scenario (debug builds only) with real screenshots and OS key presses; CI workflow; ADR 0001–0003.
- **Tests**:
  - `npm run verify`: all green;
  - `scripts/spike/run-gate1.ps1` in `child`, `parent` and `separate` modes, plus `parent` over local HTTP: all checks pass in `parent`; `child` fails visually despite passing all automated checks; `separate` fails resize/fullscreen.
- **Bug found and fixed**: the UI kept player controls after a Rust-side close → `Shutdown` event now emitted after every teardown.
- **Blockers / environment**:
  - VS 18 lacks x64 C++ libs (workaround `scripts/with-msvc.ps1`);
  - no GitHub remote, so CI has not run;
  - Emby access not yet authorized.
- **Next**: multi-track/HEVC samples for the remaining Gate 1 items; then Gate 2 after ADR 0004 and server access approval.

### 2026-10-27 — Gate 1 multi-track switching
- **Changed**: the Gate 1 scenario checks the decoded track (`current-tracks/*`) instead of the requested one, checks playback keeps advancing after an audio switch, captures a hashed subtitle cue per track, disables subtitles, and takes one screenshot per track. `PlayerDiagnostics` exposes `audioOutput` (`current-ao`). Track labels translate BCP 47 tags (`fr-FR` → Français).
- **Tests**:
  - `scripts/spike/run-gate1.ps1` (parent, multi-track MKV: 3 AAC, 3 ASS): all checks pass except `subtitle_cue_1` (Forced track, no cue in the window);
  - `npm run verify`: all green (Vitest 9/9, cargo test 10/10).
- **Bug found and fixed** (scenario): scanning a sparse track moved the playhead to the end credits.
- **Next**: HEVC and SRT/PGS samples; manual overlay/DPI/close checks.

### 2026-10-27 — Gate 1 manual checks
- **Tests** (by the user, debug build, multi-track MKV): audio audible; mouse clicks on play/pause, seek bar and track menus; Escape exits fullscreen and Space works right after; Escape closes an open track menu; Windows ✕ during playback closes at once, no leftover process (measured).
- **Not measured**: close latency; WebView2 `Chrome_WidgetWin_0` error 1412 at exit not analysed.
- **Next**: HEVC and SRT/PGS samples (public test files, download needs approval); 125/150 % DPI.

### 2026-10-27 — WebView2 error at exit
- **Bug**: every exit with an active player logged `Failed to unregister class Chrome_WidgetWin_0. Error = 1412`, because the process exited through `app.exit(0)` while the WebView2 host window still existed.
- **Changed**: after mpv teardown the main window is destroyed and Tauri exits normally (window close path and Gate 1 scenario); `scripts/spike/close-probe.ps1` added to measure Windows close idle or during playback.
- **Tests**: close-probe during playback 4/4 without the error (154–189 ms, exit 0, no leftover process); idle close 38 ms without the error; full Gate 1 scenario without the error, same check results; `npm run verify` all green.
- **Next**: HEVC and SRT/PGS samples; 125/150 % DPI.

### 2026-10-27 — Gate 2 sign-in and secure token
- **Changed**: ADR 0004; Rust `emby` module (`ServerUrl`, `EmbyClient`, `CredentialStore` on the Windows Credential Manager, `EmbyService` with `session.json` holding no secret); five Tauri commands (`emby_probe`, `emby_sign_in`, `emby_restore`, `emby_sign_out`, `emby_last_server`); French two-step sign-in screen; session restored at startup; the Gate 1 prototype stays reachable from the signed-in screen.
- **Tests**:
  - `npm run verify`: all green (Vitest 12/12, cargo test 22 passed / 2 ignored);
  - ignored tests run on demand: Windows Credential Manager round trip, real server sign-in/restore/sign-out: OK;
  - `scripts/gate2/e2e-signin.ps1` on the real server: wrong password, sign-in, restore after restart, sign-out, prefilled address; screenshots reviewed.
- **Bugs found and fixed**: focus sent to a still-disabled input; spike detection too early at startup.
- **Server side effects**: test devices left in the Emby dashboard, one with an unrevoked session ("PC test bureau"); no configuration changed.
- **Next**: libraries and paged items, then PlaybackInfo.

### 2026-10-27 — Gate 2 libraries and paged grid
- **Changed**: ADR 0005 (artwork through a local `emby-img` protocol served by Rust); `EmbyClient::views/items/image`; `EmbyService::libraries/items/image` (only library ids received from `/Views` are sent as `ParentId`, page size clamped to 200, a token rejected while browsing ends the session); commands `emby_libraries`, `emby_items`; CSP allows `http://emby-img.localhost`; signed-in home with a library sidebar and an infinite poster grid (skeletons, empty and error states, retry, "Afficher la suite" fallback).
- **Server findings** (Emby 4.10.1.0): an unknown `ParentId` is ignored and returns the whole server (16 829 items); paging past the end returns `TotalRecordCount: 0`; images answer without auth, and a missing image type answers 200 with a generic picture; ids are numeric strings.
- **Tests**:
  - `npm run verify`: all green (Vitest 19/19, cargo test 29 passed / 3 ignored);
  - `real_server_libraries_and_pages` (ignored test): OK on the 4 libraries, last page sizes match the totals, one poster fetched;
  - `scripts/gate2/e2e-browse.ps1`: sign-in, library switch by keyboard, scroll loading further pages, narrow window, sign-out; screenshots reviewed; credential store empty at the end.
- **Bug found and fixed**: `Url::parse_with_params` appended a bare `?` to every request, breaking all existing calls; caught by the contract tests.
- **Next**: item details (film; series with seasons and episodes), then PlaybackInfo.

### 2026-10-28 — Gate 2 detail pages
- **Changed**: `EmbyClient::item/seasons/episodes`; `EmbyService::item_detail/seasons/episodes` (NAS paths never deserialized, episodes of another series rejected, in-season specials kept); commands `emby_item_detail`, `emby_seasons`, `emby_episodes`; poster cards are buttons; detail page over the grid (grid kept mounted and `inert`), season switcher, episodes paged by 50; shared `languageName` on `Intl.DisplayNames` (also used by the player prototype); generic pager.
- **Server findings** (Emby 4.10.1.0): detail fields come without `Fields`; episodes need `Fields=Overview`; a `SeasonId` of another series is honoured; specials aired during a season come within it; `Studios[].Id` is a number; unknown id → 404 with French text.
- **Tests**:
  - `npm run verify`: all green (Vitest 31/31, cargo test 32 passed / 4 ignored);
  - `real_server_details_seasons_episodes` (ignored test, 60 items per library): OK;
  - `scripts/gate2/e2e-detail.ps1`: series and film pages, season switch by keyboard, Escape back with focus, scroll kept after a mouse open, narrow window, sign-out; screenshots reviewed.
- **Bugs found and fixed**: in-season specials rejected by the first series/season check (real-server test); separators lost to Svelte whitespace trimming; focus outline around the logo; first E2E keys sent before the page was ready (script now checks the foreground window before every key).
- **Next**: PlaybackInfo with a device profile, Direct Play / Direct Stream / Transcode decision, play button from the detail page.

### 2026-10-28 — UI tests without taking the desktop (DONE)
- **Why**: the `SendKeys` E2E scripts need Viewer in the foreground for the whole run; the user could not use the PC meanwhile.
- **Changed**: ADR 0006; `scripts/e2e/app.mjs` drives the debug build over WebView2's DevTools port with `playwright-core` 1.64.1 (dev dependency); debug-only `VIEWER_E2E_OFFSCREEN` opens the window off-screen without activating it; `scripts/e2e/gate2-ui.mjs` ports the sign-in, browse and detail scenarios with asserted checks; `scripts/e2e/run.ps1` reads the test account from the Windows Credential Manager (`viewer-e2e-emby`).
- **Tests**: launcher probe on the sign-in screen: CDP attached in ~0.4 s, correct screenshot, user's window stayed in the foreground, exit code 0; `npm run verify` green (Vitest 31/31, cargo test 32 passed / 4 ignored, `verify:rust` through `scripts/with-msvc.ps1`); release clippy clean.
- **Real server**: `scripts/e2e/run.ps1` run `gate2-ui-20261028-083506`, 15/15 checks passed, screenshots reviewed, credential store empty at the end; the desktop stayed usable during the run.
- **Script bugs found on the way**: a locator kept from the page closed by the restart; libraries counted before the list was filled; Playwright's `click()` scrolls its target into view, which moved the grid before the detail opened (the app keeps the scroll offset; the test now clicks a card already on screen). After a resize the grid reflows and scroll anchoring changes the offset, so that case checks the opened card is focused and on screen instead.
- **Retired**: `scripts/gate2/e2e-signin.ps1`, `e2e-browse.ps1`, `e2e-detail.ps1` (still in git history).
- **Next**: PlaybackInfo with a device profile, Direct Play / Direct Stream / Transcode decision, play button from the detail page.

### 2026-10-28 — Playback negotiation and play button
- **Changed**: ADR 0007; `src-tauri/src/emby/playback.rs` (device profile, source choice, stream URL without token, safe server headers); `EmbyClient::playback_info`, `EmbyService::prepare_playback`, command `emby_play` (the UI only gets method and container); typed errors `playbackRefused`, `noCompatibleStream`, `transcodeRequired`, `player`; "Lecture" button on the film detail page, episode thumbnails as play buttons; the player shows the server's decision and goes back on Escape, ✕ or end of file; the library stays mounted under the player. Debug-only `VIEWER_E2E_SILENT` (`ao=null`) for scenarios. `scripts/with-test-account.ps1` reads the test account for any command; `scripts/e2e/scenario.mjs` shares scenario helpers; new scenario `scripts/e2e/gate2-play.mjs`.
- **Tests**: `npm run verify` green (Vitest 32/32, cargo test 42 passed / 5 ignored), release clippy clean; real server: `real_server_playback_negotiation` 90/90 Direct Play, 206 with header / 401 without; `gate2-play` 13/13 and `gate2-ui` 15/15, screenshots reviewed.
- **Bugs found and fixed**: play buttons lost focus while disabled, so focus was not given back after playback (now `aria-disabled`); argument splatting in `with-test-account.ps1`.
- **Not verified**: video, audio and subtitles by eye and ear through the Emby path (CDP screenshots do not show mpv); playback reporting and resume do not exist yet.
- **Next**: playback reporting (start, progress, pause, stopped) with resume from the server position, verified on the real test account.

### 2026-10-28 — Playback reporting and resume
- **Changed**: ADR 0008; `src-tauri/src/emby/reporting.rs` (pure `Tracker` state machine, `Reporter` task on a tokio channel, one retry on stop, `emby://playback-stopped` event); `EmbyClient::report_playback`, `EmbyService::report_playback/playback_state`; `emby_play(itemId, resume)` reads the resume position from the server in Rust; the window close waits up to 3 s for the stop report; film detail shows "Reprendre" and "Lire depuis le début", episodes resume when started; detail and episode list update in place after a stop. Scenario helpers for engine diagnostics moved to `scenario.mjs`; new scenario `scripts/e2e/gate2-resume.mjs`.
- **Server side effects** (authorized 2026-10-28, test account only): positions written on one film and one episode, then cleared; nothing marked watched.
- **Tests**: `npm run verify` green (Vitest 32/32, cargo test 50 passed / 6 ignored), release clippy clean; real server: `real_server_playback_reporting_and_resume` passed with cleanup; `gate2-resume` 20/20, `gate2-play` and `gate2-ui` passed, screenshots reviewed.
- **Not verified**: watched state at the end of a file on the real server; close with an unreachable server; video, audio and subtitles by eye and ear.
- **Next**: transcoding through HLS with the stop report ending the server's encoding, or Gate 3 browsing (search, sort, filters, episode detail).
