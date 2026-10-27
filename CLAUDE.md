# Viewer — Claude Code project instructions

## Mission

Build a production-quality Windows 10/11 desktop client for an existing Emby server hosted on a QNAP NAS. Prioritize reliable media playback, responsiveness, accessibility, security, and maintainability. The experience should take inspiration from Infuse without copying proprietary assets or layouts pixel-for-pixel. French is the default UI language.

## Source of truth and reading order

At the start of every task, read `PRODUCT.md`, `ARCHITECTURE.md`, `DESIGN.md`, `ROADMAP.md`, and relevant sections of `TESTING.md`. Inspect the actual repository before changing it. Treat these files as specifications, but verify implementation and upstream documentation. When documents disagree, identify the conflict and propose a resolution; do not silently choose.

## Stack baseline (subject to feasibility gate)

- Windows desktop: Tauri 2; frontend: Svelte 5 + TypeScript strict + Vite (no SSR).
- Native integration: Rust; media engine: libmpv, isolated behind a `PlayerEngine` interface.
- Local metadata/image cache: SQLite plus bounded on-disk image cache. Emby remains source of truth.
- Frontend formatting/lint: Biome; Svelte type/diagnostics: svelte-check; TypeScript: tsc where applicable.
- Rust: cargo fmt, cargo clippy, cargo test. Frontend: Vitest; integration/E2E tests when practical.
- Do not add a new backend, ORM, UI framework, state library, or plugin without explaining necessity.

## Non-negotiable engineering gates

1. **Prove playback first.** Before broad UI implementation, build a Windows proof of concept for libmpv rendering, resizing, fullscreen, overlays/focus, keyboard handling, local MKV, HTTP stream, audio, subtitles, hardware decoding and clean teardown. If unavailable in the current environment, mark it BLOCKED/UNVERIFIED, not passed.
2. **Use verified Emby API contracts.** Inspect current Emby API docs and actual server responses with user permission. Never invent endpoints or claim Direct Play solely from file extension. Negotiate playback using PlaybackInfo, MediaSources, device capabilities and server response.
3. **Protect secrets.** Store user tokens in OS-backed secure storage; no credentials in git, logs, plain SQLite or URL query strings when a safer supported option exists. Validate TLS. Ask before connecting to the real NAS, altering server configuration, deleting data, or uploading telemetry.
4. **Honest compatibility.** Codec/container/AV1/HEVC/HDR/Dolby Vision/bitstream audio/subtitle support varies by GPU, driver, output path, libmpv build and licensing. Document tested combinations; provide fallbacks and diagnostics.
5. **Real results.** Do not claim working features, passing tests, measurements, or builds unless executed and evidenced. No dummy production data or empty placeholder modules passed off as complete.
6. **Quality gates.** For each changed area, run relevant formatting, lint, type checks, tests, and build. Report commands and outcomes. If blocked, give exact reason.
7. **Small vertical slices.** Implement one end-to-end behavior at a time. Avoid giant speculative rewrites, premature abstractions, and broad dependency churn.

## Workflow for every task

1. Read project docs and inspect code/tests; restate scope, assumptions, acceptance criteria and risks.
2. Consult the relevant `.claude/skills/*/SKILL.md` (skills are guidance, not proof of runtime capabilities).
3. For significant choices, record an ADR in `docs/adr/` before implementation.
4. Plan a small change, implement it, add/update tests, and verify it.
5. Update `ROADMAP.md` status and relevant docs when actual behavior changes.
6. End with: summary, files changed, commands/results, known limitations, next recommended task.

## Architecture boundaries

- Svelte UI must not construct raw media URLs or access native internals directly.
- `EmbyClient`: typed authentication/library/metadata/playback/session operations.
- `PlaybackCoordinator`: selects source and tracks lifecycle; maps Emby decisions to player requests.
- `PlayerEngine`: transport-neutral native playback abstraction and events.
- `CacheRepository`: user/server-scoped disposable cache; never authoritative for watched state.
- Tauri commands/events are typed, validated and minimal; no unrestricted shell access.
- Handle cancellation, retries with backoff, paging, race conditions, and offline states.

## UX requirements

- Follow `DESIGN.md`; no generic admin-dashboard aesthetic.
- Responsive window resizing, keyboard support, visible focus, reduced-motion preference, meaningful loading/empty/error states.
- Optimize actual image sizes, cache, pagination and virtualized grids; measure before optimizing.

## Testing and release

Follow `TESTING.md`. Never deploy or package a release if core playback gates are unverified. Audit redistribution and licensing obligations for mpv/FFmpeg and bundled binaries. Ship a Windows installer only after smoke tests on a clean Windows machine.

## Skills routing

- Emby integration → `.claude/skills/emby-api/SKILL.md`
- Player/native video → `.claude/skills/media-playback/SKILL.md`
- UI/UX → `.claude/skills/desktop-ui/SKILL.md`
- Lint/test/build → `.claude/skills/quality-gates/SKILL.md`
- Authentication/storage/Tauri permissions → `.claude/skills/security-review/SKILL.md`

## Communication

Write explanations and user-facing UI copy in French. Keep code identifiers, technical contracts and commit messages consistent with existing repository conventions. Ask concise questions only when blocked by an essential unknown or permission requirement.
