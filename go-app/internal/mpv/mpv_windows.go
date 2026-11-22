// Package mpv is a minimal, cgo-free binding to the libmpv client API
// (mpv/client.h, API 2.x), loaded at run time with LoadLibrary.
//
// It mirrors src-tauri/src/player/ffi.rs: only the functions the player needs,
// string-typed properties (no float returns through syscall), and struct
// layouts copied from client.h. Every unsafe conversion carries a SAFETY note.
package mpv

import (
	"errors"
	"fmt"
	"math"
	"runtime"
	"sync"
	"sync/atomic"
	"unsafe"

	"golang.org/x/sys/windows"
)

// Formats and event ids from mpv/client.h.
const (
	FormatNone   = 0
	FormatString = 1
	FormatFlag   = 3
	FormatDouble = 5

	EventNone            = 0
	EventShutdown        = 1
	EventLogMessage      = 2
	EventCommandReply    = 5
	EventStartFile       = 6
	EventEndFile         = 7
	EventFileLoaded      = 8
	EventVideoReconfig   = 17
	EventPlaybackRestart = 21
	EventPropertyChange  = 22
)

// End-of-file reasons (mpv_end_file_reason).
const (
	EndFileEOF      = 0
	EndFileStop     = 2
	EndFileQuit     = 3
	EndFileError    = 4
	EndFileRedirect = 5
)

// requiredAPIMajor is the client API major this binding was written against.
const requiredAPIMajor = 2

// API is the resolved function table of one loaded libmpv.
type API struct {
	dll                *windows.LazyDLL
	clientAPIVersion   *windows.LazyProc
	errorString        *windows.LazyProc
	free               *windows.LazyProc
	create             *windows.LazyProc
	initialize         *windows.LazyProc
	terminateDestroy   *windows.LazyProc
	setOptionString    *windows.LazyProc
	command            *windows.LazyProc
	commandAsync       *windows.LazyProc
	setPropertyString  *windows.LazyProc
	getPropertyString  *windows.LazyProc
	observeProperty    *windows.LazyProc
	requestLogMessages *windows.LazyProc
	waitEvent          *windows.LazyProc
	wakeup             *windows.LazyProc
}

// Load loads libmpv from an absolute path chosen by the app (never remote input)
// and resolves every symbol up front so a missing one fails here, not mid-playback.
func Load(path string) (*API, error) {
	dll := windows.NewLazyDLL(path)
	if err := dll.Load(); err != nil {
		return nil, fmt.Errorf("load %s: %w", path, err)
	}
	a := &API{dll: dll}
	for _, p := range []struct {
		dst  **windows.LazyProc
		name string
	}{
		{&a.clientAPIVersion, "mpv_client_api_version"},
		{&a.errorString, "mpv_error_string"},
		{&a.free, "mpv_free"},
		{&a.create, "mpv_create"},
		{&a.initialize, "mpv_initialize"},
		{&a.terminateDestroy, "mpv_terminate_destroy"},
		{&a.setOptionString, "mpv_set_option_string"},
		{&a.command, "mpv_command"},
		{&a.commandAsync, "mpv_command_async"},
		{&a.setPropertyString, "mpv_set_property_string"},
		{&a.getPropertyString, "mpv_get_property_string"},
		{&a.observeProperty, "mpv_observe_property"},
		{&a.requestLogMessages, "mpv_request_log_messages"},
		{&a.waitEvent, "mpv_wait_event"},
		{&a.wakeup, "mpv_wakeup"},
	} {
		proc := dll.NewProc(p.name)
		if err := proc.Find(); err != nil {
			return nil, fmt.Errorf("symbol %s: %w", p.name, err)
		}
		*p.dst = proc
	}
	r, _, _ := a.clientAPIVersion.Call()
	v := uint32(r) // the C return is a 32-bit unsigned; drop any upper register bits
	if v>>16 != requiredAPIMajor {
		return nil, &UnsupportedAPIError{Major: v >> 16, Minor: v & 0xffff}
	}
	return a, nil
}

// UnsupportedAPIError is returned by Load when the library's client API major is
// not the one this binding was written against.
type UnsupportedAPIError struct {
	Major, Minor uint32
}

func (e *UnsupportedAPIError) Error() string {
	return fmt.Sprintf("unsupported mpv client API %d.%d", e.Major, e.Minor)
}

// Version returns the client API version as "major.minor".
func (a *API) Version() string {
	r, _, _ := a.clientAPIVersion.Call()
	v := uint32(r)
	return fmt.Sprintf("%d.%d", v>>16, v&0xffff)
}

