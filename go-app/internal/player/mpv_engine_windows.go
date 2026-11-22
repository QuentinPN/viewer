package player

// libmpv implementation of PlayerEngine, port of src-tauri/src/player/mpv.rs.
//
// Threading model: libmpv's client API is thread-safe except mpv_wait_event, which
// only the event goroutine started here calls. Shutdown stops that goroutine (flag
// + mpv_wakeup) and waits for it before mpv_terminate_destroy, so the handle is
// never destroyed while the event loop still uses it. internal/mpv also refuses any
// call made after the destroy (ErrDestroyed, given here as NotOpen).

import (
	"errors"
	"fmt"
	"log/slog"
	"runtime"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"
	"unicode"

	"viewer/internal/mpv"
	"viewer/internal/redact"
	"viewer/internal/tlsroots"
)

// MpvConfig is the configuration of one engine (`MpvConfig` in mpv.rs).
type MpvConfig struct {
	// WID is the native window handle (HWND) mpv renders into, given by the caller
	// (parent mode). 0 with Headless is used by tests;
	// 0 alone lets mpv open its own window.
	WID uintptr
	// Headless sets `vo=null` / `ao=null`: decode without presenting (tests).
	Headless bool
	// Hwdec is mpv's `hwdec`. `auto-safe` only enables decoders mpv considers reliable.
	Hwdec string
	// Silent sets `ao=null` with the video still shown: scripted UI tests must not
	// play sound on the user's speakers.
	Silent bool
	// TLSRoots is where to export the Windows root certificates for HTTPS sources.
	// Empty: `tls-ca-file` stays empty and every HTTPS source is refused.
	TLSRoots string
	// OpenLimit is the wait for a file to load. Only tests shorten it.
	OpenLimit time.Duration
	// Logger receives the engine's warnings and mpv's own (redacted); nil is slog.Default().
	Logger *slog.Logger
}

// DefaultMpvConfig is `MpvConfig::default()`.
func DefaultMpvConfig() MpvConfig {
	return MpvConfig{Hwdec: "auto-safe", OpenLimit: OpenLimit}
}

// Observed property ids and async reply ids.
const (
	obsTimePos  = 1
	obsDuration = 2
	obsPause    = 3
	obsCache    = 4
	obsTracks   = 5
	obsEOF      = 6
	// replySubtitle is the reply id of the asynchronous `sub-add` commands.
	replySubtitle = 1
	// replyStallStop is the reply id of the `stop` sent when a stream stalls.
	replyStallStop = 2
)

type pendingSubtitle struct {
	target string
	sub    ExternalSubtitle
}

// MpvEngine is the libmpv PlayerEngine.
type MpvEngine struct {
	api      *mpv.API
	h        *mpv.Handle
	sink     EventSink
	log      *slog.Logger
	tlsRoots string

	mu sync.Mutex
	// pendingSubtitles of the last Load, added by the event loop on FILE_LOADED
	// (`sub-add` needs a loaded file).
	pendingSubtitles []pendingSubtitle
	// pendingTracks of the last Load, selected by the event loop on FILE_LOADED,
	// once mpv knows the file's streams.
	pendingTracks TrackSelection

	stop atomic.Bool
	// eventGoid is the id of the event goroutine, to refuse Shutdown from the sink.
	eventGoid atomic.Uint64
	done      chan struct{}
	destroyed atomic.Bool
}

var _ PlayerEngine = (*MpvEngine)(nil)

// NewMpvEngine loads libmpv from library (a path chosen by the app, never remote
// input), configures and initialises a player, and starts the event goroutine
// that forwards PlayerEvents to sink.
func NewMpvEngine(library string, config MpvConfig, sink EventSink) (*MpvEngine, error) {
	api, err := mpv.Load(library)
	if err != nil {
		var unsupported *mpv.UnsupportedAPIError
		if errors.As(err, &unsupported) {
			return nil, newError(UnsupportedAPI, fmt.Sprintf("%d.%d", unsupported.Major, unsupported.Minor))
		}
		return nil, newError(LibraryUnavailable, err.Error())
	}
	h, err := api.Create()
	if err != nil {
		return nil, mpvError("mpv_create", "NULL")
	}
	if sink == nil {
		sink = func(PlayerEvent) {}
	}
	logger := config.Logger
	if logger == nil {
		logger = slog.Default()
	}
	e := &MpvEngine{api: api, h: h, sink: sink, log: logger, tlsRoots: config.TLSRoots, done: make(chan struct{})}
	if err := e.configure(config); err != nil {
		// The handle is not shared with any goroutine yet.
		h.TerminateDestroy()
		return nil, err
	}
	go e.eventLoop(config.OpenLimit)
	return e, nil
}

