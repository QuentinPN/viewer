package diagnostics

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"io"
	"log"
	"log/slog"
	"os"
	"path/filepath"
	"regexp"
	"runtime"
	"strings"
	"sync"
	"testing"
)

func newTestLogger(t *testing.T, own Level, echo io.Writer) (*slog.Logger, *RotatingFile) {
	t.Helper()
	file, err := OpenRotating(filepath.Join(t.TempDir(), "logs"), MaxFileBytes, KeptFiles)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { file.Close() })
	return slog.New(NewFileHandler(file, own, echo)), file
}

var linePattern = regexp.MustCompile(`^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d\.\d{3}Z (ERROR|WARN |INFO |DEBUG|TRACE) `)

func Test_go_handler_writes_redacted_lines_with_target_and_attrs(t *testing.T) {
	logger, file := newTestLogger(t, LevelDebug, nil)
	logger.Info("GET https://emby/x?api_key=abc123&x=1 failed", "err", "bad gateway", "code", 502)
	logger.With("target", "mpv").Warn("[ffmpeg] Authorization: Bearer abc")
	logger.Warn("level and target", "target", "reqwest::connect", "password", "hunter2")
	logger.Debug("own debug kept")
	lines := strings.Split(strings.TrimSpace(mustRead(t, file.Path(0))), "\n")
	if len(lines) != 4 {
		t.Fatalf("%d lines: %q", len(lines), lines)
	}
	for _, l := range lines {
		if !linePattern.MatchString(l) {
			t.Errorf("odd line %q", l)
		}
	}
	for i, want := range []string{
		`INFO  viewer: GET https://emby/x?api_key=<redacted>&x=1 failed err="bad gateway" code=502`,
		`WARN  mpv: [ffmpeg] Authorization: <redacted>`,
		`WARN  reqwest::connect: level and target password=<redacted>`,
		`DEBUG viewer: own debug kept`,
	} {
		if got := lines[i][strings.Index(lines[i], "Z ")+2:]; got != want {
			t.Errorf("line %d: %q, want %q", i, got, want)
		}
	}
	if strings.Contains(strings.Join(lines, "\n"), "hunter2") || strings.Contains(strings.Join(lines, "\n"), "abc123") {
		t.Error("a secret reached the file")
	}
}

func Test_go_handler_filters_dependencies_at_warn(t *testing.T) {
	logger, file := newTestLogger(t, LevelDebug, nil)
	logger.Info("dependency info", "target", "reqwest::connect")
	logger.Warn("dependency warn", "target", "reqwest::connect")
	logger.Debug("mpv debug", "target", "mpv")
	logger.Log(context.Background(), slog.LevelDebug-4, "trace of ours")
	got := mustRead(t, file.Path(0))
	if strings.Contains(got, "dependency info") {
		t.Error("a dependency's info record was written")
	}
	for _, want := range []string{"dependency warn", "mpv debug"} {
		if !strings.Contains(got, want) {
			t.Errorf("missing %q in %q", want, got)
		}
	}
	// Release level: debug is dropped, even for the app.
	logger, file = newTestLogger(t, LevelInfo, nil)
	logger.Debug("debug dropped")
	logger.Info("info kept")
	if got := mustRead(t, file.Path(0)); strings.Contains(got, "debug dropped") || !strings.Contains(got, "info kept") {
		t.Errorf("release level: %q", got)
	}
}

func Test_go_handler_groups_and_with_attrs(t *testing.T) {
	logger, file := newTestLogger(t, LevelInfo, nil)
	logger.With("session", "s 1").WithGroup("req").Info("done", "id", 7, slog.Group("g", "k", "v"))
	got := mustRead(t, file.Path(0))
	if !strings.HasSuffix(strings.TrimSpace(got), `done session="s 1" req.id=7 req.g.k=v`) {
		t.Errorf("got %q", got)
	}
}

func Test_go_handler_echoes_to_stderr_in_debug(t *testing.T) {
	var echo bytes.Buffer
	logger, file := newTestLogger(t, LevelDebug, &echo)
	logger.Info("hello token=abc")
	if !strings.Contains(echo.String(), "hello token=<redacted>") || echo.String() != mustRead(t, file.Path(0)) {
		t.Errorf("echo %q vs file %q", echo.String(), mustRead(t, file.Path(0)))
	}
}

