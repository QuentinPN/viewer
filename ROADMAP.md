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
  - seek, fullscreen and resize following;
  - real Space key → pause through the WebView;
  - teardown ≈100–145 ms, 3 open/close cycles, process exits cleanly.
- NOT VERIFIED:
  - switching between multiple audio/subtitle tracks (test file has one of each);
  - SRT embedded, PGS;
  - HEVC, AV1, 4K, HDR;
  - audible audio output;
  - mouse clicks on overlay, Escape and focus after fullscreen;
  - 125/150 % DPI, multi-monitor;
  - window close during playback;
  - network loss, unsupported codec;
  - release build on a clean machine.
- Next: a multi-track sample (several audio + SRT/ASS/PGS) and a HEVC sample, then rerun `scripts/spike/run-gate1.ps1`.

## Gate 2 — Emby integration [TODO · needs user authorization]
Connection/auth, typed API client, libraries, item details, PlaybackInfo, secure tokens, session reporting, fake-server contract tests.
- Prerequisite: ADR 0004 (EmbyClient in Rust vs TS, proposal in `docs/analyse-initiale.md`); server URL/version; a non-admin test account; explicit permission before any request to the NAS.

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