func (e *MpvEngine) configure(config MpvConfig) error {
	// Hardening: no user config, scripts, ytdl hook or built-in key/mouse bindings.
	// All input goes through the app UI so focus stays with the WebView.
	for _, o := range [][2]string{
		{"config", "no"},
		{"load-scripts", "no"},
		{"ytdl", "no"},
		{"osc", "no"},
		{"input-default-bindings", "no"},
		{"input-vo-keyboard", "no"},
		{"input-cursor", "no"},
		{"cursor-autohide", "no"},
		{"terminal", "no"},
		{"idle", "yes"},
		{"keep-open", "yes"},
		{"hwdec", config.Hwdec},
		// HTTP through FFmpeg rather than the curl stream, whose retries are
		// immediate and give up within the same millisecond: FFmpeg reopens the
		// stream after a short network outage.
		{"curl-enabled", "no"},
		{"stream-lavf-o", streamLavfOptions},
		{"network-timeout", networkTimeout},
		{"demuxer-lavf-o", demuxerLavfOptions},
		{"tls-verify", "yes"},
	} {
		if err := e.setOption(o[0], o[1]); err != nil {
			return err
		}
	}
	if config.Headless {
		if err := e.setOption("vo", "null"); err != nil {
			return err
		}
	}
	if config.Headless || config.Silent {
		if err := e.setOption("ao", "null"); err != nil {
			return err
		}
	}
	if config.WID != 0 {
		if err := e.setOption("wid", strconv.FormatUint(uint64(config.WID), 10)); err != nil {
			return err
		}
		if err := e.setOption("force-window", "yes"); err != nil {
			return err
		}
	}
	if err := fromMpv(e.h.RequestLogMessages("warn"), "request_log_messages"); err != nil {
		return err
	}
	if err := fromMpv(e.h.Initialize(), "mpv_initialize"); err != nil {
		return err
	}
	for _, o := range []struct {
		id     uint64
		name   string
		format int32
	}{
		{obsTimePos, "time-pos", mpv.FormatDouble},
		{obsDuration, "duration", mpv.FormatDouble},
		{obsPause, "pause", mpv.FormatFlag},
		{obsCache, "paused-for-cache", mpv.FormatFlag},
		{obsTracks, "track-list", mpv.FormatString},
		// With `keep-open`, mpv stays on the last frame at the end of the file and
		// sends no `end-file`: `eof-reached` is the end, including a stream cut
		// short by the network once the cache is drained.
		{obsEOF, "eof-reached", mpv.FormatFlag},
	} {
		if err := fromMpv(e.h.ObserveProperty(o.id, o.name, o.format), o.name); err != nil {
			return err
		}
	}
	return nil
}

// fromMpv turns a binding error into the PlayerError Rust gives for the same call:
// context is what Rust names (the option, property or first command argument), and
// only mpv's message is kept from the binding error.
func fromMpv(err error, context string) error {
	if err == nil {
		return nil
	}
	var me *mpv.Error
	switch {
	case errors.As(err, &me):
		return mpvError(context, me.Message)
	case errors.Is(err, mpv.ErrDestroyed):
		// Rust cannot reach a destroyed engine; Go can, and gets NotOpen.
		return ErrNotOpen
	}
	return mpvError(context, err.Error())
}

// noNUL is Rust's CString::new: a string holding NUL never reaches libmpv.
func noNUL(values ...string) error {
	for _, v := range values {
		if strings.IndexByte(v, 0) >= 0 {
			return invalidSource("caractère NUL")
		}
	}
	return nil
}

