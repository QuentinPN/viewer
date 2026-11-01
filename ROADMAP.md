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
- VERIFIED (2026-10-28), evidence in `docs/evidence/gate2-transcodage-2026-10-28.md`, ADR 0009: HLS transcoding when the server refuses Direct Play / Direct Stream (TranscodingUrl without `api_key`, token in the header), reasons shown in French in the player badge, text subtitles delivered as external files by the server and added to mpv asynchronously (server-side burn-in fails on this NAS), the stop report ends the encoding; real server at 3 Mbit/s (test account, short sessions, cleaned up): first segment in ~6 s, 4 external subtitles, no encoding left after the stop; CDP scenario `gate2-transcode` 10/10 including a seek to 10 min in 3.4 s.
- NOT VERIFIED (transcoding): image subtitles (PGS/VOBSUB) in a transcode; transcoded picture, sound and subtitles by eye and ear; no bitrate setting in the UI (debug-only override).

## Gate 2.1 — Persistent session and resilient server connection [VERIFIED · disabled/deleted account on the fake server only]

Goal: sign in once, then reopen Viewer without entering credentials again, while handling NAS outages and invalid accounts predictably. Existing sign-in, Windows Credential Manager persistence, startup restoration and revoked-token handling are VERIFIED above; do not reimplement them or mark this new workflow VERIFIED without evidence.

- [x] On startup, load the last selected server and its saved session from secure storage; validate it asynchronously and enter the application automatically when valid. Never show the sign-in form briefly while restoration is pending; use a branded loading state.
- [x] Define explicit connection states: restoring, authenticated, server_unreachable, authentication_required, server_configuration, signed_out. Keep network errors separate from HTTP 401/403 and account revocation; classify other errors appropriately.
- [x] If the NAS is unreachable, times out, DNS fails or the network disconnects, show a dedicated French recovery screen with server address, a non-sensitive explanation, Réessayer and Changer de serveur. Preserve the saved token, server association and navigation context; never silently sign out on a transient failure.
- [x] Implement bounded timeouts, cancellable requests and limited exponential backoff with jitter for safe idempotent operations; provide immediate manual retry. Do not create endless retry loops or hammer the NAS. Detect recovery while the app remains open, with controlled automatic retries if appropriate.
- [x] If Emby definitively rejects the session (confirmed unauthorized/revoked token or disabled/deleted user), show a French reauthentication screen for the current server, prefill the known server and username if safely available, and require the password again. Invalidate only the rejected credential; do not erase credentials due to a timeout or a generic 5xx response.
- [x] Changer de serveur opens server configuration without destroying credentials for the previous server. Scope tokens and caches by server identity and user, prevent cross-server session reuse, and allow returning to the previous server. A deliberate sign-out must revoke/clear the corresponding session according to existing behavior.
- [x] When connectivity returns, validate the existing session, refresh stale data and restore the previous view, selection and scroll position where feasible. If video playback was interrupted, offer a safe resume from the last confirmed position; never falsely report a playback stop or mark watched due solely to network loss.
- [x] Ensure concurrent API 401 responses trigger a single coordinated reauthentication transition, with no redirect loops; handle a token revoked while the app is already open. Keep errors accessible and keyboard-navigable.
- [x] Add Rust fake-server contract tests and frontend state-machine tests: valid saved session after app restart; invalid token; deleted/disabled account; wrong credentials; NAS offline at startup and mid-session; timeout; 5xx; offline→online recovery; manual retry; change server→return; no credentials wiped on connectivity failure; concurrent 401; cancellation; navigation restoration.
- [x] Add an off-screen CDP E2E scenario using existing scripts/e2e/ helpers. Validate real-server restore without password after app restart; simulate network failures without changing the NAS configuration. Record test results and evidence in docs/evidence/, update the relevant ADR and PRODUCT/DESIGN/ARCHITECTURE documents if needed.
- Status 2026-10-28: VERIFIED with fake-server tests, Vitest and `gate21-session` 44/44 on the real NAS through the local proxy (`docs/evidence/gate2-1-session-2026-10-28.md`). Disabled/deleted account checked on the fake server only. While mpv still has cached data, a cut during playback is noticed only when the cache runs out (the scenario seeks past it).
- Acceptance criteria: a user authenticates once; subsequent launches connect automatically while credentials remain valid; a NAS outage shows a recovery screen instead of login; retry recovers the existing session; only a confirmed invalid session requests credentials; server switching is safe and explicit.
- TODO: episode detail, search, sorting and filters come with Gate 3.

