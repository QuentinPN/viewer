# Viewer — Testing and verification

## Quality commands

- All gates: `npm run verify` (= `verify:web` + `verify:rust`).
- Frontend (`verify:web`): `biome check .`, `svelte-check --fail-on-warnings`, `vitest run`, `vite build`.
- Rust (`verify:rust`): `cargo fmt --all -- --check`, `cargo clippy --all-targets -- -D warnings`, `cargo test` (manifest `src-tauri/Cargo.toml`). The libmpv engine test runs against `vendor/mpv/libmpv-2.dll` (or `VIEWER_LIBMPV`); set `VIEWER_REQUIRE_LIBMPV=1` to fail instead of skipping when it is missing (CI does).
- Local MSVC workaround if `LNK1104 msvcrt.lib`: prefix with `scripts/with-msvc.ps1` (ADR 0001).
- Windows packaging: `npm run tauri build`, disabled (`bundle.active=false`) until Gate 5.

## UI scenarios without taking the desktop (ADR 0006)

Preferred for UI checks: they drive the debug build over WebView2's DevTools protocol, with the window off-screen and never activated, so the user can keep working. Screenshots show the HTML UI only (never mpv video).

- Prerequisites: `cargo build` (debug), Vite dev server (`npm run dev`), and once, the Emby test account in the Windows Credential Manager: `cmdkey /generic:viewer-e2e-emby /user:<test user> /pass` (password prompted, hidden). Optional `VIEWER_EMBY_URL`, otherwise the address prefilled by the app is used.
- `./scripts/e2e/run.ps1` (default scenario `gate2-ui`): wrong password, sign-in, restore after restart, keyboard library switch, grid paging, series detail with season switch, Escape back with focus kept, film detail, narrow real window without horizontal overflow, scroll kept, sign-out, no app credential left. Asserted checks plus screenshots in `artifacts/e2e/gate2-ui-<run>/` (`report.json`). Starts in any state, ends signed out.
- Reusable launcher: `scripts/e2e/app.mjs` (`launch()`, `runDir()`).
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
- Real server, opt-in: credentials only through environment variables, never as arguments or in files: `VIEWER_EMBY_URL`, `VIEWER_EMBY_USER`, `VIEWER_EMBY_PASSWORD`.
  - `./scripts/with-msvc.ps1 cargo test --manifest-path src-tauri/Cargo.toml real_server_sign_in_restore_sign_out '--' --ignored`: sign-in, restore, sign-out, revoked token refused. It reuses the fixed device id `viewer-real-server-test`.
  - `… cargo test … real_server_libraries_and_pages '--' --ignored --nocapture`: lists libraries, reads the first and last page of each, fetches one poster, signs out.
  - `… cargo test … real_server_details_seasons_episodes '--' --ignored --nocapture` (optional `VIEWER_DETAIL_SAMPLE`, default 40 items per library): detail of each sampled item, seasons and first page of episodes of each series, one backdrop.
- Windows Credential Manager, opt-in: `cargo test … windows_store_round_trip '--' --ignored`, then check `cmdkey /list` shows no `Viewer` entry.

## Release gate

No release claims without: reproducible build, successful tests, Windows player smoke test, clean-machine installer test, dependency/license audit and documented known limitations.