func (e *MpvEngine) setOption(name, value string) error {
	if err := noNUL(name, value); err != nil {
		return err
	}
	return fromMpv(e.h.SetOption(name, value), name)
}

func (e *MpvEngine) setProperty(name, value string) error {
	if err := noNUL(name, value); err != nil {
		return err
	}
	return fromMpv(e.h.SetProperty(name, value), name)
}

func commandContext(args []string) string {
	if len(args) > 0 {
		return args[0]
	}
	return "command"
}

func (e *MpvEngine) command(args ...string) error {
	if err := noNUL(args...); err != nil {
		return err
	}
	return fromMpv(e.h.Command(args...), commandContext(args))
}

// commandAsync runs a command on mpv's threads; the outcome comes back as an
// EventCommandReply carrying reply.
func (e *MpvEngine) commandAsync(reply uint64, args ...string) error {
	if err := noNUL(args...); err != nil {
		return err
	}
	return fromMpv(e.h.CommandAsync(reply, args...), commandContext(args))
}

// Property is a raw property read for diagnostics and tests. Not exposed over IPC.
func (e *MpvEngine) Property(name string) (string, bool) {
	if strings.IndexByte(name, 0) >= 0 {
		return "", false
	}
	return e.h.Property(name)
}

// PositionSeconds is the playback position (`time-pos`); ok is false before a file plays.
func (e *MpvEngine) PositionSeconds() (float64, bool) {
	s, ok := e.Property("time-pos")
	if !ok {
		return 0, false
	}
	v, err := strconv.ParseFloat(s, 64)
	return v, err == nil
}

// SelectedStreams gives the FFmpeg indexes of the selected audio and subtitle
// streams; nil is unknown.
func (e *MpvEngine) SelectedStreams() (audio, subtitle *int32) {
	list, _ := e.Property("track-list")
	return selectedStreams(list)
}

// Load is `PlayerEngine::load`.
func (e *MpvEngine) Load(request *PlaybackRequest, source MediaSource) error {
	if source.IsHTTPS() && e.tlsRoots != "" {
		if err := tlsroots.Refresh(e.tlsRoots); err != nil {
			return newError(CertificateStore, err.Error())
		}
		if err := e.setProperty("tls-ca-file", e.tlsRoots); err != nil {
			return err
		}
	}
	if err := e.command("change-list", "http-header-fields", "clr", ""); err != nil {
		return err
	}
	for _, h := range request.HTTPHeaders {
		name, value := h[0], h[1]
		if strings.ContainsAny(name, ":\r\n") || strings.ContainsAny(value, "\r\n") {
			return invalidSource("en-tête HTTP invalide")
		}
		if err := e.command("change-list", "http-header-fields", "append", name+": "+value); err != nil {
			return err
		}
	}
	start := "none"
	if s := request.StartSeconds; s != nil {
		if _, err := finite(*s); err == nil && *s > 0 {
			start = strconv.FormatFloat(*s, 'f', 3, 64)
		}
	}
	if err := e.setProperty("start", start); err != nil {
		return err
	}
	subtitles := make([]pendingSubtitle, 0, len(request.Subtitles))
	for _, s := range request.Subtitles {
		src, err := ParseSource(s.Source)
		if err != nil {
			return err
		}
		subtitles = append(subtitles, pendingSubtitle{target: src.MpvArg(), sub: s})
	}
	e.mu.Lock()
	e.pendingSubtitles = subtitles
	e.mu.Unlock()
	for _, o := range subtitleOptions(request.SubtitleStyle) {
		if err := e.setProperty(o[0], o[1]); err != nil {
			return err
		}
	}
	// `aid` and `sid` are options too: a track picked during the last file would
	// otherwise carry over to this one.
	if err := e.setProperty("aid", "auto"); err != nil {
		return err
	}
	sid := "auto"
	if request.Tracks.Subtitle.Kind == TrackOff {
		sid = "no"
	}
	if err := e.setProperty("sid", sid); err != nil {
		return err
	}
	e.mu.Lock()
	e.pendingTracks = request.Tracks
	e.mu.Unlock()
	if err := e.setProperty("pause", "no"); err != nil {
		return err
	}
	return e.command("loadfile", source.MpvArg(), "replace")
}

