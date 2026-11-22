package main

import (
	"encoding/json"
	"fmt"
	"log"
	"math"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"time"
	"unsafe"

	"github.com/wailsapp/wails/v3/pkg/application"
	"golang.org/x/sys/windows"

	"viewer/internal/mpv"
)

type scenario struct {
	media, out, sub, audio2, dll string
	api                          *mpv.API
}

type check struct {
	Name   string `json:"name"`
	Passed bool   `json:"passed"`
	Detail string `json:"detail"`
}

type track struct {
	ID       int    `json:"id"`
	Type     string `json:"type"`
	Codec    string `json:"codec"`
	Lang     string `json:"lang,omitempty"`
	Title    string `json:"title,omitempty"`
	External bool   `json:"external"`
	Selected bool   `json:"selected"`
}

type report struct {
	Media          string            `json:"media"`
	MpvAPI         string            `json:"mpvClientApi"`
	Checks         []check           `json:"checks"`
	Props          map[string]string `json:"props"`
	Tracks         []track           `json:"tracks"`
	TeardownMs     int64             `json:"teardownMs"`
	WaitProbeMs    int64             `json:"waitEventProbeMs"`
	JS             jsReport          `json:"js"`
	MpvLogs        []string          `json:"mpvLogs"`
	ScenarioTotalS float64           `json:"scenarioTotalSeconds"`
}

var (
	user32            = windows.NewLazySystemDLL("user32.dll")
	procGetClientRect = user32.NewProc("GetClientRect")
)

type rect struct{ Left, Top, Right, Bottom int32 }

// clientSize is the HWND client area in physical pixels, i.e. what mpv's
// embedded window should fill.
func clientSize(hwnd uintptr) (int, int) {
	var r rect
	// SAFETY: r is a stack RECT that outlives the call; GetClientRect only writes it.
	procGetClientRect.Call(hwnd, uintptr(unsafe.Pointer(&r)))
	return int(r.Right - r.Left), int(r.Bottom - r.Top)
}

// externalTracks adds the generated external audio/subtitle files.
func externalTracks(c scenario) map[string]string {
	sub, audio := c.sub, c.audio2
	if !strings.HasPrefix(c.media, "http") {
		dir := filepath.Dir(c.media)
		if sub == "" {
			sub = filepath.Join(dir, "spike.ass")
		}
		if audio == "" {
			audio = filepath.Join(dir, "audio2.mka")
		}
	}
	opts := map[string]string{}
	if sub != "" && fileExists(sub) {
		opts["sub-files"] = sub
	}
	if audio != "" && fileExists(audio) {
		opts["audio-files"] = audio
	}
	return opts
}