## Gate 3 — Core experience [IN PROGRESS · home page with hero, search, sorting, filters, favorites, episode page, final player UI and next-episode card VERIFIED]

Home, film/series browsing, details, search, accessible player UI, responsive layouts and artwork cache.

- DONE: home page (ADR 0010) — Continue Watching, Next Up, latest additions per library, "Tout voir". Next Up verified on the real server (episode marked watched then unmarked): Emby 4.10.1.0 leaves `/Shows/NextUp` empty and lists the next episode in its "resume" home section, which the app now reads too.
- DONE: search (ADR 0011) — sidebar field (`/`, Ctrl+F), films and series searched in parallel through `SearchTerm`, results in place of the current view, detail and back with focus kept, Escape back to the previous view with its scroll.
- DONE: sorting and filters of a library grid (ADR 0013) — sort by title, date added, year or rating with a direction; watched filter (all, unwatched, watched, in progress for films); favorites; one genre from `emby_genres`; done by the server with paging restarted; choices kept per library, user and server; filtered empty state with "Réinitialiser les filtres".
- DONE: favorites (ADR 0014) — "Favori" toggle on film and series detail pages (optimistic, rolled back on failure); home row "Favoris" (films and series by title, hidden when empty, read again after a change).
- DONE: home hero (ADR 0015) — up to 5 titles with a backdrop, those to resume first, then films and series added recently and not watched; one title at a time, arrows, dots and ← →, no automatic rotation; "Lecture"/"Reprendre" plays the film or the episode, "Plus d'infos" opens the film or series.
- DONE: episode page (ADR 0016) — in a series the title opens the episode page and the thumbnail plays; series logo or name, "Saison N · Épisode M", episode image (else the series backdrop), air date, runtime, overview, directors, writers, guest stars, technical info, "Lecture"/"Reprendre"; previous/next across seasons with the focus kept; Escape back to the series on that episode, in its season. Home cards and the hero keep playing directly.
- DONE: final player UI (ADR 0017) — `PlayerView.svelte` for Emby playback: title (series, season and episode for an episode), controls fading after 3 s (kept while paused, buffering, scrubbing, over the bar or with a panel open), seek bar with remaining time, ±10 s, volume and mute kept per PC, "Audio", "Sous-titres" and "Informations de lecture" panels, fullscreen, keys M, ↑/↓, I, Escape. Tracks stay chosen by the server. `PlayerSpike` remains for the Gate 1 prototype.
- DONE: next-episode card (ADR 0018) — at the natural end of an episode with a next one (across seasons, from the server), a card "Épisode suivant" with a 10 s countdown, "Lire maintenant" and "Annuler"; the next episode plays in the same player, fullscreen kept. Films, last episodes and early ends close as before.
- TODO: search of episodes, artwork cache.
- DROPPED (user decision, 2026-10-28): search of people.
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

### 2026-10-28 — HLS transcoding