// Command is `PlayerEngine::command`.
func (e *MpvEngine) Command(c PlayerCommand) error {
	switch c.Type {
	case CmdSetPaused:
		if c.Paused {
			return e.setProperty("pause", "yes")
		}
		return e.setProperty("pause", "no")
	case CmdTogglePause:
		return e.command("cycle", "pause")
	case CmdSeekAbsolute, CmdSeekRelative:
		v, err := finite(c.Seconds)
		if err != nil {
			return err
		}
		mode := "absolute+exact"
		if c.Type == CmdSeekRelative {
			mode = "relative+exact"
		}
		return e.command("seek", formatFloat(v), mode)
	case CmdSetAudioTrack:
		return e.setProperty("aid", trackValue(c.ID))
	case CmdSetSubtitleTrack:
		return e.setProperty("sid", trackValue(c.ID))
	case CmdSetVolume:
		v, err := finite(clamp(c.Percent, 0, 130))
		if err != nil {
			return err
		}
		return e.setProperty("volume", formatFloat(v))
	case CmdSetSpeed:
		v, err := finite(clamp(c.Speed, 0.25, 4))
		if err != nil {
			return err
		}
		return e.setProperty("speed", formatFloat(v))
	case CmdSetNightMode:
		return e.setProperty("af", nightFilter(c.On))
	case CmdSetSubtitleDelay, CmdSetAudioDelay:
		v, err := delayValue(c.Seconds)
		if err != nil {
			return err
		}
		if c.Type == CmdSetSubtitleDelay {
			return e.setProperty("sub-delay", v)
		}
		return e.setProperty("audio-delay", v)
	case CmdSetVideoFit:
		for _, p := range c.Fit.MpvProperties() {
			if err := e.setProperty(p[0], p[1]); err != nil {
				return err
			}
		}
		return nil
	case CmdStop:
		return e.command("stop")
	}
	// Unreachable from JSON (UnmarshalJSON refuses unknown tags).
	return invalidSource(fmt.Sprintf("commande inconnue %q", c.Type))
}

// Tracks is `PlayerEngine::tracks`.
func (e *MpvEngine) Tracks() (List[Track], error) {
	list, ok := e.Property("track-list")
	if !ok {
		list = "[]"
	}
	return parseTracks(list)
}

// Diagnostics is `PlayerEngine::diagnostics`. A non-finite float is nil, which
// writes the same JSON null as serde_json writes for Rust's Some(NaN).
func (e *MpvEngine) Diagnostics() PlayerDiagnostics {
	s := func(n string) *string {
		if v, ok := e.Property(n); ok && v != "" {
			return &v
		}
		return nil
	}
	i := func(n string) *int64 {
		if v := s(n); v != nil {
			if x, err := strconv.ParseInt(*v, 10, 64); err == nil {
				return &x
			}
		}
		return nil
	}
	f := func(n string) *float64 {
		if v := s(n); v != nil {
			if x, err := strconv.ParseFloat(*v, 64); err == nil {
				if _, err := finite(x); err == nil {
					return &x
				}
			}
		}
		return nil
	}
	var paused *bool
	if v := s("pause"); v != nil {
		b := *v == "yes"
		paused = &b
	}
	options := List[[2]string]{}
	for _, n := range subtitleOptionNames {
		if v := s(n); v != nil {
			options = append(options, [2]string{n, *v})
		}
	}
	return PlayerDiagnostics{
		MpvVersion:            s("mpv-version"),
		FfmpegVersion:         s("ffmpeg-version"),
		VideoOutput:           s("current-vo"),
		GpuAPI:                s("current-gpu-context"),
		HwdecCurrent:          s("hwdec-current"),
		VideoCodec:            s("video-codec"),
		VideoWidth:            i("width"),
		VideoHeight:           i("height"),
		AudioCodec:            s("audio-codec-name"),
		AudioOutput:           s("current-ao"),
		Container:             s("file-format"),
		OsdWidth:              i("osd-width"),
		OsdHeight:             i("osd-height"),
		TimePos:               f("time-pos"),
		Duration:              f("duration"),
		Paused:                paused,
		Volume:                f("volume"),
		FrameDropCount:        i("frame-drop-count"),
		DecoderFrameDropCount: i("decoder-frame-drop-count"),
		ChapterCount:          i("chapters"),
		SubtitleOptions:       options,
	}
}

