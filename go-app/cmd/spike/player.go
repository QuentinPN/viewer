package main

import (
	"encoding/json"
	"errors"
	"os"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/wailsapp/wails/v3/pkg/application"

	"viewer/internal/mpv"
)

// PlayerError is the structured error returned to the frontend. Wails' default
// error marshaler serialises it as the `cause` of the rejected JS promise.
type PlayerError struct {
	Kind    string `json:"kind"`
	Message string `json:"message"`
}

func (e *PlayerError) Error() string { return e.Kind + ": " + e.Message }

// PlayerEvent is the payload of the `player:event` Wails event.
type PlayerEvent struct {
	State   string  `json:"state"`
	TimePos float64 `json:"timePos"`
	Paused  bool    `json:"paused"`
}

// jsReport is what the frontend sends back through bound calls, so the
// command/event roundtrip is proven in both directions.
type jsReport struct {
	Ready         bool
	EventsSeen    int
	LastEventJSON string
	ErrorName     string
	ErrorMessage  string
	ErrorCause    string
	KeyPresses    int
	KeysSeen      []string
	PageFocused   bool
	FocusChanges  int
}

const (
	obsTimePos = 1
	obsPause   = 2
	obsEOF     = 3
)

// Player is the bound Wails service. Its exported methods are callable from JS
// as `main.Player.<Method>`.
type Player struct {
	app *application.App
	api *mpv.API

	tdOnce sync.Once // teardown runs once; concurrent callers block until it finishes
	tdDone chan struct{}

	mu   sync.Mutex
	h    *mpv.Handle
	stop chan struct{}
	done chan struct{}
	logs []string

	repMu  sync.Mutex
	report jsReport
}

func (p *Player) handle() *mpv.Handle {
	p.mu.Lock()
	defer p.mu.Unlock()
	return p.h
}

// start creates and initialises mpv embedded in the window `wid` ("parent"
// mode), then starts the event goroutine.
func (p *Player) start(wid uintptr, extra map[string]string) (time.Duration, error) {
	h, err := p.api.Create()
	if err != nil {
		return 0, err
	}
	opts := [][2]string{
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
		{"hwdec", "auto-safe"},
		{"tls-verify", "yes"},
		{"wid", strconv.FormatUint(uint64(wid), 10)},
		{"force-window", "yes"},
	}
	for k, v := range extra {
		opts = append(opts, [2]string{k, v})
	}
	for _, o := range opts {
		if err := h.SetOption(o[0], o[1]); err != nil {
			h.TerminateDestroy()
			return 0, err
		}
	}
	if err := h.RequestLogMessages("warn"); err != nil {
		h.TerminateDestroy()
		return 0, err
	}
	if err := h.Initialize(); err != nil {
		h.TerminateDestroy()
		return 0, err
	}
	// Proves the C double reaches mpv_wait_event: an idle wait of 0.2 s must
	// block about 200 ms (a garbage double would return at once or hang).
	t := time.Now()
	for {
		if ev := h.WaitEvent(0.2); ev.ID == mpv.EventNone {
			break
		}
		if time.Since(t) > 3*time.Second {
			break
		}
		t = time.Now()
	}
	waitProbe := time.Since(t)

	for id, name := range map[uint64]string{obsTimePos: "time-pos", obsPause: "pause", obsEOF: "eof-reached"} {
		if err := h.ObserveProperty(id, name, mpv.FormatString); err != nil {
			h.TerminateDestroy()
			return 0, err
		}
	}
	p.mu.Lock()
	p.h = h
	p.stop = make(chan struct{})
	p.done = make(chan struct{})
	p.mu.Unlock()
	go p.eventLoop(h, p.stop, p.done)
	return waitProbe, nil
}

func (p *Player) eventLoop(h *mpv.Handle, stop, done chan struct{}) {
	defer close(done)
	state := PlayerEvent{State: "idle"}
	lastEmit := time.Time{}
	for {
		select {
		case <-stop:
			return
		default:
		}
		ev := h.WaitEvent(1.0)
		switch ev.ID {
		case mpv.EventNone:
			continue
		case mpv.EventShutdown:
			return
		case mpv.EventLogMessage:
			p.mu.Lock()
			if len(p.logs) < 50 {
				p.logs = append(p.logs, ev.LogLevel+": "+strings.TrimSpace(ev.LogText))
			}
			p.mu.Unlock()
			continue
		case mpv.EventStartFile:
			state.State = "loading"
		case mpv.EventFileLoaded:
			state.State = "playing"
		case mpv.EventPropertyChange:
			switch ev.Userdata {
			case obsTimePos:
				if v, err := strconv.ParseFloat(ev.Value, 64); err == nil {
					state.TimePos = v
				}
				// Throttle position updates like the Rust engine does.
				if time.Since(lastEmit) < 250*time.Millisecond {
					continue
				}
			case obsPause:
				state.Paused = ev.Value == "yes"
			case obsEOF:
				if ev.Value == "yes" {
					state.State = "ended"
				} else if state.State == "ended" {
					state.State = "playing" // seek back after the end (keep-open)
				}
			}
		default:
			continue
		}
		lastEmit = time.Now()
		p.app.Event.Emit("player:event", state)
	}
}