- **Changed**: ADR 0009; `playback.rs` plans `Transcode` from the `TranscodingUrl` (sanitized like the stream URL), French transcode reasons, `External` subtitle profiles for text formats and external subtitle URLs checked against the server; `PlaybackRequest.subtitles` added to mpv by asynchronous `sub-add` on `FILE_LOADED` (`mpv_command_async`); `EmbyService::with_max_streaming_bitrate`, debug-only `VIEWER_E2E_MAX_BITRATE`; the `transcodeRequired` error is removed; the player badge shows "Transcodage · <raison>". New scenario `scripts/e2e/gate2-transcode.mjs`.
- **Findings**: Emby burns the forced subtitle in by default and ffmpeg fails on this NAS (no fontconfig fonts); HLS segments are served without authentication (only the `PlaySessionId` guards them); the first access to an external subtitle can take about a minute (extraction).
- **Server side effects** (authorized 2026-10-28, test account, short sessions at 3 Mbit/s): two transcodes of one film, each ended by the stop report, no resume point left. Server configuration untouched.
- **Tests**: `npm run verify` green (Vitest 33/33, cargo test 53 passed / 7 ignored), release clippy clean; real server: `real_server_transcoding` passed; `gate2-transcode` 10/10; `gate2-play`, `gate2-ui` passed; `gate2-resume` 20/20 on the second run (first run: server timeout while loading libraries, before any playback).
- **Not verified**: image subtitles in a transcode; transcoded media by eye and ear.
- **Next**: Gate 3 browsing (home, search, sort, filters, episode detail), or reporting the audio/subtitle tracks chosen in mpv.

### 2026-10-28 — Home page

