# Viewer — Testing and verification

## Quality commands

- All gates: `npm run verify` (= `verify:web` + `verify:rust`).
- Frontend (`verify:web`): `biome check .`, `svelte-check --fail-on-warnings`, `vitest run`, `vite build`.
- Rust (`verify:rust`): `cargo fmt --all -- --check`, `cargo clippy --all-targets -- -D warnings`, `cargo test` (manifest `src-tauri/Cargo.toml`). The libmpv engine test runs against `vendor/mpv/libmpv-2.dll` (or `VIEWER_LIBMPV`); set `VIEWER_REQUIRE_LIBMPV=1` to fail instead of skipping when it is missing (CI does).
- Local MSVC workaround if `LNK1104 msvcrt.lib`: prefix with `scripts/with-msvc.ps1` (ADR 0001).
- Windows packaging: `npm run tauri build`, disabled (`bundle.active=false`) until Gate 5.

## UI scenarios without taking the desktop (ADR 0006)

Preferred for UI checks: they drive the debug build over WebView2's DevTools protocol, with the window off-screen and never activated, so the user can keep working. Screenshots show the HTML UI only (never mpv video).

- Prerequisites: `cargo build` (debug), Vite dev server (`npm run dev`), and once, the Emby test account in the Windows Credential Manager: `cmdkey /generic:viewer-e2e-emby /user:<test user> /pass` (password prompted, hidden). Optional `VIEWER_EMBY_URL`, otherwise the last server the app signed in to (`session.json`).
- `./scripts/with-test-account.ps1 <command…>` runs any command with `VIEWER_EMBY_URL` / `VIEWER_EMBY_USER` / `VIEWER_EMBY_PASSWORD` set for it only (real-server cargo tests below, scenarios). The app is started with `VIEWER_E2E_SILENT` (debug only): mpv uses `ao=null`, no sound on the speakers.
- `./scripts/e2e/run.ps1` (default scenario `gate2-ui`): wrong password, sign-in, restore after restart, keyboard library switch, grid paging, series detail with season switch, Escape back with focus kept, film detail, narrow real window without horizontal overflow, scroll kept, sign-out, no app credential left. Asserted checks plus screenshots in `artifacts/e2e/gate2-ui-<run>/` (`report.json`). Starts in any state, ends signed out.
- `./scripts/e2e/run.ps1 gate2-play`: film played from its detail page by keyboard, episode from its series; checks the server's decision shown in the player, playback advancing (engine diagnostics: codec, hwdec, position), Escape / ✕ back to the detail page with focus on the play control, player closed. Ends signed out.
- `./scripts/e2e/run.ps1 gate2-resume` (**writes positions on the test account, then clears them**): film seeked to 20 % then stopped → "Reprendre" and "Lire depuis le début" offered, resume starts at the saved position, from-start run clears it; episode seeked to 20 % then the window closed during playback → after relaunch the episode offers "Reprendre", resumes at the position, rewound and stopped → "Lire" again. Ends signed out.
- `./scripts/e2e/run.ps1 gate2-transcode` (**short transcode on the test account**, needs the debug build: `VIEWER_E2E_MAX_BITRATE=3000000`): first film plays transcoded with the badge "Transcodage · débit trop élevé", playback advances, external subtitles listed with the server's default selected, seek to 600 s lands there, rewound and Escape → back on the detail page with no resume point. Ends signed out.
- `./scripts/e2e/run.ps1 gate3-home` (**writes one position on the test account, then clears it**): home is the default view with one "Ajoutés récemment" row per library, Left/Right between cards, scroll arrows, "Tout voir" opens the library and "Accueil" comes back, a latest film opens its detail page; played to 20 % then stopped → it heads "Reprendre" with its progress bar and artwork, the card resumes at the saved position, rewound and stopped → it leaves the row. Ends signed out.
- `./scripts/e2e/run.ps1 gate3-hero` (ADR 0015): hero against `emby_hero` (order, one title, dots, carousel semantics, logo alt or title, main button label), no rotation over 7 s, arrows, wrap-around, dots, ← → with the focus kept, "Plus d'infos" opens the detail, reduced motion removes the fade, the main button plays (stopped after ~1 s: hero and resume unchanged, title kept). Ends signed out.
- `./scripts/e2e/run.ps1 gate3-favorites` (ADR 0014; signs in through the local proxy): read only by default — home row "Favoris" against `emby_favorites` (hidden when empty), film and series toggles with the server state, NAS cut while toggling (rolled back, "Favori non modifié", nothing reaches the NAS, server state back online). With `VIEWER_E2E_FAVORITE_WRITES=1` (only with the account owner's permission): add a film, home row shows it, kept after a restart, opens from the row, Space removes it, server list as before. Ends signed out.
- `./scripts/e2e/run.ps1 gate3-sort-filter` (read only, ADR 0013): defaults; year sort in both directions checked on the cards; a filter without match shows its empty state and its reset keeps the sort and focuses it; unwatched + watched = total; a genre narrows the count; favorites toggle; series libraries without "En cours" and with their own choices; choices kept after switching library and after a restart; quick successive changes show the last one; arrow key on a select; next page with the same options; cleanup to defaults with nothing stored. Ends signed out.
- `./scripts/e2e/run.ps1 gate3-search` (read only): `/` and Ctrl+F focus the sidebar search field, one request after typing, the same results with other case and accents, a result opens its detail page and Escape comes back with focus on the card, one letter sends nothing, empty state, Escape back home and back to a scrolled library at the same scroll, narrow window without overflow. Ends signed out.
- `./scripts/e2e/run.ps1 gate21-session` (ADR 0012; **writes and clears one position on the test account**; `VIEWER_EMBY_URL` must be the NAS, never a loopback address): the app signs in **through the local proxy `scripts/e2e/net-proxy.mjs`**, which simulates the outages (port closed, connection held without answer, 401 on authenticated requests) without touching the NAS. Steps: restart without the form; NAS off at startup → recovery screen, token kept, manual and automatic return; NAS hanging → screen after the 15 s timeout; outage mid-session → overlay, automatic return, view and scroll kept; revoked token → a single reauthentication screen, token deleted, return after the password; change server → return without password; playback cut → "Lecture interrompue.", no "watched", resume at the server's position, cleared afterwards. Ends signed out with no leftover credential. Recovers from an interrupted previous run.
- Reusable launcher: `scripts/e2e/app.mjs` (`launch()`, `runDir()`); checks, screenshots, report, sign-in and `resizeWindow` (real window, never activated): `scripts/e2e/scenario.mjs`.
- Ask the user before any check that needs real OS input or screen capture (Gate 1 scripts below).

## Gate 1 playback scenario

`scripts/spike/close-probe.ps1 [-Media <file>]` posts WM_CLOSE (Windows close button) idle or during playback and reports close latency, exit code, leftover processes and the WebView2 exit error.

`scripts/spike/run-gate1.ps1 -Media <file|http URL> [-Mode parent|child|separate] [-HttpToken <test token>]` drives the debug build: playback, tracks, seek, fullscreen, resize, real Space key, teardown, open/close cycles. It writes `report.json`, `driver.json` and screenshots to `artifacts/gate1/<run>/`. For HTTP, `scripts/spike/serve-file.mjs` serves one file on 127.0.0.1 with Range support and can require an `X-Emby-Token` header. **Review the screenshots**: property checks alone passed for a mode whose video was invisible.

## Playback matrix

Record for each test: Windows build, GPU/driver, libmpv build, Emby version, file codec/container, bitrate, subtitle type, audio format, direct/transcode decision, observed result, logs sanitized.

Cases: H.264 MP4, HEVC MKV, AV1 if supported, 4K sample, multiple audio tracks, SRT, ASS, PGS, seek, pause/resume, fullscreen, DPI scaling, app exit during playback, disconnect/reconnect, unsupported codec and fallback. Test HDR/passthrough only on suitable hardware.

## Contract and integration

Mock Emby API for auth failures, pagination, permissions, missing fields, playback selection and session lifecycle. With user approval, verify progress and watched state on a real test account and test media.

- `src-tauri/src/emby/tests.rs` runs `EmbyService` against a local fake Emby server (one request per connection) and an in-memory credential store. Fake responses mirror real server responses recorded in `docs/evidence/`.
- Real server, opt-in: credentials only through environment variables, never as arguments or in files: `VIEWER_EMBY_URL`, `VIEWER_EMBY_USER`, `VIEWER_EMBY_PASSWORD`. Prefix the commands below with `./scripts/with-test-account.ps1` to take them from the Windows Credential Manager.
  - `./scripts/with-msvc.ps1 cargo test --manifest-path src-tauri/Cargo.toml real_server_sign_in_restore_sign_out '--' --ignored`: sign-in, restore, sign-out, revoked token refused. It reuses the fixed device id `viewer-real-server-test`.
  - `… cargo test … real_server_libraries_and_pages '--' --ignored --nocapture`: lists libraries, reads the first and last page of each, fetches one poster, signs out.
  - `… cargo test … real_server_details_seasons_episodes '--' --ignored --nocapture` (optional `VIEWER_DETAIL_SAMPLE`, default 40 items per library): detail of each sampled item, seasons and first page of episodes of each series, one backdrop.
  - `… cargo test … real_server_playback_reporting_and_resume '--' --ignored --nocapture` (**writes on the test account**): first unwatched film with no resume point, start / pause / unpause / seek / stopped reports, resume point read back and used as the start position, then cleared by a stop at 0.
  - `… cargo test … real_server_transcoding '--' --ignored --nocapture` (**short transcode on the test account**): 3 Mbit/s cap, first unwatched film → `Transcode` plan without `api_key`, master playlist and first segment fetched with the header, every external subtitle 200, then the stop report ends the encoding (no `TranscodingInfo` left in `/Sessions`) and leaves no resume point.
  - `… cargo test … real_server_playback_negotiation '--' --ignored --nocapture` (optional `VIEWER_PLAYBACK_SAMPLE`, default 20 items per library): PlaybackInfo for films and first episodes, method per item, no `api_key` in stream URLs, one 1 KiB stream read with the token header (206) and without it (401).
  - `… cargo test … real_server_home_rows '--' --ignored --nocapture` (**writes one position on the test account, then clears it**): Continue Watching before and after a stop at 600 s on a film not in progress (it comes first, at 600 s), Next Up, latest row of each library (library kind, posters), then the position is cleared.
  - `… cargo test … real_server_next_up '--' --ignored --nocapture` (**marks one episode watched on the test account, then unwatched**): first series of "Séries TV" whose first two episodes are unwatched, episode 1 marked watched → episode 2 is in Next Up with its still, then episode 1 is unmarked and Next Up is back to its state before.
  - `… cargo test … real_server_search '--' --ignored --nocapture` (read only): same results with or without case and accents, word-prefix matching, more than 24 results flagged on a common word, one letter sends nothing.
- Windows Credential Manager, opt-in: `cargo test … windows_store_round_trip '--' --ignored`, then check `cmdkey /list` shows no `Viewer` entry.

## Release gate

No release claims without: reproducible build, successful tests, Windows player smoke test, clean-machine installer test, dependency/license audit and documented known limitations.
