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

Implemented so far (ADR 0007): steps 1–4 for Direct Play / Direct Stream in `src-tauri/src/emby/playback.rs` (`emby_play` command). The stream URL comes from the server with its `api_key` removed; the token goes to mpv as an `X-Emby-Token` header. Step 5 is implemented in `src-tauri/src/emby/reporting.rs` (ADR 0008): engine events become start/progress/stopped reports sent from Rust, the stop is flushed on window close, and the watched state and resume point are read back from the server afterwards. HLS transcoding (ADR 0009) uses the server's `TranscodingUrl` the same way; text subtitles are delivered by the server as external files and added to mpv asynchronously, and the stop report ends the server's encoding.

Player UI (ADR 0017): `emby_play` also returns `title` (`PlaybackTitle`), read with `/Users/{Id}/Items/{Id}` in parallel with PlaybackInfo; it is optional unless a resume point is needed. Emby playback is rendered by `src/lib/player/PlayerView.svelte`; `PlayerSpike.svelte` stays for the Gate 1 prototype and engine scenarios. Volume and mute are a display preference kept in WebView `localStorage` (`viewer.player.volume`, per PC, no secret) and applied with `setVolume` on each file load; audio and subtitle tracks stay chosen by the server's defaults.

Next episode (ADR 0018): for an episode, `PlayerView` reads `emby_item_detail` in the background and keeps `episode.next` (the server's `AdjacentTo`, across seasons); a failure or a stale answer gives no card. At the natural end of file it offers that episode; launching it calls `onPlayNext(itemId)` in `App.svelte`, which runs `emby_play(id, true)` and swaps `playing` while the player stays mounted, so the engine, its fullscreen and volume, and the focus to restore at the end are kept. Errors are reported to the connection coordinator and shown by the player.

Artwork cache (ADR 0019): `EmbyService::image` checks the session and the server, then reads `ArtworkCache` (`src-tauri/src/emby/artwork_cache.rs`), and only then takes one of the 6 download slots; downloads are kept on disk. Files only, keyed by server, item, type, width and tag, in the app cache directory; 512 MiB, least recently used first. No token, server address or user in the cache. `artwork_cache_stats` reports entries, bytes, quota, hits and misses; `artwork_cache_clear` empties it for every server, removing only its `.img` files and empty folders (ADR 0021, settings screen `src/lib/settings/`). SQLite stays planned for the metadata cache.

Home page (ADR 0010): `emby_continue_watching`, `emby_next_up` and `emby_latest(library_id)` return typed rows (`WatchItem`, `MediaItem`) built in `src-tauri/src/emby/session.rs`; `src/lib/home/` renders them. Continue Watching / Next Up cards go straight to `emby_play`; both rows are read again on `emby://playback-stopped`.

Sort and filters (ADR 0013): `emby_items` takes optional `ItemsOptions` (sort key, direction, watched filter, favorites, one genre), which only Rust turns into `SortBy`/`SortOrder`/`Filters`/`Genres`; a genre the server cannot filter on is refused before any request. `emby_genres(library_id)` lists the genres offered. `src/lib/library/options.ts` holds defaults, labels and the per-library memory (WebView `localStorage`, keyed by server, user and library; a display preference, no secret). A change restarts paging; the generation counter drops answers for older options.

Favorites (ADR 0014): `emby_set_favorite(item_id, favorite)` returns the state the server reports; `emby_favorites()` feeds the home row. The detail page changes the toggle optimistically (`src/lib/detail/favorite.ts`) and tells `LibraryBrowser.svelte`, which bumps `favoritesVersion` so `Home.svelte` reads its row again.

Episode page (ADR 0016): `emby_item_detail` also accepts an episode and returns its `EpisodeContext` (series, season, number) with the previous and next episodes from `/Shows/{SeriesId}/Episodes?AdjacentTo=` (across seasons, other series filtered out). `ItemDetail.svelte` renders it and nests itself: a series page opens an episode page over its own section (`inert`, `visibility: hidden`), previous/next replace it, and the return calls `EpisodeList.reveal(episodeId, seasonId)`.

Home hero (ADR 0015): `emby_hero()` returns up to five `HeroItem`s chosen in Rust (resume first, then recent unwatched films and series with a backdrop; a series' play target is its first unwatched episode). The item carries the `MediaItem` the detail page opens and the id the main button plays; `src/lib/home/Hero.svelte` only displays it.

Search (ADR 0011): `emby_search(query)` cleans the query in Rust and returns `SearchResults` (films, series and episodes, each with a "more" flag; episodes are filtered on their own title in Rust because Emby also matches the series name, ADR 0020); `src/lib/search/` renders them in place of the current view of `LibraryBrowser.svelte`, whose sidebar holds the field.

Persistent session and reconnection (ADR 0012): `emby_restore` returns a `SessionStatus` (authenticated, server unreachable, authentication required, no session) instead of failing. Only a 401 or a disabled/deleted account deletes the token of that server/user pair. The other sessions are set aside (at most 8, `emby_saved_sessions`, `emby_resume_saved`), and an epoch counter in `EmbyService` cancels checks that a sign-in, sign-out or server change made obsolete. In the UI, `src/lib/connection/` holds a pure state machine (`machine.ts`: restoring, authenticated, server_unreachable, authentication_required, server_configuration, signed_out) and a `ConnectionCoordinator`. The coordinator keeps one check in flight, uses backoff with jitter (2–30 s, 6 automatic attempts), and reacts to the `online` event and to the errors reported by views. `App.svelte` keeps the signed-in app mounted (inert) under the recovery and reauthentication screens. On return it bumps a `refresh` counter, so views retry only what failed and keep their view, selection and scroll. A playback that ends in error, or at an end of file more than 30 s before the end (observed through `eof-reached`, since `keep-open` emits no `end-file`), counts as interrupted: the stop report carries the last position, and the UI offers "Reprendre".

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
