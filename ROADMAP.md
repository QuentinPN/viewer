# Viewer — Roadmap

Status vocabulary: `TODO`, `IN PROGRESS`, `BLOCKED`, `VERIFIED`. Never mark VERIFIED without recorded evidence.

## Gate 0 — Repository and toolchain [VERIFIED locally · CI not yet run]

Initialize Tauri 2 + Svelte 5 + TypeScript strict, Biome, svelte-check, Vitest, Rust fmt/clippy/test. Establish CI and build reproducibility.

- VERIFIED (2026-10-27): `npm run verify` green locally (Biome, svelte-check, Vitest 8/8, Vite build, cargo fmt, clippy `-D warnings`, cargo test 10/10 with real libmpv). Pinned libmpv fetch with SHA-256 check (`scripts/fetch-libmpv.ps1`). Decisions: ADR 0001, ADR 0002.
- TODO: first run of `.github/workflows/ci.yml` on GitHub (no remote configured yet). Release build and installer: see Gate 5.

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

- Access authorized by the user (2026-10-27): Emby 4.10.1.0 on the mini-PC, LAN HTTP only, non-admin test account. Decision: ADR 0004 (`EmbyClient` in Rust, token in the Windows Credential Manager).
- VERIFIED (2026-10-27), evidence in `docs/evidence/gate2-2026-10-27.md`:
  - server address normalization, connection test, plain HTTP accepted only to a local address;
  - sign-in, token stored in the Windows Credential Manager only, auth in the `X-Emby-Authorization` header;
  - restore after restart, revoked token detected and forgotten, sign-out revoking the token on the server;
  - typed French errors with a hint (wrong password, unreachable, not Emby, expired session);
  - fake-server contract tests, `#[ignore]` real-server test, UI E2E on the real server.
- VERIFIED (2026-10-27): film and series libraries, poster grid paged by 60 and sorted by name, watched and unwatched-episode badges, artwork through the local `emby-img` protocol (ADR 0005), narrow layout; real server: 4 libraries, 15 to 707 items, every last page reached.
- VERIFIED (2026-10-28), evidence in `docs/evidence/gate2-2026-10-28.md`: film and series detail pages (backdrop, logo, metadata, people, technical info without the server path), seasons and paged episodes, back to the grid with scroll and focus kept; real server: 195 details, 267 seasons, 3 454 episodes deserialized.
- NOT VERIFIED: HTTPS with TLS validation against a real server (none available); retries, cancellation, offline state; 125/150 % DPI; resume and watched display against real data (test account has no history).
- Measured limitation: a cold poster costs ~170–230 ms on the server (server-side resize), so a fast scroll leaves posters blank for a few seconds; no virtualization yet (707 cards stay in the DOM).
- VERIFIED (2026-10-28), evidence in `docs/evidence/gate2-lecture-2026-10-28.md`, ADR 0007: PlaybackInfo with a libmpv device profile, Direct Play / Direct Stream / Transcode decision from the server, stream URL without its `api_key` and token in the `X-Emby-Token` header, play button on the film detail page and on each episode, back to the detail page with focus kept; real server: 90 sampled items all Direct Play, stream 206 with the header and 401 without; CDP scenario `gate2-play` 13/13.
- NOT VERIFIED (playback): picture, sound and subtitles seen or heard by a person through the Emby path; HEVC/AV1/HDR/Dolby Vision/DTS/TrueHD rendering; items with several sources.
- VERIFIED (2026-10-28), evidence in `docs/evidence/gate2-suivi-2026-10-28.md`, ADR 0008: playback reporting from Rust (`/Sessions/Playing`, progress every 10 s and on pause / unpause / seek, stopped with `Failed` on error), stop flushed when the window closes during playback, watched state and resume point read back after the stop, "Reprendre" / "Lire depuis le début" on films, started episodes resume; real server (test account, cleaned up): resume point recorded at the stop position and cleared by a stop near 0; CDP scenario `gate2-resume` 20/20.
- VERIFIED (2026-11-07): watched state set by the server at the natural end of a film (`gate4-watched`) and of an episode (`gate3-upnext`), from the stop report alone, no resume point left.
- NOT VERIFIED (reporting): stop on a hard kill (none is sent by design); close with an unreachable server (3 s bound); audio/subtitle track choice not reported.
- VERIFIED (2026-10-28), evidence in `docs/evidence/gate2-transcodage-2026-10-28.md`, ADR 0009: HLS transcoding when the server refuses Direct Play / Direct Stream (TranscodingUrl without `api_key`, token in the header), reasons shown in French in the player badge, text subtitles delivered as external files by the server and added to mpv asynchronously (server-side burn-in fails on this server), the stop report ends the encoding; real server at 3 Mbit/s (test account, short sessions, cleaned up): first segment in ~6 s, 4 external subtitles, no encoding left after the stop; CDP scenario `gate2-transcode` 10/10 including a seek to 10 min in 3.4 s.
- NOT VERIFIED (transcoding): image subtitles (PGS/VOBSUB) in a transcode; transcoded picture, sound and subtitles by eye and ear; the « Accès distant » cap on a real remote address (only a local address is tested, ADR 0037).

## Gate 2.1 — Persistent session and resilient server connection [VERIFIED · disabled/deleted account on the fake server only]

Goal: sign in once, then reopen Viewer without entering credentials again, while handling server outages and invalid accounts predictably. Existing sign-in, Windows Credential Manager persistence, startup restoration and revoked-token handling are VERIFIED above; do not reimplement them or mark this new workflow VERIFIED without evidence.

- [x] On startup, load the last selected server and its saved session from secure storage; validate it asynchronously and enter the application automatically when valid. Never show the sign-in form briefly while restoration is pending; use a branded loading state.
- [x] Define explicit connection states: restoring, authenticated, server_unreachable, authentication_required, server_configuration, signed_out. Keep network errors separate from HTTP 401/403 and account revocation; classify other errors appropriately.
- [x] If the server is unreachable, times out, DNS fails or the network disconnects, show a dedicated French recovery screen with server address, a non-sensitive explanation, Réessayer and Changer de serveur. Preserve the saved token, server association and navigation context; never silently sign out on a transient failure.
- [x] Implement bounded timeouts, cancellable requests and limited exponential backoff with jitter for safe idempotent operations; provide immediate manual retry. Do not create endless retry loops or hammer the server. Detect recovery while the app remains open, with controlled automatic retries if appropriate.
- [x] If Emby definitively rejects the session (confirmed unauthorized/revoked token or disabled/deleted user), show a French reauthentication screen for the current server, prefill the known server and username if safely available, and require the password again. Invalidate only the rejected credential; do not erase credentials due to a timeout or a generic 5xx response.
- [x] Changer de serveur opens server configuration without destroying credentials for the previous server. Scope tokens and caches by server identity and user, prevent cross-server session reuse, and allow returning to the previous server. A deliberate sign-out must revoke/clear the corresponding session according to existing behavior.
- [x] When connectivity returns, validate the existing session, refresh stale data and restore the previous view, selection and scroll position where feasible. If video playback was interrupted, offer a safe resume from the last confirmed position; never falsely report a playback stop or mark watched due solely to network loss.
- [x] Ensure concurrent API 401 responses trigger a single coordinated reauthentication transition, with no redirect loops; handle a token revoked while the app is already open. Keep errors accessible and keyboard-navigable.
- [x] Add Rust fake-server contract tests and frontend state-machine tests: valid saved session after app restart; invalid token; deleted/disabled account; wrong credentials; server offline at startup and mid-session; timeout; 5xx; offline→online recovery; manual retry; change server→return; no credentials wiped on connectivity failure; concurrent 401; cancellation; navigation restoration.
- [x] Add an off-screen CDP E2E scenario using existing scripts/e2e/ helpers. Validate real-server restore without password after app restart; simulate network failures without changing the server configuration. Record test results and evidence in docs/evidence/, update the relevant ADR and PRODUCT/DESIGN/ARCHITECTURE documents if needed.
- Status 2026-10-28: VERIFIED with fake-server tests, Vitest and `gate21-session` 44/44 on the real server through the local proxy (`docs/evidence/gate2-1-session-2026-10-28.md`). Disabled/deleted account checked on the fake server only. While mpv still has cached data, a cut during playback is noticed only when the cache runs out (the scenario seeks past it).
- Acceptance criteria: a user authenticates once; subsequent launches connect automatically while credentials remain valid; a server outage shows a recovery screen instead of login; retry recovers the existing session; only a confirmed invalid session requests credentials; server switching is safe and explicit.
- TODO: episode detail, search, sorting and filters come with Gate 3.

## Gate 3 — Core experience [IN PROGRESS · home page with hero, search, sorting, filters, favorites, episode page, final player UI, next-episode card, skip intro and credits, artwork cache, episode search and settings screen (artwork cache, playback) VERIFIED]

Home, film/series browsing, details, search, accessible player UI, responsive layouts and artwork cache.

- DONE: home page (ADR 0010) — Continue Watching, Next Up, latest additions per library, "Tout voir". Next Up verified on the real server (episode marked watched then unmarked): Emby 4.10.1.0 leaves `/Shows/NextUp` empty and lists the next episode in its "resume" home section, which the app now reads too.
- DONE: search (ADR 0011) — sidebar field (`/`, Ctrl+F), films and series searched in parallel through `SearchTerm`, results in place of the current view, detail and back with focus kept, Escape back to the previous view with its scroll.
- DONE: sorting and filters of a library grid (ADR 0013) — sort by title, date added, year or rating with a direction; watched filter (all, unwatched, watched, in progress for films); favorites; one genre from `emby_genres`; done by the server with paging restarted; choices kept per library, user and server; filtered empty state with "Réinitialiser les filtres".
- DONE: favorites (ADR 0014) — "Favori" toggle on film and series detail pages (optimistic, rolled back on failure); home row "Favoris" (films and series by title, hidden when empty, read again after a change).
- DONE: home hero (ADR 0015) — up to 5 titles with a backdrop, those to resume first, then films and series added recently and not watched; one title at a time, arrows, dots and ← →, no automatic rotation; "Lecture"/"Reprendre" plays the film or the episode, "Plus d'infos" opens the film or series.
- DONE: episode page (ADR 0016) — in a series the title opens the episode page and the thumbnail plays; series logo or name, "Saison N · Épisode M", episode image (else the series backdrop), air date, runtime, overview, directors, writers, guest stars, technical info, "Lecture"/"Reprendre"; previous/next across seasons with the focus kept; Escape back to the series on that episode, in its season. Home cards and the hero keep playing directly.
- DONE: final player UI (ADR 0017) — `PlayerView.svelte` for Emby playback: title (series, season and episode for an episode), controls fading after 3 s (kept while paused, buffering, scrubbing, over the bar or with a panel open), seek bar with remaining time, ±10 s, volume and mute kept per PC, "Audio", "Sous-titres" and "Informations de lecture" panels, fullscreen, keys M, ↑/↓, I, Escape. Tracks stay chosen by the server. `PlayerSpike` remains for the Gate 1 prototype.
- DONE: next-episode card (ADR 0018) — at the natural end of an episode with a next one (across seasons, from the server), a card "Épisode suivant" with a 10 s countdown, "Lire maintenant" and "Annuler"; the next episode plays in the same player, fullscreen kept. Films, last episodes and early ends close as before.
- DONE: skip intro and credits (ADR 0030) — segments from Emby's chapter markers, then IntroDB, then TheIntroDB (anonymous, series ids with season and episode); « Passer l'intro » / « Passer le générique » / « Épisode suivant » at the bottom right, kept when the controls fade, Entrée; « Passer automatiquement » once per playback; Réglages › Lecture with the external sources on by default. The test server has no marker today.
- DONE: chapters (ADR 0034) — from the Emby item's `Chapters`; marks on the seek bar, the playing chapter's name under it, a « Chapitres » panel, Page Up/Page Down. No thumbnails: the server extracts no chapter images. Multi-version items use the default version's chapters.
- DONE: artwork disk cache (ADR 0019) — behind `emby-img`, per server, 512 MiB with least-recently-used eviction, files only (no SQLite for artwork); served after the session and server checks, before the server download slots; `artwork_cache_stats`.
- DONE: search of episodes (ADR 0020) — a third parallel query (`Episode`, 100 asked) filtered in Rust on the episode's own title, because Emby also matches the series name; "Épisodes" section of 16:9 cards opening the episode page, previous/next in place, Escape back to the card.
- DONE: settings screen, artwork cache section (ADR 0021) — « Réglages » in the sidebar; size against the quota, image count, reads since launch; « Vider le cache des images » (`artwork_cache_clear`) removes the `.img` files of every server and the empty folders, keeps other files, announces what was freed; no confirmation.
- TODO: other settings sections (connection, playback, UI) once they have real behavior; adjustable cache quota.
- DROPPED (user decision, 2026-10-28): search of people.
- Known input: the control bar hides subtitles at narrow widths (Gate 1 finding); views must provide an opaque background when no video plays (transparent window).