// Shutdown is `PlayerEngine::shutdown`, plus the Shutdown event that Rust sends
// from AppState::close_player (lib.rs): after every teardown, whoever started it,
// the UI must return to idle. Idempotent; blocks until mpv is gone, so
// never call it on the UI thread.
//
// Called from the event sink it would wait for the event loop, which waits for the
// sink: it returns ErrShutdownFromSink at once and tears nothing down.
func (e *MpvEngine) Shutdown() error {
	if id := e.eventGoid.Load(); id != 0 && id == goid() {
		return ErrShutdownFromSink
	}
	if e.destroyed.Swap(true) {
		return nil
	}
	e.stop.Store(true)
	// The handle is still alive; wakeup only interrupts mpv_wait_event.
	e.h.Wakeup()
	<-e.done
	// The event loop has exited, so nothing else waits on the handle.
	e.h.TerminateDestroy()
	e.sink(Shutdown{})
	return nil
}

// goid is the current goroutine's id, read from its stack header ("goroutine 7 [").
func goid() uint64 {
	var buf [32]byte
	n := runtime.Stack(buf[:], false)
	id, _ := strconv.ParseUint(strings.Fields(string(buf[:n]))[1], 10, 64)
	return id
}

// emit feeds the watch, then the sink.
func (e *MpvEngine) emit(stall *StallWatch, event PlayerEvent) {
	stall.OnEvent(event, time.Now())
	e.sink(event)
}

// endStalled ends the playback on an error, which follows the path of any failure
// (fallback or message during an opening; stop report with the last position,
// "Lecture interrompue" afterwards), and mpv stops its retries.
func (e *MpvEngine) endStalled(stall Stall) {
	var detail string
	if stall == StallOpening {
		e.log.Warn("flux non ouvert dans le délai : ouverture abandonnée")
		detail = "le flux ne s'ouvre pas"
	} else {
		e.log.Warn(fmt.Sprintf("position figée depuis %d s : lecture interrompue", int(StallLimit/time.Second)))
		detail = "le flux ne répond plus"
	}
	// Asynchronous: this goroutine must keep draining events while mpv cancels the
	// read. Before the event: the UI's fallback loads a file in reaction, and this
	// stop must reach mpv first or it would cancel that load.
	if err := e.commandAsync(replyStallStop, "stop"); err != nil {
		e.log.Warn("arrêt après blocage du flux : " + err.Error())
	}
	e.sink(EndFile{Reason: EndError, Error: &detail})
}

func (e *MpvEngine) eventLoop(openLimit time.Duration) {
	defer close(e.done)
	e.eventGoid.Store(goid())
	stall := NewStallWatch(StallLimit, openLimit, time.Now())
	for !e.stop.Load() {
		if kind, ok := stall.Expired(time.Now()); ok {
			e.endStalled(kind)
		}
		// Only this goroutine calls WaitEvent; the binding copies the event out.
		ev := e.h.WaitEvent(0.5)
		switch ev.ID {
		case mpv.EventNone:
		case mpv.EventShutdown:
			e.emit(stall, Shutdown{})
			return
		case mpv.EventStartFile:
			stall.OnLoadStarted(time.Now())
		case mpv.EventFileLoaded:
			e.selectPendingTracks()
			e.addPendingSubtitles()
			e.emit(stall, FileLoaded{})
		case mpv.EventCommandReply:
			if ev.Userdata == replySubtitle && ev.Error < 0 {
				e.log.Warn("sous-titres externes non chargés : " + e.api.ErrorString(ev.Error))
			}
		case mpv.EventVideoReconfig:
			e.sink(VideoReconfigured{})
		case mpv.EventPlaybackRestart:
			e.sink(Seeked{})
		case mpv.EventEndFile:
			reason := EndOther
			switch ev.EndReason {
			case mpv.EndFileEOF:
				reason = EndEOF
			case mpv.EndFileStop:
				reason = EndStopped
			case mpv.EndFileQuit:
				reason = EndQuit
			case mpv.EndFileError:
				reason = EndError
			}
			var message *string
			if ev.Error < 0 {
				m := e.api.ErrorString(ev.Error)
				message = &m
			}
			// Once FFmpeg gives up on an HLS list, mpv tries each segment as a file:
			// the first failure ends it. Before the event, as in endStalled.
			if reason == EndError {
				if err := e.commandAsync(replyStallStop, "stop"); err != nil {
					e.log.Warn("arrêt après un échec du flux : " + err.Error())
				}
			}
			e.emit(stall, EndFile{Reason: reason, Error: message})
		case mpv.EventLogMessage:
			text := strings.TrimRightFunc(ev.LogText, unicode.IsSpace)
			e.log.Warn(fmt.Sprintf("[%s] %s", ev.LogPrefix, redact.Redact(text)), "target", "mpv")
		case mpv.EventPropertyChange:
			if pe := mapProperty(ev); pe != nil {
				e.emit(stall, pe)
			}
		}
	}
}