// teardown tears mpv down: stop the event goroutine, then mpv_terminate_destroy.
// Must be called off the UI thread (the video window is a child of the main
// window, whose thread must keep pumping messages while mpv destroys it).
// Idempotent and joinable: a second call waits for the first to finish. p.h
// stays set until the end so handle() != nil means "not fully torn down".
func (p *Player) teardown() (elapsed time.Duration) {
	p.tdOnce.Do(func() {
		defer close(p.tdDone)
		p.mu.Lock()
		h, stop, done := p.h, p.stop, p.done
		p.mu.Unlock()
		if h == nil {
			return
		}
		t := time.Now()
		close(stop)
		h.Wakeup()
		<-done
		h.TerminateDestroy()
		elapsed = time.Since(t)
		p.mu.Lock()
		p.h = nil
		p.mu.Unlock()
		p.app.Event.Emit("player:event", PlayerEvent{State: "closed"})
	})
	<-p.tdDone
	return elapsed
}

// prop reads an mpv property as a string ("" if unavailable).
func (p *Player) prop(name string) string {
	h := p.handle()
	if h == nil {
		return ""
	}
	v, _ := h.Property(name)
	return v
}

func (p *Player) command(args ...string) error {
	h := p.handle()
	if h == nil {
		return &PlayerError{Kind: "not_started", Message: "lecteur non démarré"}
	}
	return h.Command(args...)
}

func (p *Player) setProp(name, value string) error {
	h := p.handle()
	if h == nil {
		return &PlayerError{Kind: "not_started", Message: "lecteur non démarré"}
	}
	return h.SetProperty(name, value)
}

// ---- Methods bound to the frontend ----

// Open loads a local file or an http(s) URL. Errors are structured.
func (p *Player) Open(source string) error {
	switch {
	case source == "":
		return &PlayerError{Kind: "invalid_source", Message: "source vide"}
	case strings.HasPrefix(source, "http://"), strings.HasPrefix(source, "https://"), strings.HasPrefix(source, "av://lavfi:"):
	default:
		if _, err := os.Stat(source); err != nil {
			if errors.Is(err, os.ErrNotExist) {
				return &PlayerError{Kind: "not_found", Message: "fichier introuvable"}
			}
			return &PlayerError{Kind: "io", Message: err.Error()}
		}
	}
	if err := p.command("loadfile", source, "replace"); err != nil {
		return &PlayerError{Kind: "mpv", Message: err.Error()}
	}
	return nil
}

// TogglePause flips mpv's pause flag (used by the frontend Space handler).
func (p *Player) TogglePause() error {
	p.repMu.Lock()
	p.report.KeyPresses++
	p.repMu.Unlock()
	return p.command("cycle", "pause")
}

// ReportKey records every keydown the page sees (keyboard diagnostics).
func (p *Player) ReportKey(code string) {
	p.repMu.Lock()
	if len(p.report.KeysSeen) < 20 {
		p.report.KeysSeen = append(p.report.KeysSeen, code)
	}
	p.repMu.Unlock()
}

// ReportFocus records the page's window focus/blur events.
func (p *Player) ReportFocus(focused bool) {
	p.repMu.Lock()
	p.report.PageFocused = focused
	p.report.FocusChanges++
	p.repMu.Unlock()
}

// Ready is called by the frontend once the Wails runtime is loaded.
func (p *Player) Ready() {
	p.repMu.Lock()
	p.report.Ready = true
	p.report.PageFocused = true
	p.repMu.Unlock()
}

// ReportEvent lets the frontend acknowledge `player:event` receipts.
func (p *Player) ReportEvent(count int, last any) {
	b, _ := json.Marshal(last)
	p.repMu.Lock()
	p.report.EventsSeen = count
	p.report.LastEventJSON = string(b)
	p.repMu.Unlock()
}

// ReportError lets the frontend send back the error it caught from Open.
func (p *Player) ReportError(name, message string, cause any) {
	b, _ := json.Marshal(cause)
	p.repMu.Lock()
	p.report.ErrorName = name
	p.report.ErrorMessage = message
	p.report.ErrorCause = string(b)
	p.repMu.Unlock()
}

func (p *Player) snapshot() jsReport {
	p.repMu.Lock()
	defer p.repMu.Unlock()
	return p.report
}

func (p *Player) logLines() []string {
	p.mu.Lock()
	defer p.mu.Unlock()
	return append([]string(nil), p.logs...)
}