## Gate 4 — End-to-end quality [IN PROGRESS · stream reconnection, stall limit, cuts during a transcode, transcode fallback, security review and HTTPS in the player VERIFIED · performance baselines MEASURED]

Verify real server resume/watched state, unsupported format fallback, network interruptions, performance and security reviews.

- VERIFIED (2026-11-06): stream reconnection and stall limit (ADR 0022) through the local proxy, `docs/evidence/gate4-reseau-2026-11-06.md`.
- VERIFIED (2026-11-06): transcode fallback when the direct source does not open (ADR 0023), on the real server with unreadable bytes from the local proxy, `docs/evidence/gate4-repli-2026-11-06.md`.
- VERIFIED (2026-11-06): short and long cuts during an HLS transcode through the local proxy, `docs/evidence/gate4-coupure-hls-2026-11-06.md`.
- VERIFIED (2026-11-06): the position played reaches the server when the network comes back after a long cut (ADR 0024), on the real server through the local proxy, `docs/evidence/gate4-renvoi-arret-2026-11-06.md`. Application closed during the cut: the position is still lost.
- VERIFIED (2026-11-06): security review, with player hardening (ADR 0025), `docs/evidence/gate4-securite-2026-11-06.md`.
- VERIFIED (2026-11-06): HTTPS streams open with the Windows root certificates (ADR 0026), `docs/evidence/gate4-https-2026-11-06.md`. Probed with real libmpv on public and local servers and a fake token; NOT VERIFIED against a real HTTPS Emby server or from the app's UI, as none is available.
- VERIFIED (2026-11-06): `cargo audit` reports no vulnerability after updating `quick-xml` and `time`; 7 warnings remain, none a known vulnerability, `docs/evidence/gate4-audit-dependances-2026-11-06.md`.
- VERIFIED (2026-11-06): with no stop report, the encoding leaves `/Sessions` by itself 50 to 70 s after the client goes silent, over 4 tries on the real server, `docs/evidence/gate4-encodeur-sans-rapport-2026-11-06.md`. The ffmpeg process itself was not observed.
- VERIFIED (2026-11-06): playback resumes after a 5 min pause during a transcode, past what mpv had read ahead and with nothing skipped, on the real server, `docs/evidence/gate4-pause-longue-2026-11-06.md`. The encoding stayed present during the pause; a 30 min pause was not run (owner's choice, low stake).
- NOT VERIFIED: a real network cut, a real file mpv cannot read, a file whose video alone is unreadable.
- MEASURED (2026-11-06): performance baselines, debug build, `docs/evidence/gate4-performance-2026-11-06.md`: playback start median 1184 ms over 11 films, 0 dropped frames in 60 s off-screen, navigation under 100 ms at the median, 92 % artwork hits on the second launch, up to 994 MiB private memory with the whole grid.
- VERIFIED (2026-11-06): a stream that does not open ends after 45 s with a fallback or a message (ADR 0027), `docs/evidence/gate4-ouverture-2026-11-06.md`: through the local proxy with the final code; on the real film before the last two changes, as it has since left the library.

## Gate 5 — Packaging [TODO]

Redistribution/license audit, release build, Windows installer, clean-machine smoke test, documentation. Licence decided (2026-11-06, ADR 0002): personal use on the owner's own PCs, no distribution, so the current GPL-3.0 libmpv stays. Giving the app to anyone else reopens the choice between a GPL-3.0 project licence and an in-house LGPL libmpv; audit and options in `docs/evidence/gate5-licences-2026-11-06.md`.

- DONE (2026-11-07): release build and per-user NSIS installer in French, with `libmpv-2.dll` beside the executable (ADR 0028). Built, not yet installed or run.
- VERIFIED (2026-11-07): installed on the owner's PC, the release build signs in, plays a film directly and seeks; release-only behaviors hold (bundled frontend, prototype refused, `VIEWER_*` ignored); uninstall removes files, entry and shortcuts, `docs/evidence/gate5-installation-2026-11-07.md`. Reinstalled for use.
- TODO: clean-machine smoke test; picture, sound and subtitles by eye and ear with the release build.

## Gate TV-1 — Android TV playback feasibility [IN PROGRESS · libmpv on the Shield VERIFIED]

Separate native app in `android-tv/` (Kotlin, libmpv from mpv-android, ADR 0038), for the TV where the official Emby app shows ASS subtitles minutes late.

- VERIFIED (2026-11-09) on the NVIDIA Shield (Android 11), `docs/evidence/gate-tv1-2026-11-09.md`: HEVC 1080p 10-bit with ASS and HEVC 4K HDR10 with AC3 5.1, hardware decoding (`mediacodec`), no decoder drops, French subtitles picked by default, ASS on the picture at the next line, seek, pause, track switching, token in a header only, teardown 130–309 ms.
- TODO: HDR output to the TV by eye; Dolby Vision, DTS and bitstream audio; AudioTrack refusing AAC 44.1 kHz stereo (OpenSL ES fallback plays); cache wait when switching subtitles on 4K; HTTPS (`tls-ca-file`); Fire TV and Nokia.
- VERIFIED (2026-11-09) against the real server, `docs/evidence/tv-client-2026-11-09.md` (ADR 0039): sign-in, token in the Android Keystore, home with posters (latest additions per film and series library), film played through PlaybackInfo (Direct Play, hardware decoding), token absent from logcat; series page with seasons and episodes, episode playback, home focus kept on return; playback reporting (ADR 0040): resume row, resume start, "Vu" after the end, screens updated in place; film details page with "Reprendre à …" / "Lire depuis le début"; player controls (ADR 0041): title, progress, time, pause, ±10 s, audio and subtitle menu; last audio/subtitle choice remembered per user, subtitles moved above the controls (ADR 0042); next episode (ADR 0043): card at the natural end with a 10 s countdown, "Lire maintenant" and "Annuler", next episode played in the same player; library grid (ADR 0044): "Tout voir" at the end of each latest-additions shelf, all films or series by title, paged by 60 as it scrolls; sort and filters in that grid (ADR 0045): title, date added, year or rating, watch state, favorites and one genre, kept per library; poster marks (ADR 0046): a check once watched, the episodes left on a series, the progress bar on a started film; season pills (ADR 0049): episodes left in the accent colour or a check, the first season with episodes left opened by default, counts read again after a playback.
- TODO: track choice from the Emby account preferences; resending a stop report lost to a network cut; Transcode with external subtitles and resume start on the real server; sign-out checked on the device.

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

- **Changed**: `EmbyClient::item/seasons/episodes`; `EmbyService::item_detail/seasons/episodes` (server paths never deserialized, episodes of another series rejected, in-season specials kept); commands `emby_item_detail`, `emby_seasons`, `emby_episodes`; poster cards are buttons; detail page over the grid (grid kept mounted and `inert`), season switcher, episodes paged by 50; shared `languageName` on `Intl.DisplayNames` (also used by the player prototype); generic pager.
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
- **Findings**: Emby burns the forced subtitle in by default and ffmpeg fails on this server (no fontconfig fonts); HLS segments are served without authentication (only the `PlaySessionId` guards them); the first access to an external subtitle can take about a minute (extraction).
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
- **Tests**: `npm run verify` green (Vitest 67/67, cargo test 74 passed / 10 ignored, clippy, svelte-check); `gate21-session` 44/44 on the real server through the proxy; `gate2-ui` passed. Evidence: `docs/evidence/gate2-1-session-2026-10-28.md`.
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
- **Environment**: the server moved to `http://192.0.2.14:8096` (Emby 4.10.2.0, same server id); runs use `VIEWER_EMBY_URL`.
- **Not verified**: a film or the last episode at EOF (no card, closes; not run to avoid marking a film played), a failed launch of the next episode, screen readers, a real mouse over the native video.
- **Next**: search of episodes or artwork cache (Gate 3).

### 2026-11-01 — Artwork disk cache

- **Changed**: ADR 0019; `src-tauri/src/emby/artwork_cache.rs` (`ArtworkCache`: `{serverId}/{itemId}/{Type}-{width}-{tag}.img` in `%LOCALAPPDATA%\io.github.quentinpn.viewer\artwork`, content type from the first bytes, write aside then rename, quota 512 MiB down to 80 % by last use, stale temporary files removed); `EmbyService::image` reads the cache after the session and server checks and keeps what it downloads; command `artwork_cache_stats`; debug-only `VIEWER_E2E_ARTWORK_DIR`; fake server `JFIF:` bodies; scenario `scripts/e2e/gate3-artwork.mjs`.
- **Tests**: `npm run verify` green (Vitest 104/104, cargo test 92 passed / 14 ignored); `gate3-artwork` 7/7; regressions `gate2-ui`, `gate3-home`, `gate3-hero`, `gate3-episode`, `gate21-session` passed. Details in `docs/evidence/gate3-cache-images-2026-11-01.md`.
- **Measured** (12 film posters, real server): 137 ms with an empty cache, 27 ms from the disk; second launch 71 hits, 0 downloads.
- **Not verified**: eviction at the real 512 MiB size (unit-tested with a small quota), images during a server outage with the session open, disk full or read-only cache folder.
- **Next**: search of episodes, or a settings screen (cache size, "Vider le cache").

### 2026-11-01 — Search of episodes

- **Changed**: ADR 0020; `EmbyClient::search` takes `Episode` with the home cards' images; `EmbyService::search` runs three queries and keeps episodes whose own title matches (`title_matches`, word start, case and Latin accents folded); `SearchResults.episodes`/`moreEpisodes`; `WatchCard` gains an "open" mode; `SearchResults.svelte` "Épisodes" section; `LibraryBrowser` opens the episode page, handles previous/next and returns focus to the opening card; `ItemDetail` `backLabel`.
- **Tests**: `npm run verify` green (Vitest 106/106, cargo test 93 passed / 14 ignored); `real_server_search` passed; `gate3-search` 26/26; regressions `gate3-home`, `gate3-episode`, `gate2-ui` passed. Details in `docs/evidence/gate3-recherche-episodes-2026-11-01.md`.
- **Found**: Emby's `SearchTerm` on episodes also matches the series name ("reincarne" returned the Slime series' episodes); no parameter in the server's OpenAPI avoids it.
- **Not verified**: more than 100 episodes matched only by their series before a real result, titles with non-Latin accents, screen readers.
- **Next**: settings screen (cache size, "Vider le cache"), or the Gate 3 remaining items.

### 2026-11-01 — Settings screen: artwork cache