// Error is a negative mpv_error code with its mpv message.
type Error struct {
	Context string
	Code    int32
	Message string
}

func (e *Error) Error() string { return fmt.Sprintf("%s: %s (%d)", e.Context, e.Message, e.Code) }

// ErrDestroyed is returned by calls made after TerminateDestroy.
var ErrDestroyed = errors.New("mpv handle destroyed")

// Handle is one mpv_handle. Every C call holds mu.RLock and checks for
// destruction, so TerminateDestroy (mu.Lock) can never free the handle under a
// call in flight: later calls return ErrDestroyed. WaitEvent must still only be
// called from one goroutine (its returned event is valid until the next wait).
//
// Deadlock freedom: WaitEvent blocks while holding RLock, so TerminateDestroy
// first sets closing (new calls fail fast) and issues mpv_wakeup under RLock
// (shared with the waiter). mpv_wakeup is sticky for the next wait, so a waiter
// that had not entered mpv_wait_event yet returns immediately too; in the worst
// case it returns at its timeout. Only then is the write Lock taken.
type Handle struct {
	api     *API
	mu      sync.RWMutex
	closing atomic.Bool
	h       uintptr
}

// begin read-locks the handle; ok is false (and nothing is held) if destroyed.
func (h *Handle) begin() (ok bool) {
	if h.closing.Load() {
		return false
	}
	h.mu.RLock()
	if h.h == 0 || h.closing.Load() {
		h.mu.RUnlock()
		return false
	}
	return true
}

// Create calls mpv_create. The handle is not initialised yet.
func (a *API) Create() (*Handle, error) {
	r, _, _ := a.create.Call()
	if r == 0 {
		return nil, errors.New("mpv_create returned NULL")
	}
	return &Handle{api: a, h: r}, nil
}

// cstr returns a NUL-terminated copy of s. A string containing NUL is rejected
// rather than silently truncated.
func cstr(s string) (*byte, error) {
	return windows.BytePtrFromString(s)
}

// goString copies a C string owned by libmpv.
func goString(p uintptr) string {
	if p == 0 {
		return ""
	}
	// SAFETY: p is a non-NULL, NUL-terminated char* returned by libmpv and still
	// owned by it for the duration of this call; we only read and copy it. The
	// pointer comes from outside the Go heap, so the GC never moves or frees it.
	return windows.BytePtrToString(*(**byte)(unsafe.Pointer(&p)))
}

func (h *Handle) check(code uintptr, context string) error {
	c := int32(code)
	if c >= 0 {
		return nil
	}
	r, _, _ := h.api.errorString.Call(uintptr(c))
	return &Error{Context: context, Code: c, Message: goString(r)}
}

// SetOption calls mpv_set_option_string (before or after Initialize).
func (h *Handle) SetOption(name, value string) error {
	if !h.begin() {
		return ErrDestroyed
	}
	defer h.mu.RUnlock()
	n, err := cstr(name)
	if err != nil {
		return err
	}
	v, err := cstr(value)
	if err != nil {
		return err
	}
	r, _, _ := h.api.setOptionString.Call(h.h, uintptr(unsafe.Pointer(n)), uintptr(unsafe.Pointer(v)))
	runtime.KeepAlive(n)
	runtime.KeepAlive(v)
	return h.check(r, "set_option "+name)
}

// Initialize calls mpv_initialize.
func (h *Handle) Initialize() error {
	if !h.begin() {
		return ErrDestroyed
	}
	defer h.mu.RUnlock()
	r, _, _ := h.api.initialize.Call(h.h)
	return h.check(r, "mpv_initialize")
}

// RequestLogMessages enables MPV_EVENT_LOG_MESSAGE at the given level.
func (h *Handle) RequestLogMessages(level string) error {
	if !h.begin() {
		return ErrDestroyed
	}
	defer h.mu.RUnlock()
	l, err := cstr(level)
	if err != nil {
		return err
	}
	r, _, _ := h.api.requestLogMessages.Call(h.h, uintptr(unsafe.Pointer(l)))
	runtime.KeepAlive(l)
	return h.check(r, "request_log_messages")
}

