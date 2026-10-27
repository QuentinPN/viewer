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

## Gate 2 — Emby integration [IN PROGRESS · sign-in and secure token VERIFIED]
Connection/auth, typed API client, libraries, item details, PlaybackInfo, secure tokens, session reporting, fake-server contract tests.
- Access authorized by the user (2026-10-27): Emby 4.10.1.0 on the QNAP, LAN HTTP only, non-admin test account. Decision: ADR 0004 (`EmbyClient` in Rust, token in the Windows Credential Manager).
- VERIFIED (2026-10-27), evidence in `docs/evidence/gate2-2026-10-27.md`:
  - server address normalization, connection test, plain HTTP accepted only to a local address;
  - sign-in, token stored in the Windows Credential Manager only, auth in the `X-Emby-Authorization` header;
  - restore after restart, revoked token detected and forgotten, sign-out revoking the token on the server;
  - typed French errors with a hint (wrong password, unreachable, not Emby, expired session);
  - fake-server contract tests, `#[ignore]` real-server test, UI E2E on the real server.
- NOT VERIFIED: HTTPS with TLS validation against a real server (none available); retries, cancellation, offline state; 125/150 % DPI.
- TODO: libraries (`/Users/{id}/Views`, paged items), item details, PlaybackInfo / MediaSources, session reporting.

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