- **Changed**: ADR 0021; `ArtworkCache::clear` (only `.img` files, then empty folders, size lock held), `EmbyService::clear_artwork`, command `artwork_cache_clear`; `embyApi.artworkStats`/`clearArtwork`; `src/lib/settings/Settings.svelte` and `format.ts`; `LibraryBrowser` « Réglages » button (`aria-current`), the search takes over and gives the settings back, home and libraries close them; scenario `scripts/e2e/gate3-settings.mjs`.
- **Tests**: `npm run verify` green (Vitest 111/111, cargo test 94 passed / 14 ignored); `gate3-settings` 16/16; regressions `gate3-search` 26/26, `gate2-ui` 15/15, `gate3-artwork` 7/7, `gate3-home` 22/22 on the second run (first run: `detail_back_focus_on_card` read too early, not related). Details in `docs/evidence/gate3-reglages-cache-2026-11-01.md`.
- **Found**: a `disabled` button loses the keyboard focus while it works; the clear button uses `aria-disabled` while emptying.
- **Not verified**: images still held by the WebView after clearing (until the next launch), clearing while images download, screen readers.
- **Next**: Gate 3 remaining items, or a metadata cache (SQLite) for offline browsing.

### 2026-11-01 — Startup baseline (metadata cache decision)

- **Changed**: scenario `scripts/e2e/measure-startup.mjs` (three launches with the saved session, five calls of each home and grid command); no app code.
- **Measured** (debug build, LAN): home complete ≈ 670 ms after process start, ≈ 440 ms of it before the page runs; commands 9–56 ms median. Details in `docs/evidence/mesure-demarrage-2026-11-01.md`.
- **Decided**: no SQLite metadata cache for now — it could save only ≈ 100 ms on the LAN, for a dependency, a schema, migrations and invalidation. Reconsider for remote access or if offline metadata (post-MVP in PRODUCT.md) is asked for.
- **Next**: Gate 4 — network interruptions and unsupported format fallback on the real server.

### 2026-11-06 — Stream reconnection and stall limit

- **Changed**: ADR 0022; `curl-enabled=no`, `stream-lavf-o` reconnect options and `network-timeout=15` in `MpvEngine`; pure `StallWatch` in `src-tauri/src/player/mod.rs`, fed by the event thread: a loaded, unpaused file whose position stays frozen 30 s ends as `EndFile { reason: Error }` and mpv stops retrying; scenario `scripts/e2e/gate4-network.mjs`.
- **Tests**: `gate4-network` 4/4, `gate21-session` 44/44, `gate2-play` 13/13, `gate3-player` 34/34, `gate2-transcode` 10/10 (HLS still plays with `curl-enabled=no`), `npm run verify` exit 0 (`cargo test` 98 passed, 14 ignored). Details in `docs/evidence/gate4-reseau-2026-11-06.md`.
- **Found**: with FFmpeg's reconnection, a dead stream never reaches an end of file, so the UI had no signal; the limit lives in the engine, independently of any UI.
- **Not verified**: a cut during an HLS transcode, a real network cut (the local proxy only), a very slow transcode close to the limit.
- **Decided**: stay on Tauri; a native egui rewrite was considered and dropped by the user (2026-11-06), no measured problem justifies it.
- **Next**: Gate 4 remaining items.

### 2026-11-06 — Transcode fallback

- **Changed**: ADR 0023; `prepare_transcode` asks `PlaybackInfo` with `EnableDirectPlay` and `EnableDirectStream` false, and refuses a server that still offers the file; `emby_play` takes `transcodeFallback` and returns `resume`; `PlayerView` retries once as a transcode when a direct source ends in error before any position; French message for a failed open, mpv's text as a detail; scenario `scripts/e2e/gate4-fallback.mjs`, proxy modes `junk-direct` and `junk-all`.
- **Tests**: `gate4-fallback` 14/14, `gate21-session` 44/44, `gate4-network` 4/4, `gate2-play` 13/13, `gate3-player` 34/34, `gate2-transcode` 10/10, `npm run verify` exit 0 (`cargo test` 100 passed, 14 ignored). Details in `docs/evidence/gate4-repli-2026-11-06.md`.
- **Found**: `active` was true as soon as the engine answered, so a failed open could pass for an interruption; `fileLoaded` comes before decoding, so only a position proves the file played. The fallback must also keep "Lire depuis le début", hence `resume` in `PlaybackStart`.
- **Not verified**: a real file mpv cannot read (unreadable bytes from the proxy only), a file whose video alone is unreadable (probably plays audio with no fallback), an error between `fileLoaded` and the first position, the next-episode path (`gate3-upnext` not rerun, it marks items played).
- **Next**: Gate 4 remaining items: a cut during an HLS transcode, security review, performance measurements.

### 2026-11-06 — Cuts during an HLS transcode

- **Changed**: scenario `scripts/e2e/gate4-hls-cut.mjs`; ADR 0022 consequences. No application code.
- **Tests**: `gate4-hls-cut` 11/11 (run `gate4-hls-cut-20261106-122730`), Biome on the scenario. Details in `docs/evidence/gate4-coupure-hls-2026-11-06.md`.
- **Found**: nothing is skipped after an 8 s cut (largest position step 0.5 s). A long cut is announced late because mpv reads ahead of a transcode: 385.7 s of reserve, announced after 419.5 s. Resume goes back to the last progress received: 610.0 s saved for 758.3 s played; the stop report is lost during the cut. Not fixed. No resume point at about 100 s; a server threshold is likely, not checked.
- **Not verified**: the server encoder when the stop report never arrives, a real network cut, `blackhole` during a transcode, a very slow transcode close to the limit.
- **Next**: send the last position once the connection is back, then the security review and performance measurements.

### 2026-11-06 — Lost stop report sent again after a cut

- **Changed**: ADR 0024; `reporting.rs` keeps a stop report lost to a passing failure and sends it again after 5 s, 10 s, then every 15 s, for at most 1 h, as a progress report then the stop; `session.rs` sends nothing if the session changed; `emby_play` asks for a resend first and waits at most 5 s, and a new playback drops what is left. The stop body now carries `PlayMethod`. Scenario `gate4-hls-cut` requires the position played.
- **Tests**: `gate4-hls-cut` 11/11 (run `gate4-hls-cut-20261106-130845`), `gate21-session` 44/44, `gate4-network` 4/4, `gate2-play` 13/13, `npm run verify` exit 0 (Vitest 113, `cargo test` 104 passed, 14 ignored), Biome on the scenario. Details in `docs/evidence/gate4-renvoi-arret-2026-11-06.md`.
- **Found**: Emby ignores the position of a stop with `Failed=true`, and any stop once it closed the playback itself, after about 60 s of silence. A late progress report saves the position and reopens the playback; the stop closes it. Measured: 820.0 s saved for 820.6 s played, 2.1 s after the network came back. A progress report at 97% marks the item watched.
- **Not verified**: on the real server, the resend before a new playback, the last try when the window closes, giving up after 1 h. Why real runs kept 610.0 s when direct requests show an extrapolated 670 s. A playback on another device during the cut. A real network cut.
- **Next**: security review, then performance measurements.

### 2026-11-06 — Security review