func Test_go_handler_is_safe_for_concurrent_use(t *testing.T) {
	logger, file := newTestLogger(t, LevelInfo, nil)
	var wg sync.WaitGroup
	for g := range 8 {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for i := range 100 {
				logger.Info("record", "g", g, "i", i)
			}
		}()
	}
	wg.Wait()
	lines := strings.Split(strings.TrimSpace(mustRead(t, file.Path(0))), "\n")
	if len(lines) != 800 {
		t.Fatalf("%d lines, want 800", len(lines))
	}
	for _, l := range lines {
		if !linePattern.MatchString(l) {
			t.Fatalf("interleaved line %q", l)
		}
	}
}

func Test_go_init_installs_the_default_logger_and_logs_a_panic(t *testing.T) {
	previous := slog.Default()
	t.Cleanup(func() { slog.SetDefault(previous) })
	dir := filepath.Join(t.TempDir(), "logs")
	l, err := Init(dir, false, "9.9.9")
	if err != nil || l == nil {
		t.Fatalf("Init: %v, %v", l, err)
	}
	defer l.Close()
	slog.Warn("through the default logger")
	log.Print("through the standard log package")
	func() {
		defer func() {
			if v := recover(); v != "boom api_key=secret-value" {
				t.Errorf("the panic was not re-raised: %v", v)
			}
		}()
		func() {
			defer l.RecoverAndRepanic()
			panic("boom api_key=secret-value")
		}()
	}()
	got := mustRead(t, filepath.Join(dir, LogFile))
	for _, want := range []string{
		"Viewer 9.9.9 started (release build)",
		"through the default logger",
		"through the standard log package",
		"panic: goroutine panicked: boom api_key=<redacted>",
		"Test_go_init_installs_the_default_logger_and_logs_a_panic", // the stack names the test
	} {
		if !strings.Contains(got, want) {
			t.Errorf("log lacks %q:\n%s", want, got)
		}
	}
	if strings.Contains(got, "secret-value") {
		t.Error("the panic message was not redacted")
	}
	if l, err := Init("", true, "x"); l != nil || err != nil {
		t.Errorf("Init with no folder: %v, %v", l, err)
	}
	// No panic: nothing happens, and a nil logger does not break the guard.
	func() { defer l.RecoverAndRepanic() }()
}

func Test_go_init_reports_an_unopenable_folder(t *testing.T) {
	file := filepath.Join(t.TempDir(), "afile")
	if err := os.WriteFile(file, nil, 0o644); err != nil {
		t.Fatal(err)
	}
	if l, err := Init(filepath.Join(file, "sub"), false, "x"); l != nil || err == nil {
		t.Errorf("got %v, %v", l, err)
	}
}

// The owner can delete or move the log from the explorer while the app runs
// (FILE_SHARE_DELETE), as with Rust's std.
func Test_go_log_file_can_be_deleted_while_open(t *testing.T) {
	if runtime.GOOS != "windows" {
		t.Skip("Windows sharing modes")
	}
	logger, file := newTestLogger(t, LevelInfo, nil)
	logger.Info("one")
	if err := os.Rename(file.Path(0), file.Path(0)+".moved"); err != nil {
		t.Fatalf("rename of the open log: %v", err)
	}
	logger.Info("two")
	if !strings.Contains(mustRead(t, file.Path(0)+".moved"), "two") {
		t.Error("writes did not follow the moved file")
	}
}

func Test_go_rotation_with_one_kept_file_replaces_the_current_one(t *testing.T) {
	// kept=1: Rust removes `path(0)`, the current file, then recreates it.
	file, err := OpenRotating(filepath.Join(t.TempDir(), "l"), 30, 1)
	if err != nil {
		t.Fatal(err)
	}
	defer file.Close()
	for _, s := range []string{strings.Repeat("a", 20), strings.Repeat("b", 20)} {
		if err := file.WriteLine(s); err != nil {
			t.Fatal(err)
		}
	}
	if got := mustRead(t, file.Path(0)); got != strings.Repeat("b", 20)+"\n" {
		t.Errorf("got %q", got)
	}
	if _, err := os.Stat(file.Path(1)); err == nil {
		t.Error("an archive exists with kept=1")
	}
}

func Test_go_cut_never_splits_a_rune(t *testing.T) {
	// 3-byte runes: the cut point falls inside one for some prefix lengths.
	for pad := range 3 {
		msg := strings.Repeat("a", pad) + strings.Repeat("€", maxRecordBytes)
		line := FormatRecord("T", LevelInfo, "t", msg)
		body := strings.TrimSuffix(line, " [cut]")
		if body == line || !strings.HasSuffix(strings.ToValidUTF8(body, "\x00"), "€") {
			t.Errorf("pad %d: bad cut", pad)
		}
	}
}

