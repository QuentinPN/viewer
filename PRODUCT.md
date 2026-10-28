# Viewer — Product specification

## Vision

A polished, fast Windows desktop alternative to the official Emby client. Read media from the user's QNAP-hosted Emby server, favor direct playback, and preserve Emby users, libraries and playback progress.

## Target users and constraints

Initially a single Windows 10/11 x64 user with Emby Premiere. Multiple users and multiple servers should be architecturally possible, but not required for MVP. French UI first. LAN and HTTPS remote access. No extra backend.

## MVP (P0)

- Add server URL, validate connection, sign in/out securely, show recoverable connection errors.
- Sign in once: later launches reopen the session automatically; a NAS outage shows a recovery screen (retry, change server) instead of the sign-in form; only a session rejected by the server asks for the password again; switching server keeps the previous session to return to (ADR 0012).
- Browse authorized film and series libraries; posters, metadata, search and pagination.
- Movie/episode details; play, pause, seek, fullscreen, select audio/subtitle tracks.
- Select media source using server playback info; expose Direct Play/Direct Stream/Transcode diagnostic status.
- Resume playback; send playback started/progress/stopped; display watched status from Emby.
- Functional native video renderer, stable teardown, hardware decode when supported.
- Accessible keyboard navigation, loading/empty/error states, installer and crash diagnostics without secrets.

## P1

Continue watching, next episodes, favorites, filtering/sorting, chapters, advanced subtitle preferences, cache management, auto-next episode, multi-version selection, remote bitrate preference.

## P2 / exploratory

Multiple Emby servers, offline metadata mode, advanced HDR/audio passthrough support, auto-update, more library types. These are not MVP promises.

## Out of scope initially

Media server implementation, transcoding engine, DRM bypass, scraping third-party catalogs, media downloads/offline sync, TV/mobile clients.

## Acceptance criteria

- Can sign in to a permitted Emby test account without exposing secrets.
- Can browse a representative library without blocking UI.
- On Windows, can play a real local MKV and an authorized Emby HTTP media stream; seek, fullscreen, switch tracks and close without leaked player process.
- Playback progress is reflected on Emby after resume/stop, verified against real server state.
- An unsupported file fails gracefully with actionable diagnostics or negotiated fallback.
- Lint, type checks, unit tests and release build pass in supported environment; exceptions explicitly documented.

## Success measures (measure, do not assume)

Cold start time, time to interactive, navigation latency, image cache hit rate, playback start latency, dropped frames, RAM usage, server transcoding frequency, crashes. Establish baselines before setting numerical targets.

## Constraints and risks

Emby Premiere does not guarantee third-party feature entitlements; verify. Codec, HDR and audio passthrough support depend on platform and licensing. Windows WebView/native video overlay integration is a critical feasibility risk.
