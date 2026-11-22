package player

// PlayerEngine is the native playback engine contract (`PlayerEngine` in
// player/mod.rs): transport-neutral, the UI and the coordinator only see these
// types; libmpv stays behind MpvEngine.
//
// Implementations must release every native resource on Shutdown, including
// after errors. Go has no Drop: whoever creates an engine must call Shutdown.
type PlayerEngine interface {
	Load(request *PlaybackRequest, source MediaSource) error
	Command(command PlayerCommand) error
	Tracks() (List[Track], error)
	Diagnostics() PlayerDiagnostics
	// Shutdown tears the engine down. The Shutdown event is emitted by the engine
	// when it is torn down; it may arrive twice if mpv quits by itself, like Rust
	// (event thread, then close_player). Exception: commands.rs:671 sends Shutdown to
	// the reporter alone on a load failure, without engine teardown: the app-shell
	// must reproduce that. Blocks until the player is gone, so not on the UI thread
	// (ADR 0003). From the event sink it returns ErrShutdownFromSink and does nothing.
	Shutdown() error
}

// EventSink receives every PlayerEvent, on the engine's event goroutine (and the
// final Shutdown on the goroutine that called Shutdown). It must not block: the
// event loop waits for it, and Shutdown waits for the event loop. So the sink must
// never call Shutdown itself (it gets ErrShutdownFromSink), and the caller must not hold, across Shutdown, a lock
// the sink takes (Rust's close_player takes the engine out of its mutex first).
type EventSink func(PlayerEvent)

// SurfaceMode is how the video is hosted (video_surface.rs, ADR 0003), reported
// to the UI by `app_info` (`surfaceMode`). Only `parent` is ported: mpv gets the
// main window's HWND as `wid` and creates its own child window, the transparent
// WebView2 composites above it. `child` and `separate` were rejected by Gate 1.
type SurfaceMode string

// SurfaceParent is the only hosting mode.
const SurfaceParent SurfaceMode = "parent"
