// Package diagnostics is the local log file and crash diagnostics (ADR 0033), port of
// src-tauri/src/diagnostics.rs.
//
// A log/slog handler writes every record to `viewer.log` in the app log directory,
// rotated by size, after redact.Redact removed tokens, passwords and authorization
// headers. A panic guard writes the panic and its stack there before the process
// goes on. Nothing leaves the PC. `redact` itself and its 5 tests live in
// internal/redact (wave 3).
package diagnostics

import (
	"fmt"
	"strings"
	"time"
	"unicode/utf8"

	"viewer/internal/redact"
)

const (
	// LogFile is the current file name; archives are `viewer.1.log` (newest) to
	// `viewer.{n}.log`.
	LogFile = "viewer.log"
	// MaxFileBytes is the size at which the current file is archived.
	MaxFileBytes = 2 * 1024 * 1024
	// KeptFiles is the number of files kept in all, the current one included: at most
	// 6 MiB on disk.
	KeptFiles = 3
	// maxRecordBytes: a single record is cut beyond this, so one message cannot fill
	// a file.
	maxRecordBytes = 64 * 1024
)

// Level is a log level (the `log` crate's `Level` and `LevelFilter` in one type):
// Off is only a filter, the others also label records.
type Level int

// Levels in the order of the log crate: a record is shown when its level is <= the
// filter.
const (
	LevelOff Level = iota
	LevelError
	LevelWarn
	LevelInfo
	LevelDebug
	LevelTrace
)

// String is the Display of `log::Level`, padded by the caller.
func (l Level) String() string {
	switch l {
	case LevelError:
		return "ERROR"
	case LevelWarn:
		return "WARN"
	case LevelInfo:
		return "INFO"
	case LevelDebug:
		return "DEBUG"
	case LevelTrace:
		return "TRACE"
	default:
		return "OFF"
	}
}

// LevelFor is `level_for`: the level for a record's target. The app's own records
// log at own, dependencies (and any other target) at warnings and errors only.
func LevelFor(target string, own Level) Level {
	if strings.HasPrefix(target, "viewer") || target == "mpv" || target == "panic" {
		return own
	}
	return min(LevelWarn, own)
}

// Timestamp is `2026-11-07T10:11:12.345Z` (UTC), from a duration since the Unix
// epoch. Rust computes the civil date by hand (Hinnant); time.Time does the same
// without a leap-year or era bug, and truncates to milliseconds like `subsec_millis`.
func Timestamp(sinceEpoch time.Duration) string {
	return time.Unix(0, 0).UTC().Add(sinceEpoch).Format("2006-01-02T15:04:05.000Z")
}

// FormatRecord gives one redacted line per record: time, level, target and message,
// cut at 64 KiB.
func FormatRecord(timestamp string, level Level, target, message string) string {
	line := redact.Redact(fmt.Sprintf("%s %-5s %s: %s", timestamp, level, target, message))
	if len(line) > maxRecordBytes {
		cut := maxRecordBytes
		for !utf8.RuneStart(line[cut]) {
			cut--
		}
		line = line[:cut] + " [cut]"
	}
	return line
}