// Command runs a synchronous mpv_command with a NULL-terminated argv.
func (h *Handle) Command(args ...string) error {
	if !h.begin() {
		return ErrDestroyed
	}
	defer h.mu.RUnlock()
	ptrs := make([]*byte, len(args)+1)
	for i, a := range args {
		p, err := cstr(a)
		if err != nil {
			return err
		}
		ptrs[i] = p
	}
	r, _, _ := h.api.command.Call(h.h, uintptr(unsafe.Pointer(&ptrs[0])))
	// The argv array and its strings are Go memory: keep them alive until
	// mpv_command returns (it copies what it needs).
	runtime.KeepAlive(ptrs)
	if len(args) > 0 {
		return h.check(r, "command "+args[0])
	}
	return h.check(r, "command")
}

// CommandAsync runs mpv_command_async: the command runs on mpv's threads and its
// outcome comes back as an EventCommandReply carrying reply in Userdata. An error
// here means mpv could not parse or queue it, and then no reply follows.
func (h *Handle) CommandAsync(reply uint64, args ...string) error {
	if !h.begin() {
		return ErrDestroyed
	}
	defer h.mu.RUnlock()
	ptrs := make([]*byte, len(args)+1)
	for i, a := range args {
		p, err := cstr(a)
		if err != nil {
			return err
		}
		ptrs[i] = p
	}
	// uint64_t reply_userdata fits the 64-bit integer register on Windows x64.
	r, _, _ := h.api.commandAsync.Call(h.h, uintptr(reply), uintptr(unsafe.Pointer(&ptrs[0])))
	// mpv copies the arguments before returning: the Go argv only has to live
	// until the call returns.
	runtime.KeepAlive(ptrs)
	if len(args) > 0 {
		return h.check(r, "command_async "+args[0])
	}
	return h.check(r, "command_async")
}

// SetProperty calls mpv_set_property_string.
func (h *Handle) SetProperty(name, value string) error {
	if !h.begin() {
		return ErrDestroyed
	}
	defer h.mu.RUnlock()
	n, err := cstr(name)
	if err != nil {
		return err
	}
	v, err := cstr(value)
	if err != nil {
		return err
	}
	r, _, _ := h.api.setPropertyString.Call(h.h, uintptr(unsafe.Pointer(n)), uintptr(unsafe.Pointer(v)))
	runtime.KeepAlive(n)
	runtime.KeepAlive(v)
	return h.check(r, "set_property "+name)
}

// Property reads a property as a string; ok is false if it is unavailable.
func (h *Handle) Property(name string) (value string, ok bool) {
	if !h.begin() {
		return "", false
	}
	defer h.mu.RUnlock()
	n, err := cstr(name)
	if err != nil {
		return "", false
	}
	r, _, _ := h.api.getPropertyString.Call(h.h, uintptr(unsafe.Pointer(n)))
	runtime.KeepAlive(n)
	if r == 0 {
		return "", false
	}
	s := goString(r)
	// The returned string is allocated by libmpv and must be released with mpv_free.
	h.api.free.Call(r)
	return s, true
}

// ObserveProperty calls mpv_observe_property.
func (h *Handle) ObserveProperty(id uint64, name string, format int32) error {
	if !h.begin() {
		return ErrDestroyed
	}
	defer h.mu.RUnlock()
	n, err := cstr(name)
	if err != nil {
		return err
	}
	r, _, _ := h.api.observeProperty.Call(h.h, uintptr(id), uintptr(unsafe.Pointer(n)), uintptr(format))
	runtime.KeepAlive(n)
	return h.check(r, "observe "+name)
}

// Event is a decoded copy of one mpv_event (nothing points into libmpv memory).
type Event struct {
	ID        int32
	Error     int32
	Userdata  uint64
	Property  string // MPV_EVENT_PROPERTY_CHANGE: name
	Value     string // MPV_EVENT_PROPERTY_CHANGE with MPV_FORMAT_STRING: value
	HasValue  bool
	EndReason int32  // MPV_EVENT_END_FILE
	LogLevel  string // MPV_EVENT_LOG_MESSAGE
	LogText   string
	// MPV_EVENT_PROPERTY_CHANGE: the format mpv delivered (FormatNone when the
	// property is unavailable), and the value for FormatFlag and FormatDouble.
	// HasValue is true whenever a string, flag or double value was decoded.
	Format    int32
	Flag      bool
	Double    float64
	LogPrefix string // MPV_EVENT_LOG_MESSAGE
}

// Layouts from mpv/client.h (x64: int is 32 bits, pointers 64).
type rawEvent struct {
	eventID       int32
	error         int32
	replyUserdata uint64
	data          uintptr
}

type rawEventProperty struct {
	name   uintptr
	format int32
	_      int32
	data   uintptr
}

type rawEventEndFile struct {
	reason int32
	error  int32
}