func runSelftest(app *application.App, win *application.WebviewWindow, p *Player, hwnd uintptr, c scenario) {
	started := time.Now()
	rep := report{Media: filepath.Base(c.media), MpvAPI: c.api.Version(), Props: map[string]string{}}
	_ = os.MkdirAll(c.out, 0o755)
	_ = os.WriteFile(filepath.Join(c.out, "hwnd.txt"), []byte(strconv.FormatUint(uint64(hwnd), 10)), 0o644)
	push := func(name string, ok bool, format string, args ...any) {
		d := fmt.Sprintf(format, args...)
		log.Printf("check %-24s %v %s", name, ok, d)
		rep.Checks = append(rep.Checks, check{Name: name, Passed: ok, Detail: d})
	}
	hold := func(name string, d time.Duration) {
		_ = os.WriteFile(filepath.Join(c.out, "checkpoint.txt"), []byte(name), 0o644)
		time.Sleep(d)
	}
	num := func(name string) float64 {
		v, err := strconv.ParseFloat(p.prop(name), 64)
		if err != nil {
			return math.NaN()
		}
		return v
	}
	timePos := func() float64 {
		v := num("time-pos")
		if math.IsNaN(v) {
			return -1
		}
		return v
	}
	osd := func() (int, int) { return int(num("osd-width")), int(num("osd-height")) }

	defer func() {
		rep.JS = p.snapshot()
		rep.MpvLogs = p.logLines()
		rep.ScenarioTotalS = time.Since(started).Seconds()
		b, _ := json.MarshalIndent(rep, "", "  ")
		_ = os.WriteFile(filepath.Join(c.out, "report.json"), b, 0o644)
		hold("done", 500*time.Millisecond)
		// Close the window and let Wails quit on the last window closed, as a
		// user's click on the close button would. Calling app.Quit() directly
		// left WebView2 logging "Failed to unregister class Chrome_WidgetWin_0.
		// Error = 1412".
		// app.Quit() stays as a fallback. The driver measures time to exit.
		win.Close()
		time.Sleep(2 * time.Second)
		_ = os.WriteFile(filepath.Join(c.out, "quit-fallback.txt"), []byte("window close did not end the app within 2 s"), 0o644)
		app.Quit()
		// Watchdog: if Quit does not end the process, record it and force exit,
		// so a hang is visible rather than masked.
		time.Sleep(5 * time.Second)
		_ = os.WriteFile(filepath.Join(c.out, "watchdog.txt"), []byte("app.Quit did not exit within 5 s"), 0o644)
		os.Exit(3)
	}()

	ready := waitUntil(10*time.Second, func() bool { return p.snapshot().Ready })
	push("frontend_runtime_ready", ready, "Player.Ready() called by JS via /wails/runtime.js")
	hold("start", 3*time.Second)

	waitProbe, err := p.start(hwnd, externalTracks(c))
	rep.WaitProbeMs = waitProbe.Milliseconds()
	if err != nil {
		push("engine_create", false, "%v", err)
		return
	}
	push("engine_create", true, "wid=%d dll=%s", hwnd, filepath.Base(c.dll))
	push("wait_event_double_timeout", waitProbe >= 150*time.Millisecond && waitProbe < 600*time.Millisecond,
		"idle mpv_wait_event(0.2) took %d ms", waitProbe.Milliseconds())

	t0 := time.Now()
	openErr := p.Open(c.media)
	loaded := openErr == nil && waitUntil(20*time.Second, func() bool { return timePos() > 1 })
	push("load_and_play", loaded, "err=%v first second played after %d ms", openErr, time.Since(t0).Milliseconds())
	if !loaded {
		return
	}
	for _, k := range []string{"current-vo", "current-gpu-context", "hwdec-current", "video-codec", "video-format",
		"hwdec", "current-ao", "audio-codec-name", "frame-drop-count", "decoder-frame-drop-count", "width", "height", "duration"} {
		rep.Props[k] = p.prop(k)
	}
	w, h := osd()
	rep.Props["osd-initial"] = fmt.Sprintf("%dx%d", w, h)
	push("video_output", rep.Props["current-vo"] != "" && w > 0, "vo=%s gpu=%s osd=%dx%d",
		rep.Props["current-vo"], rep.Props["current-gpu-context"], w, h)
	hw := rep.Props["hwdec-current"]
	push("hardware_decoding", hw != "" && hw != "no", "hwdec-current=%q codec=%q format=%q", hw, rep.Props["video-codec"], rep.Props["video-format"])

	// Subtitles start off (sub-files auto-selects the external track), so the
	// "playing" capture has none and "subtitle-on" shows the switch.
	_ = p.setProp("sid", "no")
	subOff := waitUntil(3*time.Second, func() bool { return p.prop("current-tracks/sub/id") == "" })
	push("subtitle_off_initially", subOff, "current-tracks/sub/id=%q", p.prop("current-tracks/sub/id"))
	hold("playing", 3*time.Second)
	js := p.snapshot()
	push("event_received_by_js", js.EventsSeen > 0, "JS acknowledged %d player:event, last=%s", js.EventsSeen, js.LastEventJSON)

	// Tracks.
	var tracks []track
	_ = json.Unmarshal([]byte(p.prop("track-list")), &tracks)
	rep.Tracks = tracks
	var audio, subs []int
	for _, t := range tracks {
		switch t.Type {
		case "audio":
			audio = append(audio, t.ID)
		case "sub":
			subs = append(subs, t.ID)
		}
	}
	push("track_list", len(audio) >= 2 && len(subs) >= 1, "audio=%v sub=%v", audio, subs)
	// Switch to the last audio track first (the external one), then back to the first.
	order := append([]int(nil), audio...)
	if len(order) > 1 {
		order = []int{audio[len(audio)-1], audio[0]}
	}
	for _, id := range order {
		ids := strconv.Itoa(id)
		reqErr := p.setProp("aid", ids)
		active := reqErr == nil && waitUntil(3*time.Second, func() bool { return p.prop("current-tracks/audio/id") == ids })
		before := timePos()
		advancing := active && waitUntil(3*time.Second, func() bool { return timePos() > before+0.5 })
		push(fmt.Sprintf("audio_track_%d", id), advancing, "active=%s external=%s codec=%s ao=%s advancing=%v",
			p.prop("current-tracks/audio/id"), p.prop("current-tracks/audio/external"),
			p.prop("audio-codec-name"), p.prop("current-ao"), advancing)
		hold(fmt.Sprintf("audio-%d", id), 3*time.Second)
	}

	if len(subs) > 0 {
		ids := strconv.Itoa(subs[0])
		reqErr := p.setProp("sid", ids)
		active := reqErr == nil && waitUntil(3*time.Second, func() bool { return p.prop("current-tracks/sub/id") == ids })
		var text string
		cue := active && waitUntil(5*time.Second, func() bool {
			text = strings.TrimSpace(p.prop("sub-text"))
			return text != ""
		})
		push("subtitle_on", active && cue, "active=%s codec=%s external=%s sub-text=%q",
			p.prop("current-tracks/sub/id"), p.prop("current-tracks/sub/codec"), p.prop("current-tracks/sub/external"), text)
		hold("subtitle-on", 3*time.Second)
	} else {
		push("subtitle_on", false, "no subtitle track")
	}

	// Resize: mpv's render size must follow the client area.
	win.SetSize(900, 500)
	var cw, ch int
	resized := waitUntil(5*time.Second, func() bool {
		cw, ch = clientSize(hwnd)
		w, h := osd()
		return cw > 0 && w == cw && h == ch && cw < 1280
	})
	ww, wh := win.Size()
	w, h = osd()
	push("resize_render_size", resized, "SetSize(900,500) window.Size=%dx%d client=%dx%d osd=%dx%d", ww, wh, cw, ch, w, h)
	hold("resized", 3*time.Second)

	// Fullscreen: osd must match the monitor's physical size.
	var sw, sh int
	if scr, err := win.GetScreen(); err == nil && scr != nil {
		sw, sh = scr.PhysicalBounds.Width, scr.PhysicalBounds.Height
		rep.Props["screen"] = fmt.Sprintf("%s %dx%d scale=%.2f", scr.Name, sw, sh, scr.ScaleFactor)
	}
	win.Fullscreen()
	fs := waitUntil(5*time.Second, func() bool {
		w, h := osd()
		return sw > 0 && w == sw && h == sh
	})
	cw, ch = clientSize(hwnd)
	w, h = osd()
	push("fullscreen_on", fs, "screen=%dx%d client=%dx%d osd=%dx%d isFullscreen=%v", sw, sh, cw, ch, w, h, win.IsFullscreen())
	hold("fullscreen", 3*time.Second)

	win.UnFullscreen()
	unfs := waitUntil(5*time.Second, func() bool {
		cw, ch = clientSize(hwnd)
		w, h := osd()
		return w == cw && h == ch && w < sw
	})
	w, h = osd()
	push("fullscreen_off", unfs, "client=%dx%d osd=%dx%d isFullscreen=%v", cw, ch, w, h, win.IsFullscreen())
	hold("fullscreen-off", 3*time.Second)

	// Seek to the middle.
	dur := num("duration")
	target := math.Floor(dur / 2)
	seekErr := p.command("seek", strconv.FormatFloat(target, 'f', 3, 64), "absolute")
	seeked := seekErr == nil && waitUntil(8*time.Second, func() bool { return math.Abs(timePos()-target) < 1.5 })
	push("seek_middle", seeked, "target=%.0f pos=%.2f duration=%.2f", target, timePos(), dur)
	hold("seeked", 3*time.Second)

	// Keyboard: the driver posts a synthetic Space key (PostMessage); the page's keydown
	// handler calls Player.TogglePause. Proves focus stays in the WebView.
	// Rewind first: with keep-open=yes mpv pauses by itself at the end of the
	// 20 s clip, which made the first run pass this check without any key press.
	_ = p.command("seek", "2", "absolute")
	_ = p.setProp("pause", "no")
	waitUntil(3*time.Second, func() bool { t := timePos(); return t >= 2 && t < 6 && p.prop("eof-reached") != "yes" })
	before := p.snapshot().KeyPresses
	// Same as the Rust spike (window.set_focus()): focus the window before the
	// driver's synthetic key.
	win.Focus()
	time.Sleep(500 * time.Millisecond)
	hold("keyboard-press-space", 0)
	paused := waitUntil(10*time.Second, func() bool {
		return p.snapshot().KeyPresses > before && p.prop("pause") == "yes" && p.prop("eof-reached") != "yes"
	})
	js = p.snapshot()
	push("keyboard_space_pauses", paused, "pause=%s eof=%s pos=%.2f TogglePause calls from JS=%d keys=%v pageFocused=%v",
		p.prop("pause"), p.prop("eof-reached"), timePos(), js.KeyPresses-before, js.KeysSeen, js.PageFocused)
	hold("paused", 3*time.Second)
	_ = p.setProp("pause", "no")

	// Structured error roundtrip: Go → event → JS calls Open(bad) → error → JS reports back.
	app.Event.Emit("spike:try-error")
	gotErr := waitUntil(5*time.Second, func() bool { return p.snapshot().ErrorName != "" })
	js = p.snapshot()
	var cause PlayerError
	_ = json.Unmarshal([]byte(js.ErrorCause), &cause)
	push("structured_error_roundtrip", gotErr && js.ErrorName == "RuntimeError" && cause.Kind == "not_found",
		"name=%s message=%q cause=%s", js.ErrorName, js.ErrorMessage, js.ErrorCause)
	hold("error-roundtrip", 3*time.Second)

	// Teardown off the UI thread (this goroutine is not Wails' main thread).
	td := p.teardown()
	rep.TeardownMs = td.Milliseconds()
	push("teardown", p.handle() == nil && td < 3*time.Second, "mpv_terminate_destroy off UI thread in %d ms", td.Milliseconds())
	hold("closed", 3*time.Second)
}

func waitUntil(timeout time.Duration, pred func() bool) bool {
	deadline := time.Now().Add(timeout)
	for time.Now().Before(deadline) {
		if pred() {
			return true
		}
		time.Sleep(100 * time.Millisecond)
	}
	return pred()
}