- **Changed**: ADR 0010; `emby_continue_watching`, `emby_next_up`, `emby_latest(library_id)` (Rust, `WatchItem` with wide artwork and resume point, unknown library refused before reaching the server's 500); `src/lib/home/` (home page, horizontal rows with keyboard and scroll buttons, 16:9 watch cards that play at once); home is the default signed-in view with an "Accueil" entry; new scenario `scripts/e2e/gate3-home.mjs`, `gate2-ui` waits for the home page after sign-in.
- **Server side effects** (test account, within the 2026-10-28 authorization): one film position written at 600 s / 20 % and cleared, by the cargo test and the scenario. Nothing marked watched.
- **Tests**: `npm run verify` green (Vitest 37/37, cargo test 56 passed / 8 ignored); real server: `real_server_home_rows` passed; `gate3-home` 23/23; `gate2-ui` and `gate2-play` passed. Evidence: `docs/evidence/gate3-accueil-2026-10-28.md`.
- **Not verified**: Next Up with real data (no watched episode on the test account; marking watched not authorized); screen readers; look on a real screen.
- **Next**: search (Gate 3), then sort and filters in the library grid.

### 2026-10-28 — Next Up on the real server

- **Changed**: `emby_next_up` also reads the server's "resume" home section (`/Users/{Id}/HomeSections`, `/Users/{Id}/Sections/resume/Items`) in parallel with `/Shows/NextUp`, keeping unwatched, unstarted episodes (ADR 0010 revision); tokio `macros` feature for `join!`.
- **Finding**: on Emby 4.10.1.0 `/Shows/NextUp` without `SeriesId` stays empty after an episode is marked watched (by `PlayedItems` or by a stop report); the web client gets Next Up from the "resume" section.
- **Server side effects** (test account, authorized on 2026-10-28): episode 1 of one series marked watched then unwatched, several times while diagnosing; each run ended with the episode unwatched and Next Up empty.
- **Tests**: `real_server_next_up` passed (episode 2 in Next Up in 99 ms, cleanup confirmed); `real_server_home_rows` passed; fake-server tests for the merge, the 404 fallback and the expired session.
- **Not verified**: the Next Up row shown in the UI with real data (the app cannot mark watched yet).
- **Next**: search (Gate 3).

### 2026-10-28 — Search

- **Changed**: ADR 0011; `emby_search(query)` (Rust: query cleaned and bounded, nothing sent under 2 characters, films and series as two parallel `/Users/{Id}/Items?SearchTerm=` requests of 25 to show 24 and flag more, since `TotalRecordCount` is unreliable); `src/lib/search/` (query helpers with Vitest tests, results component); search field at the top of the sidebar of `LibraryBrowser.svelte`; new scenario `scripts/e2e/gate3-search.mjs`; `resizeWindow` moved to `scripts/e2e/scenario.mjs`.
- **Finding**: no `/Search/Hints` on Emby 4.10.1.0; `SearchTerm` ignores case and accents, does not fold "œ", and matches the start of title words only.
- **Fixed during verification**: leaving the search brought a scrolled library back to the top (`bind:value` re-rendered before the scroll was saved).
- **Server side effects**: none (reads only).
- **Tests**: `npm run verify` green (Vitest 44/44, cargo test 61 passed / 10 ignored); real server: `real_server_search` passed; `gate3-search` 21/21; `gate2-ui` and `gate3-home` passed. Evidence: `docs/evidence/gate3-recherche-2026-10-28.md`.
- **Not verified**: screen readers; look on a real screen.
- **Next**: sorting and filters in the library grid (Gate 3).

### 2026-10-28 — Persistent session and reconnection (Gate 2.1)

- **Changed**: ADR 0012; Rust `SessionStatus`, error kinds `accountUnavailable` / `serverMismatch` / `cancelled`, session epoch, sessions set aside (`emby_change_server`, `emby_saved_sessions`, `emby_resume_saved`), images scoped by server; `src/lib/connection/` (machine, coordinator, `ConnectionLost.svelte`); `App.svelte` drives the states and keeps the app mounted under the overlays; `SignIn.svelte` reauthentication and "Revenir à …"; views retry on `refresh`; `PlayerSpike.svelte` reports interrupted playback; mpv observes `eof-reached`; `scripts/e2e/net-proxy.mjs` and `gate21-session.mjs`.
- **Finding**: with `keep-open=yes`, mpv emits no `end-file` when the stream stops; it played ~3 min from its cache after the cut, then stayed paused.
- **Server side effects** (test account): repeated sign-ins and sign-outs; one position written then cleared on the first film per run; the one left by the interrupted run was cleared.
- **Fixed during verification**: focus did not come back to "Reprendre" after the recovery screen (`App.svelte` now refocuses the last element focused in the app).
- **Tests**: `npm run verify` green (Vitest 67/67, cargo test 74 passed / 10 ignored, clippy, svelte-check); `gate21-session` 44/44 on the real NAS through the proxy; `gate2-ui` passed. Evidence: `docs/evidence/gate2-1-session-2026-10-28.md`.
- **Not verified**: disabled/deleted account on the real server; screen readers; a cut noticed before mpv's cache runs out.
- **Next**: sorting and filters in the library grid (Gate 3).

### 2026-10-28 — Sorting and filters

- **Changed**: ADR 0013; Rust `ItemsOptions` (`SortKey`, `WatchFilter`, favorites, genre) mapped to `SortBy`/`SortOrder`/`Filters`/`Genres`, genre validated before sending; `emby_items` takes optional `options`; new `emby_genres(library_id)` (`/Genres`); `src/lib/library/options.ts` (defaults, labels, sanitize, per-library storage) with Vitest tests; toolbar in `LibraryBrowser.svelte`; new scenario `scripts/e2e/gate3-sort-filter.mjs`.
- **Finding**: genre lists mix languages ("Comédie"/"Comedy"); the first `/Genres` of a library can take 3.4 s cold.
- **Fixed during verification**: "Trier par" showed a stale value when coming back to a library (`selected` on `<option>` is ignored once the user changed the list; now `value` on `<select>`).
- **Server side effects**: none (reads only).
- **Tests**: `npm run verify` green (Vitest 74/74, cargo test 78 passed / 11 ignored); `real_server_library_filters` passed; `gate3-sort-filter` 25/25; `gate3-search`, `gate3-home` passed; `gate2-ui` failed once (next page still loading after ~5 s), then passed twice. Evidence: `docs/evidence/gate3-tri-filtres-2026-10-28.md`.
- **Not verified**: watched, in-progress and favorite filters with non-empty results on the real server (nothing watched or favorite on the test account); narrow-window toolbar in a scenario; screen readers.
- **Next**: favorites row and hero on the home page, or episode detail (Gate 3).

### 2026-10-28 — Favorites

- **Changed**: ADR 0014; Rust `set_favorite` (`POST`/`DELETE /Users/{Id}/FavoriteItems/{Id}`) and `favorites` (`Filters=IsFavorite`, films and series, 16 by title); commands `emby_set_favorite`, `emby_favorites`; `ItemDetail.favorite`; `src/lib/detail/favorite.ts` (optimistic change) with Vitest tests; "Favori" toggle in `ItemDetail.svelte`; row "Favoris" in `Home.svelte`, reloaded through `favoritesVersion` from `LibraryBrowser.svelte`; new scenario `scripts/e2e/gate3-favorites.mjs`.
- **Found during verification**: the Vite dev server kept serving the old CSS of `ItemDetail.svelte` after the edit (unstyled star); the production build was right; touching the file fixed it. Development-only.
- **Server side effects**: with the user's permission, one favorite (film "Runner") added then removed on the test account; its favorites list ends empty as before. `gate3-home` failed once mid-run and left a resume position on one film; its next run cleared it (positions authorized earlier).
- **Tests**: `npm run verify` green (Vitest 77/77, cargo test 81 passed / 11 ignored); `gate3-favorites` read only 10/10, then with writes 18/18; `gate3-sort-filter`, `gate2-ui` passed; `gate3-home` 1 failure (not reproduced), then 2 passes. Evidence: `docs/evidence/gate3-favoris-2026-10-28.md`.
- **Not verified**: a grid filtered on favorites after a change on a detail page (not reloaded, by design); screen readers.
- **Next**: hero on the home page (design decision first) or episode detail (Gate 3).

### 2026-10-28 — Home hero

- **Changed**: ADR 0015; Rust `hero_resume`, `hero_latest` (`/Users/{Id}/Items` with `ImageTypes=Backdrop`, `IsPlayed=false`, `SortBy=DateCreated`), `first_unplayed_episode` (`/Shows/{Id}/Episodes?IsPlayed=false&Limit=1`); `EmbyService::hero` and command `emby_hero` returning `HeroItem` (detail card, reason, backdrop, logo, overview, play target, episode, resume); `BaseItemDto` reads `ParentLogoItemId`, `ParentLogoImageTag`, `SeriesPrimaryImageTag`; `src/lib/home/hero.ts` with Vitest tests, `Hero.svelte`, hero in `Home.svelte` (skeleton, error with retry, reloaded with "Reprendre" after playback and on reconnection); `seasonEpisodeCode` shared in `format.ts`; new scenario `scripts/e2e/gate3-hero.mjs`.
- **Server side effects**: `gate3-hero` starts one playback and stops it after ~1 s (no resume point kept, checked); `gate3-home` writes then clears one resume position as before.
- **Tests**: `npm run verify` green (Vitest 83/83, cargo test 83 passed / 12 ignored); `real_server_hero` passed (5 titles, 1.9 s); `gate3-hero` 1 failure (`emby_hero` rejected right after the short playback, cause not captured), then 3 passes; `gate3-home`, `gate3-favorites` (read only), `gate3-sort-filter`, `gate2-ui` passed. Evidence: `docs/evidence/gate3-heros-2026-10-28.md`.
- **Not verified**: the failure above; specials (season 0) as the first unwatched episode; a narrow window; screen readers.
- **Next**: episode detail or search of episodes and people (Gate 3).

### 2026-10-28 — Episode page

- **Changed**: ADR 0016; `DetailKind::Episode`, `EpisodeContext`, `EpisodeLink`, `ItemDetail.writers`/`episode`; `EmbyClient::adjacent_episodes` (`/Shows/{Id}/Episodes?AdjacentTo=`, without images or user data); `EmbyService::item_detail` accepts episodes (guest stars as cast, series poster and logo, episode image else series backdrop) and keeps the two neighbours of the same series; `format.ts` `episodePosition`, `episodeLinkCode`, `episodeItem`; `ItemDetail.svelte` nests an episode page over a series page (`inert`, hidden) with previous/next and focus kept; `EpisodeList.svelte` title button and `reveal(episodeId, seasonId)`; scenario helper `episodeTitle`, new scenario `scripts/e2e/gate3-episode.mjs`.
- **Server finding**: `AdjacentTo` returns up to 5 episodes before and the whole rest of the series, across seasons.
- **Tests**: `npm run verify` green (Vitest 87/87, cargo test 84 passed / 13 ignored); `real_server_episode_detail` passed (3 series, next in season 2); `gate3-episode` 21/21 after a focus bug found and fixed; regressions `gate2-ui`, `gate21-session`, `gate3-home`, `gate3-hero`, `gate3-sort-filter`, `gate3-search` passed. Evidence: `docs/evidence/gate3-fiche-episode-2026-10-28.md`.
- **Server side effects**: short playbacks rewound to 0 before stopping; no resume point left (checked). Interrupted runs had left a resume point on one episode, origin not explained, cleared by the next run.
- **Not verified**: specials and double episodes on the real server; return to an episode outside the loaded pages; narrow window; screen readers.
- **Next**: search of episodes, or the final player UI (Gate 3).

### 2026-10-28 — Final player UI

- **Changed**: ADR 0017; `PlaybackStart.title` (`PlaybackTitle`: name, series, season, episode numbers) read in parallel with PlaybackInfo (`tokio::join!`; without resume a failed item read gives no title, a 401 still ends the session); `PlayerDiagnostics.volume`; `src/lib/player/PlayerView.svelte`; `volume.ts` (level and mute in `localStorage` key `viewer.player.volume`); `format.ts` `formatRemaining`, `playbackHeading`; `keyboard.ts` M, ↑/↓ (±5 %), I; `App.svelte` uses `PlayerView` for Emby playback; scenarios `gate2-play`, `gate2-resume`, `gate2-transcode`, `gate3-home` follow the new controls; new scenario `scripts/e2e/gate3-player.mjs`.
- **Tests**: `npm run verify` green (Vitest 100/100, cargo test 86 passed / 13 ignored); `gate3-player` 34/34; regressions listed in `docs/evidence/gate3-lecteur-2026-10-28.md`.
- **Server side effects**: short playbacks rewound to 0 before stopping; no resume point left (checked).
- **Not verified**: real mouse hit-testing over the native video (CDP events only), audible volume (E2E audio output is `null`), narrow window, screen readers, fullscreen rendering by eye.
- **Next**: next-episode card with a 10 s countdown (Gate 3).

### 2026-11-01 — Next-episode card

- **Changed**: ADR 0018; `src/lib/player/upnext.ts` (`UP_NEXT_SECONDS`, `nextTitle`, `isEpisode`, `startCountdown`) and tests; `PlayerView.svelte` (next episode read with `emby_item_detail`, card at EOF, `onPlayNext`); `App.svelte` `playNext`; ignored Rust test `real_server_mark_unplayed` (cleanup); scenario `scripts/e2e/gate3-upnext.mjs`.
- **Tests**: `npm run verify` green (Vitest 104/104, cargo test 86 passed / 14 ignored); `gate3-upnext` 23/23; regressions `gate3-player`, `gate2-play`, `gate2-resume`, `gate3-episode`, `gate2-ui` passed. Details in `docs/evidence/gate3-episode-suivant-2026-11-01.md`.
- **Server side effects** (allowed by the user): the first episode of the first series was marked played three times, then unplayed again; its successor was rewound to 0, no resume point left.
- **Environment**: the NAS moved to `http://192.0.2.14:8096` (Emby 4.10.2.0, same server id); runs use `VIEWER_EMBY_URL`.
- **Not verified**: a film or the last episode at EOF (no card, closes; not run to avoid marking a film played), a failed launch of the next episode, screen readers, a real mouse over the native video.
- **Next**: search of episodes or artwork cache (Gate 3).