func Test_go_levels(t *testing.T) {
	if LevelFor("other", LevelOff) != LevelOff || LevelFor("other", LevelTrace) != LevelWarn {
		t.Error("min(Warn, own)")
	}
	for l, s := range map[Level]string{LevelError: "ERROR", LevelWarn: "WARN", LevelInfo: "INFO", LevelDebug: "DEBUG", LevelTrace: "TRACE", LevelOff: "OFF"} {
		if l.String() != s {
			t.Errorf("%d is %q", l, l.String())
		}
	}
	for l, want := range map[slog.Level]Level{
		slog.LevelError + 4: LevelError, slog.LevelError: LevelError, slog.LevelWarn: LevelWarn,
		slog.LevelInfo: LevelInfo, slog.LevelDebug: LevelDebug, slog.LevelDebug - 1: LevelTrace,
	} {
		if levelOf(l) != want {
			t.Errorf("levelOf(%v) = %v, want %v", l, levelOf(l), want)
		}
	}
}

// --- command: diagnostics_open_log_dir -------------------------------------

func Test_go_open_log_dir_opens_the_folder_and_creates_it(t *testing.T) {
	dir := filepath.Join(t.TempDir(), "logs")
	var opened []string
	h := OpenLogDirHandler(dir, func(target string) error { opened = append(opened, target); return nil })
	got, err := h(context.Background(), json.RawMessage(`{}`))
	if err != nil || got != nil {
		t.Fatalf("got %v, %v", got, err)
	}
	if len(opened) != 1 || opened[0] != dir {
		t.Errorf("opened %v", opened)
	}
	if info, err := os.Stat(dir); err != nil || !info.IsDir() {
		t.Errorf("the folder was not created: %v", err)
	}
}

func Test_go_open_log_dir_errors_are_bare_strings(t *testing.T) {
	// No folder on this PC.
	_, err := OpenLogDirHandler("", nil)(context.Background(), nil)
	if !errors.Is(err, ErrUnavailable) {
		t.Fatalf("got %v", err)
	}
	if b, _ := json.Marshal(err); string(b) != `"dossier des journaux indisponible sur ce poste"` {
		t.Errorf("got %s", b)
	}
	// The launch fails with a missing explorer.
	dir := filepath.Join(t.TempDir(), "logs")
	t.Setenv("SystemRoot", filepath.Join(t.TempDir(), "no-windows-here"))
	_, err = OpenLogDirHandler(dir, OpenInExplorer)(context.Background(), nil)
	if err == nil || err.Error() != "dossier des journaux non ouvert : entity not found" {
		t.Errorf("got %v", err)
	}
	if b, _ := json.Marshal(err); string(b) != `"dossier des journaux non ouvert : entity not found"` {
		t.Errorf("got %s", b)
	}
	// The folder cannot be created: a file stands where it should be.
	file := filepath.Join(t.TempDir(), "afile")
	os.WriteFile(file, nil, 0o644)
	err = OpenLogDir(filepath.Join(file, "logs"), func(string) error { t.Error("opened"); return nil })
	var de *Error
	if !errors.As(err, &de) || !strings.HasPrefix(err.Error(), "dossier des journaux non ouvert : ") {
		t.Errorf("got %v", err)
	}
	// The opener's own failure.
	err = OpenLogDir(dir, func(string) error { return os.ErrPermission })
	if err == nil || err.Error() != "dossier des journaux non ouvert : permission denied" {
		t.Errorf("got %v", err)
	}
}

func Test_go_default_log_dir(t *testing.T) {
	t.Setenv("LOCALAPPDATA", `C:\Users\x\AppData\Local`)
	t.Setenv("VIEWER_E2E_LOG_DIR", `D:\e2e`)
	if got := DefaultLogDir("io.github.quentinpn.viewer", false); got != `C:\Users\x\AppData\Local\io.github.quentinpn.viewer\logs` && !strings.HasSuffix(filepath.ToSlash(got), "io.github.quentinpn.viewer/logs") {
		t.Errorf("release: %q", got)
	}
	if got := DefaultLogDir("io.github.quentinpn.viewer.debug", true); got != `D:\e2e` {
		t.Errorf("debug override: %q", got)
	}
	t.Setenv("VIEWER_E2E_LOG_DIR", "")
	if got := DefaultLogDir("io.github.quentinpn.viewer.debug", true); !strings.HasSuffix(filepath.ToSlash(got), "io.github.quentinpn.viewer.debug/logs") {
		t.Errorf("debug: %q", got)
	}
	t.Setenv("LOCALAPPDATA", "")
	if got := DefaultLogDir("x", false); got != "" {
		t.Errorf("no local data: %q", got)
	}
}
