package diagnostics

import (
	"context"
	"encoding/json"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"viewer/internal/iokind"
)

// Error is `DiagnosticsError`. Rust serializes it as its `#[error]` text, a bare
// string (`serialize_str`), which is what a Go error without MarshalJSON becomes in
// the bridge (MAP.md, decisions).
type Error struct {
	unavailable bool
	detail      string // the Display of the io::ErrorKind, for Open
}

// ErrUnavailable is `DiagnosticsError::Unavailable`.
var ErrUnavailable = &Error{unavailable: true}

// OpenError is `DiagnosticsError::Open(kind)`.
func OpenError(kind string) *Error { return &Error{detail: kind} }

func (e *Error) Error() string {
	if e.unavailable {
		return "dossier des journaux indisponible sur ce poste"
	}
	return "dossier des journaux non ouvert : " + e.detail
}

// Is compares the variant, and the kind of an Open error.
func (e *Error) Is(target error) bool {
	t, ok := target.(*Error)
	return ok && t.unavailable == e.unavailable && t.detail == e.detail
}

// MarshalJSON makes the error serialize as a bare string, like Rust.
func (e *Error) MarshalJSON() ([]byte, error) { return json.Marshal(e.Error()) }

// ioKind is the Display of the io::ErrorKind Rust would report for err. Only the
// kinds a folder creation or a launch gives are told apart; the rest is "other error".
func ioKind(err error) string { return iokind.Text(err) }

// Opener hands a folder (or a web address) to the Windows shell.
type Opener func(target string) error

// OpenInExplorer is `open_in_explorer`: hands target (a folder, or a web address
// opened in the default browser) to the Windows shell. Callers build target
// themselves: never from the WebView as is. explorer.exe is started by its full path,
// so there is no lookup in the current directory or PATH, and it exits with 1 even on
// success: only the launch is checked.
func OpenInExplorer(target string) error {
	windows := os.Getenv("SystemRoot")
	if windows == "" {
		windows = `C:\Windows`
	}
	cmd := exec.Command(filepath.Join(windows, "explorer.exe"), target)
	if err := cmd.Start(); err != nil {
		return err
	}
	return cmd.Process.Release()
}

// OpenLogDir is `diagnostics_open_log_dir`: opens the log folder in the Windows file
// explorer. Takes no argument: the WebView cannot choose what is opened. dir is the
// managed `LogDir` ("" when none).
func OpenLogDir(dir string, open Opener) error {
	if dir == "" {
		return ErrUnavailable
	}
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return OpenError(ioKind(err))
	}
	if err := open(dir); err != nil {
		return OpenError(ioKind(err))
	}
	return nil
}

// OpenLogDirHandler is the `diagnostics_open_log_dir` command, with the signature of
// bridge.Handler (the bridge is not imported, so the package stays a leaf). open is
// OpenInExplorer in the app and a double in tests.
func OpenLogDirHandler(dir string, open Opener) func(ctx context.Context, args json.RawMessage) (any, error) {
	return func(context.Context, json.RawMessage) (any, error) {
		if err := OpenLogDir(dir, open); err != nil {
			return nil, err
		}
		return nil, nil
	}
}

// DefaultLogDir is Tauri's `app_log_dir` on Windows: `%LOCALAPPDATA%\{identifier}\logs`
// ("io.github.quentinpn.viewer", or "io.github.quentinpn.viewer.debug" for the debug build). In debug
// builds `VIEWER_E2E_LOG_DIR` overrides it (scripts/e2e). "" when there is no local
// data folder.
func DefaultLogDir(identifier string, debugBuild bool) string {
	if debugBuild {
		if dir := os.Getenv("VIEWER_E2E_LOG_DIR"); dir != "" {
			return dir
		}
	}
	local := os.Getenv("LOCALAPPDATA")
	if strings.TrimSpace(local) == "" {
		return ""
	}
	return filepath.Join(local, identifier, "logs")
}