- **Changed**: ADR 0025; `mpv.rs` adds `max_redirects=0`, a protocol whitelist for FFmpeg's own opens and an explicit `tls-verify=yes`, and redacts `apikey=`; `player_open` refuses outside development builds and the prototype button is hidden there; libmpv loads only from beside the executable in release; `VIEWER_VIDEO_MODE` is ignored in release; the CSP gains `object-src`, `base-uri` and `form-action`.
- **Tests**: two new tests against real libmpv, both failing without the options; `gate2-play` 13/13, `gate2-transcode` 10/10, `gate3-player` 34/34 (first run failed 1/2, second run unchanged passed; the scenario read the library list without waiting is the probable cause, not proven), `gate4-fallback` 14/14, `gate4-network` 4/4, `gate21-session` 44/44, `npm run verify` exit 0 (Vitest 113, `cargo test` 106 passed, 14 ignored), `cargo clippy --release --all-targets` no warning. Details in `docs/evidence/gate4-securite-2026-11-06.md`.
- **Found**: a server HLS playlist could read and play a local file; the token followed a redirect to another host; a segment on another host still receives the token (accepted, ADR 0025); `access-references=no` blocks HLS; HTTPS streams are refused even with a valid public certificate; no logger is installed, so mpv and `log` messages are lost.
- **Not verified**: `cargo audit` (not installed, installing it was refused by the session's protection); mpv/FFmpeg licensing (ADR 0002, blocks Gate 5); release-only behaviors at run time; HTTPS against a real Emby server; an item Emby serves through a redirect.
- **Next**: HTTPS playback through the Windows certificate store, then performance measurements.

### 2026-11-06 — Emby runs on a mini-PC

- **Changed**: documents, comments and test names said "the NAS" for the Emby server. Emby runs on a mini-PC, which the app connects to; the QNAP NAS only stores the media files. Measurements recorded as "on the NAS" (encoding, poster times) were taken against the mini-PC. No behavior change.

### 2026-11-06 — HTTPS in the player

- **Changed**: ADR 0026; `player/tls_roots.rs` exports the Windows ROOT store (server authentication, not expired, not in `Disallowed`) to a PEM file in the cache folder before each HTTPS load, and `MpvEngine::load` gives it to `tls-ca-file`. A failed export loads nothing. `schannel` and `base64` become direct dependencies; both were already compiled.
- **Tests**: 5 new (Vitest 113, `cargo test` 111 passed, 14 ignored), `npm run verify` and `cargo clippy --release --all-targets` clean; probes with real libmpv: public certificate accepted; untrusted, wrong name, expired, self-signed and no roots refused; media and HLS over HTTPS with a test authority; accented folder. `gate2-play` 13/13; `gate2-transcode` failed once on the "Sous-titres" click (cause not established), then 10/10 unchanged.
- **Not verified**: a real HTTPS Emby server, so the whole path from the UI; a root Windows downloads on demand; a root in `Disallowed`; revocation is not checked by the player.
- **Next**: performance measurements, the last Gate 4 item.

### 2026-11-06 — Performance baselines

- **Changed**: scenario `scripts/e2e/measure-gate4.mjs`; `launch()` waits for the CDP port of the previous WebView2 to close. No app code.
- **Tests**: `measure-gate4` ran to its end on the real server (run `measure-gate4-20261106-183307`), after a first run stopped by that port. Temporary probes: 3 + 1 more tries of the film that fails, 15 play and close cycles for memory, direct reads of the stream outside the app. The scenario now fails when a film does not start; that one-word change was not rerun.
- **Not verified**: release build, clean machine, visible window, long 4K or HDR sessions, access from outside the network. Why mpv ends no file on that HTTP 500 is not established.
- **Next**: an opening deadline, so a stream in error shows a message. The film itself needs the server's mount looked at, by its owner.

### 2026-11-06 — Opening limit

- **Changed**: ADR 0027; `StallWatch` also bounds the time between `MPV_EVENT_START_FILE` and `FileLoaded` to 45 s; any end in error sends `stop` before the event, so mpv does not walk a failed HLS list as a playlist; `PlayerView` is active only once mpv has opened a container; proxy modes `hold-direct` and `error-files`; two more steps in `gate4-fallback`.
- **Tests**: `npm run verify` exit 0 (Vitest 113, `cargo test` 117 passed, 14 ignored); the playlist test failed before its fix; `gate4-fallback` 20/20, `gate4-network`, `gate3-player`, `gate2-play` all passed. Details in `docs/evidence/gate4-ouverture-2026-11-06.md`.
- **Found**: the performance evidence blamed Direct Play for that endless wait. A trace shows the direct 500 ends in 20 ms and the fallback starts; the wait came from the transcode, whose segments all failed.
- **Not verified**: the final code on the real failing film: it left the library during this work (id 404, not found by search), cause not established. Release build, visible window, a 4K transcode slower than 45 s, a NAS waking up, a segment failing after playback started.
- **Next**: Gate 4's remaining NOT VERIFIED items, or the licence decision that blocks Gate 5.

### 2026-11-06 — Dependency audit

- **Changed**: `plist` 1.10.3 brings `quick-xml` 0.42.0 and `time` 0.3.55, which fix 3 advisories; `rust-version` goes from 1.85 to 1.88 because they require it; clippy then asks for let chains, so two nested `if` were collapsed, with no behavior change.
- **Tests**: `cargo audit` 3 vulnerabilities before, 0 after, 7 warnings; `npm run verify` exit 0 (Vitest 113, `cargo test` 117 passed, 14 ignored); debug build; `gate2-play` passed on the real server. Details in `docs/evidence/gate4-audit-dependances-2026-11-06.md`.
- **Found**: 5 unmaintained `unic-*` crates remain in the Windows build, pinned through `urlpattern` by `tauri-utils`, so they wait for a Tauri update.
- **Not verified**: the release build; `npm audit`; the libmpv DLL, which RustSec does not cover. The audit is not in `npm run verify` or CI, so a new advisory is seen only by rerunning it.
- **Next**: what the server encoder does when the stop report never arrives.

### 2026-11-06 — Server encoder without a stop report

- **Changed**: new on-demand test `real_server_encoder_without_stop_report`. No app code: the server cleans up by itself.
- **Tests**: 4 tries on the real server (film "2 Coeurs", 3 Mbit/s, test account): `TranscodingInfo` gone for good after 50, 60, 70 and 70 s, within 10 s, and "now playing" gone at 60 to 70 s; tries 3 and 4 watched 120 s more with no return. `npm run verify` exit 0 (Vitest 113, `cargo test` 117 passed, 15 ignored).
- **Found**: `TranscodingInfo` can vanish then return, from 20 s on, cause not established, so the first absence does not mean the end. The test's first version reported 20 s for that reason; it now waits for a stable absence.
- **Not verified**: the ffmpeg process itself, a client that played for long before going silent, a long pause, a real network cut, another film or Emby version.
- **Next**: a long pause during a transcode, or the real network cut with the owner at the PC.

### 2026-11-06 — Long pause during a transcode

- **Changed**: new scenario `scripts/e2e/gate4-pause.mjs`; the local proxy gets a `log-segments` mode that notes each HLS segment's number, time and status, with no URL. No app code.
- **Tests**: one run on the real server (3 Mbit/s, test account), all checks passed: paused at 5.1 s for 5 min, 132 segments read ahead then none for 266 s; resumed to 426.2 s past the 396 s limit, largest step 1.2 s, 142 segments served after the resume, none refused; seek after a second pause landed at 1628.0 s in 2.3 s; no resume point left. Details in `docs/evidence/gate4-pause-longue-2026-11-06.md`.
- **Found**: unlike a silent client, the encoding stays present during a pause, cause not established.
- **Not verified**: a 30 min pause (started, stopped at the owner's request, judged low stake); the mini-PC's load; the committed scenario after its last edits (a check renamed, periodic encoder samples added).
- **Next**: Gate 5, release build and installer.

### 2026-11-07 — Release build and installer

- **Changed**: ADR 0028; bundling turned on in `tauri.conf.json`: NSIS, per-user install, French, `libmpv-2.dll` copied beside the executable. `@tauri-apps/api` pinned to 2.11.1: the CLI refused to build with 2.12.4 against the `tauri` 2.11.6 crate, and `tauri` 2.12 needs Rust 1.90.
- **Tests**: `./scripts/with-msvc.ps1 npx tauri build` exit 0, installer 35.63 MiB, SHA-256 `6ad4aea9…6966c`; the generated NSIS script installs `libmpv-2.dll` into the install folder and removes it on uninstall; `npm run verify:web` exit 0 (Vitest 113).
- **Not verified**: installing, launching or playing with the release build; uninstalling; a clean machine; `verify:rust` after the change (no Rust code changed).
- **Next**: install on the owner's PC and run the release smoke test.

### 2026-11-07 — Release smoke test on the owner's PC

- **Changed**: scenario `scripts/e2e/gate5-release.mjs`; `launch()` in `scripts/e2e/app.mjs` takes an `exe`. No app code.
- **Tests**: silent install and uninstall, exit 0; `gate5-release` all passed on the installed build: frontend from `tauri.localhost`, window on screen, prototype refused, bitrate cap ignored ("Lecture directe · MKV"), "2 Coeurs" played (first picture after 5.1 s), seek to 600 s in 1.3 s, no resume left, exit 0 with no process left. Uninstall removed the folder, the entry and both shortcuts; reinstalled. Details in `docs/evidence/gate5-installation-2026-11-07.md`.
- **Not verified**: a clean machine (WebView2 present, data shared with the debug build); picture, sound and subtitles by eye and ear; SmartScreen; transcoding, cuts and series with the release build.
- **Next**: the owner uses the installed app; a clean-machine test if one is available.

### 2026-11-07 — App icon and custom title bar

- **Changed**: own icon (`src-tauri/icons/source/logo.svg`, every Windows icon regenerated with `npx tauri icon`) in place of Tauri's. ADR 0029: no Windows title bar; `TitleBar.svelte` with drag area and Windows' caption buttons, inside the player's top gradient while playing, none in fullscreen; six window permissions for the main window.
- **Tests**: `npm run verify:web` exit 0 (Vitest 113); debug build; `gate2-play` all passed, the bar shows over the video; `gate2-ui` passed except `no_app_credential_left`: an app credential that this run did not create remains, probably from the 30 min pause run stopped yesterday, left as is.
- **Not verified**: dragging, double-click, resizing from the edges and snapping with a real mouse; the icon in the taskbar and installer (release not rebuilt).
- **Next**: Apple TV style mockups.
- **Reinstalled** (2026-11-07): release rebuilt with the new icon and title bar, installed over the old one; `gate5-release` all passed; icon of the installed exe checked; title bar in the installed window not checked by eye.
- **Correction** (2026-11-07): those installs ran inside Claude Desktop's MSIX container and landed in its LocalCache; that copy is uninstalled and the owner installs the release themself.

### 2026-11-07 — Skip intro and credits

- **Changed**: ADR 0030; `src-tauri/src/segments/` (`SegmentService`, `SegmentProvider` for IntroDB and TheIntroDB, Emby markers) and tests; `BaseItemDto` reads `ProviderIds` and `Chapters`; `EmbyService::segment_query`; command `emby_segments`; `src/lib/player/segments.ts` and tests; button and Entrée in `PlayerView.svelte`; Réglages › Lecture; scenario `scripts/e2e/gate3-skip.mjs`. No new dependency or capability.
- **Tests**: cargo fmt, clippy `-D warnings`, cargo test 127 passed / 15 ignored; `npm run verify:web` exit 0 (Vitest 123); debug build; `gate3-skip` 12/12 on an episode with IntroDB data. Details in `docs/evidence/intros-generiques-2026-11-07.md`.
- **Not verified**: TheIntroDB in the real player (no looked up episode had data there); the « Épisode suivant » skip and credits skipped to the end (they would mark the episode played); Emby markers on a real server (none on the test server); the video by eye.
- **Next**: the owner tries it on a few series; submitting segments to the services is out of scope.

### 2026-11-07 — Redesign: navigation pill and home (ADR 0031)

- **Changed**: theme tokens of the Apple TV direction (previous commit). The sidebar of `LibraryBrowser.svelte` gives way to a floating pill (search, Accueil, libraries, Réglages, account menu as a `popover`), hidden on detail pages; Ctrl+1 for Accueil and Ctrl+2… for the libraries; Ctrl+F also from a detail page. Home: edge-to-edge hero and rows, white and glass buttons, lift on hover and focus (`WatchCard`, `PosterCard`). Narrow window: the search shrinks to its icon, the hero arrows are hidden. Scenarios open the account menu through `accountAction()` in `scripts/e2e/scenario.mjs`.
- **Tests**: `npm run verify:web` exit 0 (Vitest 123); scenarios on the debug build: `gate3-home` (one run of five lost the focus on the card after the detail closed, the next ones passed), `gate3-hero`, `gate3-search`, `gate3-settings`, `gate21-session`, `gate3-sort-filter`, `gate3-favorites` all passed; `gate2-ui` passed except `no_app_credential_left` (an app credential this run did not create, probably the owner's installed app, left as is). A screenshot run checked the lift (×1.06, none with reduced motion), the hidden nav and Ctrl+F on a detail page, Ctrl+1, and no overflow of the page or the pill at 684 px, search focused or not. It found two defects, fixed: the account menu let clicks through (it inherited `pointer-events: none`), and the hero logo slid under the nav in a 720 px high window.
- **Not verified**: Ctrl+2… by a scenario; the nav and the lift by the owner's eye; the account menu with a screen reader. The grid toolbar, the detail pages, the player and the sign-in keep their old layout with the new tokens.
- **Next**: library grid toolbar, then film detail.

### 2026-11-07 — Redesign: library grid toolbar (ADR 0031)

- **Changed**: `LibraryBrowser.svelte` styles only: 44 px title, glass native selects with a chevron, glass "Favoris" toggle (white when pressed), white "Réinitialiser les filtres", grid gaps 30/24 px.
- **Tests**: `npm run verify:web` exit 0 (Vitest 123); `gate3-sort-filter` all passed, its screenshots checked (defaults, no match, genre and favorites, keyboard focus on "Trier par").
- **Not verified**: the open native list (drawn by Windows, dark through `color-scheme`) by eye; a narrow window.
- **Next**: film detail.

### 2026-11-07 — Redesign: film and episode detail (ADR 0031)

- **Changed**: `ItemDetail.svelte` styles and back button: round glass icon button "Retour" (tooltip "Retour (Échap)"), hero 72 % of the window height (420 to 760 px) with black scrims, poster only without a backdrop, 64 px title, logo bounded by the height, glass genre and status pills, white "Lecture" and progress bar, glass "Favori" and adjacent-episode cards, technical info in a material panel. `scripts/e2e/scenario.mjs`: `accountAction` closes detail pages first, since the navigation is hidden there.
- **Tests**: `npm run verify:web` exit 0; `gate3-episode`, `gate3-favorites` and `gate4-fallback` all passed (the first fallback run lost the server transcode once, the rerun passed); `gate2-ui` passed except the known `no_app_credential_left` (credential of the installed app). Screenshots checked: film, series, episode, narrow film.
- **Not verified**: 125 % and 150 % scaling.
- **Next**: series page and episode list.

### 2026-11-07 — Redesign: series episode list (ADR 0031)

- **Changed**: `EpisodeList.svelte` styles: seasons as pills (white with black text when selected, transparent otherwise), episodes as a grid of 16:9 cards (thumbnail above "Épisode N · durée · date", title and three lines of overview), thumbnail lift on hover and focus (×1.04, none under reduced motion), white progress bar inside the thumbnail, dark translucent "vu" check, glass "Afficher plus d'épisodes".
- **Tests**: `npm run verify:web` exit 0; `gate3-episode` all passed; `gate2-ui` passed except the known `no_app_credential_left`. Screenshots checked: second season, back from an episode (focus ring on the card).
- **Not verified**: 125 % and 150 % scaling.
- **Next**: player.

### 2026-11-07 — Redesign: player controls (ADR 0031)

- **Changed**: `PlayerView.svelte`: the buttons are three groups (volume on the left, ±10 s around a 72 px white play/pause in the middle, panels on the right), the seek bar runs alone with the times below; white tracks, glass back button, frosted panels with the selected row tinted, white "Passer l'intro" and "Lire maintenant", frosted next-episode card and "Épisode suivant" skip button.
- **Tests**: `npm run verify:web` exit 0; `gate3-player`, `gate3-upnext` and `gate3-skip` all passed. Screenshots checked: film and episode controls, subtitles panel, intro button, next-episode card (the capture shows the WebView only, so the video area is white).
- **Not verified**: the frosted panels over real video by eye (blur over the native surface), fullscreen, 125 % and 150 % scaling.
- **Next**: sign-in and connection lost.

### 2026-11-07 — Redesign: sign-in and connection lost (ADR 0031)

- **Changed**: new `Logo.svelte` (inline app logo, unique gradient ids); `SignIn.svelte` and `ConnectionLost.svelte` are frosted cards with the logo, centered title ("Bienvenue" / "Connexion à Emby"), white primary and glass secondary buttons, tinted error box; compact layout below 860 px of height; the overlays (reauthentication, connection lost mid-session) clear the title bar and blur the app underneath.
- **Tests**: `npm run verify:web` exit 0 (123 tests); `gate21-session` all passed; `gate2-ui` passed everything except the known `no_app_credential_left` (leftover credential of the installed app, not touched). Screenshots checked at 1280 × 720: address step, saved sessions, reauthentication overlay (fits, "Changer de serveur" on one line).
- **Not verified**: 125 % and 150 % scaling.
- **Next**: splash and interrupted-playback banner.

### 2026-11-07 — Redesign: splash and interrupted-playback banner (ADR 0031)

- **Changed**: `App.svelte`: the splash shows the 88 px logo over the same dark radial backdrop as sign-in, with a white sweeping bar; the "Lecture interrompue." banner is a frosted card with pill buttons ("Reprendre" white, "Fermer" glass) and a visible focus ring. `gate21-session` now captures the splash while the server hangs.
- **Tests**: `npm run verify:web` exit 0 (123 tests); `gate21-session` all passed twice. Screenshots checked at 1280 × 720: splash, banner over the film detail.
- **Not verified**: 125 % and 150 % scaling.
- **Next**: the remaining hard-coded `rgb(11 13 17)` scrims.

### 2026-11-07 — Redesign: last hard-coded scrims (ADR 0031)

- **Changed**: `PlayerSpike.svelte` (Gate 1 prototype, debug builds only): the floating message and the badges use `--material-thick` / `--material` with `--material-blur` instead of `rgb(11 13 17)`. No `rgb(11 13 17)` is left in `src/`.
- **Tests**: `npm run verify:web` exit 0 (123 tests).
- **Not verified**: the prototype by eye (`run-gate1.ps1` needs a local media file; not run).
- **Next**: check every redesigned screen at 125 % and 150 % scaling.

### 2026-11-07 — Redesign: display scaling check (ADR 0031)

- **Added**: `scripts/e2e/gate3-scaling.mjs`, the redesigned screens at the CSS sizes of 125 % and 150 % scaling (window resized, device scale factor set over CDP).
- **Tests**: `gate3-scaling` all passed (no horizontal overflow on 21 screens). Screenshots checked: at 853 × 480 the navigation pill, the toolbar, the detail heroes, the episode grid (two columns), the settings and the sign-in card all fit; nothing needed changing.
- **Not verified**: a real 125 % or 150 % Windows setting (font rasterization, native frame); the player and the connection-lost screens at those sizes; a library list long enough to overflow the navigation pill at 853 px.
- **Next**: the player and connection-lost screens at those sizes.

### 2026-11-07 — Redesign: player and connection lost at 150 % scaling (ADR 0031)

- **Changed**: `gate3-scaling` also covers the player with its subtitles panel (one short playback per size, rewound to 0) and the connection-lost overlay (network cut through `net-proxy.mjs`), with checks for the panel below the title bar and the actions on screen.
- **Fixed**: at 853 × 480 the subtitles panel went under the title bar (`max-height` now also bounded by `100vh − 240px`); the connection-lost card overflowed so its buttons were off screen (37 px at 125 %, 133 px at 150 %): compact layout below 600 px of height, and `place-items: safe center` on the sign-in and connection-lost screens so a tall card scrolls instead of being cut at the top.
- **Tests**: `gate3-scaling` all passed (panel top 120 / 63 / 264 px, actions 121 / 25 / 99 px above the bottom); screenshots checked. `npm run verify:web` exit 0 (123 tests).
- **Not verified**: a real 125 % or 150 % Windows setting; a library list long enough to overflow the navigation pill at 853 px.
- **Next**: the navigation pill with many libraries.

### 2026-11-07 — Redesign: navigation pill with many libraries (ADR 0031)

- **Fixed**: with more libraries than fit, the whole pill scrolled with a hidden scrollbar: "Réglages" and the account went out of view and the mouse wheel could not bring them back. Only the library list scrolls now; the wheel scrolls it sideways, its edges fade where libraries are hidden, and the current library is scrolled into view (`LibraryBrowser.svelte`).
- **Added**: proxy mode "many-views" in `net-proxy.mjs` (library list answered with each library four times, server only read) and `scripts/e2e/gate3-nav-many.mjs`; `setScaledSize` moved to `scenario.mjs`, shared with `gate3-scaling`.
- **Tests**: `gate3-nav-many` failed before the fix (Réglages, account and last library out of reach at both sizes) and all passed after it, 16 libraries; screenshots checked. `gate3-scaling` all passed again. `npm run verify:web` exit 0 (123 tests).
- **Not verified**: a touchpad's horizontal swipe on the list (native, not driven); a real 125 % or 150 % Windows setting.
- **Next**: the Svelte dev warning in `Home.svelte`.

### 2026-11-07 — Home: Svelte dev warning on the "Ajoutés récemment" rows

- **Fixed**: `Home.svelte` stored each library's latest row with an arrow returning the assignment to a `$state` property, which Svelte flags in dev (`assignment_value_stale`, once per library on each home load). The callback has a block body now; behavior unchanged.
- **Tests**: `gate3-nav-many` all passed, the rows show; the Vite log has no new warning (the previous run of the same scenario logged 16). `npm run verify:web` exit 0 (123 tests).
- **Next**: the Vite dev server serving old CSS.

### 2026-11-07 — E2E: guard against old CSS from the Vite dev server

- **Problem**: twice this session the dev server served a component's new markup with its old CSS; scenarios then tested the old layout, and a restart of `npm run dev` fixed it.
- **Investigated**: a single edit and five writes 50 ms apart to `LibraryBrowser.svelte` were served at once; one listener on port 1420. Cause not found.
- **Added**: `scripts/e2e/dev-styles.mjs`, called by `launch()` for the debug build: the page fetches each component's style module and every one-line declaration of the source `<style>` must be in it (`animation` left out, Svelte renames scoped keyframes). Otherwise the launch stops and names the components.
- **Tests**: no false positive on the current tree (launch 523 ms including the check); a style module served with one declaration removed (Playwright route) is reported; `gate3-nav-many` all passed through it. `npm run verify:web` exit 0 (123 tests).
- **Not verified**: the check on a real occurrence (not reproduced). Multi-line declarations and `animation` are not compared.
- **Next**: fullscreen from a maximized window (below).

### 2026-11-07 — Fix: fullscreen from a maximized window left the taskbar visible

- **Reported** by the user on the installed build: fullscreen showed the Windows taskbar.
- **Cause** (measured, 2560×1440 monitor, work area 1400 px high): from a maximized window, tao kept `WS_MAXIMIZE` in fullscreen. The window covered the monitor, but the client area stayed 2560×1400 (`innerHeight` 1400). The 40 px strip at the bottom of the transparent window showed the taskbar. From a restored window the client area was already 2560×1440.
- **Changed**: `window_set_fullscreen` restores a maximized window before going fullscreen, and maximizes it again on the way out.
- **Tests**: `gate3-player` now checks that fullscreen covers the screen (`innerWidth/innerHeight` = `screen`) from a restored and from a maximized window, and that the window is maximized again afterwards: all passed against the real server. `npm run verify:web` exit 0; `verify:rust` 127 tests passed.
- **Not verified**: the installed release by eye. Windows hides the taskbar only while the fullscreen window is in the foreground; a background fullscreen window stays under it, as expected.
- **Next**: version numbers (below).

### 2026-11-07 — Version numbers and bumps (ADR 0032)

- **Asked** by the user: every build stayed 0.1.0.
- **Added**: SemVer in 0.x until the `PRODUCT.md` scope is complete and verified. `package.json` is the single source; `tauri.conf.json` reads it (`"version": "../package.json"`). `npm run release -- <patch|minor|major|X.Y.Z>` (`scripts/release.ts`, logic in `src/release/version.ts`) refuses a dirty tree or a version that does not go up, writes the four version files, adds a dated `CHANGELOG.md` section from the commits since the last tag, commits « Release vX.Y.Z » and tags it, without pushing. Réglages › « À propos » shows the version from `app_info`.
- **History**: tags set afterwards on the builds already installed: `v0.1.0` (6d3450d), `v0.2.0` (c844da3), `v0.3.0` (0c0b004); the fullscreen fix ships as `v0.3.1`. `CHANGELOG.md` sums up 0.1.0 to 0.3.0 by hand.
- **Tests**: `src/release/version.test.ts` (9 tests: bumps, explicit versions, numeric compare, Cargo.toml, Cargo.lock with CRLF, package-lock root only, changelog order, the four files equal, Tauri reading package.json); `npm run verify:web` exit 0 (132 tests); `verify:rust` exit 0 (127 tests); debug build with the new `tauri.conf.json`; `gate3-settings` all passed with `version_shown`.
- **Not verified**: the NSIS upgrade from 0.1.0 to 0.3.1 over the installed build (the owner runs it).
- **Next**: the 1.0 work (below), in parallel sessions.

### 2026-11-07 — Watched state at the end of a film

- **Added**: `scripts/e2e/gate4-watched.mjs`. An unwatched film without a resume point is played to its natural end, so the player closes by itself. The server then marks it watched from the stop report alone (the app never calls `PlayedItems`): no resume point is left, and the detail page shows « Déjà vu ». The film is marked unplayed again at the end (`real_server_mark_unplayed`).
- **Tests**: all passed on the real server with « 2 Hearts » (6103 s), which was marked unplayed again. The episode case was already checked by `gate3-upnext` (`first_episode_played`).
- **Server side effects** (test account): one film marked watched, then unwatched.
- **Next**: to be chosen.

### 2026-11-07 — Crash diagnostics without secrets (ADR 0033)

- **Problem**: `log::` calls existed but no logger was installed, in debug or release; a panic left no trace outside the console.
- **Added**: `src-tauri/src/diagnostics.rs`, a `log` backend with no new dependency. It writes `viewer.log` in `app_log_dir` (`%LOCALAPPDATA%\io.github.quentinpn.viewer\logs`), rotated at 2 MiB over 3 files, with a record cut at 64 KiB. It logs info in release and debug in debug builds (plus stderr); dependencies log warnings only. Every line is redacted: `api_key`, tokens, passwords, `Pw`, `Authorization`/`X-Emby-Authorization` to the end of the line, URL `user:password@`. `mpv.rs` now uses the same function. A panic hook writes the message, location, thread and backtrace before the previous hook. Réglages › « Diagnostics » has « Ouvrir le dossier des journaux » (`diagnostics_open_log_dir`, no argument, `%SystemRoot%\explorer.exe`, no capability added). Debug-only `VIEWER_E2E_LOG_DIR` and `VIEWER_E2E_PANIC` serve the scenario.
- **Tests**: `cargo test diagnostics` 11 tests; `npm run verify:web` exit 0 (134 tests, `diagnostics.test.ts`); `verify:rust` exit 0 (138 passed). `gate3-settings` all passed against the real server, with `diagnostics_shown`, `open_logs_focus_visible`, `log_started`, `log_panic_recorded` and `log_has_no_secret`.
- **Not verified**: the button opening the explorer (not clicked, it opens a window on the desktop); the release build's log level and its backtrace without symbols (`strip = true`).
- **Next**: to be chosen.

### 2026-11-07 — Chapters in the player (ADR 0034)

- **Asked**: chapter navigation (`PRODUCT.md` P1).
- **Checked** read-only on the test server (`docs/evidence/chapitres-2026-11-07.md`): the item's `Chapters` come without `Fields`; 398/400 films and 397/400 episodes have chapters; none has an `ImageTag` (chapter image extraction is off), and `PlaybackInfo` gives `"Chapters": []`.
- **Added**: `emby_chapters` (Emby is the source of truth; plain chapters only, the intro and credits markers stay with ADR 0030); marks on the seek bar, the playing chapter's name under it and in `aria-valuetext`; a « Chapitres » button and panel (`ChaptersPanel.svelte`); Page Up/Page Down for previous/next chapter; `chapterCount` from mpv in the diagnostics.
- **Tests**: Rust `emby::chapters` and `chapters_come_from_the_item_in_seconds`; Vitest `chapters.test.ts` and the keys in `keyboard.test.ts`; new scenario `gate3-chapters` all passed on film 30207 (Direct Play MKV, 16 chapters at Emby and mpv).
- **Not verified**: chapters during a transcode in the real player, multi-version items (the default version's chapters are used), thumbnails (none on the server).
- **Next**: per-source chapters once `PlaybackStart` carries the chosen media source.

### 2026-11-07 — Choice between versions of a film or episode (ADR 0036)

- **Asked**: `PRODUCT.md` P1 "multi-version selection".
- **Measured** (real server, read only, Emby 4.10.2.0): 11 954 films and episodes scanned, none with more than one `MediaSource`. `openapi.json` gives `MediaSourceId` in the `PlaybackInfoRequest` body; on a single-source film its own id returns that source and an unknown id returns HTTP 200 with no source and `NoCompatibleStream`.
- **Added**: « Version » select on film and episode detail pages when there are two versions or more (server name, resolution, codec, HDR, size or bitrate); `emby_play` takes an optional `media_source_id`, validated in Rust and sent in the PlaybackInfo body; the plan keeps only that source, never another one; reports, the transcode fallback and « Reprendre » follow it. The choice is kept per server, user and item in WebView `localStorage`.
- **Tests**: Rust `playback_of_one_version_names_it_to_the_server_and_in_reports` (fake server) and `the_version_asked_for_is_played_with_the_servers_decision_for_it`; Vitest `src/lib/detail/versions.test.ts` (5 tests). `npm run verify:web` exit 0 (137 tests); `./scripts/with-msvc.ps1 npm run verify:rust` exit 0 (129 passed).
- **Not verified**: no e2e scenario or real playback of a chosen version, since the test server has no multi-version item. Home, hero, Continue Watching and next episode play the server's default version.
- **Next**: an e2e check once a multi-version item exists on the server.

### 2026-11-07 — Streaming quality setting (ADR 0037)

- **Asked**: the maximum streaming bitrate as a user setting (`PRODUCT.md` P1 "remote bitrate preference").
- **Added**: Réglages › « Qualité de lecture » (`PlaybackQuality.svelte`), with two caps, « Réseau local » and « Accès distant »: « Maximale (automatique) », 40, 20, 10, 8, 4, 3 or 1,5 Mbit/s. The cap follows the server address (`ServerUrl::is_local`: private, loopback and link-local IPs, `.local`/`.lan`/`.home.arpa`). It is stored per Emby server id in `playback-quality.json` next to `session.json`, with an atomic write, and only offered values are accepted (`emby::quality`). New commands `emby_playback_quality` and `emby_set_playback_quality`; the cap goes into PlaybackInfo `MaxStreamingBitrate` and the device profile at the next negotiation. Priority: the debug `VIEWER_E2E_MAX_BITRATE`, then the setting, then 400 Mbit/s; release builds still ignore `VIEWER_*`. `PlaybackStart` carries `bitrateCap` and `localAddress`; the player info panel shows « Débit maximal ».
- **Tests**: Rust: 5 `quality` unit tests and 2 service tests (cap sent for a local address, remote cap ignored there, invalid value refused, kept after reopen, the env override wins); `verify:rust` exit 0 (134 passed). Vitest `quality.test.ts` (3); `npm run verify:web` exit 0 (135 tests). `gate3-quality` all passed on the real server: 1,5 Mbit/s set with the keyboard, kept after a restart, "2 Coeurs" transcoded "débit trop élevé" in 720×300 with « 1,5 Mbit/s · réseau local »; back to « Maximale » it played directly (1920×800, « Automatique · réseau local »); no resume point left.
- **Not verified**: the « Accès distant » cap on a real remote address (only a local address is reachable here); `gate5-release` not rerun (the override code is unchanged). A cap changed during a playback applies only to the next one.
- **Next**: to be chosen.

### 2026-11-07 — Audio and subtitle preferences (ADR 0035)

- **Asked**: `PRODUCT.md` P1, advanced subtitle preferences.
- **Checked on the server** (read only, Emby 4.10.1.0): in Direct Play `AudioStreamIndex`/`SubtitleStreamIndex` are ignored; in a transcode `AudioStreamIndex` is honoured only with `MediaSourceId`, and subtitles come as external files.
- **Added**: Réglages › « Audio et sous-titres » — audio language, subtitles (« Comme sur le serveur Emby », « Désactivés », « Forcés uniquement », « Toujours ») and their language; size, colour, outline or dark box, position of text subtitles, and an opt-in for ASS. Kept on this PC (`viewer.player.subtitles`), sent with `emby_play`, validated in Rust. `emby/tracks.rs` picks the tracks from the source's streams; Direct Play selects them in mpv by `ff-index`, a transcode with another audio asks a second PlaybackInfo with `MediaSourceId` and `AudioStreamIndex` and selects the external subtitle file. The engine resets `aid`/`sid` and applies the `sub-*` options at each load (a manual pick no longer carries over to the next file). The server's preferences in the Emby account are left untouched.
- **Tests**: `emby::tracks` (7), two fake-server playbacks (transcode with another audio, Direct Play), `player::mpv` (ff-index lookup; style, `sid=no`, audio by ff-index and reset read back from mpv), `subtitles.test.ts` (5). E2E `gate3-subtitles` all passed (17 checks) on film 30207: English audio and the full French subtitles with the preferences, the server's French audio and default subtitle without, style options read in mpv, no resume point left.
- **Not verified**: the look of the subtitles on the picture (the mpv video is not captured); a file whose Emby `Index` differs from the FFmpeg index (mpv's pick then stays).
- **Next**: to be chosen.

### 2026-11-07 — Caption buttons during playback

- **Problem** (owner report): in the player, minimize, maximize and close did not answer; the click paused or resumed the video. The title bar sat inside the player chrome, which takes no clicks once it fades after 3 s, so clicks fell through to the video surface.
- **Fixed**: `PlayerView.svelte` renders the title bar outside the chrome; it fades with it but always takes clicks.
- **Tests**: `gate3-player` all passed with the new check `caption_buttons_take_clicks_when_faded` (hit test of the three buttons while the controls are hidden); `npm run verify:web` exit 0 (157 tests). After the merge of the five 1.0 sessions: `verify:rust` exit 0 (163 passed), 25 of 26 scenarios passed; `gate2-ui` fails only on `no_app_credential_left`, the old leftover credential the owner keeps; `gate4-pause` failed once on an early end of file after the server reset the connection at resume, then passed in full on a rerun.
- **Next**: release 1.0.0.

### 2026-11-09 — Gate TV-1: libmpv on Android TV

- **Why**: on the LG TV the official Emby app shows ASS subtitles up to about 5 minutes late. One Android TV app covers the Shield, Fire TV and the Nokia TV.
- **Added**: ADR 0038; `android-tv/` Gradle project (AGP 9.5.1, Gradle 9.9.0, SDK 37, minSdk 28, arm64-v8a); `MPVLib` from mpv-android (MIT); `PlayerEngine` / `MpvPlayerEngine` with the Windows hardening, token in `http-header-fields`, log redaction, French subtitles by default; `PlaybackPocActivity` driven by the remote with a diagnostics overlay; `scripts/fetch-libmpv-android.ps1` (pinned release, SHA-256 checked, native libraries not in git); `scripts/spike/run-gate-tv1.ps1`.
- **Tests**: `MediaSourceTest` 3/3; six runs on the Shield over `adb reverse`, `docs/evidence/gate-tv1-2026-11-09.md`.
- **Not verified**: HDR output to the TV, Dolby Vision, DTS, bitstream audio, HTTPS, the real Emby server, Fire TV and Nokia; picture and sound on the TV by the owner.
- **Next**: owner check on the TV, then the Kotlin Emby client (sign-in, PlaybackInfo, direct play).

### 2026-11-09 — Android TV: Emby client, first slice

- **Added**: ADR 0039; Kotlin `EmbyClient` (OkHttp 5, kotlinx.serialization) with the Windows contracts and device profile; `PlaybackPlan` (Direct Play / Direct Stream / Transcode, same-origin `/videos/` URLs only, external subtitles); `SessionStore` (token encrypted by an Android Keystore AES-GCM key); Compose for TV sign-in and home screens (Coil posters, auth header only for the server origin); `PlayerActivity` (not exported) negotiating through PlaybackInfo, resume start position.
- **Bug found and fixed**: libplayer.so copies libmpv's verbose log to logcat itself, so setting `http-header-fields` after init leaked the token ("Set property: …"); the `set` command and `loadfile` per-file options leak too. Headers are now set as an option before `mpv_initialize`, so the engine is created after PlaybackInfo.
- **Tests**: `testDebugUnitTest` 24/24, `assembleDebug`, `lintDebug` OK; on the Shield against the real server: sign-in, session kept across restarts, home with posters, film in Direct Play with `mediacodec`, ASS subtitles on the picture, 0 token lines in logcat, `docs/evidence/tv-client-2026-11-09.md`.
- **Not verified**: Transcode with external subtitles, resume start and sign-out on the device; default subtitle track follows mpv's `slang`, not Emby's default.
- **Next**: series → seasons → episodes and a details page, then playback reporting.

### 2026-11-09 — Android TV: series, seasons and episodes

- **Added**: `EmbyClient.item`, `seasons`, `episodes` (paged by 100, other series dropped); `SeriesScreen` (overview, backdrop, one button per season, episode row with progress or "Vu", focused episode overview); series cards on the home open it, back returns.
- **Bug found and fixed**: back from a series reloaded the home and reset focus to the first card; `HomeMemory` keeps the shelves, scroll and opened card.
- **Tests**: `testDebugUnitTest` 27/27, `assembleDebug`, `lintDebug` OK; on the Shield: six series opened in a row, season switch on a two-season series, an episode played in Direct Play, back navigation.
- **Not verified**: a season over 100 episodes on the real server, specials, resuming a started episode, the "Vu" marker.
- **Next**: playback reporting (Playing / Progress / Stopped) so resume and watched state work, then a film details page.

### 2026-11-09 — Android TV: playback reporting

- **Added**: ADR 0040; `PlaybackTracker` (Kotlin port of the Windows tracker: start on load, Pause/Unpause, TimeUpdate after a seek and every 10 s, stop with `Failed` on an early end); `PlaybackReporter`, one process-wide coroutine so the stop report survives the activity; `EmbyClient.reportPlayback`; engine events `time-pos` (whole seconds), `duration`, `eof-reached`, seek; the player closes at the end of the file and when sent to the background; home and series page reload their user data in place after each stop.
- **Bug found and fixed**: progress bars and the "Vu" badge never showed: a TV `Card` lays its content out in a `Column`, so the full-size image pushed them out. The card content is now a `Box`.
- **Tests**: `testDebugUnitTest` 35/35 (`PlaybackTrackerTest` 6, 2 new `EmbyClientTest`), `assembleDebug`, `lintDebug` OK; on the Shield: start/progress/pause/seek/stop reports, resume row after 279 s, resume start at 279 s, bar updated in place, "Vu" after the end, 0 token lines in logcat, `docs/evidence/tv-client-2026-11-09.md`.
- **Not verified**: a stop lost to a network cut (one retry, no later resend); Home button during playback; Transcode; a film.
- **Next**: a film details page (Reprendre / Lire depuis le début), then player controls.

### 2026-11-09 — Android TV: film details page

- **Added**: `MovieScreen`: backdrop, title, year, length, "Vu" badge, overview; "Reprendre à h:mm:ss" (focused) and "Lire depuis le début" when a resume point exists, else "Lire"; reloaded in place after each stop. Film posters of the latest-additions rows open it; cards of the "Reprendre" row still play at once. The series page helpers (`Load`, `Backdrop`, `Retry`, `Centered`) are shared.
- **Bug found and fixed**: after "Lire depuis le début" and a short stop, the reload removed the focused button and nothing had focus. The first button now stays in place and takes focus again one frame after the resume state changes.
- **Tests**: `testDebugUnitTest` 38/38 (`DurationLabelsTest` 2, 1 new `EmbyClientTest`), `assembleDebug`, `lintDebug` OK; on the Shield with a 1 h 36 film: page shown, stop at 473–495 s → "Reprendre à 7:53" focused, resume start at 495 s, start over at 0, stop at 68 s clears the resume point ("Lire" focused), back to home with focus on the same poster, 0 token lines in logcat, `docs/evidence/tv-client-2026-11-09.md`.
- **Not verified**: a film already watched ("Vu" badge); a film without backdrop or overview.
- **Next**: player controls (progress bar, time, track menus).

### 2026-11-09 — Android TV: player controls

- **Added**: ADR 0041. A Compose overlay sits over the mpv surface: title (series and "S1 · É2 — title", or film and year), progress bar, time played and left, and "En pause". It shows on load and on every key, and hides after 5 s of playback. OK pauses, left/right seek 10 s, up shows the overlay, down or Menu opens the audio and subtitle menu. The menu is built from mpv's `track-list`, with French labels, and selects through `aid`/`sid`. Back closes the menu, then the overlay, then the player. New engine methods: `tracks()`, `selectTrack()`.
- **Bug found and fixed**: the first build ignored every key. Android only hands keys to the root view when a view has focus, so the activity now also handles `onKeyDown`/`onKeyUp`. Overriding `Activity.dispatchKeyEvent` is refused by lint (`RestrictedApi`).
- **Tests**: `testDebugUnitTest` 41/41 (`TracksTest` 3), `assembleDebug`, `lintDebug` OK. Checked on the Shield with an anime episode (3 French ASS subtitle tracks, 3 audio tracks):
  - overlay with title, time and remaining time;
  - seek, pause, auto-hide after 5 s;
  - menu focus starts on the subtitle in use; subtitle switched (`sid=3`) and audio switched to Japanese (`aid=2`), check mark moved;
  - Back cascade;
  - stop report on exit, 0 token lines in logcat.

  Evidence: `docs/evidence/tv-client-2026-11-09.md`.
- **Not verified**: the Menu key and media keys on a remote that has them; a file without a subtitle; transcoded streams.
- **Next**: remember the chosen audio/subtitle language per user, and move subtitles above the overlay while it shows.

### 2026-11-09 — Android TV: remembered tracks, subtitles above the controls

- **Added**: ADR 0042.
  - **Remembered choice.** A pick in the track menu is saved per Emby user: language and title, or subtitles off. It is applied when every later file loads: the same language and title first, else the first track of that language, else mpv's choice. The logic is in `player/TrackPreferences.kt` (`choose`, `sameLanguage`).
  - **Subtitles move up.** While the controls show, the overlay measures how much of the screen its text covers, and the player sets `sub-pos` to that height. It goes back to 100 when they hide.
- **Tests**: `testDebugUnitTest` 45/45 (`TrackPreferencesTest` 4). `assembleDebug` and `lintDebug` OK. Checked on the Shield, same episode:
  - a subtitle line sits above the title while paused with the controls, and the same line is at its normal place once they hide;
  - after picking full French subtitles and Japanese audio, the next playback applies them on its own (`aid=2 (préférence)`, `sid=2 (préférence)`), and the menu shows both checks;
  - "Désactivés" is remembered too (`sid=no (préférence)`);
  - 0 token lines in logcat.

  Evidence: `docs/evidence/tv-client-2026-11-09.md`.
- **Not verified**:
  - the choice applied to another file, such as the next episode; the matching logic is unit-tested only;
  - external (Emby-delivered) subtitles chosen as the preference;
  - a top-placed sign subtitle while the controls show.
- **Next**: a "Prochain épisode" prompt at the end of an episode, or a full library grid per collection.

### 2026-11-09 — Android TV: next episode

- **Added**: ADR 0043.
  - `EmbyClient.adjacentEpisodes` (`/Shows/{id}/Episodes?AdjacentTo=`), `emby/NextEpisode.kt` (`nextEpisode`, `startTicks`).
  - In the player overlay, a card "Épisode suivant": series, "S1 · É3 — titre", 10 s countdown with a gauge, "Lire maintenant" (focused) and "Annuler".
  - `PlayerActivity` loads the next episode into the same mpv (`loadfile replace`, unpaused), ignores the replaced file's end and stale answers.
- **Fixed**: back on the home screen, the card that had focus can leave "Reprendre" (episode watched). The focus fell on "Déconnecter", and one OK press signed out. The home screen now waits one frame before focusing the target card.
- **Tests**: `testDebugUnitTest` 50/50 (`NextEpisodeTest` 3, `EpisodeLabelTest` 1, `EmbyClientTest` +1). `assembleDebug` and `lintDebug` OK. Checked on the Shield (Overgeared, account `claude`):
  - É2→É3→É4→É5 chained on their own after the countdown, unpaused, each with its `Stopped` then `Start` report;
  - the remembered Japanese audio stayed on the next episodes;
  - "Annuler" closes the player (`Stopped position=14190000000`), and the home focus lands on the new "Reprendre" card (É6);
  - 0 token lines in logcat.

  Evidence: `docs/evidence/tv-client-2026-11-09.md`.
- **Not verified**:
  - a next episode in another season;
  - the last episode of a series (no card expected);
  - Back on the card (same `finish` as "Annuler").
- **Side effect**: É2 to É5 of Overgeared are now watched on the test account.
- **Next**: a full library grid per collection, or Emby's credits markers to show the card earlier.

### 2026-11-09 — Android TV: library grid

- **Added**: ADR 0044.
  - `EmbyClient.libraryItems`: the Windows grid query (`/Users/{id}/Items`, `ParentId`, `Recursive`, `Movie` or `Series`, by `SortName`, `StartIndex`/`Limit`).
  - `ui/LibraryScreen.kt`: a poster grid (`LazyVerticalGrid`, 5 columns on the Shield) with the title count, paged by 60. `shouldLoadMore` asks for the next page 18 cards before the end. `LibraryMemory` keeps pages, scroll and the opened card.
  - Home: a "Tout voir" card at the end of each "Derniers ajouts" shelf.
- **Tests**: `testDebugUnitTest` 54/54 (`LibraryPagingTest` 3, `EmbyClientTest` +1). `assembleDebug` and `lintDebug` OK, no new warning. Checked on the Shield:
  - "Anime · 141 titres": scrolled to the last row, a single card ("You are Ms. Servant"), as 141 cards in rows of 5 give;
  - "Films · 606 titres", the first rows in title order;
  - a series (Shaman King) and a film (2 Guns) opened from the grid; Back returns to the same card, and Back again to "Tout voir";
  - 0 token lines in logcat.

  Evidence: `docs/evidence/tv-client-2026-11-09.md`.
- **Not verified**:
  - a burst of 20 Down keys sent by adb in one command moved fewer rows than asked; slower presses reach the end. A held key on the real remote was not tried;
  - a page that fails mid-grid (the bottom "Réessayer");
  - the whole 606-film grid scrolled to the end.
- **Next**: sort and filters in the grid (ADR 0013 on Windows), or Emby's credits markers for the next-episode card.

### 2026-11-09 — Android TV: library sort and filters

- **Added**: ADR 0045, the Windows choices of ADR 0013 on the TV grid.
  - `emby/ItemsOptions.kt`: `SortKey`, `WatchFilter`, `ItemsOptions` and `isUsableGenre`, as in `models.rs`.
  - `EmbyClient.libraryItems` takes the options (`SortBy`, `SortOrder`, `Filters`, `Genres` only when set); `EmbyClient.genres` reads `/Genres` for one library.
  - `ui/LibraryScreen.kt`: a toolbar above the grid (Tri, direction, Visionnage, Favoris, Genre, Réinitialiser). Each change starts over from page 0 with a new grid state; a page of the old query is dropped. A side list picks the genre. "Aucun titre ne correspond à ces filtres." when nothing matches.
  - `ui/LibraryPreferences.kt`: choices kept per user and per library in plain preferences.
- **Fixed during the check**:
  - the grid stayed on "Chargement…": `LazyGridState.scrollToItem` waits for the grid's first layout, and the grid is only shown once a page has landed. A new `LazyGridState` per query replaces the scroll;
  - a filter button that turns filled is another composable, so it lost the focus and the next OK changed the sort. The pressed button takes the focus back.
- **Tests**: `testDebugUnitTest` 64/64 (`ItemsOptionsTest` 4, `LibraryOptionsTest` 3, `EmbyClientTest` +3). `assembleDebug` and `lintDebug` OK; one new `UseKtx` hint, the same `prefs.edit()` form as `TrackPreferenceStore`. Checked on the Shield, account `claude`, read only:
  - Films: by date added, by year (2026 first), by rating; "Non vus" 606, "Vus" 0 and "En cours" 0 with the empty message; "Animation" 38 titles; "Favoris" 0; "Réinitialiser" back to 606;
  - the choices survive an app restart;
  - focus stays on the pressed button; Back closes the genre list on the "Genre" button; a film opened after the changes returns to its card;
  - 0 token lines in logcat.

  Evidence: `docs/evidence/tv-client-2026-11-09.md`.
- **Not verified**:
  - a non-empty "Vus", "En cours" or "Favoris" list: the test account has none, and no favorite was added on the server;
  - the Anime library's toolbar (no "En cours" there, covered by a unit test only);
  - the D-pad can leave the genre list sideways while it is open.
- **Next**: Emby's credits markers for the next-episode card, or watched marks on grid posters.

### 2026-11-09 — Android TV: poster marks

- **Added**: ADR 0046, the Windows `PosterCard` marks on every TV poster (home shelves and library grid).
  - `UserItemData.unplayedItemCount` (`UserData.UnplayedItemCount`).
  - `ui/HomeScreen.kt`: `badge()` gives a check once watched, else the episodes left on a series; a dark round mark in the top-right corner. The progress bar now shows on posters too.
- **Tests**: `testDebugUnitTest` 67/67 (`PosterBadgeTest` 3). `assembleDebug` and `lintDebug` OK. Checked on the Shield: "Derniers ajouts · Anime" shows 6, 2 (Overgeared, episodes 2 to 5 watched), 5, 6 on the series posters; 0 token lines in logcat.
- **Not verified**: the check on a watched film or series and the bar on a started film: the test account has none, and nothing was marked on the server for it.
- **Next**: search, or watched marks and unplayed counts on the series page.

### 2026-11-09 — Android TV: season marks

- **Added**: ADR 0049, the Windows season pills (`EpisodeList.svelte`, `defaultSeason` in `format.ts`) on the TV series page.
  - `ui/SeriesScreen.kt`: `SeasonText` shows the episodes left in the accent colour, or a check once the season is watched. `defaultSeason` opens the first regular season with episodes left, else the first regular one, else the specials. Seasons are read again in place after a playback, the season shown kept.
- **Tests**: `testDebugUnitTest` 70/70 (`DefaultSeasonTest` 3). `assembleDebug` and `lintDebug` OK. Checked on the Shield: Overgeared shows "Saison 1 2" (accent), É5 "Vu", focus on É6.
- **Not verified**: a watched season's check and a series with several seasons where the first is finished (none on the test account).
- **Next**: search and skip intro/credits (in progress).

### 2026-11-09 — Android TV: detail credits

- **Added**: ADR 0050, the Windows facts list (`ItemDetail.svelte`) on the TV film and series pages.
  - `BaseItemDto`: `Genres`, `Studios` (name only), `People` (name, type), `OfficialRating`, `CommunityRating`, already returned by `/Users/{Id}/Items/{Id}`.
  - `ui/Credits.kt`: `tags()` puts the rating, three genres and the score after the year; `facts()` lists Réalisation, Scénario (films), Avec (6 actors) and Studio / Diffusion. The series page keeps two lines for the episodes row.
- **Tests**: `testDebugUnitTest` 73/73 (`FactsTest` 3). `assembleDebug` and `lintDebug` OK, no new warning. Checked on the Shield: Overgeared "2026 · BR-14 · Fantasy, Animation, Action · ★ 7,7", Avec and Diffusion; a film with Réalisation, Scénario, Avec and Studio.
- **Not verified**: an item without people or genres (lines left out by `facts`, covered by the tests only).

### 2026-11-09 — Android TV: search

- **Added**: ADR 0047, the Windows search (`emby_search`, `title_matches`, ADR 0011 and 0020) on TV, reached from "Rechercher" in the home header.
  - `emby/Search.kt`: same cleaning, two-letter minimum, three requests in parallel (25 films, 25 series, 100 episodes, 24 shown), episodes kept when a word of their title starts with the term.
  - `ui/SearchScreen.kt`: results 300 ms after the last key, or at once with the keyboard's Search key; a new term cancels the request running. Films, Séries and Épisodes rows; an episode opens its series (no episode page yet). Back from a film or series returns to the results with the term and the focused card.
  - D-pad Down leaves the text field for the results (the field kept the key for its cursor; found on the device and fixed).
- **Tests**: `testDebugUnitTest` 84/84 (`SearchTest` 8, `SearchGroupsTest` 3). `assembleDebug` and `lintDebug` OK, no warning in the new files. Checked on the Shield: "over" gives Films · 2 (Overdrive, Overdose) and Séries · 3 (Overgeared, Tonikawa, My Instant Death Ability…); Tonikawa opened and Back returned to its card; Back from the results lands on "Rechercher"; 0 token lines in logcat.
- **Not verified**: the voice input, a term with no match and the error state on the device (covered by the tests only).

### 2026-11-09 — Android TV: skip intro and credits

- **Added**: ADR 0048, the Windows segments (`src-tauri/src/segments`, ADR 0030) in the TV player. Supersedes ADR 0043 on the Emby credits markers.
  - `emby/Segments.kt`: Emby chapter markers first, then IntroDB, then TheIntroDB, in the Windows order; ids checked before any URL, a separate HTTP client without the Emby header or redirects (3 s connect, 4 s per call, 64 KiB answers), 256 definitive answers cached in memory.
  - `player/Skip.kt`, `PlayerOverlay.kt`: "Passer l'intro" bottom right during the intro, focused; at the credits of an episode with a next one, the next-episode card opens once, and "Annuler" or Back lets the credits play with "Passer le générique"; a film or last episode gets "Passer le générique".
- **Tests**: `testDebugUnitTest` 104/104 (`SegmentsTest` 13, `SkipTest` 6, `EmbyClientTest` +1). `assembleDebug` and `lintDebug` OK, no warning in the changed files. Checked on the Shield: Overgeared S1É6 logs `segments=Intro@112.0-199.0 (IntroDb), Credits@1330.0-1421.68 (IntroDb)`; "Passer l'intro" shown at 2:49 with the focus, OK logs `skip to 199.0` and the button goes; 0 token lines in logcat.
- **Not verified**: the credits (the stop report at about 93 % would mark the episode watched on the server), the button with the track menu open, Emby's own markers (none on the server), a film. The provider ids of what is watched go to two third-party services, as on Windows.

### 2026-11-09 — Android TV: lost stop report resent

- **Added**: the Windows resend of a lost stop report (ADR 0024, `src-tauri/src/emby/reporting.rs`) in `PlaybackReporter`.
  - A stop report still not sent after its one retry, for a passing failure (unreachable, HTTP 5xx), is kept with its session and tried again after 5 s, then 10 s, then every 15 s, and given up after an hour or on a lasting error.
  - Each try sends a progress report first (`Report.lateProgress()`): the server ignores a stop's position once it closed the playback itself. A new playback drops the lost report, so it never overwrites a newer position. Screens reload their user data once it goes through.
- **Tests**: `testDebugUnitTest` 107/107 (`PlaybackTrackerTest` +3: schedule, late progress, idle). `assembleDebug` and `lintDebug` OK. Checked on the Shield: a normal stop is still sent (`report Stopped position=2400000000`); 0 token lines in logcat.
- **Not verified**: the resend itself on the device: cutting the network also cuts adb, which runs over Wi-Fi. Covered by the unit tests only. Unlike Windows, a new playback does not wait for the resend: the TV pages pass the resume point they read.

### 2026-11-09 — Android TV: settings

- **Added**: ADR 0051, a "Réglages" screen opened from the home header, saved per Emby user on the device.
  - From the Windows settings: IntroDB/TheIntroDB on or off (default on), "Passer automatiquement" (default off), and the "Réseau local" and "Accès distant" streaming caps, picked from the Windows list (40 down to 1.5 Mbit/s, or "Maximale (automatique)"). The cap used depends on the server address. An "À propos" section shows the version.
  - Automatic skip passes each intro or credits once per playback, and never while the track menu or the up-next card is open.
- **Tests**: `testDebugUnitTest` 117/117 (`PlaybackSettingsTest` 6, `SkipTest` +3, `ServerUrlTest` +1). `assembleDebug` and `lintDebug` OK. Checked on the Shield:
  - the screen and its note ("adresse du réseau local"); Back returns to "Réglages";
  - a 1.5 Mbit/s local cap logs `method=Transcode maxBitrate=1500000` and plays 720p H.264; back on "Maximale" it logs `DirectPlay maxBitrate=400000000`;
  - the automatic skip logs `auto skip`, `skip to 199.0`; going back into the intro shows the button with no second skip;
  - with the external sources off, `segments=` is empty;
  - the settings survive a restart. All were set back to their defaults afterwards.
- **Not verified**: the credits with automatic skip and the up-next card (an end-of-episode stop would mark it watched), another Emby user getting the defaults.

### 2026-11-09 — Android TV: token kept out of mpv's verbose log

- **Fixed**: ADR 0052. On an HLS transcode, lavf logged the unused `X-Emby-Token` header at verbose level, and libplayer.so copies every libmpv line to logcat itself, at VERBOSE priority. A 20-line C library (`app/src/main/cpp/viewerlog.c`, built by CMake) now raises the process's minimum logcat priority to DEBUG before mpv starts. The redacted Kotlin copy at INFO and above stays.
- **Tests**: found on the Shield during the settings check (1 token line on a transcode). After the fix, the same transcode gives 0 token lines and 0 `mpv` lines in logcat. `testDebugUnitTest`, `assembleDebug` and `lintDebug` OK.
- **Not verified**: Android 9 and 10 (minSdk 28), where `__android_log_set_minimum_priority` does not exist: the app logs a warning and the verbose copy remains. The Shield runs Android 11.

### 2026-11-09 — Android TV: subtitle appearance

- **Added**: ADR 0053, the Windows subtitle settings (size, colour, readability, position, apply to ASS) in a "Sous-titres" section of the TV settings, per Emby user. `player/SubtitleOptions.kt` maps them to the Windows mpv options, set before each `loadfile`; `sub-pos` takes the higher of the chosen place and the one that clears the controls.
- **Deviation from Windows**: `sub-ass-override` is `yes` instead of `no` when the ASS option is off. With `no`, mpv ignored `sub-pos` for ASS subtitles, which stayed under the TV controls (seen on the Shield). `yes` keeps their font and colours; the position setting now moves them too, and the setting's text says so.
- **Tests**: `testDebugUnitTest` 123/123 (`SubtitleOptionsTest`, `PlaybackSettingsTest`). `assembleDebug` and `lintDebug` OK. Checked on the Shield: the section shows and cycles; ASS on gives large yellow text on a dark box above the controls; ASS off keeps the file's white outlined style and, after the fix, moves above the controls. Settings and tracks reset to defaults afterwards; 0 token lines in logcat.
- **Not verified**: text subtitles (SRT, none clearly available for a test), the "En haut" position on the device, another Emby user, the next episode in the same engine.

### 2026-11-09 — Android TV: an episode found by search opens its season

- **Added**: ADR 0054. An episode chosen in the search results opens its series on that episode's season (`SeasonId`, else its season number, else the default season) with the focus on its card; Back returns to the results on the same card. Home's episode cards still play.
- **Fixed** (search, ADR 0047): coming back from a page opened from the results, the system keyboard opened over them, since the field held the focus for a frame. It is now closed once the card has the focus.
- **Tests**: `testDebugUnitTest` 130/130 (`SeriesTargetTest` 7). `assembleDebug` and `lintDebug` OK. Checked on the Shield with "Raid": Commissaire Moulin S7·É7 opens on "Saison 7" (not the default season 1) with É7 focused; Pokémon S19·É40 opens on É40; Back returns to the focused card without the keyboard; 0 token lines in logcat.
- **Not verified**: whether the search answer carries `SeasonId` or the season number was used (both give the same season here), a special episode.

### 2026-11-09 — Android TV: tracks follow the server's defaults

- **Added**: ADR 0055. With no remembered choice (ADR 0042), the player takes the tracks the server picked in PlaybackInfo (`DefaultAudioStreamIndex`, `DefaultSubtitleStreamIndex`, computed from the account's language preferences), mapped to mpv by `ff-index` as on Windows; mpv decides only when the server names nothing usable. A remembered choice still wins. Read-only: nothing is written to the account.
- **Fixed**: on a transcode only the server's subtitle file is selected (every file used to be added with `select`); `aid`/`sid` are reset before each file, so the next episode does not inherit them.
- **Tests**: `testDebugUnitTest` 136/136. `assembleDebug` and `lintDebug` OK. Checked on the Shield, Overgeared S1·É6 in Direct Play: with the remembered choice, `tracks audio=-→2 (remembered) sub=-→no (remembered)`; with it set aside, `tracks audio=1→1 (server) sub=4→1 (server)`, the menu checking "Français — Forced · ASS" and "Français · AAC stéréo". Remembered choices restored afterwards; 0 token lines in logcat.
- **Not verified**: a title where the server's pick differs from mpv's (here they match), a transcode, the next episode in the same engine, each `SubtitleMode` of the account.
