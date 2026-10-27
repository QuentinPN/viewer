# Viewer — Testing and verification

## Quality commands

- All gates: `npm run verify` (= `verify:web` + `verify:rust`).
- Frontend (`verify:web`): `biome check .`, `svelte-check --fail-on-warnings`, `vitest run`, `vite build`.
- Rust (`verify:rust`): `cargo fmt --all -- --check`, `cargo clippy --all-targets -- -D warnings`, `cargo test` (manifest `src-tauri/Cargo.toml`). The libmpv engine test runs against `vendor/mpv/libmpv-2.dll` (or `VIEWER_LIBMPV`); set `VIEWER_REQUIRE_LIBMPV=1` to fail instead of skipping when it is missing (CI does).
- Local MSVC workaround if `LNK1104 msvcrt.lib`: prefix with `scripts/with-msvc.ps1` (ADR 0001).
- Windows packaging: `npm run tauri build`, disabled (`bundle.active=false`) until Gate 5.

## Gate 1 playback scenario

`scripts/spike/close-probe.ps1 [-Media <file>]` posts WM_CLOSE (Windows close button) idle or during playback and reports close latency, exit code, leftover processes and the WebView2 exit error.

`scripts/spike/run-gate1.ps1 -Media <file|http URL> [-Mode parent|child|separate] [-HttpToken <test token>]` drives the debug build: playback, tracks, seek, fullscreen, resize, real Space key, teardown, open/close cycles. It writes `report.json`, `driver.json` and screenshots to `artifacts/gate1/<run>/`. For HTTP, `scripts/spike/serve-file.mjs` serves one file on 127.0.0.1 with Range support and can require an `X-Emby-Token` header. **Review the screenshots**: property checks alone passed for a mode whose video was invisible.

## Playback matrix

Record for each test: Windows build, GPU/driver, libmpv build, Emby version, file codec/container, bitrate, subtitle type, audio format, direct/transcode decision, observed result, logs sanitized.

Cases: H.264 MP4, HEVC MKV, AV1 if supported, 4K sample, multiple audio tracks, SRT, ASS, PGS, seek, pause/resume, fullscreen, DPI scaling, app exit during playback, disconnect/reconnect, unsupported codec and fallback. Test HDR/passthrough only on suitable hardware.

## Contract and integration

Mock Emby API for auth failures, pagination, permissions, missing fields, playback selection and session lifecycle. With user approval, verify progress and watched state on a real test account and test media.

## Release gate

No release claims without: reproducible build, successful tests, Windows player smoke test, clean-machine installer test, dependency/license audit and documented known limitations.