type rawEventLogMessage struct {
	prefix uintptr
	level  uintptr
	text   uintptr
	//lint:ignore U1000 mirrors the C mpv_event_log_message layout
	logLevel int32
}

// WaitEvent calls mpv_wait_event with a timeout in seconds and copies the event.
// Only one goroutine may call it. Returns EventNone once the handle is destroyed.
func (h *Handle) WaitEvent(timeout float64) Event {
	if !h.begin() {
		return Event{ID: EventNone}
	}
	defer h.mu.RUnlock()
	// The timeout is a C double. On Windows x64 Go's syscall trampoline mirrors
	// the first four integer arguments into XMM0-XMM3, so the bit pattern of the
	// float64 lands in XMM1 where the callee expects it.
	r, _, _ := h.api.waitEvent.Call(h.h, uintptr(math.Float64bits(timeout)))
	if r == 0 {
		return Event{ID: EventNone}
	}
	// SAFETY: mpv_wait_event never returns NULL in practice and the returned
	// mpv_event stays valid until the next mpv_wait_event on this handle, which
	// only this goroutine calls. Its memory is libmpv's (not the Go heap), and
	// every field is copied out before returning.
	ev := *(**rawEvent)(unsafe.Pointer(&r))
	out := Event{ID: ev.eventID, Error: ev.error, Userdata: ev.replyUserdata}
	if ev.data == 0 {
		return out
	}
	switch ev.eventID {
	case EventPropertyChange:
		// SAFETY: for MPV_EVENT_PROPERTY_CHANGE, data points to mpv_event_property.
		p := *(**rawEventProperty)(unsafe.Pointer(&ev.data))
		out.Property = goString(p.name)
		out.Format = p.format
		if p.data != 0 {
			switch p.format {
			case FormatString:
				// SAFETY: with MPV_FORMAT_STRING, data points to a char*.
				out.Value = goString(**(**uintptr)(unsafe.Pointer(&p.data)))
				out.HasValue = true
			case FormatFlag:
				// SAFETY: with MPV_FORMAT_FLAG, data points to a C int (32 bits)
				// in libmpv's memory, valid until the next wait; read once here.
				out.Flag = **(**int32)(unsafe.Pointer(&p.data)) != 0
				out.HasValue = true
			case FormatDouble:
				// SAFETY: with MPV_FORMAT_DOUBLE, data points to a C double in
				// libmpv's memory, valid until the next wait; read once here.
				out.Double = **(**float64)(unsafe.Pointer(&p.data))
				out.HasValue = true
			}
		}
	case EventEndFile:
		// SAFETY: for MPV_EVENT_END_FILE, data points to mpv_event_end_file.
		e := *(**rawEventEndFile)(unsafe.Pointer(&ev.data))
		out.EndReason = e.reason
		out.Error = e.error
	case EventLogMessage:
		// SAFETY: for MPV_EVENT_LOG_MESSAGE, data points to mpv_event_log_message.
		m := *(**rawEventLogMessage)(unsafe.Pointer(&ev.data))
		out.LogPrefix = goString(m.prefix)
		out.LogLevel = goString(m.level)
		out.LogText = goString(m.text)
	}
	return out
}

// Wakeup interrupts a blocked WaitEvent.
func (h *Handle) Wakeup() {
	if !h.begin() {
		return
	}
	defer h.mu.RUnlock()
	h.api.wakeup.Call(h.h)
}

// TerminateDestroy shuts the player down and frees the handle. It blocks until
// the core has quit; with an embedded video window it must run off the UI
// thread. Safe to call concurrently with other methods and twice.
func (h *Handle) TerminateDestroy() {
	if h.closing.Swap(true) {
		h.mu.Lock() // another destroy is running: wait for it
		//lint:ignore SA2001 deliberate barrier: waits for the running destroy to finish
		h.mu.Unlock()
		return
	}
	// closing is set, so new calls fail fast; wake a blocked WaitEvent (it holds
	// RLock) before taking the write lock. See the Handle comment.
	h.mu.RLock()
	if h.h != 0 {
		h.api.wakeup.Call(h.h)
	}
	h.mu.RUnlock()
	h.mu.Lock()
	defer h.mu.Unlock()
	if h.h == 0 {
		return
	}
	h.api.terminateDestroy.Call(h.h)
	h.h = 0
}

// ErrorString returns mpv's message for an error code.
func (a *API) ErrorString(code int32) string {
	r, _, _ := a.errorString.Call(uintptr(code))
	return goString(r)
}
