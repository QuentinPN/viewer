package diagnostics

import (
	"context"
	"fmt"
	"io"
	"log/slog"
	"os"
	"runtime/debug"
	"strconv"
	"strings"
	"sync"
	"time"
	"unicode"
)

// DefaultTarget is the target of a record that names none: the app's own code. The
// Rust `log` crate always has one (the module path); Go callers name one with the
// `target` attribute (internal/player logs mpv's lines with `target=mpv`).
const DefaultTarget = "viewer"

// core is the part of a handler that clones share: the file, its lock and the level.
type core struct {
	mu    sync.Mutex
	file  *RotatingFile
	level Level
	echo  io.Writer // stderr in debug builds; nil otherwise
}

// FileHandler is the slog.Handler of the log file (`FileLogger`): one redacted line
// per record, filtered per target by LevelFor.
type FileHandler struct {
	core   *core
	groups string      // "a.b." prefix of the attribute keys
	attrs  []slog.Attr // handler attributes, already prefixed
	target string
}

// NewFileHandler returns the handler writing to file at the given own level. echo,
// when not nil, also receives every line (the debug build echoes to stderr).
func NewFileHandler(file *RotatingFile, level Level, echo io.Writer) *FileHandler {
	return &FileHandler{core: &core{file: file, level: level, echo: echo}, target: DefaultTarget}
}

// levelOf maps a slog level to the log crate's: slog has no Trace, so anything under
// Debug is Trace.
func levelOf(l slog.Level) Level {
	switch {
	case l >= slog.LevelError:
		return LevelError
	case l >= slog.LevelWarn:
		return LevelWarn
	case l >= slog.LevelInfo:
		return LevelInfo
	case l >= slog.LevelDebug:
		return LevelDebug
	default:
		return LevelTrace
	}
}

// Enabled is the cheap pre-check: the target is only known in Handle, so a record is
// kept here when its level passes the app's own filter, the loosest one.
func (h *FileHandler) Enabled(_ context.Context, l slog.Level) bool {
	return levelOf(l) <= h.core.level
}

// WithAttrs implements slog.Handler. A `target` attribute sets the target of every
// record of the returned handler.
func (h *FileHandler) WithAttrs(attrs []slog.Attr) slog.Handler {
	c := *h
	c.attrs = append([]slog.Attr(nil), h.attrs...)
	for _, a := range attrs {
		if a.Key == "target" && h.groups == "" {
			c.target = a.Value.Resolve().String()
			continue
		}
		a.Key = h.groups + a.Key
		c.attrs = append(c.attrs, a)
	}
	return &c
}

// WithGroup implements slog.Handler.
func (h *FileHandler) WithGroup(name string) slog.Handler {
	if name == "" {
		return h
	}
	c := *h
	c.groups = h.groups + name + "."
	return &c
}

// Handle formats and writes one record. Errors are swallowed like Rust's: a log
// failure must not break the caller (it is echoed in debug builds).
func (h *FileHandler) Handle(_ context.Context, r slog.Record) error {
	target := h.target
	var b strings.Builder
	b.WriteString(r.Message)
	for _, a := range h.attrs {
		writeAttr(&b, "", a)
	}
	r.Attrs(func(a slog.Attr) bool {
		if a.Key == "target" && h.groups == "" {
			target = a.Value.Resolve().String()
			return true
		}
		writeAttr(&b, h.groups, a)
		return true
	})
	level := levelOf(r.Level)
	if level > LevelFor(target, h.core.level) {
		return nil
	}
	when := r.Time
	if when.IsZero() {
		when = time.Now()
	}
	line := FormatRecord(Timestamp(when.Sub(time.Unix(0, 0))), level, target, b.String())
	if h.core.echo != nil {
		fmt.Fprintln(h.core.echo, line)
	}
	h.core.mu.Lock()
	err := h.core.file.WriteLine(line)
	h.core.mu.Unlock()
	if err != nil && h.core.echo != nil {
		fmt.Fprintln(h.core.echo, "log file not written")
	}
	return nil
}

// writeAttr appends ` key=value` (the slog text convention) for an attribute,
// flattening groups as `group.key`. Values with spaces, quotes or control characters
// are quoted. The whole line is redacted afterwards by FormatRecord.
func writeAttr(b *strings.Builder, prefix string, a slog.Attr) {
	a.Value = a.Value.Resolve()
	if a.Equal(slog.Attr{}) {
		return
	}
	if a.Value.Kind() == slog.KindGroup {
		p := prefix
		if a.Key != "" {
			p += a.Key + "."
		}
		for _, g := range a.Value.Group() {
			writeAttr(b, p, g)
		}
		return
	}
	b.WriteByte(' ')
	b.WriteString(prefix + a.Key)
	b.WriteByte('=')
	v := a.Value.String()
	if v == "" || strings.ContainsFunc(v, func(r rune) bool {
		return unicode.IsSpace(r) || r == '"' || r == '=' || !unicode.IsPrint(r)
	}) {
		v = strconv.Quote(v)
	}
	b.WriteString(v)
}

// Logger is the installed file logger: Init's result.
type Logger struct {
	handler *FileHandler
	file    *RotatingFile
	log     *slog.Logger
}

// Slog is the logger writing to the file.
func (l *Logger) Slog() *slog.Logger { return l.log }

// Handler is the slog.Handler writing to the file.
func (l *Logger) Handler() *FileHandler { return l.handler }

// Dir is the folder of the log files.
func (l *Logger) Dir() string { return l.file.dir }

// Close closes the log file.
func (l *Logger) Close() error {
	l.handler.core.mu.Lock()
	defer l.handler.core.mu.Unlock()
	return l.file.Close()
}

// Init installs the file logger in dir as the default slog logger (the standard
// `log` package follows it). Debug builds log at debug level and echo to stderr;
// release builds log at info level. With an empty dir it does nothing and returns a
// nil Logger, like `init(None)`. When the log file cannot be opened it prints
// `log file not opened` to stderr and returns the error: the app goes on without a
// file log.
func Init(dir string, debugBuild bool, version string) (*Logger, error) {
	if dir == "" {
		return nil, nil
	}
	level, echo := LevelInfo, io.Writer(nil)
	kind := "release"
	if debugBuild {
		level, echo, kind = LevelDebug, os.Stderr, "debug"
	}
	file, err := OpenRotating(dir, MaxFileBytes, KeptFiles)
	if err != nil {
		fmt.Fprintf(os.Stderr, "log file not opened: %v\n", err)
		return nil, err
	}
	h := NewFileHandler(file, level, echo)
	l := &Logger{handler: h, file: file, log: slog.New(h)}
	slog.SetDefault(l.log)
	l.log.Info(fmt.Sprintf("Viewer %s started (%s build)", version, kind))
	return l, nil
}

// LogPanic writes a panic (value and stack) to the log under the `panic` target, the
// counterpart of Rust's panic hook. Go has no global hook: goroutines that must be
// covered start with `defer logger.RecoverAndRepanic()`.
func (l *Logger) LogPanic(value any, stack []byte) {
	l.log.Error(fmt.Sprintf("goroutine panicked: %v\n%s", value, stack), "target", "panic")
}

// RecoverAndRepanic is meant for `defer`: when the goroutine panics, it logs the
// panic with its stack, then panics again with the same value so the crash goes on as
// it would have ("then runs the previous hook"). Without a panic it does nothing.
func (l *Logger) RecoverAndRepanic() {
	if v := recover(); v != nil {
		if l != nil {
			l.LogPanic(v, debug.Stack())
		}
		panic(v)
	}
}
