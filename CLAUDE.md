# Viewer — Claude Code project instructions

## Mission

Build a production-quality Windows 10/11 desktop client for an existing Emby server hosted on a mini-PC (the media files live on a QNAP NAS). Prioritize reliable media playback, responsiveness, accessibility, security, and maintainability. The experience should take inspiration from Infuse without copying proprietary assets or layouts pixel-for-pixel. French is the default UI language.

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
3. **Protect secrets.** Store user tokens in OS-backed secure storage; no credentials in git, logs, plain SQLite or URL query strings when a safer supported option exists. Validate TLS. Ask before connecting to the real Emby server or the NAS, altering server configuration, deleting data, or uploading telemetry.
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

## Compact instructions

Auto-compaction is triggered at 20% context usage (`CLAUDE_AUTOCOMPACT_PCT_OVERRIDE=20` in `.claude/settings.local.json`). When compacting, keep everything needed to continue without re-explaining:

- Where we are: current branch, uncommitted files, last commit.
- Every decision taken and why.
- What the user asked for or refused, in their own words.
- What remains to do.
- Key files, and pitfalls already encountered (failed commands, workarounds, environment quirks).

## Boilerplate (claude-boilerplate)

Added from `QuentinPN/claude-boilerplate`. The project sections above take precedence when they conflict.
### Orchestration

```
Opus 5.5 main session plans + signs off
├── .claude/agents/explorer.md → model: haiku  · Read, Grep, Glob
├── .claude/agents/fixer.md    → model: sonnet · Read, Edit, Bash
├── .claude/agents/checker.md  → model: sonnet · cross-checks every patch
└── .claude/settings.json      → PostToolUse hook runs tests after each edit
```

- The main session stays on Opus 5.5: **plan, delegate, read summaries only, sign off.**
- Search and reading → `explorer` (summary < 200 words).
- Patches → `fixer` (smallest patch that fixes the issue).
- Every patch → `checker` (reviews it against its callers, reports regressions) before sign-off.
- Every subagent gets its own context window. The main session never reads raw output (logs, full files, diffs) when a subagent can summarize it.

### DRY (mandatory)

Code MUST be DRY. Do not duplicate definitions or logic. When the same constant, type, validation, or block of logic appears in more than one place, abstract it into a shared helper/utility and reuse it. Flag copy-pasted logic and repeated literals/definitions that should be centralized.

### Slash commands

| Command | Purpose |
|---|---|
| `/state-machines` | Document explicit/implicit state machines in `docs/STATE_MACHINES.md`, find reachable invalid states, fix with regression tests |
| `/dead-code` | Separate branch: remove every `src/` function/file without a caller |
| `/dedupe` | Separate branch: merge duplicated logic in `src/` into single functions |
| `/unused-deps` | Separate branch: remove unused `package.json` dependencies one by one |
| `/clean-worktrees` | Remove worktrees whose branch is already merged, log them in `MENAGE.md` |

### Behavioral guidelines

Source: [multica-ai/andrej-karpathy-skills](https://github.com/multica-ai/andrej-karpathy-skills/blob/main/CLAUDE.md)

Behavioral guidelines to reduce common LLM coding mistakes. Merge with project-specific instructions as needed.

**Tradeoff:** These guidelines bias toward caution over speed. For trivial tasks, use judgment.

#### 1. Think Before Coding

**Don't assume. Don't hide confusion. Surface tradeoffs.**

Before implementing:
- State your assumptions explicitly. If uncertain, ask.
- If multiple interpretations exist, present them - don't pick silently.
- If a simpler approach exists, say so. Push back when warranted.
- If something is unclear, stop. Name what's confusing. Ask.

#### 2. Simplicity First

**Minimum code that solves the problem. Nothing speculative.**

- No features beyond what was asked.
- No abstractions for single-use code. (Duplicated code is not single-use: see DRY above.)
- No "flexibility" or "configurability" that wasn't requested.
- No error handling for impossible scenarios.
- If you write 200 lines and it could be 50, rewrite it.

Ask yourself: "Would a senior engineer say this is overcomplicated?" If yes, simplify.

#### 3. Surgical Changes

**Touch only what you must. Clean up only your own mess.**

When editing existing code:
- Don't "improve" adjacent code, comments, or formatting.
- Don't refactor things that aren't broken.
- Match existing style, even if you'd do it differently.
- If you notice unrelated dead code, mention it - don't delete it.

When your changes create orphans:
- Remove imports/variables/functions that YOUR changes made unused.
- Don't remove pre-existing dead code unless asked.

The test: Every changed line should trace directly to the user's request.

#### 4. Goal-Driven Execution

**Define success criteria. Loop until verified.**

Transform tasks into verifiable goals:
- "Add validation" → "Write tests for invalid inputs, then make them pass"
- "Fix the bug" → "Write a test that reproduces it, then make it pass"
- "Refactor X" → "Ensure tests pass before and after"

For multi-step tasks, state a brief plan:
```
1. [Step] → verify: [check]
2. [Step] → verify: [check]
3. [Step] → verify: [check]
```

Strong success criteria let you loop independently. Weak criteria ("make it work") require constant clarification.

**These guidelines are working if:** fewer unnecessary changes in diffs, fewer rewrites due to overcomplication, and clarifying questions come before implementation rather than after mistakes.

