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

Player UI (ADR 0017): `emby_play` also returns `title` (`PlaybackTitle`), read with `/Users/{Id}/Items/{Id}` in parallel with PlaybackInfo; it is optional unless a resume point is needed. Emby playback is rendered by `src/lib/player/PlayerView.svelte`; `PlayerSpike.svelte` stays for the Gate 1 prototype and engine scenarios. Volume and mute are a display preference kept in WebView `localStorage` (`viewer.player.volume`, per PC, no secret) and applied with `setVolume` on each file load; audio and subtitle tracks follow the server's defaults unless the preferences of ADR 0035 ask otherwise.

Track preferences (ADR 0035): `emby_play(..., preferences)` gets `{tracks, subtitleStyle}` from `viewer.player.subtitles`; `emby::tracks::apply` picks the Emby stream indexes (Direct Play) or the external subtitle file, `SessionService::prepare` asks a second PlaybackInfo with `MediaSourceId` and `AudioStreamIndex` for a transcode with another audio, and `PlaybackRequest.tracks`/`subtitle_style` reach the engine, which resets `aid`/`sid`, sets the `sub-*` options at load and selects the picked streams by `ff-index` once the file is loaded.

Next episode (ADR 0018): for an episode, `PlayerView` reads `emby_item_detail` in the background and keeps `episode.next` (the server's `AdjacentTo`, across seasons); a failure or a stale answer gives no card. At the natural end of file it offers that episode; launching it calls `onPlayNext(itemId)` in `App.svelte`, which runs `emby_play(id, true)` and swaps `playing` while the player stays mounted, so the engine, its fullscreen and volume, and the focus to restore at the end are kept. Errors are reported to the connection coordinator and shown by the player.

Intro and credits (ADR 0030): `PlayerView` calls `emby_segments(itemId, external)` once per playback; a failure or a stale answer gives no button. In Rust, `EmbyService::segment_query` reads the item (its `Chapters` markers, and for an episode its series' `ProviderIds` with season and episode numbers) and `segments::SegmentService` completes each kind from IntroDB, then TheIntroDB, on its own HTTP client (no Emby header, no redirect, 3 s connect, 4 s total, 64 KiB body), with an in-memory cache of definitive answers per server, user, item and setting. The UI gets seconds, never a URL; `segments.ts` picks the active segment and the button's action.

Chapters (ADR 0034): `PlayerView` calls `emby_chapters(itemId)` once per playback, in the background. `EmbyService::chapters` reads the item's `Chapters` (`/Users/{Id}/Items/{Id}`) and `emby/chapters.rs` keeps the plain chapters (not the ADR 0030 markers), sorted, merged under 1 s, with cleaned names, none under two. Emby is the source of truth (valid for transcodes too); mpv's count is only in `player_diagnostics.chapterCount`. `chapters.ts` finds the playing chapter, the Page Up/Down target and the seek bar marks; `ChaptersPanel.svelte` lists them.

Artwork cache (ADR 0019): `EmbyService::image` checks the session and the server, then reads `ArtworkCache` (`src-tauri/src/emby/artwork_cache.rs`), and only then takes one of the 6 download slots; downloads are kept on disk. Files only, keyed by server, item, type, width and tag, in the app cache directory; 512 MiB, least recently used first. No token, server address or user in the cache. `artwork_cache_stats` reports entries, bytes, quota, hits and misses; `artwork_cache_clear` empties it for every server, removing only its `.img` files and empty folders (ADR 0021, settings screen `src/lib/settings/`). SQLite stays planned for the metadata cache, deferred while the LAN baseline shows little to gain (`docs/evidence/mesure-demarrage-2026-11-01.md`).

Home page (ADR 0010): `emby_continue_watching`, `emby_next_up` and `emby_latest(library_id)` return typed rows (`WatchItem`, `MediaItem`) built in `src-tauri/src/emby/session.rs`; `src/lib/home/` renders them. Continue Watching / Next Up cards go straight to `emby_play`; both rows are read again on `emby://playback-stopped`.

Sort and filters (ADR 0013): `emby_items` takes optional `ItemsOptions` (sort key, direction, watched filter, favorites, one genre), which only Rust turns into `SortBy`/`SortOrder`/`Filters`/`Genres`; a genre the server cannot filter on is refused before any request. `emby_genres(library_id)` lists the genres offered. `src/lib/library/options.ts` holds defaults, labels and the per-library memory (WebView `localStorage`, keyed by server, user and library; a display preference, no secret). A change restarts paging; the generation counter drops answers for older options.

Favorites (ADR 0014): `emby_set_favorite(item_id, favorite)` returns the state the server reports; `emby_favorites()` feeds the home row. The detail page changes the toggle optimistically (`src/lib/detail/favorite.ts`) and tells `LibraryBrowser.svelte`, which bumps `favoritesVersion` so `Home.svelte` reads its row again.

Version choice (ADR 0036): `MediaInfo.id` names each version of an item; `emby_play` takes an optional `media_source_id`, validated in Rust and sent as `MediaSourceId` in the PlaybackInfo body, and `playback::plan` keeps only that source (`NoCompatibleStream` if the server does not return it, never another version). Reports, the transcode fallback and « Reprendre » follow it. `src/lib/detail/versions.ts` builds the labels and keeps the choice per server, user and item in WebView `localStorage`; only the detail page uses it.

Episode page (ADR 0016): `emby_item_detail` also accepts an episode and returns its `EpisodeContext` (series, season, number) with the previous and next episodes from `/Shows/{SeriesId}/Episodes?AdjacentTo=` (across seasons, other series filtered out). `ItemDetail.svelte` renders it and nests itself: a series page opens an episode page over its own section (`inert`, `visibility: hidden`), previous/next replace it, and the return calls `EpisodeList.reveal(episodeId, seasonId)`.

Home hero (ADR 0015): `emby_hero()` returns up to five `HeroItem`s chosen in Rust (resume first, then recent unwatched films and series with a backdrop; a series' play target is its first unwatched episode). The item carries the `MediaItem` the detail page opens and the id the main button plays; `src/lib/home/Hero.svelte` only displays it.

Search (ADR 0011): `emby_search(query)` cleans the query in Rust and returns `SearchResults` (films, series and episodes, each with a "more" flag; episodes are filtered on their own title in Rust because Emby also matches the series name, ADR 0020); `src/lib/search/` renders them in place of the current view of `LibraryBrowser.svelte`, whose sidebar holds the field.

Persistent session and reconnection (ADR 0012): `emby_restore` returns a `SessionStatus` (authenticated, server unreachable, authentication required, no session) instead of failing. Only a 401 or a disabled/deleted account deletes the token of that server/user pair. The other sessions are set aside (at most 8, `emby_resume_saved`); signed-out and rejected sessions stay there without token, and the pre-connection shelf (ADR 0103) lists them all through `emby_shelf` and forgets one through `emby_forget_server`. An epoch counter in `EmbyService` cancels checks that a sign-in, sign-out or server change made obsolete. In the UI, `src/lib/connection/` holds a pure state machine (`machine.ts`: restoring, authenticated, server_unreachable, authentication_required, server_configuration, signed_out) and a `ConnectionCoordinator`. The coordinator keeps one check in flight, uses backoff with jitter (2–30 s, 6 automatic attempts), and reacts to the `online` event and to the errors reported by views. `App.svelte` keeps the signed-in app mounted (inert) under the recovery and reauthentication screens. On return it bumps a `refresh` counter, so views retry only what failed and keep their view, selection and scroll. A playback that ends in error, or at an end of file more than 30 s before the end (observed through `eof-reached`, since `keep-open` emits no `end-file`), counts as interrupted: the stop report carries the last position, and the UI offers "Reprendre". Stream reconnection and its limit are in the engine (ADR 0022): FFmpeg reconnects after a brief cut, and `StallWatch` ends a playback whose position stays frozen 30 s, independently of any UI. It also ends an opening with no `FileLoaded` after 45 s, and any end in error stops mpv, so that it does not walk a failed HLS list as a playlist (ADR 0027).

## Important uncertainty

Tauri WebView2 and libmpv rendering/overlay composition can conflict. Prototype embedded native surface vs separate video window and record evidence in an ADR. Do not commit to a fragile embedding mechanism without tests.

## Data and security

Secure OS credential store, TLS validation, user-scoped permissions, sanitized logs, narrow Tauri capabilities, no arbitrary shell commands, no credentials in cache. User must authorize real server access and any server-side change. The player follows no redirects and FFmpeg's own opens are limited to network protocols; a segment on another host named by an HLS playlist still receives the token, an accepted risk (ADR 0025). FFmpeg validates HTTPS against the Windows root certificates, exported to a PEM file before each HTTPS load (ADR 0026). The token stays out of the WebView, and `player_open`, which takes a URL from the WebView, is refused outside development builds.

Logs (ADR 0033): `src-tauri/src/diagnostics.rs` is the `log` backend. It writes `viewer.log` in the app log directory, keeps 3 files of 2 MiB and redacts every line (`diagnostics::redact`) before writing. A panic hook writes the panic and its backtrace there. `diagnostics_open_log_dir` takes no argument and opens only that folder in the explorer. The only other caller of that explorer launch is `ur_open_trailer` (ADR 0127): it takes a YouTube video id, validates it and builds the `https://www.youtube.com/watch?v=` address itself, so the WebView never chooses what is opened.

## Request (ADR 0125)

A second remote service, separate from Emby: the household's request service. Spec `docs/superpowers/specs/2026-11-19-request-integration-design.md`.

- Boundary: Rust `src-tauri/src/request/` (`client.rs` reqwest client, `service.rs` session and token rules, `commands.rs` typed `ur_*` Tauri commands, `images.rs` image protocol); frontend `src/lib/requests/` (`api.ts` wraps the commands) never builds a URL or sees the token. Android TV: `request/` (OkHttp `RequestClient`, `RequestRepository`, `RequestStore`) and `ui/requests/`.
- Sign-in: `POST /api/auth/emby-token` exchanges the Emby identity for a session token; everything else is `Authorization: Bearer`. One exchange at a time (single-flight); a 401 drops the token and retries once. The server must report API major `1` and the features `auth.emby-token`, `auth.bearer`, `auth.sessions`, `media.item-id`, else the tab says « trop ancien ».
- Token: OS secure storage (`CredentialStore` on desktop, Keystore on the TV), per Emby account (`request/{embyServerId}/{embyUserId}`), bound to the address that issued it; never in a URL, log or SQLite. The address and the signed-out flag live in `request.json`.
- Images: the `requestimg` protocol (`http://requestimg.localhost/{size}/{file}`) is served by Rust with the Bearer and a disk cache; `size` in w185/w342/w500/w780/w1280, `file` `[A-Za-z0-9_-]{1,64}` + jpg/jpeg/png/webp, else 400. CSP `img-src` gains only that origin. TV: Coil on the authenticated client.
- Shared contract: `contracts/request/openapi.json` (pinned) and `contracts/request/fixtures/`, read by the Vitest contract test, the Rust tests and the Kotlin tests.

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