// mapProperty turns an observed property change into its event; nil when the
// property is unavailable (no file loaded) or in another format than requested.
func mapProperty(ev mpv.Event) PlayerEvent {
	if !ev.HasValue {
		return nil
	}
	switch {
	case ev.Userdata == obsTimePos && ev.Format == mpv.FormatDouble:
		return TimePos{Seconds: ev.Double}
	case ev.Userdata == obsDuration && ev.Format == mpv.FormatDouble:
		return Duration{Seconds: ev.Double}
	case ev.Userdata == obsPause && ev.Format == mpv.FormatFlag:
		return Paused{Paused: ev.Flag}
	case ev.Userdata == obsCache && ev.Format == mpv.FormatFlag:
		return Buffering{Active: ev.Flag}
	case ev.Userdata == obsEOF && ev.Format == mpv.FormatFlag:
		if ev.Flag {
			return EndFile{Reason: EndEOF}
		}
	case ev.Userdata == obsTracks && ev.Format == mpv.FormatString:
		if tracks, err := parseTracks(ev.Value); err == nil {
			return Tracks{Tracks: tracks}
		}
	}
	return nil
}

// selectPendingTracks selects the tracks asked by the last Load. A stream the file
// does not have leaves mpv's own choice.
func (e *MpvEngine) selectPendingTracks() {
	e.mu.Lock()
	tracks := e.pendingTracks
	e.pendingTracks = TrackSelection{}
	e.mu.Unlock()
	for _, p := range []struct {
		pick     TrackPick
		kind     string
		property string
	}{
		{tracks.Audio, "audio", "aid"},
		{tracks.Subtitle, "sub", "sid"},
	} {
		if p.pick.Kind != TrackStream {
			continue
		}
		list, _ := e.Property("track-list")
		id, ok := trackWithFFIndex(list, p.kind, p.pick.Index)
		if !ok {
			e.log.Warn(fmt.Sprintf("piste %s %d absente du fichier", p.kind, p.pick.Index))
			continue
		}
		if err := e.setProperty(p.property, strconv.FormatInt(id, 10)); err != nil {
			e.log.Warn(fmt.Sprintf("piste %s %d non choisie : %v", p.kind, p.pick.Index, err))
		}
	}
}

// addPendingSubtitles runs `sub-add` for the subtitles of the file that just
// loaded. A failure leaves the media playing without that track.
func (e *MpvEngine) addPendingSubtitles() {
	e.mu.Lock()
	pending := e.pendingSubtitles
	e.pendingSubtitles = nil
	e.mu.Unlock()
	// Asynchronous: Emby may take a minute to extract a subtitle from the file, and
	// a synchronous sub-add would stall playback meanwhile.
	for _, p := range pending {
		flag := "auto"
		if p.sub.Selected {
			flag = "select"
		}
		title, lang := "", ""
		if p.sub.Title != nil {
			title = *p.sub.Title
		}
		if p.sub.Lang != nil {
			lang = *p.sub.Lang
		}
		if err := e.commandAsync(replySubtitle, "sub-add", p.target, flag, title, lang); err != nil {
			e.log.Warn("sous-titres externes non chargés : " + redact.Redact(err.Error()))
		}
	}
}
