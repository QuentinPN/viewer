# Viewer — Technical architecture

## Proposed architecture

`Svelte 5 UI -> typed application services -> Tauri IPC -> Rust native adapters`.
Services: `EmbyClient`, `LibraryService`, `PlaybackCoordinator`, `PlayerEngine`, `CacheRepository`, `SettingsStore`, `SecureCredentialStore`.

## Components

- Frontend: Svelte 5 runes, TypeScript strict, Vite, route/state organization proportional to project size.
- Native: Tauri 2 commands/events, Rust adapters, libmpv integration after a verified Windows feasibility spike.
- Server: existing Emby API over HTTP(S); no proxy/backend by default. `EmbyClient` lives in Rust (`src-tauri/src/emby/`, ADR 0004): the token stays in Rust and in the Windows Credential Manager (`SecureCredentialStore`); the UI only sees typed models and `{kind, message}` errors. Plain HTTP is accepted only to a local-network address. Artwork reaches the WebView through the local `emby-img` protocol, fetched by Rust with the session (ADR 0005); the future image cache plugs in there.
- Cache: SQLite metadata + files for resized artwork; server/user namespacing, TTL, quotas, invalidation and schema migrations. Never store auth tokens there.

## Playback flow

1. User selects authorized item; fetch item and PlaybackInfo with supported device profile.
2. PlaybackCoordinator examines MediaSources, streams, transcoding requirements and user preferences.
3. Choose supported direct media URL or server-negotiated fallback; protect credentials and honor API contract.
4. Pass a typed playback request to native PlayerEngine; observe time/track/buffering/error events.
5. Report start/progress/stop to Emby, including seek/pause/stop transitions as supported; reconcile server watched state.
6. Release native resources on navigation/exit, including failure paths.

## Important uncertainty

Tauri WebView2 and libmpv rendering/overlay composition can conflict. Prototype embedded native surface vs separate video window and record evidence in an ADR. Do not commit to a fragile embedding mechanism without tests.

## Data and security

Secure OS credential store, TLS validation, user-scoped permissions, sanitized logs, narrow Tauri capabilities, no arbitrary shell commands, no credentials in cache. User must authorize real NAS access and any server-side change.

## Error handling

Typed error categories: authentication, authorization, unavailable server, timeout, invalid metadata, unsupported format, playback/transcode failure, local decoder failure. UI presents recoverable states. Bound retries and cancel stale requests.

## Suggested directory structure (adjust to actual repo)

```
src/
  lib/components/
  lib/services/
  lib/api/
  lib/stores/
  lib/types/
  routes-or-views/
src-tauri/src/
  commands/
  player/
  storage/
  security/
tests/
docs/adr/
.claude/skills/
```

## Architecture decision records

For significant choices create `docs/adr/NNNN-title.md` with Context, Decision, Alternatives, Consequences, Validation and Status. First ADR should settle native video integration based on a working spike.
